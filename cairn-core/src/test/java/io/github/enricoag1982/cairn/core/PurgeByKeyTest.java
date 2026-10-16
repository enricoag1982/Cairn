package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static io.github.enricoag1982.cairn.schema.ValueType.STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.Change;
import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeKey;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Purges by unique key beyond the scenarios of the {@code purge} spec (ADR-0033, design D1, D2 and D4 of
 * {@code add-purges-by-unique-key}): which entities a key names, what the result lists, which keys are invalid, where
 * each problem is, and what a purge keeps of a key.
 */
class PurgeByKeyTest {

    private static final TemporaryId HOME = new TemporaryId("home");
    private static final TemporaryId HAS_ADDRESS = new TemporaryId("hasAddress");

    private static final UniqueKey ACME_LEI = new UniqueKey("Organization", "lei", Value.of(LEI));
    private static final UniqueKey ACCOUNT_A1 = new UniqueKey("Account", "number", Value.of("A-1"));

    /** An abstract {@code Asset} with a unique {@code tag}, and two kinds of asset, which inherit it. */
    private static final SchemaDefinition CATALOG = SchemaDefinition.builder()
            .entityType("Asset", asset -> asset.abstractType()
                    .attribute("tag", STRING, a -> a.unique().required()))
            .entityType("Car", car -> car.supertype("Asset").aliases("Auto"))
            .entityType("House", house -> house.supertype("Asset"))
            .build();

    private final ReferenceStore reference = new ReferenceStore();

    private static Purge.Builder purge() {
        return Purge.by("dpo").reason("Erasure request");
    }

    private static PurgeResult.Purged purged(PurgeResult result) {
        assertThat(result).as("purge result").isInstanceOf(PurgeResult.Purged.class);
        return (PurgeResult.Purged) result;
    }

    private static List<Problem> rejected(PurgeResult result) {
        assertThat(result).as("purge result").isInstanceOf(PurgeResult.Rejected.class);
        return ((PurgeResult.Rejected) result).problems();
    }

    private static PurgeResult.ResolvedKey resolved(UniqueKey key, EntityId entity) {
        return new PurgeResult.ResolvedKey(new PurgeKey(key, Optional.empty()), entity);
    }

    /** ACME has the LEI of RD-9 until 1 March, and Gamma has it from 1 March. Returns the IDs of ACME and Gamma. */
    private List<EntityId> leiMovesToGamma() {
        EntityId acme = entity(reference.createParties(), ACME);
        reference.commit(Transaction.by("importer")
                .patch(acme, EffectivePeriod.between(on(1, 1), on(3, 1)), values("lei", LEI)).build());
        Receipt gamma = reference.replay(ReferenceStories.rd9Gamma());
        return List.of(acme, entity(gamma, GAMMA));
    }

    // ------------------------------------------------------------------ which entities, and what the result lists

    @Test
    void aKeyAndAnIdThatNameTheSameEntityPurgeItOnce() {
        EntityId acme = entity(reference.createParties(), ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));

        PurgeResult.Purged result = purged(reference.store.purge(purge().entity(acme).entity(ACME_LEI).build()));

        assertThat(result.entities()).as("without a repeat").containsExactly(acme);
        assertThat(result.keys()).as("the key is resolved all the same").containsExactly(resolved(ACME_LEI, acme));
        assertThat(result.record().entities()).isEqualTo(1);
        assertThat(reference.store.purges()).containsExactly(result.record());
    }

    @Test
    void twoKeysThatNameTheSameEntityPurgeItOnceAndEachKeyIsListed() {
        EntityId acme = entity(reference.createParties(), ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        UniqueKey sameKeyInOtherCase = new UniqueKey("organization", "LEI", Value.of(LEI));

        PurgeResult.Purged result = purged(reference.store.purge(purge().entity(ACME_LEI).entity(sameKeyInOtherCase)
                .build()));

        assertThat(result.entities()).containsExactly(acme);
        assertThat(result.keys()).containsExactly(resolved(ACME_LEI, acme), resolved(sameKeyInOtherCase, acme));
    }

    @Test
    void theEntitiesNamedByKeysComeAfterTheOnesNamedByIdAndBeforeTheTargetsOfCompositions() {
        Receipt accounts = reference.createAccounts();
        EntityId beta = entity(reference.parties, BETA);
        EntityId alice = entity(reference.parties, ALICE);
        EntityId a1 = entity(accounts, A1);
        RelationshipId heldByA1 = relationship(accounts, HELD_BY_A1);
        Receipt withAddress = reference.commit(Transaction.by("importer")
                .create(HOME, "Address", from(1, 1),
                        values("street", "1 Main St", "city", "Springfield", "country", "US"))
                .relate(HAS_ADDRESS, "HasAddress", alice, HOME, from(1, 1), values()).build());
        EntityId home = entity(withAddress, HOME);
        RelationshipId hasAddress = relationship(withAddress, HAS_ADDRESS);

        PurgeResult.Purged result = purged(reference.store.purge(purge().entity(ACCOUNT_A1).entity(beta)
                .entity(alice).build()));

        assertThat(result.entities()).as("by ID, in the order given, then by key, then the targets of compositions")
                .containsExactly(beta, alice, a1, home);
        assertThat(result.keys()).containsExactly(resolved(ACCOUNT_A1, a1));
        assertThat(result.relationships()).containsExactlyInAnyOrder(heldByA1, hasAddress);
    }

    @Test
    void keysAreListedInTheOrderOfThePurgesKeys() {
        Receipt accounts = reference.createAccounts();
        EntityId a1 = entity(accounts, A1);
        EntityId a2 = entity(accounts, ReferenceStories.A2);
        UniqueKey accountA2 = new UniqueKey("Account", "number", Value.of("A-2"));

        PurgeResult.Purged result = purged(reference.store.purge(purge().entity(accountA2).entity(ACCOUNT_A1)
                .build()));

        assertThat(result.entities()).containsExactly(a2, a1);
        assertThat(result.keys()).containsExactly(resolved(accountA2, a2), resolved(ACCOUNT_A1, a1));
    }

    @Test
    void anEntityThatAKeyNamesIsPurgedWithItsRelationshipsAndTheTargetsOfItsCompositions() {
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        Receipt withAddress = reference.commit(Transaction.by("importer")
                .create(HOME, "Address", from(1, 1),
                        values("street", "1 Main St", "city", "Springfield", "country", "US"))
                .relate(HAS_ADDRESS, "HasAddress", acme, HOME, from(1, 1), values()).build());

        PurgeResult.Purged result = purged(reference.store.purge(purge().entity(ACME_LEI).build()));

        assertThat(result.entities()).containsExactly(acme, entity(withAddress, HOME));
        assertThat(result.relationships()).containsExactly(relationship(withAddress, HAS_ADDRESS));
        assertThat(reference.view(on(3, 31)).entities("Address")).isEmpty();
    }

    @Test
    void theMinimumsAreCheckedOverWhatTheKeysNameToo() {
        Receipt accounts = reference.createAccounts();
        EntityId beta = entity(reference.parties, BETA);
        EntityId a1 = entity(accounts, A1);

        List<Problem> problems = rejected(reference.store.purge(purge().entity(beta).build()));

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.TOO_FEW_RELATIONSHIPS);
            assertThat(problem.path()).isEqualTo(a1.value());
        });
        PurgeResult.Purged result = purged(reference.store.purge(purge().entity(beta).entity(ACCOUNT_A1).build()));
        assertThat(result.entities()).as("A-1 named by its number does not stay without its holder")
                .containsExactly(beta, a1);
    }

    // ------------------------------------------------------------------ what a key finds

    @Test
    void aValueThatOneEntityHeldInTwoPeriodsNamesThatEntity() {
        EntityId acme = entity(reference.createParties(), ACME);
        reference.commit(Transaction.by("importer")
                .patch(acme, EffectivePeriod.between(on(1, 1), on(2, 1)), values("lei", LEI)).build());
        reference.commit(Transaction.by("importer").patch(acme, from(4, 1), values("lei", LEI)).build());

        PurgeResult.Purged result = purged(reference.store.purge(purge().entity(ACME_LEI).build()));

        assertThat(result.entities()).containsExactly(acme);
        assertThat(result.keys()).containsExactly(resolved(ACME_LEI, acme));
    }

    @Test
    void anEntityThatHasEndedIsFoundWithoutAnEffectiveTime() {
        EntityId acme = entity(reference.createParties(), ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        reference.recordedOn(on(6, 1));
        reference.commit(Transaction.by("importer").endDate(acme, on(6, 1)).build());
        assertThat(reference.view(on(12, 31)).entity(acme)).as("ACME ended on 1 June").isEmpty();
        assertThat(reference.view(on(12, 31)).entity(ACME_LEI)).as("so a view then does not find it").isEmpty();

        PurgeResult.Purged result = purged(reference.store.purge(purge().entity(ACME_LEI).build()));

        assertThat(result.entities()).containsExactly(acme);
    }

    @Test
    void aKeyWithAnEffectiveTimeNamesTheEntityThatHeldTheValueThenWhoeverHoldsItNow() {
        List<EntityId> moved = leiMovesToGamma();

        PurgeResult.Purged result = purged(reference.store.purge(purge().entity(ACME_LEI, on(2, 1)).build()));

        assertThat(result.entities()).containsExactly(moved.get(0));
        assertThat(result.keys()).containsExactly(new PurgeResult.ResolvedKey(
                new PurgeKey(ACME_LEI, Optional.of(on(2, 1))), moved.get(0)));
        assertThat(reference.view(on(4, 1)).entity(moved.get(1))).as("Gamma stays").isPresent();
    }

    @Test
    void theEffectiveTimeIsExclusiveAtTheEndOfAPeriodAndInclusiveAtItsStart() {
        List<EntityId> moved = leiMovesToGamma();
        Instant lastInstantOfAcme = on(3, 1).minusNanos(1);

        assertThat(purged(reference.store.purge(purge().entity(ACME_LEI, on(3, 1)).build())).entities())
                .as("1 March is Gamma's first instant").containsExactly(moved.get(1));
        assertThat(purged(reference.store.purge(purge().entity(ACME_LEI, lastInstantOfAcme).build())).entities())
                .as("the instant before it is ACME's").containsExactly(moved.get(0));
    }

    @Test
    void aKeyWithAnEffectiveTimeAtWhichNobodyHeldTheValueIsNotFound() {
        leiMovesToGamma();
        Instant before = on(2025, 12, 1);
        Basis basis = reference.store.latestBasis();

        List<Problem> problems = rejected(reference.store.purge(purge().entity(ACME_LEI, before).build()));

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.UNIQUE_KEY_NOT_FOUND);
            assertThat(problem.path()).isEqualTo("@purge.key.0");
            assertThat(problem.message()).contains(ACME_LEI.toString()).contains(before.toString());
        });
        assertThat(reference.store.latestBasis()).isEqualTo(basis);
    }

    @Test
    void aValueThatALaterBasisCorrectedAwayIsNotFoundAlthoughAnOlderBasisHadIt() {
        EntityId acme = entity(reference.createParties(), ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        Basis withLei = reference.store.latestBasis();
        reference.recordedOn(on(2, 1));
        reference.commit(Transaction.by("importer")
                .patch(acme, from(1, 1), values(), Set.of("lei"), ConflictCheck.none()).build());
        assertThat(reference.store.view(Coordinates.effectiveAt(on(3, 31)).knownAt(withLei)).entity(ACME_LEI))
                .as("a view at the older basis finds ACME").isPresent();

        List<Problem> problems = rejected(reference.store.purge(purge().entity(ACME_LEI).build()));

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.UNIQUE_KEY_NOT_FOUND);
            assertThat(problem.path()).isEqualTo("@purge.key.0");
        });
    }

    @Test
    void aKeyOfASupertypeNamesAnEntityOfASubtypeAndANameOrAliasIsComparedWithoutRegardToCase() {
        ReferenceStore catalog = ReferenceStore.with(CATALOG);
        TemporaryId car = new TemporaryId("car");
        TemporaryId house = new TemporaryId("house");
        Receipt receipt = catalog.commit(Transaction.by("importer")
                .create(car, "Car", from(1, 1), values("tag", "T-1"))
                .create(house, "House", from(1, 1), values("tag", "T-2")).build());

        List<Problem> notACar = rejected(catalog.store.purge(purge().entity(new UniqueKey("Auto", "tag",
                Value.of("T-2"))).build()));
        PurgeResult.Purged result = purged(catalog.store.purge(purge().entity(new UniqueKey("ASSET", "TAG",
                Value.of("T-1"))).build()));

        assertThat(notACar).singleElement().satisfies(problem -> assertThat(problem.code())
                .as("T-2 is a House, and a key of Car does not name it").isEqualTo(ProblemCode.UNIQUE_KEY_NOT_FOUND));
        assertThat(result.entities()).containsExactly(entity(receipt, car));
        assertThat(catalog.view(on(2, 1)).entity(entity(receipt, house))).isPresent();
    }

    // ------------------------------------------------------------------ the problems of a purge

    @Test
    void aKeyThatDoesNotPassTheCheckOfKeysInWritesIsInvalidAtItsPositionAmongTheKeys() {
        EntityId acme = entity(reference.createParties(), ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        Basis basis = reference.store.latestBasis();

        List<Problem> problems = rejected(reference.store.purge(purge()
                .entity(ACME_LEI)
                .entity(new UniqueKey("Organization", "name", Value.of("ACME")))
                .entity(new UniqueKey("Orgnization", "lei", Value.of(LEI)))
                .entity(new UniqueKey("Organization", "lei", Value.of(5)))
                .entity(new UniqueKey("Person", "name", Value.of("Alice"))).build()));

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.INVALID_UNIQUE_KEY, "@purge.key.1"),
                tuple(ProblemCode.INVALID_UNIQUE_KEY, "@purge.key.2"),
                tuple(ProblemCode.INVALID_UNIQUE_KEY, "@purge.key.3"),
                tuple(ProblemCode.INVALID_UNIQUE_KEY, "@purge.key.4"));
        assertThat(problems.get(0).message()).contains("not unique");
        assertThat(problems.get(1).hint()).contains("Did you mean 'Organization'?");
        assertThat(problems.get(2).message()).contains("does not fit");
        assertThat(reference.store.latestBasis()).as("no basis is used").isEqualTo(basis);
        assertThat(reference.view(on(3, 31)).entity(acme)).as("ACME, which the first key names, stays").isPresent();
    }

    @Test
    void theProblemsAboutIdsAndAboutKeysHaveTheirOwnPaths() {
        Receipt parties = reference.createParties();
        EntityId alice = entity(parties, ALICE);
        EntityId unknown = ReferenceStore.id(99);

        List<Problem> problems = rejected(reference.store.purge(purge().entity(alice).entity(unknown)
                .entity(ACME_LEI).entity(new UniqueKey("Nothing", "lei", Value.of(LEI))).build()));

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactlyInAnyOrder(
                tuple(ProblemCode.UNKNOWN_ENTITY, "@purge.1"),
                tuple(ProblemCode.UNIQUE_KEY_NOT_FOUND, "@purge.key.0"),
                tuple(ProblemCode.INVALID_UNIQUE_KEY, "@purge.key.1"));
    }

    @Test
    void everyProblemIsReportedAndTheMinimumsAreStillCheckedForWhatDidResolve() {
        Receipt accounts = reference.createAccounts();
        EntityId beta = entity(reference.parties, BETA);
        EntityId a1 = entity(accounts, A1);
        UniqueKey nobody = new UniqueKey("Account", "number", Value.of("A-9"));
        Basis basis = reference.store.latestBasis();

        List<Problem> problems = rejected(reference.store.purge(purge().entity(beta).entity(nobody).build()));

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactlyInAnyOrder(
                tuple(ProblemCode.UNIQUE_KEY_NOT_FOUND, "@purge.key.0"),
                tuple(ProblemCode.TOO_FEW_RELATIONSHIPS, a1.value()));
        assertThat(reference.store.latestBasis()).isEqualTo(basis);
        assertThat(reference.store.purges()).isEmpty();
    }

    @Test
    void severalKeysThatDoNotResolveAreEachReportedAtTheirOwnPosition() {
        leiMovesToGamma();
        UniqueKey nobody = new UniqueKey("Account", "number", Value.of("A-9"));

        List<Problem> problems = rejected(reference.store.purge(purge().entity(nobody).entity(ACME_LEI)
                .entity(ACME_LEI, on(2, 1)).build()));

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.UNIQUE_KEY_NOT_FOUND, "@purge.key.0"),
                tuple(ProblemCode.UNIQUE_KEY_AMBIGUOUS, "@purge.key.1"));
    }

    // ------------------------------------------------------------------ the ambiguous problem

    @Test
    void anAmbiguousKeyNamesEachCandidateWithItsPeriodsMergedAndInTheOrderOfThePeriods() {
        // ACME's LEI is 2 January to 1 March, and again from 1 June, with a rename in between that splits its versions;
        // Gamma, which has a higher ID, had it from 1 March to 1 June.
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        reference.commit(Transaction.by("importer")
                .patch(acme, EffectivePeriod.between(on(1, 2), on(3, 1)), values("lei", LEI)).build());
        reference.commit(Transaction.by("importer")
                .patch(acme, EffectivePeriod.between(on(2, 1), on(2, 15)), values("name", "ACME Ltd")).build());
        Receipt gamma = reference.commit(Transaction.by("importer")
                .create(GAMMA, "Organization", EffectivePeriod.between(on(3, 1), on(6, 1)),
                        values("name", "Gamma", "lei", LEI)).build());
        reference.commit(Transaction.by("importer").patch(acme, from(6, 1), values("lei", LEI)).build());
        EntityId gammaId = entity(gamma, GAMMA);
        assertThat(gammaId.value()).as("Gamma's ID comes after ACME's").isGreaterThan(acme.value());

        List<Problem> problems = rejected(reference.store.purge(purge().entity(ACME_LEI).build()));

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.UNIQUE_KEY_AMBIGUOUS);
            assertThat(problem.path()).isEqualTo("@purge.key.0");
            String acmeHeld = acme.value() + " from " + on(1, 2) + " until " + on(3, 1) + " and from " + on(6, 1)
                    + " until further notice";
            String gammaHeld = gammaId.value() + " from " + on(3, 1) + " until " + on(6, 1);
            assertThat(problem.message()).as("ACME's two periods, and the rename does not split the first")
                    .contains(acmeHeld, gammaHeld).doesNotContain(on(2, 1).toString());
            assertThat(problem.found()).hasValueSatisfying(found -> assertThat(found).contains(acme.value())
                    .contains(gammaId.value()));
        });
    }

    @Test
    void theCandidateThatHeldTheValueFirstComesFirstWhateverItsId() {
        // ACME has the lower ID and holds the LEI from 1 March; Gamma has the higher ID and held it until 1 March.
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        reference.commit(Transaction.by("importer").patch(acme, from(3, 1), values("lei", LEI)).build());
        Receipt gamma = reference.commit(Transaction.by("importer")
                .create(GAMMA, "Organization", EffectivePeriod.between(on(1, 1), on(3, 1)),
                        values("name", "Gamma", "lei", LEI)).build());
        EntityId gammaId = entity(gamma, GAMMA);

        List<Problem> problems = rejected(reference.store.purge(purge().entity(ACME_LEI).build()));

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.UNIQUE_KEY_AMBIGUOUS);
            assertThat(problem.message().indexOf(gammaId.value())).isLessThan(problem.message().indexOf(acme.value()));
        });
    }

    // ------------------------------------------------------------------ what a purge keeps of a key

    @Test
    void thePurgeRecordAndTheChangeFeedKeepNeitherTheKeyNorItsValue() {
        EntityId acme = entity(reference.createParties(), ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));

        PurgeResult.Purged result = purged(reference.store.purge(purge().entity(ACME_LEI).build()));

        assertThat(reference.store.purges()).containsExactly(result.record());
        assertThat(reference.store.purges().toString()).doesNotContain(LEI).doesNotContain("lei");
        List<Change> changes = reference.store.changes(Basis.NONE, 100);
        assertThat(changes).hasSize(4);
        assertThat(changes.toString()).doesNotContain(LEI).doesNotContain(acme.value());
        assertThat(reference.store.transactions().toString()).doesNotContain(LEI);
    }
}
