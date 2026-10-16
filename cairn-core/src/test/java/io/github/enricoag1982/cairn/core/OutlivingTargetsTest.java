package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.assertProblem;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A2;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Relationships whose type outlives its targets, beyond the scenarios of the {@code relationships} spec (ADR-0034,
 * design D2 to D4 of {@code add-relationships-that-outlive-their-targets}): the lifetimes that are checked again when a
 * target is corrected, the stretches of a relationship, views at older bases, what a purge removes, and the types that do
 * not declare it. The world is the reference domain with RD-1 and RD-5, and the test-only association {@code OpenedBy}
 * from an account to the person who opened it, with an attribute {@code channel}, and its subtype {@code OpenedByAgent},
 * which does not declare it, and the test-only association {@code Neighbour} between addresses, which does, so that a
 * relationship can have the same entity at both ends.
 */
class OutlivingTargetsTest {

    private static final TemporaryId OPENED = new TemporaryId("opened");
    private static final TemporaryId HOME = new TemporaryId("home");
    private static final TemporaryId OFFICE = new TemporaryId("office");
    private static final TemporaryId HAS_ADDRESS = new TemporaryId("hasAddress");
    private static final TemporaryId HAS_OFFICE = new TemporaryId("hasOffice");
    private static final TemporaryId NEXT_TO_ITSELF = new TemporaryId("nextToItself");
    private static final TemporaryId NEXT_DOOR = new TemporaryId("nextDoor");

    private static SchemaDefinition model() {
        return Definitions.plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .relationshipType("OpenedBy", r -> r.from("Account").to("Person")
                        .targetsPerSource(Multiplicity.ZERO_OR_ONE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .outlivesTarget().inverse("opened").attribute("channel", ValueType.STRING))
                .relationshipType("OpenedByAgent", r -> r.supertype("OpenedBy").from("Account").to("Person")
                        .targetsPerSource(Multiplicity.ZERO_OR_ONE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("openedAsAgent"))
                .relationshipType("Neighbour", r -> r.from("Address").to("Address")
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .outlivesTarget().inverse("neighbouredBy"))
                .build());
    }

    private final ReferenceStore store = ReferenceStore.with(model());
    private Receipt accounts;
    private EntityId a1;
    private EntityId a2;
    private EntityId alice;

    @BeforeEach
    void theWorldOfRd1AndRd5() {
        accounts = store.createAccounts();
        a1 = entity(accounts, A1);
        a2 = entity(accounts, A2);
        alice = entity(store.parties, ALICE);
    }

    private static Transaction.Builder by() {
        return Transaction.by("importer");
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    /** Relates an account to Alice, recorded on a date, from an effective period, with a channel. */
    private RelationshipId open(String type, EntityId account, EffectivePeriod period, String channel, Instant recorded) {
        store.recordedOn(recorded);
        return relationship(store.commit(by().relate(OPENED, type, account, alice, period, values("channel", channel))
                .build()), OPENED);
    }

    private RelationshipId open(EntityId account, EffectivePeriod period) {
        return open("OpenedBy", account, period, "branch", on(1, 20));
    }

    private Receipt endAlice(Instant from, Instant recorded) {
        store.recordedOn(recorded);
        return store.commit(by().endDate(alice, from).build());
    }

    // ------------------------------------------------------------------ the target exists when the relationship starts

    @Test
    void aCorrectionThatEndsTheTargetBeforeTheRelationshipStartsIsRejected() {
        RelationshipId opened = open(a2, from(3, 1));
        store.recordedOn(on(5, 1));

        List<Problem> problems = store.reject(by().endDate(alice, on(2, 1)).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.RELATIONSHIP_OUTLIVES_END, opened.value());
        assertThat(problem.message()).contains("OpenedBy", "target", "does not exist at 2026-03-01T00:00:00Z");
        assertThat(store.view(on(3, 1)).entity(alice)).as("nothing was ended").isPresent();
    }

    @Test
    void aTargetThatEndsWhereTheRelationshipStartsIsRejectedToo() {
        RelationshipId opened = open(a2, from(3, 1));
        store.recordedOn(on(5, 1));

        List<Problem> problems = store.reject(by().endDate(alice, on(3, 1)).build());

        assertProblem(only(problems), ProblemCode.RELATIONSHIP_OUTLIVES_END, opened.value());
    }

    @Test
    void aCorrectionThatEndsTheTargetAfterTheRelationshipStartsIsAccepted() {
        RelationshipId opened = open(a2, from(3, 1));

        Receipt receipt = endAlice(on(3, 2), on(5, 1));

        assertThat(receipt.ended()).isEmpty();
        assertThat(store.view(on(3, 1)).entity(alice)).isPresent();
        assertThat(store.view(on(3, 2)).entity(alice)).isEmpty();
        assertThat(store.view(on(9, 1)).relationship(opened)).isPresent();
    }

    @Test
    void aRelationshipThatLastsPastItsTargetMayAlsoEndOnItsOwn() {
        RelationshipId opened = open(a1, EffectivePeriod.between(on(3, 1), on(12, 1)));
        endAlice(on(6, 1), on(5, 25));

        assertThat(store.view(on(8, 1)).navigate(a1, "OpenedBy")).singleElement().satisfies(link -> {
            assertThat(link.relationship().id()).isEqualTo(opened);
            assertThat(link.entity().period()).isEqualTo(EffectivePeriod.between(on(1, 1), on(6, 1)));
        });
        assertThat(store.view(on(12, 1)).navigate(a1, "OpenedBy")).as("the relationship has ended").isEmpty();

        store.recordedOn(on(8, 10));
        store.commit(by().endDate(opened, on(9, 1)).build());

        assertThat(store.view(on(8, 31)).navigate(a1, "OpenedBy")).hasSize(1);
        assertThat(store.view(on(9, 1)).navigate(a1, "OpenedBy")).isEmpty();
    }

    @Test
    void aChangeOfValuesAfterTheTargetEndedDoesNotStartAnotherStretch() {
        RelationshipId opened = open(a1, from(1, 1));
        endAlice(on(6, 1), on(5, 25));
        store.recordedOn(on(6, 15));

        Receipt receipt = store.commit(by().patch(opened, from(7, 1), values("channel", "web")).build());

        assertThat(receipt.ended()).isEmpty();
        assertThat(store.view(on(8, 1)).relationshipTimeline(opened)).extracting(state -> state.period().from(),
                state -> state.values().get("channel")).containsExactly(tuple(on(1, 1), Value.of("branch")),
                tuple(on(7, 1), Value.of("web")));
        assertThat(store.view(on(8, 1)).navigate(a1, "OpenedBy")).singleElement().satisfies(link -> {
            assertThat(link.relationship().values()).containsEntry("channel", Value.of("web"));
            assertThat(link.entity().period()).isEqualTo(EffectivePeriod.between(on(1, 1), on(6, 1)));
        });
    }

    @Test
    void theSourceOfARelationshipThatOutlivesItsTargetMustExistThroughoutIt() {
        RelationshipId opened = open(a1, from(1, 1));
        RelationshipId heldBy = relationship(accounts, HELD_BY_A1);
        store.recordedOn(on(5, 25));

        List<Problem> problems = store.reject(by().endDate(a1, on(6, 1)).endDate(heldBy, on(6, 1)).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.RELATIONSHIP_OUTLIVES_END, opened.value());
        assertThat(problem.message()).contains("OpenedBy", "source", "does not exist from 2026-06-01T00:00:00Z");
        assertThat(store.view(on(7, 1)).entity(a1)).as("nothing was ended").isPresent();

        store.recordedOn(on(5, 26));
        Receipt receipt = store.commit(by().endDate(a1, on(6, 1)).endDate(heldBy, on(6, 1)).endDate(opened, on(6, 1))
                .build());

        assertThat(receipt.ended()).isEmpty();
        assertThat(store.view(on(5, 31)).navigate(a1, "OpenedBy")).hasSize(1);
        assertThat(store.view(on(6, 1)).entity(a1)).isEmpty();
    }

    private static Map<String, Value> address(String street) {
        return values("street", street, "city", "Springfield", "country", "US");
    }

    @Test
    void aRelationshipFromAndToTheSameEntityEndsWhenACascadeEndsTheEntity() {
        EntityId acme = entity(store.parties, ACME);
        Receipt addresses = store.commit(by()
                .create(HOME, "Address", from(1, 1), address("1 Main St"))
                .create(OFFICE, "Address", from(1, 1), address("2 High St"))
                .relate(HAS_ADDRESS, "HasAddress", alice, HOME, from(1, 1), values())
                .relate(HAS_OFFICE, "HasAddress", acme, OFFICE, from(1, 1), values())
                .relate(NEXT_TO_ITSELF, "Neighbour", HOME, HOME, from(1, 1), values())
                .relate(NEXT_DOOR, "Neighbour", OFFICE, HOME, from(1, 1), values())
                .build());
        EntityId home = entity(addresses, HOME);
        EntityId office = entity(addresses, OFFICE);
        RelationshipId nextToItself = relationship(addresses, NEXT_TO_ITSELF);
        RelationshipId nextDoor = relationship(addresses, NEXT_DOOR);
        store.recordedOn(on(5, 25));

        Receipt receipt = store.commit(by().endDate(alice, on(6, 1)).build());

        assertThat(receipt.ended()).as("Alice's address ends with her, and so does what is from and to the address, "
                + "which cannot outlive itself as its source; what is from the office stays")
                .containsExactly(relationship(addresses, HAS_ADDRESS), home, nextToItself);
        assertThat(store.view(on(5, 31)).relationship(nextToItself)).isPresent();
        assertThat(store.view(on(6, 1)).relationship(nextToItself)).isEmpty();
        assertThat(store.view(on(6, 1)).entity(home)).isEmpty();
        assertThat(store.view(on(7, 1)).relationship(nextDoor)).as("the office is next to an address that has ended")
                .isPresent();
        assertThat(store.view(on(7, 1)).navigate(office, "Neighbour")).singleElement().satisfies(link -> {
            assertThat(link.entity().id()).isEqualTo(home);
            assertThat(link.entity().period()).isEqualTo(EffectivePeriod.between(on(1, 1), on(6, 1)));
        });
    }

    // ------------------------------------------------------------------ the types that do not declare it

    @Test
    void aSubtypeThatDoesNotDeclareItEndsWithItsTarget() {
        RelationshipId agent = open("OpenedByAgent", a2, from(1, 1), "branch", on(1, 20));
        store.recordedOn(on(5, 25));

        List<Problem> problems = store.reject(by().endDate(alice, on(6, 1)).build());

        assertProblem(only(problems), ProblemCode.RELATIONSHIP_OUTLIVES_END, agent.value());
        store.recordedOn(on(5, 26));
        Receipt receipt = store.commit(by().endDate(alice, on(6, 1)).endDate(agent, on(6, 1)).build());
        assertThat(receipt.ended()).isEmpty();
        assertThat(store.view(on(7, 1)).navigate(a2, "OpenedBy")).as("by the name of the supertype too").isEmpty();
    }

    @Test
    void thePurgeOfATargetRemovesTheRelationshipsThatOutliveIt() {
        RelationshipId opened = open(a1, from(1, 1));
        endAlice(on(6, 1), on(5, 25));

        PurgeResult result = store.store.purge(Purge.by("dpo").reason("Erasure request").entity(alice).build());

        assertThat(result).isInstanceOf(PurgeResult.Purged.class);
        PurgeResult.Purged purged = (PurgeResult.Purged) result;
        assertThat(purged.entities()).containsExactly(alice);
        assertThat(purged.relationships()).containsExactly(opened);
        assertThat(store.view(on(7, 1)).relationship(opened)).isEmpty();
        assertThat(store.view(on(7, 1)).navigate(a1, "OpenedBy")).isEmpty();
    }

    // ------------------------------------------------------------------ views at older bases

    @Test
    void aViewReadsTheTargetAsKnownAtItsBasis() {
        open(a1, from(1, 1));
        endAlice(on(7, 1), on(5, 25));
        store.recordedOn(on(8, 10));
        store.commit(by().endDate(alice, on(6, 1)).build());
        store.recordedOn(on(8, 20));
        store.commit(by().patch(alice, EffectivePeriod.between(on(2, 1), on(6, 1)), values("name", "Alicia")).build());

        Coordinates august1 = Coordinates.effectiveAt(on(8, 1));
        Coordinates june15 = Coordinates.effectiveAt(on(6, 15));

        assertThat(opener(august1.knownAt(on(6, 1)))).as("1 August, known on 1 June: Alice ended on 1 July")
                .hasValueSatisfying(opener -> {
                    assertThat(opener.period()).isEqualTo(EffectivePeriod.between(on(1, 1), on(7, 1)));
                    assertThat(opener.values()).containsEntry("name", Value.of("Alice"));
                });
        assertThat(opener(august1.knownAt(on(8, 15)))).as("1 August, known on 15 August: she ended on 1 June")
                .hasValueSatisfying(opener -> {
                    assertThat(opener.period()).isEqualTo(EffectivePeriod.between(on(1, 1), on(6, 1)));
                    assertThat(opener.values()).containsEntry("name", Value.of("Alice"));
                });
        assertThat(opener(august1)).as("1 August, known now: her last version is the corrected one")
                .hasValueSatisfying(opener -> {
                    assertThat(opener.period()).isEqualTo(EffectivePeriod.between(on(2, 1), on(6, 1)));
                    assertThat(opener.values()).containsEntry("name", Value.of("Alicia"));
                });
        assertThat(opener(june15.knownAt(on(6, 1)))).as("15 June, known on 1 June: Alice existed then")
                .hasValueSatisfying(opener -> {
                    assertThat(opener.period()).isEqualTo(EffectivePeriod.between(on(1, 1), on(7, 1)));
                    assertThat(opener.period().contains(on(6, 15))).isTrue();
                });
        assertThat(opener(june15)).as("15 June, known now: Alice has ended").hasValueSatisfying(opener ->
                assertThat(opener.period()).isEqualTo(EffectivePeriod.between(on(2, 1), on(6, 1))));
    }

    /** Who A-1's OpenedBy leads to, as the view at the coordinates reads it. */
    private Optional<EntityState> opener(Coordinates coordinates) {
        return store.store.view(coordinates).navigate(a1, "OpenedBy").stream().map(Link::entity).findFirst();
    }

    // ------------------------------------------------------------------ nothing to navigate from what does not exist

    @Test
    void anEntityThatDoesNotExistHasNothingToTraverseOrNavigate() {
        open(a1, from(1, 1));
        endAlice(on(6, 1), on(5, 25));

        assertThat(store.view(on(5, 31)).traverse(alice, "opened")).extracting(step -> step.entity().id())
                .containsExactly(a1);
        assertThat(store.view(on(7, 1)).traverse(alice, "opened")).isEmpty();
        assertThat(store.view(on(7, 1)).navigate(alice, "opened")).isEmpty();
        assertThat(store.view(on(7, 1)).navigateTimeline(alice, "opened")).as("across effective time, as before")
                .hasSize(1);
    }
}
