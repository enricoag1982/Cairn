package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.assertProblem;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.CUSTOMER_ROLE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PLAYED_BY;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The scenarios of the {@code relationships} spec, one test each. */
class RelationshipsScenariosTest {

    private static final TemporaryId OWNS = new TemporaryId("owns");
    private static final TemporaryId EMPLOYS = new TemporaryId("employs");
    private static final TemporaryId ROLE = new TemporaryId("role");
    private static final TemporaryId PLAYED_BY = new TemporaryId("playedBy");
    private static final TemporaryId HOME = new TemporaryId("home");
    private static final TemporaryId HAS_ADDRESS = new TemporaryId("hasAddress");
    private static final TemporaryId A3 = new TemporaryId("a3");

    /** The reference domain with the symmetric relationship type of the scenario "Related parties". */
    private static final SchemaDefinition WITH_RELATED_PARTY = Definitions.plus(ReferenceModel.definition(),
            SchemaDefinition.builder()
                    .relationshipType("RelatedParty", r -> r.from("Party").to("Party").symmetric()
                            .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                            .inverse("relatedParties"))
                    .build());

    private final ReferenceStore reference = new ReferenceStore();

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    /** RD-2, before its correction: recorded 10 February, ACME owns 60% of Beta from 1 February. */
    private RelationshipId acmeOwnsBeta(EntityId acme, EntityId beta) {
        reference.recordedOn(on(2, 10));
        return relationship(reference.commit(Transaction.by("importer")
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 60)).build()), OWNS);
    }

    private static BigDecimal share(RelationshipState state) {
        return ((Value.DecimalValue) state.values().get("share")).value();
    }

    /** The condition of the ultimate parent: the share is above 50. */
    private static Predicate<RelationshipState> shareAbove50() {
        return relationship -> relationship.values().get("share") instanceof Value.DecimalValue share
                && share.value().compareTo(BigDecimal.valueOf(50)) > 0;
    }

    // ------------------------------------------------------------------ relating entities

    @Test
    @DisplayName("Scenario: An organization owns another")
    void anOrganizationOwnsAnother() {
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);

        Receipt receipt = reference.replay(ReferenceStories.rd2Ownership(acme, beta));

        RelationshipId owns = relationship(receipt, ACME_OWNS_BETA);
        assertThat(receipt.created()).containsExactly(owns);
        RelationshipState state = reference.view(on(3, 1)).relationship(owns).orElseThrow();
        assertThat(state.relationshipType()).isEqualTo("Owns");
        assertThat(state.source()).isEqualTo(acme);
        assertThat(state.target()).isEqualTo(beta);
        assertThat(state.values()).containsEntry("share", Value.of(new BigDecimal("60")));
        assertThat(state.period()).isEqualTo(from(2, 1));
        assertThat(state.recordedAt()).isEqualTo(on(2, 10));
        assertThat(reference.view(on(1, 31)).relationship(owns)).as("before it starts").isEmpty();
    }

    @Test
    @DisplayName("Scenario: Correcting a share")
    void correctingAShare() {
        Receipt parties = reference.createParties();
        RelationshipId owns = relationship(reference.replay(
                ReferenceStories.rd2Ownership(entity(parties, ACME), entity(parties, BETA))), ACME_OWNS_BETA);

        reference.replay(ReferenceStories.rd2ShareCorrection(owns));

        View beforeTheCorrection = reference.store.view(Coordinates.effectiveAt(on(3, 1)).knownAt(on(4, 1)));
        assertThat(share(beforeTheCorrection.relationship(owns).orElseThrow())).isEqualByComparingTo("60");
        assertThat(share(reference.view(on(3, 1)).relationship(owns).orElseThrow())).isEqualByComparingTo("55");
        assertThat(reference.view(on(3, 1)).relationshipTimeline(owns)).singleElement()
                .satisfies(version -> assertThat(version.recordedAt()).isEqualTo(on(5, 2)));
    }

    @Test
    @DisplayName("Scenario: Relating to a subtype")
    void relatingToASubtype() {
        Receipt parties = reference.createParties();
        reference.recordedOn(on(3, 1));

        Receipt receipt = reference.commit(Transaction.by("importer")
                .relate(EMPLOYS, "Employs", entity(parties, BETA), entity(parties, ALICE), from(3, 1),
                        values("title", "Analyst")).build());

        assertThat(receipt.created()).singleElement().isInstanceOf(RelationshipId.class);
        assertThat(reference.view(on(4, 1)).entity(entity(parties, ALICE)).orElseThrow().entityType())
                .isEqualTo("Person");
        assertThat(reference.view(on(4, 1)).relationship(relationship(receipt, EMPLOYS)).orElseThrow().target())
                .isEqualTo(entity(parties, ALICE));
    }

    @Test
    @DisplayName("Scenario: Wrong end type")
    void wrongEndType() {
        Receipt accounts = reference.createAccounts();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate("Owns", entity(accounts, A1), entity(reference.parties, BETA), from(2, 1),
                        values("share", 60)).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.WRONG_END_TYPE, "@write.0");
        assertThat(problem.message()).contains("source");
    }

    // ------------------------------------------------------------------ lifetimes

    @Test
    @DisplayName("Scenario: Ending an entity with continuing relationships")
    void endingAnEntityWithContinuingRelationships() {
        Receipt accounts = reference.createAccounts();
        RelationshipId heldByA1 = relationship(accounts, HELD_BY_A1);

        List<Problem> problems = reference.rejectReplay(
                ReferenceStories.rd8EndBetaAlone(entity(reference.parties, BETA)));

        assertProblem(only(problems), ProblemCode.RELATIONSHIP_OUTLIVES_END, heldByA1.value());
    }

    @Test
    @DisplayName("Scenario: Ending an entity together with its relationships")
    void endingAnEntityTogetherWithItsRelationships() {
        Receipt accounts = reference.createAccounts();
        EntityId acme = entity(reference.parties, ACME);
        EntityId beta = entity(reference.parties, BETA);
        EntityId a1 = entity(accounts, A1);
        Receipt ownership = reference.replay(ReferenceStories.rd2Ownership(acme, beta));
        Receipt employment = reference.replay(ReferenceStories.rd3Employment(beta, entity(reference.parties, ALICE)));
        Receipt customer = reference.replay(ReferenceStories.rd4CustomerRole(beta));

        Receipt receipt = reference.replay(ReferenceStories.rd8EndBeta(beta, acme, a1,
                relationship(accounts, HELD_BY_A1), relationship(ownership, ACME_OWNS_BETA),
                relationship(employment, BETA_EMPLOYS_ALICE), entity(customer, CUSTOMER_ROLE),
                relationship(customer, PLAYED_BY)));

        assertThat(receipt.created()).hasSize(1);
        assertThat(receipt.ended()).as("everything was ended by the writes themselves").isEmpty();
        assertThat(reference.view(on(5, 31)).entity(beta)).isPresent();
        assertThat(reference.view(on(6, 1)).entity(beta)).isEmpty();
        assertThat(reference.view(on(5, 31)).entity(entity(customer, CUSTOMER_ROLE))).isPresent();
        assertThat(reference.view(on(6, 1)).entity(entity(customer, CUSTOMER_ROLE))).isEmpty();
        assertThat(reference.view(on(6, 1)).entity(a1)).isPresent();
        assertThat(reference.view(on(6, 1)).navigate(a1, "HeldBy")).singleElement()
                .satisfies(link -> assertThat(link.entity().id()).isEqualTo(acme));
    }

    @Test
    @DisplayName("Scenario: Relationship before an end exists")
    void relationshipBeforeAnEndExists() {
        Receipt parties = reference.createParties();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate(EMPLOYS, "Employs", entity(parties, BETA), entity(parties, ALICE),
                        EffectivePeriod.from(on(2025, 12, 1)), values()).build());

        assertProblem(only(problems), ProblemCode.END_NOT_EFFECTIVE, "$employs");
    }

    // ------------------------------------------------------------------ multiplicities

    @Test
    @DisplayName("Scenario: Account without a holder")
    void accountWithoutAHolder() {
        reference.createAccounts();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(A3, "Account", from(1, 1), values("number", "A-3", "currency", "EUR", "balance", money("500")))
                .build());

        assertProblem(only(problems), ProblemCode.TOO_FEW_RELATIONSHIPS, "$a3[HeldBy]");
    }

    @Test
    @DisplayName("Scenario: Account with two holders")
    void accountWithTwoHolders() {
        Receipt accounts = reference.createAccounts();
        EntityId a1 = entity(accounts, A1);

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate("HeldBy", a1, entity(reference.parties, ACME), from(6, 1), values()).build());

        assertProblem(only(problems), ProblemCode.TOO_MANY_RELATIONSHIPS, a1.value() + "[HeldBy]");
    }

    @Test
    @DisplayName("Scenario: Moving an account to another holder")
    void movingAnAccountToAnotherHolder() {
        Receipt accounts = reference.createAccounts();
        EntityId a1 = entity(accounts, A1);
        EntityId acme = entity(reference.parties, ACME);
        EntityId beta = entity(reference.parties, BETA);
        reference.recordedOn(on(5, 20));

        Receipt receipt = reference.commit(Transaction.by("importer")
                .endDate(relationship(accounts, HELD_BY_A1), on(6, 1))
                .relate("HeldBy", a1, acme, from(6, 1), values()).build());

        assertThat(receipt.created()).hasSize(1);
        for (Instant instant : List.of(on(1, 1), on(3, 1), on(6, 1).minusNanos(1000), on(6, 1), on(2027, 1, 1))) {
            List<Link> holders = reference.view(instant).navigate(a1, "HeldBy");
            assertThat(holders).as("holders of A-1 at %s", instant).hasSize(1);
            assertThat(holders.getFirst().entity().id()).isEqualTo(instant.isBefore(on(6, 1)) ? beta : acme);
        }
    }

    // ------------------------------------------------------------------ duplicates and compositions

    @Test
    @DisplayName("Scenario: Owning the same organization twice")
    void owningTheSameOrganizationTwice() {
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        reference.commit(Transaction.by("importer").relate("Owns", acme, beta, from(2, 1), values("share", 55))
                .build());

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate(OWNS, "Owns", acme, beta, from(3, 1), values("share", 10)).build());

        assertProblem(only(problems), ProblemCode.DUPLICATE_RELATIONSHIP, "$owns");
    }

    // ------------------------------------------------------------------ symmetric types

    @Test
    @DisplayName("Scenario: Related parties")
    void relatedParties() {
        ReferenceStore store = ReferenceStore.with(WITH_RELATED_PARTY);
        Receipt parties = store.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        store.commit(Transaction.by("importer").relate("RelatedParty", acme, beta, from(2, 1), values()).build());

        List<Problem> problems = store.reject(Transaction.by("importer")
                .relate(OWNS, "RelatedParty", beta, acme, from(2, 1), values()).build());

        assertProblem(only(problems), ProblemCode.DUPLICATE_RELATIONSHIP, "$owns");
        assertThat(store.view(on(3, 1)).navigate(beta, "RelatedParty")).singleElement()
                .satisfies(link -> assertThat(link.entity().id()).isEqualTo(acme));
        assertThat(store.view(on(3, 1)).navigate(acme, "RelatedParty")).singleElement()
                .satisfies(link -> assertThat(link.entity().id()).isEqualTo(beta));
    }

    // ------------------------------------------------------------------ navigation

    @Test
    @DisplayName("Scenario: Who owns Beta")
    void whoOwnsBeta() {
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        RelationshipId owns = relationship(reference.replay(ReferenceStories.rd2Ownership(acme, beta)),
                ACME_OWNS_BETA);
        reference.replay(ReferenceStories.rd2ShareCorrection(owns));

        List<Link> owners = reference.view(on(3, 1)).navigate(beta, "ownedBy");

        assertThat(owners).singleElement().satisfies(link -> {
            assertThat(link.entity().id()).isEqualTo(acme);
            assertThat(link.entity().values()).containsEntry("name", Value.of("ACME"));
            assertThat(link.relationship().id()).isEqualTo(owns);
            assertThat(link.relationship().relationshipType()).isEqualTo("Owns");
            assertThat(share(link.relationship())).isEqualByComparingTo("55");
        });
    }

    @Test
    @DisplayName("Scenario: Where Alice works")
    void whereAliceWorks() {
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        reference.replay(ReferenceStories.rd3Employment(beta, alice));

        List<Link> employers = reference.view(on(4, 1)).navigate(alice, "employedBy");

        assertThat(employers).singleElement().satisfies(link -> {
            assertThat(link.entity().id()).isEqualTo(beta);
            assertThat(link.relationship().relationshipType()).isEqualTo("Employs");
            assertThat(link.relationship().values()).containsEntry("title", Value.of("Analyst"));
        });
    }

    @Test
    @DisplayName("Scenario: A role that has ended")
    void aRoleThatHasEnded() {
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        Receipt customer = reference.replay(ReferenceStories.rd4CustomerRole(beta));

        List<Link> inApril = reference.view(on(4, 1)).navigate(beta, "roles");
        List<Link> inNovember = reference.view(on(11, 1)).navigate(beta, "roles");

        assertThat(inApril).singleElement().satisfies(link -> {
            assertThat(link.entity().id()).isEqualTo(entity(customer, CUSTOMER_ROLE));
            assertThat(link.entity().entityType()).isEqualTo("CustomerRole");
            assertThat(link.entity().values()).containsEntry("segment", Value.of("Corporate"));
            assertThat(link.relationship().relationshipType()).isEqualTo("PlayedBy");
        });
        assertThat(inNovember).isEmpty();
    }

    @Test
    @DisplayName("Scenario: Every owner of Beta over time")
    void everyOwnerOfBetaOverTime() {
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        reference.recordedOn(on(2, 10));
        RelationshipId acmeOwns = relationship(reference.commit(Transaction.by("importer")
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 40)).build()), OWNS);
        reference.recordedOn(on(4, 1));
        Receipt withGamma = reference.commit(Transaction.by("importer")
                .create(GAMMA, "Organization", from(4, 1), values("name", "Gamma"))
                .relate(GAMMA_OWNS_BETA, "Owns", GAMMA, beta, from(4, 1), values("share", 50)).build());
        EntityId gamma = entity(withGamma, GAMMA);
        RelationshipId gammaOwns = relationship(withGamma, GAMMA_OWNS_BETA);

        List<RelationshipState> history = reference.view(on(5, 1)).navigateTimeline(beta, "ownedBy");

        assertThat(history).extracting(RelationshipState::id, RelationshipState::source,
                state -> state.period().from(), state -> share(state).intValueExact())
                .containsExactly(tuple(acmeOwns, acme, on(2, 1), 40), tuple(gammaOwns, gamma, on(4, 1), 50));
        assertThat(history).extracting(RelationshipState::relationshipType).containsOnly("Owns");
        assertThat(reference.view(on(1, 1)).navigateTimeline(beta, "ownedBy")).as("the effective time does not matter")
                .isEqualTo(history);
    }

    // ------------------------------------------------------------------ traversal

    @Test
    @DisplayName("Scenario: Ultimate parent")
    void ultimateParent() {
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        RelationshipId owns = relationship(reference.replay(ReferenceStories.rd2Ownership(acme, beta)),
                ACME_OWNS_BETA);
        reference.replay(ReferenceStories.rd2ShareCorrection(owns));

        List<Step> steps = reference.view(on(3, 1)).traverse(beta, "ownedBy", shareAbove50(), 10);

        assertThat(steps).singleElement().satisfies(step -> {
            assertThat(step.entity().id()).isEqualTo(acme);
            assertThat(step.depth()).isEqualTo(1);
            assertThat(step.via().id()).isEqualTo(owns);
        });
        assertThat(steps.getLast().entity().values()).as("Beta's ultimate parent").containsEntry("name",
                Value.of("ACME"));
    }

    @Test
    @DisplayName("Scenario: Ownership cycle")
    void ownershipCycle() {
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        reference.commit(Transaction.by("importer")
                .relate("Owns", acme, beta, from(2, 1), values("share", 60))
                .relate("Owns", beta, acme, from(2, 1), values("share", 60)).build());

        List<Step> steps = reference.view(on(3, 1)).traverse(beta, "ownedBy", shareAbove50(), 10);

        assertThat(steps).singleElement().satisfies(step -> {
            assertThat(step.entity().id()).isEqualTo(acme);
            assertThat(step.depth()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("Scenario: Addresses end with their party")
    void addressesEndWithTheirParty() {
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        Receipt withAddress = reference.commit(Transaction.by("importer")
                .create(HOME, "Address", from(1, 1),
                        values("street", "1 Main St", "city", "Springfield", "country", "US"))
                .relate(HAS_ADDRESS, "HasAddress", beta, HOME, from(1, 1), values()).build());
        EntityId home = entity(withAddress, HOME);

        reference.commit(Transaction.by("importer").endDate(beta, on(6, 1)).build());

        assertThat(reference.view(on(5, 31)).entity(beta)).isPresent();
        assertThat(reference.view(on(5, 31)).entity(home)).isPresent();
        assertThat(reference.view(on(6, 1)).entity(beta)).isEmpty();
        assertThat(reference.view(on(6, 1)).entity(home)).isEmpty();
        assertThat(reference.view(on(6, 1)).entities("Address")).extracting(EntityState::id).isEmpty();
    }
}
