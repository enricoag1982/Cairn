package io.github.enricoag1982.cairn.agents;

import static io.github.enricoag1982.cairn.agents.AgentFixture.args;
import static io.github.enricoag1982.cairn.agents.AgentFixture.createOrganization;
import static io.github.enricoag1982.cairn.agents.AgentFixture.entity;
import static io.github.enricoag1982.cairn.agents.AgentFixture.payload;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.agents.AgentPolicy.Decision;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tool {@code cairn_list_changes} beyond the scenarios (ADR-0029, design D7 of {@code add-change-feed}): its defaults
 * and limits, the problems of its arguments, how an agent pages through the changes with {@code next}, the three kinds of
 * change as JSON, and that the approval policy has no say in a read.
 */
class AgentChangesTest {

    private final AgentFixture fixture = new AgentFixture();

    private static ObjectNode arguments(long after, int limit) {
        return Json.object().put("after", after).put("limit", limit);
    }

    private static List<String> paths(JsonNode problems) {
        List<String> paths = new ArrayList<>();
        problems.get("problems").forEach(problem -> paths.add(problem.get("path").stringValue()));
        return paths;
    }

    @Test
    void withoutArgumentsItReadsFromTheFirstChangeAndReportsTheLatestBasis() {
        Receipt parties = fixture.createParties();

        JsonNode result = fixture.call("cairn_list_changes");

        assertThat(result.propertyNames()).containsExactly("changes", "next", "latestBasis");
        assertThat(result.get("changes")).hasSize(2);
        assertThat(result.get("changes").get(0).get("kind").stringValue()).isEqualTo("schema");
        assertThat(result.get("changes").get(0).get("origin").get("name").stringValue()).isEqualTo("reference-model");
        assertThat(result.get("changes").get(1).get("kind").stringValue()).isEqualTo("transaction");
        assertThat(result.get("next").longValue()).isEqualTo(parties.basis().value());
        assertThat(result.get("latestBasis").longValue()).isEqualTo(parties.basis().value());
    }

    @Test
    void aStoreWithoutChangesGivesAnEmptyListAndNextIsTheBasisGiven() {
        AgentFixture empty = new AgentFixture(AgentPolicy.defaults(), false);

        JsonNode none = empty.call("cairn_list_changes");
        JsonNode beyond = fixture.call("cairn_list_changes", Json.object().put("after", 7));

        assertThat(none.get("changes")).isEmpty();
        assertThat(none.get("next").longValue()).isZero();
        assertThat(none.get("latestBasis").longValue()).isZero();
        assertThat(beyond.get("changes")).as("after the latest basis").isEmpty();
        assertThat(beyond.get("next").longValue()).as("the given basis, as there is no change").isEqualTo(7);
        assertThat(beyond.get("latestBasis").longValue()).isEqualTo(1);
    }

    @Test
    void anAgentPagesThroughTheChangesWithNextAndTheLimit() {
        Receipt parties = fixture.createParties();
        fixture.replay(ReferenceStories.rd5Accounts(entity(parties, BETA), entity(parties, ACME)));
        fixture.replay(ReferenceStories.rd3Employment(entity(parties, BETA), entity(parties, ALICE)));
        List<Long> seen = new ArrayList<>();
        long next = 0;

        for (int page = 0; page < 10; page++) {
            JsonNode result = fixture.call("cairn_list_changes", arguments(next, 2));
            result.get("changes").forEach(change -> seen.add(change.get("basis").longValue()));
            assertThat(result.get("changes").size()).isLessThanOrEqualTo(2);
            if (result.get("changes").isEmpty()) {
                assertThat(result.get("next").longValue()).isEqualTo(next);
                break;
            }
            next = result.get("next").longValue();
        }

        assertThat(seen).containsExactly(1L, 2L, 3L, 4L);
        assertThat(next).isEqualTo(4);
    }

    @Test
    void theLimitIsFrom1To1000AndTheBasisIsAWholeNumberOfAtLeast0() {
        fixture.createParties();

        assertThat(fixture.call("cairn_list_changes", arguments(0, 1000)).has("changes")).isTrue();
        assertThat(fixture.call("cairn_list_changes", arguments(0, 1)).get("changes")).hasSize(1);
        for (int limit : new int[] {0, -1, 1001, Integer.MAX_VALUE}) {
            JsonNode result = fixture.call("cairn_list_changes", Json.object().put("limit", limit));
            assertThat(paths(result)).as("limit %d", limit).containsExactly("@arguments.limit");
            assertThat(result.get("problems").get(0).get("code").stringValue()).isEqualTo("INVALID_VALUE");
            assertThat(result.get("problems").get(0).get("expected").stringValue()).contains("1 to 1000");
        }
        JsonNode negative = fixture.call("cairn_list_changes", Json.object().put("after", -1));
        assertThat(paths(negative)).containsExactly("@arguments.after");
        assertThat(negative.get("problems").get(0).get("found").stringValue()).isEqualTo("-1");
    }

    @Test
    void argumentsOfTheWrongKindUnknownOnesAndSeveralProblemsAreReportedAtTheirNames() {
        assertThat(paths(fixture.call("cairn_list_changes", Json.object().put("after", "one"))))
                .containsExactly("@arguments.after");
        assertThat(paths(fixture.call("cairn_list_changes", Json.object().put("limit", 2.5))))
                .containsExactly("@arguments.limit");
        assertThat(paths(fixture.call("cairn_list_changes", Json.object().put("limit", true))))
                .containsExactly("@arguments.limit");
        assertThat(paths(fixture.call("cairn_list_changes", Json.object().put("after", 1e30))))
                .containsExactly("@arguments.after");
        JsonNode unknown = fixture.call("cairn_list_changes", Json.object().put("lmit", 5));
        assertThat(paths(unknown)).containsExactly("@arguments.lmit");
        assertThat(unknown.get("problems").get(0).get("hint").stringValue()).contains("limit");
        assertThat(paths(fixture.call("cairn_list_changes", arguments(-5, 0))))
                .containsExactly("@arguments.after", "@arguments.limit");
        assertThat(fixture.call("cairn_list_changes", Json.object().putNull("limit").putNull("after")).get("changes"))
                .as("null is not given").hasSize(1);
        assertThat(Json.parse(fixture.tools.listChanges(null)).get("changes")).hasSize(1);
        assertThat(Json.parse(fixture.tools.listChanges("[]")).get("problems")).hasSize(1);
    }

    @Test
    void aPurgeIsAChangeWithTheNumbersItRemovedAndTheChangesOfItsEntitiesAreEmptiedOfThem() {
        Receipt parties = fixture.createParties();
        fixture.replay(ReferenceStories.rd3Employment(entity(parties, BETA), entity(parties, ALICE)));
        fixture.store.purge(Purge.by("dpo").onBehalfOf("DSR-1").reason("Erasure request")
                .entity(entity(parties, ALICE)).build());

        JsonNode result = fixture.call("cairn_list_changes");

        JsonNode changes = result.get("changes");
        assertThat(changes).hasSize(4);
        JsonNode created = changes.get(1);
        assertThat(created.get("changed")).as("RD-1 lists ACME and Beta only").hasSize(2);
        assertThat(created.get("changed").findValuesAsString("entity")).doesNotContain(entity(parties, ALICE).value());
        assertThat(changes.get(2).has("changed")).as("RD-3 created only the purged relationship").isFalse();
        JsonNode purge = changes.get(3);
        assertThat(purge.get("kind").stringValue()).isEqualTo("purge");
        assertThat(purge.get("principal").stringValue()).isEqualTo("dpo");
        assertThat(purge.get("onBehalfOf").stringValue()).isEqualTo("DSR-1");
        assertThat(purge.get("reason").stringValue()).isEqualTo("Erasure request");
        assertThat(purge.get("entities").intValue()).isEqualTo(1);
        assertThat(purge.get("relationships").intValue()).isEqualTo(1);
        assertThat(result.get("next").longValue()).isEqualTo(purge.get("basis").longValue());
    }

    @Test
    void theApprovalPolicyDoesNotApplyToReadingChanges() {
        for (Decision decision : Decision.values()) {
            AgentFixture restricted = new AgentFixture(new AgentPolicy(decision, decision));
            restricted.createParties();

            JsonNode result = restricted.call("cairn_list_changes");

            assertThat(result.get("changes")).as("%s", decision).hasSize(2);
            assertThat(restricted.queue.all()).isEmpty();
        }
    }

    @Test
    void whatAnAgentCommitsThroughTheToolsShowsUpInTheChangesWithItsPrincipal() {
        JsonNode committed = fixture.call("cairn_commit", args("payload", payload(
                "{ \"create\": { \"temporaryId\": \"acme\", \"entityType\": \"Organization\", "
                        + "\"from\": \"2026-01-01T00:00:00Z\", \"values\": { \"name\": \"ACME\" } } }")));
        assertThat(committed.get("outcome").stringValue()).isEqualTo("committed");
        assertThat(createOrganization("x")).isNotBlank();

        JsonNode result = fixture.call("cairn_list_changes", Json.object().put("after", 1));

        assertThat(result.get("changes")).singleElement().satisfies(change -> {
            assertThat(change.get("principal").stringValue()).isEqualTo(AgentFixture.PRINCIPAL);
            assertThat(change.get("changed")).singleElement().satisfies(changed -> {
                assertThat(changed.get("type").stringValue()).isEqualTo("Organization");
                assertThat(changed.get("created").booleanValue()).isTrue();
                assertThat(changed.get("periods").get(0).get("from").stringValue()).isEqualTo("2026-01-01T00:00:00Z");
                assertThat(changed.get("periods").get(0).has("until")).isFalse();
            });
        });
    }

    @Test
    void theToolIsInTheCatalogWithItsArgumentsAndTheLimitsOfTheLimit() {
        AgentTools.ToolDescription tool = AgentTools.toolDescriptions().stream()
                .filter(description -> description.name().equals("cairn_list_changes")).findFirst().orElseThrow();

        JsonNode schema = Json.parse(tool.inputSchema());

        assertThat(schema.get("required")).isEmpty();
        assertThat(schema.get("properties").propertyNames()).containsExactlyInAnyOrder("after", "limit");
        assertThat(schema.get("properties").get("limit").get("minimum").intValue()).isEqualTo(1);
        assertThat(schema.get("properties").get("limit").get("maximum").intValue()).isEqualTo(1000);
        assertThat(schema.get("properties").get("limit").get("default").intValue()).isEqualTo(100);
        assertThat(schema.get("properties").get("after").get("default").intValue()).isZero();
        assertThat(tool.description()).contains("next").contains("cairn_read_entity").contains("only reads");
    }
}
