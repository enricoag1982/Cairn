package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.assertProblem;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scenarios of the {@code relationships} spec that are decided by what a commit accepts or rejects, one test each.
 * The scenarios that read relationships through a view (an organization owns another, correcting a share, moving an
 * account to another holder, related parties, navigation and traversal) come with the views of relationships.
 */
class RelationshipsScenariosTest {

    private static final TemporaryId OWNS = new TemporaryId("owns");
    private static final TemporaryId EMPLOYS = new TemporaryId("employs");
    private static final TemporaryId ROLE = new TemporaryId("role");
    private static final TemporaryId PLAYED_BY = new TemporaryId("playedBy");
    private static final TemporaryId HOME = new TemporaryId("home");
    private static final TemporaryId HAS_ADDRESS = new TemporaryId("hasAddress");
    private static final TemporaryId A3 = new TemporaryId("a3");

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

    // ------------------------------------------------------------------ relating entities

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

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .endDate(entity(reference.parties, BETA), on(6, 1)).build());

        assertProblem(only(problems), ProblemCode.RELATIONSHIP_OUTLIVES_END, heldByA1.value());
    }

    @Test
    @DisplayName("Scenario: Ending an entity together with its relationships")
    void endingAnEntityTogetherWithItsRelationships() {
        Receipt accounts = reference.createAccounts();
        EntityId acme = entity(reference.parties, ACME);
        EntityId beta = entity(reference.parties, BETA);
        EntityId alice = entity(reference.parties, ALICE);
        EntityId a1 = entity(accounts, A1);
        RelationshipId owns = acmeOwnsBeta(acme, beta);
        reference.recordedOn(on(3, 1));
        RelationshipId employs = relationship(reference.commit(Transaction.by("importer")
                .relate(EMPLOYS, "Employs", beta, alice, from(3, 1), values("title", "Analyst")).build()), EMPLOYS);
        Receipt customer = reference.commit(Transaction.by("importer")
                .create(ROLE, "CustomerRole", EffectivePeriod.between(on(3, 1), on(10, 1)),
                        values("segment", "Corporate"))
                .relate(PLAYED_BY, "PlayedBy", ROLE, beta, EffectivePeriod.between(on(3, 1), on(10, 1)), values())
                .build());
        reference.recordedOn(on(5, 20));

        Receipt receipt = reference.commit(Transaction.by("importer")
                .endDate(beta, on(6, 1))
                .endDate(relationship(accounts, HELD_BY_A1), on(6, 1))
                .relate("HeldBy", a1, acme, from(6, 1), values())
                .endDate(owns, on(6, 1))
                .endDate(employs, on(6, 1))
                .endDate(entity(customer, ROLE), on(6, 1))
                .endDate(relationship(customer, PLAYED_BY), on(6, 1)).build());

        assertThat(receipt.created()).hasSize(1);
        assertThat(reference.view(on(5, 31)).entity(beta)).isPresent();
        assertThat(reference.view(on(6, 1)).entity(beta)).isEmpty();
        assertThat(reference.view(on(6, 1)).entity(entity(customer, ROLE))).isEmpty();
        assertThat(reference.view(on(6, 1)).entity(a1)).isPresent();
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
