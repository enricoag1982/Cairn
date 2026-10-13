package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.assertProblem;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI_WRONG_CHECK_DIGITS;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
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
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Design D3 and D4 of {@code add-relationships}: the integrity of relationships at every point in effective time (the
 * types and lifetimes of the ends, multiplicities, duplicates), the composition cascades, and the order and reach of
 * the problems.
 *
 * <p>The world is the reference domain of RD-1 and RD-5: ACME, Beta and Alice from 1 January, and accounts A-1, held by
 * Beta, and A-2, held by ACME.
 */
class StoreIntegrityTest {

    private static final TemporaryId OWNS = new TemporaryId("owns");
    private static final TemporaryId EMPLOYS = new TemporaryId("employs");
    private static final TemporaryId HOME = new TemporaryId("home");
    private static final TemporaryId A3 = new TemporaryId("a3");
    private static final TemporaryId ROLE = new TemporaryId("role");

    private final ReferenceStore reference = new ReferenceStore();
    private EntityId acme;
    private EntityId beta;
    private EntityId alice;
    private EntityId a1;
    private RelationshipId heldByA1;

    @BeforeEach
    void theWorldOfRd5() {
        Receipt accounts = reference.createAccounts();
        acme = entity(reference.parties, ACME);
        beta = entity(reference.parties, BETA);
        alice = entity(reference.parties, ALICE);
        a1 = entity(accounts, A1);
        heldByA1 = relationship(accounts, HELD_BY_A1);
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    private static Transaction.Builder transaction() {
        return Transaction.by("importer");
    }

    private static Transaction.Builder newAccount(Transaction.Builder builder, TemporaryId account) {
        return builder.create(account, "Account", from(1, 1),
                values("number", "A-3", "currency", "EUR", "balance", money("10")));
    }

    private static Transaction.Builder newAddress(Transaction.Builder builder, TemporaryId address) {
        return builder.create(address, "Address", from(1, 1),
                values("street", "1 Main St", "city", "Springfield", "country", "US"));
    }

    // ------------------------------------------------------------------ the types of the ends

    @Test
    void theSourceMustBeOfTheSourceTypeOrOneOfItsSubtypes() {
        Problem problem = only(reference.reject(transaction()
                .relate("Owns", a1, beta, from(2, 1), values("share", 60)).build()));

        assertProblem(problem, ProblemCode.WRONG_END_TYPE, "@write.0");
        assertThat(problem.message()).contains("source", "Owns", "Organization", "Account");
        assertThat(problem.found()).contains("Account");
    }

    @Test
    void theTargetMustBeOfTheTargetTypeOrOneOfItsSubtypes() {
        Problem problem = only(reference.reject(transaction()
                .relate(EMPLOYS, "Employs", acme, beta, from(3, 1), values()).build()));

        assertProblem(problem, ProblemCode.WRONG_END_TYPE, "$employs");
        assertThat(problem.message()).contains("target", "Person", "Organization");
    }

    @Test
    void bothEndsAreReportedIfBothAreWrong() {
        List<Problem> problems = reference.reject(transaction()
                .relate("Employs", alice, acme, from(3, 1), values()).build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.WRONG_END_TYPE, "@write.0"), tuple(ProblemCode.WRONG_END_TYPE, "@write.0"));
        assertThat(problems.get(0).message()).contains("source");
        assertThat(problems.get(1).message()).contains("target");
    }

    @Test
    void subtypesOfTheEndTypesAreAccepted() {
        reference.commit(transaction()
                .relate("Owns", acme, alice, from(2, 1), values("share", 10))
                .relate("Employs", beta, alice, from(3, 1), values())
                .build());
        reference.commit(newAddress(transaction(), HOME).relate("HasAddress", alice, HOME, from(1, 1), values())
                .build());
    }

    @Test
    void aRelationshipWithWrongEndsTakesPartInNoMultiplicityCheckAndMakesNoneReported() {
        List<Problem> problems = reference.reject(newAccount(transaction(), A3)
                .relate("HeldBy", beta, A3, from(1, 1), values()).build());

        assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.WRONG_END_TYPE,
                ProblemCode.WRONG_END_TYPE);
    }

    @Test
    void anEntityThatCouldNotBeCreatedIsNotRelatedAndDoesNotAddProblems() {
        List<Problem> problems = reference.reject(transaction()
                .create(GAMMA, "Organisation", from(1, 1), values("name", "Gamma"))
                .relate(OWNS, "Owns", GAMMA, beta, from(2, 1), values("share", 10)).build());

        assertProblem(only(problems), ProblemCode.UNKNOWN_ENTITY_TYPE, "$gamma");
    }

    @Test
    void anEntityMayBeRelatedToItself() {
        reference.commit(transaction().relate("Owns", acme, acme, from(2, 1), values("share", 10)).build());

        List<Problem> problems = reference.reject(transaction()
                .relate(OWNS, "Owns", acme, acme, from(3, 1), values("share", 10)).build());

        assertProblem(only(problems), ProblemCode.DUPLICATE_RELATIONSHIP, "$owns");
    }

    // ------------------------------------------------------------------ ends exist throughout a relationship

    @Test
    void aRelationshipCannotStartBeforeItsEndsExist() {
        List<Problem> problems = reference.reject(transaction()
                .relate(OWNS, "Employs", beta, alice, EffectivePeriod.from(on(2025, 12, 1)), values()).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.END_NOT_EFFECTIVE, "$owns");
        assertThat(problem.message()).as("both ends are named, in one problem").contains("source", "target");
    }

    @Test
    void aRelationshipCannotLastLongerThanAnEnd() {
        reference.commit(transaction().endDate(alice, on(6, 1)).build());

        List<Problem> problems = reference.reject(transaction()
                .relate(OWNS, "Employs", beta, alice, from(5, 1), values()).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.END_NOT_EFFECTIVE, "$owns");
        assertThat(problem.message()).contains("target").contains("2026-06-01T00:00:00Z until further notice");
        reference.commit(transaction().relate(OWNS, "Employs", beta, alice, EffectivePeriod.between(on(5, 1),
                on(6, 1)), values()).build());
    }

    @Test
    void aRelationshipThatLastsLongerThanAnEndThatTheSameTransactionEndsIsNotEffectiveEither() {
        List<Problem> problems = reference.reject(transaction()
                .endDate(alice, on(6, 1))
                .relate(OWNS, "Employs", beta, alice, from(5, 1), values()).build());

        assertProblem(only(problems), ProblemCode.END_NOT_EFFECTIVE, "$owns");
    }

    @Test
    void anEntityMayBeRelatedFromTheDayItIsCreatedInTheSameTransaction() {
        reference.commit(transaction()
                .create(GAMMA, "Organization", from(3, 1), values("name", "Gamma"))
                .relate(OWNS, "Owns", GAMMA, beta, from(3, 1), values("share", 10)).build());

        List<Problem> problems = reference.reject(transaction()
                .create(GAMMA, "Organization", from(3, 1), values("name", "Gamma"))
                .relate(OWNS, "Owns", GAMMA, beta, from(2, 1), values("share", 10)).build());

        assertProblem(only(problems), ProblemCode.END_NOT_EFFECTIVE, "$owns");
    }

    // ------------------------------------------------------------------ a relationship does not outlive an end

    @Test
    void endingAnEntityWhileARelationshipToItContinuesIsRejectedWithAProblemAboutTheRelationship() {
        List<Problem> problems = reference.reject(transaction().endDate(beta, on(6, 1)).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.RELATIONSHIP_OUTLIVES_END, heldByA1.value());
        assertThat(problem.message()).contains("HeldBy", "target");
    }

    @Test
    void endingAnEntityTogetherWithItsRelationshipsFromTheSameInstantIsAccepted() {
        reference.commit(transaction()
                .endDate(beta, on(6, 1))
                .endDate(heldByA1, on(6, 1))
                .relate("HeldBy", a1, acme, from(6, 1), values()).build());

        assertThat(reference.view(on(5, 31)).entity(beta)).isPresent();
        assertThat(reference.view(on(6, 1)).entity(beta)).isEmpty();
    }

    @Test
    void aRelationshipThatEndsWhenAnEndEndsIsAccepted() {
        reference.commit(transaction()
                .relate(OWNS, "Employs", beta, alice, EffectivePeriod.between(on(3, 1), on(5, 1)), values())
                .build());

        reference.commit(transaction().endDate(alice, on(5, 1)).build());

        assertThat(reference.view(on(5, 1)).entity(alice)).isEmpty();
    }

    @Test
    void aRelationshipThatEndsAfterAnEndIsRejected() {
        RelationshipId employs = relationship(reference.commit(transaction()
                .relate(OWNS, "Employs", beta, alice, EffectivePeriod.between(on(3, 1), on(8, 1)), values())
                .build()), OWNS);

        List<Problem> problems = reference.reject(transaction().endDate(alice, on(5, 1)).build());

        assertProblem(only(problems), ProblemCode.RELATIONSHIP_OUTLIVES_END, employs.value());
    }

    @Test
    void aRelationshipThatTheTransactionChangesButDoesNotEndIsStillOutliving() {
        List<Problem> problems = reference.reject(transaction()
                .endDate(beta, on(6, 1))
                .patch(heldByA1, from(3, 1), values()).build());

        assertProblem(only(problems), ProblemCode.RELATIONSHIP_OUTLIVES_END, heldByA1.value());
    }

    @Test
    void aRelationshipWhoseTwoEndsEndIsReportedOnce() {
        RelationshipId employs = relationship(reference.commit(transaction()
                .relate(OWNS, "Employs", beta, alice, from(3, 1), values()).build()), OWNS);

        List<Problem> problems = reference.reject(transaction()
                .endDate(alice, on(6, 1))
                .endDate(beta, on(6, 1))
                .endDate(heldByA1, on(6, 1))
                .relate("HeldBy", a1, acme, from(6, 1), values()).build());

        assertProblem(only(problems), ProblemCode.RELATIONSHIP_OUTLIVES_END, employs.value());
    }

    @Test
    void anEntityThatLastsLongerThanItsRelationshipsIsNotAffectedByTheirEnd() {
        RelationshipId employs = relationship(reference.commit(transaction()
                .relate(OWNS, "Employs", beta, alice, from(3, 1), values()).build()), OWNS);

        reference.commit(transaction().endDate(employs, on(6, 1)).build());

        assertThat(reference.view(on(7, 1)).entity(alice)).isPresent();
        assertThat(reference.view(on(7, 1)).entity(beta)).isPresent();
    }

    // ------------------------------------------------------------------ multiplicities

    @Test
    void anAccountWithoutAHolderIsRejectedWithAProblemAboutTheAccountAndTheRelationshipType() {
        List<Problem> problems = reference.reject(newAccount(transaction(), A3).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.TOO_FEW_RELATIONSHIPS, "$a3[HeldBy]");
        assertThat(problem.message()).contains("0 HeldBy relationships", "at least 1");
        assertThat(problem.found()).contains("0");
    }

    @Test
    void anAddressWithoutAPartyIsReportedUnderTheInverseNameOfTheRelationshipType() {
        List<Problem> problems = reference.reject(newAddress(transaction(), HOME).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.TOO_FEW_RELATIONSHIPS, "$home[party]");
        assertThat(problem.message()).contains("HasAddress", "party");
    }

    @Test
    void aRoleWithoutAPartyIsReportedUnderTheNameOfTheRelationshipTypeBecauseItIsTheSource() {
        List<Problem> problems = reference.reject(transaction()
                .create(ROLE, "CustomerRole", from(3, 1), values("segment", "Retail")).build());

        assertProblem(only(problems), ProblemCode.TOO_FEW_RELATIONSHIPS, "$role[PlayedBy]");
    }

    @Test
    void theMinimumHoldsForTheWholeLifetimeAndTheGapIsReported() {
        List<Problem> problems = reference.reject(newAccount(transaction(), A3)
                .relate("HeldBy", A3, beta, from(2, 1), values()).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.TOO_FEW_RELATIONSHIPS, "$a3[HeldBy]");
        assertThat(problem.message()).contains("from 2026-01-01T00:00:00Z until 2026-02-01T00:00:00Z");
    }

    @Test
    void theMinimumAppliesOnlyWhereTheEntityExists() {
        reference.commit(transaction()
                .create(A3, "Account", EffectivePeriod.between(on(3, 1), on(9, 1)),
                        values("number", "A-3", "currency", "EUR", "balance", money("10")))
                .relate("HeldBy", A3, beta, EffectivePeriod.between(on(3, 1), on(9, 1)), values()).build());
    }

    @Test
    void anAccountWithTwoHoldersAtTheSameTimeIsRejected() {
        List<Problem> problems = reference.reject(transaction()
                .relate("HeldBy", a1, acme, from(6, 1), values()).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.TOO_MANY_RELATIONSHIPS, a1.value() + "[HeldBy]");
        assertThat(problem.message()).contains("2 HeldBy relationships", "from 2026-06-01T00:00:00Z", "at most 1");
    }

    @Test
    void movingAnAccountToAnotherHolderWithoutAGapOrAnOverlapIsAccepted() {
        reference.commit(transaction()
                .endDate(heldByA1, on(6, 1))
                .relate("HeldBy", a1, acme, from(6, 1), values()).build());
    }

    @Test
    void movingAnAccountWithAGapLeavesItWithoutAHolderForAWhile() {
        List<Problem> problems = reference.reject(transaction()
                .endDate(heldByA1, on(6, 1))
                .relate("HeldBy", a1, acme, from(7, 1), values()).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.TOO_FEW_RELATIONSHIPS, a1.value() + "[HeldBy]");
        assertThat(problem.message()).contains("from 2026-06-01T00:00:00Z until 2026-07-01T00:00:00Z");
    }

    @Test
    void endingTheOnlyHolderOfAnAccountLeavesItWithoutOne() {
        List<Problem> problems = reference.reject(transaction().endDate(heldByA1, on(6, 1)).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.TOO_FEW_RELATIONSHIPS, a1.value() + "[HeldBy]");
        assertThat(problem.message()).contains("from 2026-06-01T00:00:00Z until further notice");
    }

    @Test
    void aMaximumOnTheTargetEndIsCheckedToo() {
        reference.commit(newAddress(transaction(), HOME).relate("HasAddress", alice, HOME, from(1, 1), values())
                .build());
        Receipt second = reference.commit(newAddress(transaction(), new TemporaryId("work"))
                .relate("HasAddress", beta, new TemporaryId("work"), from(1, 1), values()).build());
        EntityId work = entity(second, new TemporaryId("work"));

        List<Problem> problems = reference.reject(transaction().relate("HasAddress", acme, work, from(4, 1), values())
                .build());

        assertProblem(only(problems), ProblemCode.TOO_MANY_RELATIONSHIPS, work.value() + "[party]");
    }

    @Test
    void anEntityThatHasNoMultiplicityToKeepNeverHasAProblem() {
        reference.commit(transaction()
                .relate("Owns", acme, beta, from(2, 1), values("share", 10))
                .relate("Owns", beta, alice, from(2, 1), values("share", 10))
                .relate("Employs", acme, alice, from(2, 1), values()).build());
    }

    // ------------------------------------------------------------------ duplicates

    @Test
    void twoRelationshipsOfOneTypeBetweenTheSameEntitiesMayNotOverlap() {
        reference.commit(transaction().relate("Owns", acme, beta, from(2, 1), values("share", 55)).build());

        List<Problem> problems = reference.reject(transaction()
                .relate(OWNS, "Owns", acme, beta, from(3, 1), values("share", 10)).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.DUPLICATE_RELATIONSHIP, "$owns");
        assertThat(problem.message()).contains("from 2026-03-01T00:00:00Z until further notice");
    }

    @Test
    void relationshipsThatDoNotOverlapAreNotDuplicates() {
        reference.commit(transaction()
                .relate("Owns", acme, beta, EffectivePeriod.between(on(2, 1), on(5, 1)), values("share", 55))
                .build());

        reference.commit(transaction().relate("Owns", acme, beta, from(5, 1), values("share", 10)).build());
    }

    @Test
    void relationshipsOfOtherTypesOrBetweenOtherEntitiesAreNotDuplicates() {
        reference.commit(transaction().relate("Owns", acme, alice, from(2, 1), values("share", 55)).build());

        reference.commit(transaction()
                .relate("Employs", acme, alice, from(2, 1), values())
                .relate("Owns", acme, beta, from(2, 1), values("share", 55))
                .relate("Owns", beta, acme, from(2, 1), values("share", 55)).build());
    }

    @Test
    void twoDuplicatesInOneTransactionAreReportedOnTheSecond() {
        List<Problem> problems = reference.reject(transaction()
                .relate(new TemporaryId("first"), "Owns", acme, beta, from(2, 1), values("share", 10))
                .relate(OWNS, "Owns", acme, beta, from(3, 1), values("share", 10)).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.DUPLICATE_RELATIONSHIP, "$owns");
        assertThat(problem.message()).contains("$first");
    }

    // ------------------------------------------------------------------ symmetric types, subtypes and compositions

    /** Types that the reference domain does not have: symmetric and subtype relationship types, compositions in a chain. */
    private static SchemaDefinition extras() {
        return Definitions.plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .relationshipType("RelatedParty", r -> r.from("Party").to("Party").symmetric()
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("relatedParties"))
                .relationshipType("Partner", r -> r.from("Person").to("Person").symmetric()
                        .targetsPerSource(Multiplicity.ZERO_OR_ONE).sourcesPerTarget(Multiplicity.ZERO_OR_ONE)
                        .inverse("partners"))
                .relationshipType("WorksFor", r -> r.from("Person").to("Organization")
                        .targetsPerSource(Multiplicity.ZERO_OR_ONE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("workers"))
                .relationshipType("Contracts", r -> r.supertype("WorksFor").from("Person").to("Organization")
                        .targetsPerSource(Multiplicity.ZERO_OR_ONE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("contractors"))
                .entityType("Region", e -> e.attribute("name", ValueType.STRING))
                .entityType("Site", e -> e.attribute("name", ValueType.STRING))
                .entityType("Room", e -> e.attribute("name", ValueType.STRING))
                .entityType("Tag", e -> e.attribute("name", ValueType.STRING))
                .relationshipType("HasSite", r -> r.from("Region").to("Site").composition()
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.EXACTLY_ONE)
                        .inverse("region"))
                .relationshipType("HasRoom", r -> r.from("Site").to("Room").composition()
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.EXACTLY_ONE)
                        .inverse("site"))
                .relationshipType("Tagged", r -> r.from("Site").to("Tag")
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("tags"))
                .build());
    }

    private static final TemporaryId REGION = new TemporaryId("region");
    private static final TemporaryId SITE = new TemporaryId("site");
    private static final TemporaryId ROOM = new TemporaryId("room");
    private static final TemporaryId TAG = new TemporaryId("tag");

    /** A store of the reference domain with the extra types, ACME, Beta and Alice, and a region with a site and a room. */
    private static final class Estate {
        final ReferenceStore store = ReferenceStore.with(extras());
        final Receipt parties = store.createParties();
        final Receipt estate = store.commit(transaction()
                .create(REGION, "Region", from(1, 1), values("name", "North"))
                .create(SITE, "Site", from(1, 1), values("name", "Depot"))
                .create(ROOM, "Room", from(1, 1), values("name", "Room 1"))
                .create(TAG, "Tag", from(1, 1), values("name", "cold"))
                .relate(new TemporaryId("hasSite"), "HasSite", REGION, SITE, from(1, 1), values())
                .relate(new TemporaryId("hasRoom"), "HasRoom", SITE, ROOM, from(1, 1), values())
                .relate(new TemporaryId("tagged"), "Tagged", SITE, TAG, from(1, 1), values()).build());
    }

    @Test
    void aSymmetricRelationshipIsTheSameInEitherOrder() {
        ReferenceStore store = ReferenceStore.with(extras());
        Receipt parties = store.createParties();
        EntityId acmeId = entity(parties, ACME);
        EntityId betaId = entity(parties, BETA);
        store.commit(transaction().relate("RelatedParty", acmeId, betaId, from(2, 1), values()).build());

        List<Problem> problems = store.reject(transaction()
                .relate(new TemporaryId("again"), "RelatedParty", betaId, acmeId, from(2, 1), values()).build());

        assertProblem(only(problems), ProblemCode.DUPLICATE_RELATIONSHIP, "$again");
    }

    @Test
    void anAsymmetricRelationshipInTheOtherOrderIsNotADuplicate() {
        reference.commit(transaction()
                .relate("Owns", acme, beta, from(2, 1), values("share", 60))
                .relate("Owns", beta, acme, from(2, 1), values("share", 60)).build());
    }

    @Test
    void aSymmetricRelationshipAcceptsTheEntitiesOfItsTypeInEitherRole() {
        ReferenceStore store = ReferenceStore.with(extras());
        Receipt parties = store.createParties();

        store.commit(transaction()
                .relate("RelatedParty", entity(parties, ALICE), entity(parties, ACME), from(2, 1), values())
                .relate("RelatedParty", entity(parties, BETA), entity(parties, ALICE), from(2, 1), values()).build());
    }

    @Test
    void aSymmetricMultiplicityCountsTheRelationshipsAtEitherEnd() {
        ReferenceStore store = ReferenceStore.with(extras());
        Receipt people = store.commit(transaction()
                .create(new TemporaryId("p"), "Person", from(1, 1), values("name", "P"))
                .create(new TemporaryId("q"), "Person", from(1, 1), values("name", "Q"))
                .create(new TemporaryId("r"), "Person", from(1, 1), values("name", "R"))
                .relate("Partner", new TemporaryId("q"), new TemporaryId("p"), from(2, 1), values()).build());
        EntityId p = entity(people, new TemporaryId("p"));

        List<Problem> problems = store.reject(transaction()
                .relate("Partner", p, entity(people, new TemporaryId("r")), from(3, 1), values()).build());

        assertProblem(only(problems), ProblemCode.TOO_MANY_RELATIONSHIPS, p.value() + "[Partner]");
    }

    @Test
    void theRelationshipsOfSubtypesCountTowardsTheMaximumOfTheirSupertype() {
        ReferenceStore store = ReferenceStore.with(extras());
        Receipt created = store.commit(transaction()
                .create(new TemporaryId("p"), "Person", from(1, 1), values("name", "P"))
                .create(new TemporaryId("c"), "Organization", from(1, 1), values("name", "C"))
                .create(new TemporaryId("d"), "Organization", from(1, 1), values("name", "D"))
                .relate("WorksFor", new TemporaryId("p"), new TemporaryId("c"), from(2, 1), values()).build());
        EntityId p = entity(created, new TemporaryId("p"));

        List<Problem> problems = store.reject(transaction()
                .relate("Contracts", p, entity(created, new TemporaryId("d")), from(3, 1), values()).build());

        assertProblem(only(problems), ProblemCode.TOO_MANY_RELATIONSHIPS, p.value() + "[WorksFor]");
    }

    @Test
    void endingAnEntityEndsItsCompositionsAndTheirTargetsFromTheSameInstant() {
        reference.commit(newAddress(transaction(), HOME).relate("HasAddress", alice, HOME, from(1, 1), values())
                .build());

        reference.commit(transaction().endDate(alice, on(6, 1)).build());

        assertThat(reference.view(on(5, 31)).entities("Address")).hasSize(1);
        assertThat(reference.view(on(6, 1)).entity(alice)).isEmpty();
        assertThat(reference.view(on(6, 1)).entities("Address")).isEmpty();
    }

    @Test
    void theCompositionsCreatedInTheSameTransactionEndToo() {
        reference.commit(newAddress(transaction(), HOME).relate("HasAddress", alice, HOME, from(1, 1), values())
                .endDate(alice, on(6, 1)).build());

        assertThat(reference.view(on(5, 31)).entities("Address")).hasSize(1);
        assertThat(reference.view(on(6, 1)).entities("Address")).isEmpty();
    }

    @Test
    void theEndsOfACompositionCascadeThroughSeveralLevels() {
        Estate estate = new Estate();
        EntityId region = entity(estate.estate, REGION);
        EntityId site = entity(estate.estate, SITE);
        EntityId room = entity(estate.estate, ROOM);
        RelationshipId tagged = relationship(estate.estate, new TemporaryId("tagged"));

        List<Problem> without = estate.store.reject(transaction().endDate(region, on(6, 1)).build());
        estate.store.commit(transaction().endDate(region, on(6, 1)).endDate(tagged, on(6, 1)).build());

        assertProblem(only(without), ProblemCode.RELATIONSHIP_OUTLIVES_END, tagged.value());
        assertThat(estate.store.view(on(5, 31)).entities("Room")).hasSize(1);
        assertThat(estate.store.view(on(6, 1)).entity(region)).isEmpty();
        assertThat(estate.store.view(on(6, 1)).entity(site)).as("a target of the composition").isEmpty();
        assertThat(estate.store.view(on(6, 1)).entity(room)).as("the target of its target").isEmpty();
        assertThat(estate.store.view(on(6, 1)).entities("Tag")).as("an association does not end its target")
                .hasSize(1);
    }

    @Test
    void aCompositionThatEndedEarlierIsLeftAlone() {
        Estate estate = new Estate();
        EntityId room = entity(estate.estate, ROOM);
        RelationshipId hasRoom = relationship(estate.estate, new TemporaryId("hasRoom"));
        estate.store.commit(transaction().endDate(hasRoom, on(4, 1)).endDate(room, on(4, 1)).build());

        estate.store.commit(transaction()
                .endDate(entity(estate.estate, REGION), on(6, 1))
                .endDate(relationship(estate.estate, new TemporaryId("tagged")), on(6, 1)).build());

        assertThat(estate.store.view(on(5, 1)).timeline(room)).singleElement().satisfies(state ->
                assertThat(state.period().until()).contains(on(4, 1)));
    }

    @Test
    void endingTheTargetOfACompositionAloneLeavesItsRelationshipOutlivingIt() {
        Estate estate = new Estate();
        EntityId room = entity(estate.estate, ROOM);
        RelationshipId hasRoom = relationship(estate.estate, new TemporaryId("hasRoom"));

        List<Problem> problems = estate.store.reject(transaction().endDate(room, on(6, 1)).build());

        assertProblem(only(problems), ProblemCode.RELATIONSHIP_OUTLIVES_END, hasRoom.value());
    }

    @Test
    void aCompositionThatStartsAfterTheEndIsLeftForTheIntegrityChecksToReport() {
        Estate estate = new Estate();
        EntityId site = entity(estate.estate, SITE);
        Receipt later = estate.store.commit(transaction()
                .create(new TemporaryId("annex"), "Room", from(8, 1), values("name", "Annex"))
                .relate(new TemporaryId("hasAnnex"), "HasRoom", site, new TemporaryId("annex"), from(8, 1), values())
                .build());
        RelationshipId hasAnnex = relationship(later, new TemporaryId("hasAnnex"));

        List<Problem> problems = estate.store.reject(transaction()
                .endDate(entity(estate.estate, REGION), on(6, 1))
                .endDate(relationship(estate.estate, new TemporaryId("tagged")), on(6, 1)).build());

        assertProblem(only(problems), ProblemCode.RELATIONSHIP_OUTLIVES_END, hasAnnex.value());
    }

    // ------------------------------------------------------------------ dry runs, order and rules

    @Test
    void aDryRunChecksIntegrityAsACommitDoes() {
        Transaction withoutHolder = newAccount(transaction(), A3).build();
        Transaction endingBeta = transaction().endDate(beta, on(6, 1)).build();
        Transaction twoHolders = transaction().relate("HeldBy", a1, acme, from(6, 1), values()).build();
        Transaction duplicate = transaction().relate("Owns", acme, beta, from(2, 1), values("share", 10))
                .relate("Owns", acme, beta, from(3, 1), values("share", 10)).build();

        for (Transaction invalid : List.of(withoutHolder, endingBeta, twoHolders, duplicate)) {
            DryRunResult dry = reference.store.dryRun(invalid);
            assertThat(dry).isInstanceOf(DryRunResult.Rejected.class);
            assertThat(((DryRunResult.Rejected) dry).problems()).isEqualTo(reference.reject(invalid));
        }
    }

    @Test
    void aDryRunOfAValidTransactionThatRelatesRecordsNothing() {
        var basis = reference.store.latestBasis();

        DryRunResult dry = reference.store.dryRun(transaction()
                .endDate(heldByA1, on(6, 1))
                .relate("HeldBy", a1, acme, from(6, 1), values()).build());

        assertThat(dry).isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(reference.store.latestBasis()).isEqualTo(basis);
        assertThat(reference.view(on(7, 1)).entity(a1)).isPresent();
    }

    @Test
    void problemsComeInTheOrderOfTheTransactionThenTheWritesThenTheSubjectsInTheOrderOfFirstTouch() {
        List<Problem> problems = reference.reject(transaction()
                .create(A3, "Account", from(1, 1), values("number", "A-3", "balance", money("10")))
                .relate("Owns", acme, beta, EffectivePeriod.between(on(3, 1), on(2, 1)), values("share", 10))
                .relate("Owns", acme, alice, from(2, 1), values("share", 0)).build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.INVALID_PERIOD, "@write.1"),
                tuple(ProblemCode.MISSING_REQUIRED_VALUE, "$a3.currency"),
                tuple(ProblemCode.TOO_FEW_RELATIONSHIPS, "$a3[HeldBy]"),
                tuple(ProblemCode.VALUE_OUT_OF_RANGE, "@write.2.share"));
    }

    @Test
    void aPrincipalThatIsMissingComesBeforeEverything() {
        List<Problem> problems = reference.reject(Transaction.by("").create(A3, "Account", from(1, 1), values())
                .build());

        assertThat(problems.getFirst().code()).isEqualTo(ProblemCode.MISSING_PRINCIPAL);
    }

    @Test
    void anEntityWithAProblemAboutItsRelationshipsIsNotCheckedByItsRules() {
        SchemaDefinition sponsored = Definitions.plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .relationshipType("SponsoredBy", r -> r.from("Organization").to("Party")
                        .targetsPerSource(Multiplicity.EXACTLY_ONE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("sponsors"))
                .build());
        ReferenceStore store = ReferenceStore.with(sponsored);
        Receipt parties = store.commit(transaction()
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .create(ALICE, "Person", from(1, 1), values("name", "Alice"))
                .relate("SponsoredBy", ACME, ALICE, from(1, 1), values()).build());
        EntityId aliceId = entity(parties, ALICE);

        List<Problem> withoutSponsor = store.reject(transaction()
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma", "lei", LEI_WRONG_CHECK_DIGITS))
                .build());
        List<Problem> withSponsor = store.reject(transaction()
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma", "lei", LEI_WRONG_CHECK_DIGITS))
                .relate("SponsoredBy", GAMMA, aliceId, from(1, 1), values()).build());

        assertThat(withoutSponsor).extracting(Problem::code, Problem::path)
                .containsExactly(tuple(ProblemCode.TOO_FEW_RELATIONSHIPS, "$gamma[SponsoredBy]"));
        assertThat(withSponsor).extracting(Problem::code, Problem::path)
                .containsExactly(tuple(ProblemCode.RULE_VIOLATION, "$gamma.lei"));
    }

    @Test
    void anEntityThatIsOnlyTheEndOfAChangedRelationshipHasItsMultiplicitiesChecked() {
        EntityState before = reference.view(on(3, 1)).entity(a1).orElseThrow();

        List<Problem> problems = reference.reject(transaction().endDate(heldByA1, on(6, 1)).build());

        assertProblem(only(problems), ProblemCode.TOO_FEW_RELATIONSHIPS, a1.value() + "[HeldBy]");
        assertThat(reference.view(on(3, 1)).entity(a1)).contains(before);
    }
}
