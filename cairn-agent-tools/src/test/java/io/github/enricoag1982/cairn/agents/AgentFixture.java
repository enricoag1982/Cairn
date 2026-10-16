package io.github.enricoag1982.cairn.agents;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.documents.SchemaDocuments;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Submission;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * An in-memory store with the reference model registered, the rules of the reference domain with their code, a clock
 * that the test moves and deterministic IDs, served by {@link AgentTools} that act as {@value #PRINCIPAL}. The
 * helpers call tools with JSON arguments and parse what they return.
 */
final class AgentFixture {

    /** The principal that the tools of the fixture act as. */
    static final String PRINCIPAL = "agent:onboarding";

    final TestClock clock = new TestClock();
    final Store store;
    final ApprovalQueue queue;
    final AgentTools tools;

    /** A store with the reference model, and tools that allow everything. */
    AgentFixture() {
        this(AgentPolicy.defaults());
    }

    AgentFixture(AgentPolicy policy) {
        this(policy, true);
    }

    /**
     * @param withReferenceModel whether the reference model 1.0.0 is registered; without it the store has no schema
     */
    AgentFixture(AgentPolicy policy, boolean withReferenceModel) {
        this(policy, withReferenceModel, true);
    }

    /**
     * @param rulesHaveCode whether the store has the code of the rules of the reference domain; if not, it declares
     *                      them
     */
    AgentFixture(AgentPolicy policy, boolean withReferenceModel, boolean rulesHaveCode) {
        this(policy, withReferenceModel ? Optional.of(ReferenceModel.definition()) : Optional.empty(), rulesHaveCode,
                Clearance.none());
    }

    /**
     * Tools with a clearance, over a store with another schema than the reference model registered, under the name of
     * the reference model (ADR-0036): the way a server started with {@code --clearance} serves its store.
     *
     * @param definition the schema to register
     * @param clearance  the clearance of the tools
     */
    AgentFixture(SchemaDefinition definition, Clearance clearance) {
        this(AgentPolicy.defaults(), Optional.of(definition), true, clearance);
    }

    private AgentFixture(AgentPolicy policy, Optional<SchemaDefinition> definition, boolean rulesHaveCode,
            Clearance clearance) {
        StoreSettings.Builder settings = StoreSettings.builder().clock(clock)
                .idGenerator(IdGenerators.deterministic());
        if (rulesHaveCode) {
            settings.rules(ReferenceModel.executableRules());
        } else {
            settings.declaredRules(ReferenceModel.rules());
        }
        store = Stores.inMemory(settings.build());
        if (definition.isPresent()) {
            assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), definition.get(),
                    Submission.by("importer"))).isInstanceOf(RegistrationResult.Applied.class);
        }
        queue = new ApprovalQueue(store, clock);
        tools = new AgentTools(store, policy, queue, PRINCIPAL, Optional.empty(), clearance);
    }

    // ------------------------------------------------------------------ calling tools

    /** Calls a tool and parses its JSON result. */
    JsonNode call(String tool, ObjectNode arguments) {
        return Json.parse(tools.call(tool, Json.render(arguments)));
    }

    /** Calls a tool without arguments. */
    JsonNode call(String tool) {
        return call(tool, Json.object());
    }

    /** Arguments from alternating names and text values. */
    static ObjectNode args(String... namesAndValues) {
        ObjectNode arguments = Json.object();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            arguments.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return arguments;
    }

    // ------------------------------------------------------------------ stories

    /** Records RD-1 directly in the store: ACME, Beta and Alice. */
    Receipt createParties() {
        return ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
    }

    /** Records a story of the reference domain directly in the store. */
    Receipt replay(ReferenceStories.Recording recording) {
        return ReferenceStories.replay(store, clock, recording);
    }

    static EntityId entity(Receipt receipt, TemporaryId id) {
        return (EntityId) receipt.temporaryIds().get(id);
    }

    static RelationshipId relationship(Receipt receipt, TemporaryId id) {
        return (RelationshipId) receipt.temporaryIds().get(id);
    }

    // ------------------------------------------------------------------ payloads and documents

    /** A transaction payload of the principal of the fixture, from the JSON of its writes. */
    static String payload(String... writes) {
        return payloadOf(PRINCIPAL, writes);
    }

    /** A transaction payload of a principal, from the JSON of its writes. */
    static String payloadOf(String principal, String... writes) {
        return "{\"cairn\": 1, \"principal\": \"" + principal + "\", \"writes\": [" + String.join(", ", writes) + "]}";
    }

    /** A write that creates an organization from 1 January with a name. */
    static String createOrganization(String name) {
        return "{\"create\": {\"entityType\": \"Organization\", \"from\": \"2026-01-01T00:00:00Z\", "
                + "\"values\": {\"name\": \"" + name + "\"}}}";
    }

    /** A document of the reference model 1.1.0 that lacks the attribute birthDate of Person: a breaking change. */
    static String documentWithoutBirthDate() {
        return SchemaDocuments.writeYaml(ReferenceModel.origin("1.1.0"),
                Definitions.changeEntityType(ReferenceModel.definition(), "Person",
                        type -> Definitions.withAttributes(type, List.of())));
    }

    /** The text of the document of an origin, as {@code cairn_describe} reports it. */
    String documentOf(String origin) {
        for (JsonNode node : call(ToolCatalog.DESCRIBE).get("origins")) {
            if (node.get("name").stringValue().equals(origin)) {
                return node.get("document").stringValue();
            }
        }
        throw new AssertionError("No document for the origin " + origin);
    }
}
