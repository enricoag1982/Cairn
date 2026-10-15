package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.assertProblem;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static io.github.enricoag1982.cairn.schema.ValueType.LONG;
import static io.github.enricoag1982.cairn.schema.ValueType.STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.KeyResolution;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unique keys beyond the scenarios of the specs (ADR-0026, design D1 to D3 of {@code add-unique-key-references}): how a
 * key resolves in subtypes, with aliases, at the edges of periods, at both ends of a relationship, in dry runs and in
 * retries, what is invalid, and what a view finds.
 */
class UniqueKeysTest {

    /** A valid LEI, other than that of ACME. */
    private static final String BANK_LEI = "7245008A9C2K8H1Z7B28";

    /**
     * An abstract {@code Asset} with two unique attributes of its own, a string {@code tag}, with the alias {@code label},
     * and a long {@code serial}; {@code Car}, with the alias {@code Auto}, and {@code House} are assets; a {@code Car}
     * also has a unique {@code plate}; a {@code Note} has no unique attribute; and {@code Near} relates assets.
     */
    private static final SchemaDefinition CATALOG = SchemaDefinition.builder()
            .entityType("Asset", asset -> asset.abstractType()
                    .attribute("tag", STRING, a -> a.unique().required().aliases("label"))
                    .attribute("serial", LONG, a -> a.unique()))
            .entityType("Car", car -> car.supertype("Asset").aliases("Auto")
                    .attribute("plate", STRING, a -> a.unique()))
            .entityType("House", house -> house.supertype("Asset"))
            .entityType("Note", note -> note.attribute("text", STRING))
            .relationshipType("Near", near -> near.from("Asset").to("Asset").inverse("nearTo"))
            .build();

    private final ReferenceStore reference = new ReferenceStore();

    private static UniqueKey key(String entityType, String attribute, Value value) {
        return new UniqueKey(entityType, attribute, value);
    }

    private static UniqueKey key(String entityType, String attribute, String value) {
        return key(entityType, attribute, Value.of(value));
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    private static Transaction.Builder importer() {
        return Transaction.by("importer");
    }

    /** A store with the catalog registered, a car and a house, both from 1 January. */
    private record Catalog(ReferenceStore store, EntityId car, EntityId house) {

        static Catalog open() {
            ReferenceStore store = ReferenceStore.with(CATALOG);
            TemporaryId car = new TemporaryId("car");
            TemporaryId house = new TemporaryId("house");
            Receipt receipt = store.commit(importer()
                    .create(car, "Car", from(1, 1), values("tag", "T-1", "serial", 7L, "plate", "B-AB 1"))
                    .create(house, "House", from(1, 1), values("tag", "T-2", "serial", 8L))
                    .build());
            return new Catalog(store, entity(receipt, car), entity(receipt, house));
        }
    }

    /** ACME has the LEI until 1 March, and Gamma from 1 March. Returns the IDs of ACME and Gamma. */
    private List<EntityId> leiMoves() {
        EntityId acme = entity(reference.createParties(), ACME);
        reference.commit(importer().patch(acme, EffectivePeriod.between(on(1, 1), on(3, 1)), values("lei", LEI))
                .build());
        Receipt gamma = reference.replay(ReferenceStories.rd9Gamma());
        return List.of(acme, entity(gamma, GAMMA));
    }

    // ------------------------------------------------------------------ subtypes, aliases and values

    @Test
    void aKeyOfASupertypeNamesAnEntityOfAnySubtype() {
        Catalog catalog = Catalog.open();
        catalog.store().recordedOn(on(2, 1));

        Receipt receipt = catalog.store().commit(importer()
                .patch(key("Asset", "tag", "T-1"), from(2, 1), values("plate", "B-XY 2")).build());

        assertThat(receipt.created()).isEmpty();
        assertThat(catalog.store().view(on(2, 1)).entity(catalog.car()).orElseThrow().values())
                .as("the values of the write are those of the entity's own type, Car")
                .containsEntry("plate", Value.of("B-XY 2"));
        assertThat(catalog.store().view(on(2, 1)).entity(key("Asset", "tag", "T-2")).orElseThrow().id())
                .isEqualTo(catalog.house());
    }

    @Test
    void theValuesOfAWriteByAKeyAreCheckedAgainstTheTypeOfTheEntityTheKeyNames() {
        Catalog catalog = Catalog.open();

        List<Problem> problems = catalog.store().reject(importer()
                .patch(key("Asset", "tag", "T-2"), from(2, 1), values("plate", "B-XY 2")).build());

        assertThat(problems).singleElement().satisfies(problem -> assertProblem(problem,
                ProblemCode.UNKNOWN_ATTRIBUTE, catalog.house().value() + ".plate"));
    }

    @Test
    void anEntityOfASupertypeIsNotNamedByAKeyOfASubtype() {
        Catalog catalog = Catalog.open();

        List<Problem> problems = catalog.store().reject(importer()
                .patch(key("Car", "tag", "T-2"), from(2, 1), values("serial", 9L)).build());

        assertThat(only(problems).code()).as("T-2 is a House").isEqualTo(ProblemCode.UNIQUE_KEY_NOT_FOUND);
        assertThat(catalog.store().view(on(2, 1)).entity(key("Car", "tag", "T-2"))).isEmpty();
        assertThat(catalog.store().view(on(2, 1)).entity(key("Car", "tag", "T-1"))).as("an inherited attribute")
                .hasValueSatisfying(state -> assertThat(state.id()).isEqualTo(catalog.car()));
    }

    @Test
    void namesAndAliasesOfTheTypeAndTheAttributeAreComparedWithoutRegardToCase() {
        Catalog catalog = Catalog.open();
        catalog.store().recordedOn(on(2, 1));

        catalog.store().commit(importer().patch(key("auto", "LABEL", "T-1"), from(2, 1), values("plate", "B-XY 2"))
                .build());

        assertThat(catalog.store().view(on(2, 1)).entity(catalog.car()).orElseThrow().values())
                .containsEntry("plate", Value.of("B-XY 2"));
        assertThat(catalog.store().view(on(2, 1)).entity(key("ASSET", "Tag", "T-2"))).isPresent();
        assertThat(catalog.store().view(on(2, 1)).entity(key("Car", "PLATE", "B-XY 2"))).isPresent();
    }

    @Test
    void aNarrowerNumberFitsAWiderAttributeAndAnythingElseIsInvalid() {
        Catalog catalog = Catalog.open();
        View view = catalog.store().view(on(2, 1));

        assertThat(view.entity(key("Car", "serial", Value.of(7)))).as("an int for a long")
                .hasValueSatisfying(state -> assertThat(state.id()).isEqualTo(catalog.car()));
        assertThat(view.entity(key("Car", "serial", Value.of(7L)))).isPresent();
        for (Value wrong : List.of(Value.of("7"), Value.of(new BigDecimal("7")), Value.of(true))) {
            List<Problem> problems = catalog.store().reject(importer()
                    .patch(key("Car", "serial", wrong), from(2, 1), values("plate", "X")).build());

            assertThat(only(problems).code()).as(wrong.toString()).isEqualTo(ProblemCode.INVALID_UNIQUE_KEY);
            assertThatThrownBy(() -> view.entity(key("Car", "serial", wrong))).as(wrong.toString())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not fit");
        }
        Receipt receipt = catalog.store().commit(importer()
                .patch(key("Car", "serial", Value.of(7)), from(2, 1), values("plate", "X")).build());
        assertThat(receipt.created()).isEmpty();
    }

    // ------------------------------------------------------------------ invalid keys

    @Test
    void anUnknownTypeIsInvalidAndTheHintSuggestsTheClosestName() {
        reference.createAccounts();

        Problem problem = only(reference.reject(importer()
                .patch(key("Acount", "number", "A-1"), from(3, 15), values("balance", money("1500"))).build()));

        assertProblem(problem, ProblemCode.INVALID_UNIQUE_KEY, "@write.0");
        assertThat(problem.message()).contains("Acount(number: A-1)").contains("no entity type 'Acount'");
        assertThat(problem.hint()).contains("Did you mean 'Account'?");
    }

    @Test
    void anAttributeTheTypeDoesNotHaveIsInvalidAndTheExpectedValueListsTheUniqueAttributes() {
        Catalog catalog = Catalog.open();

        Problem problem = only(catalog.store().reject(importer()
                .patch(key("Car", "platte", "B-AB 1"), from(2, 1), values("serial", 1L)).build()));

        assertProblem(problem, ProblemCode.INVALID_UNIQUE_KEY, "@write.0");
        assertThat(problem.expected()).contains("one of tag, serial, plate");
        assertThat(problem.hint()).contains("Did you mean 'plate'?");
    }

    @Test
    void aTypeWithoutUniqueAttributesSaysSoInsteadOfListingNone() {
        Catalog catalog = Catalog.open();

        Problem problem = only(catalog.store().reject(importer()
                .patch(key("Note", "text", "x"), from(2, 1), values("text", "y")).build()));

        assertProblem(problem, ProblemCode.INVALID_UNIQUE_KEY, "@write.0");
        assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected)
                .contains("Note").contains("unique").contains("none"));
        assertThat(problem.hint()).contains("Name the entity by its ID.");
    }

    @Test
    void aRelationshipTypeIsNoEntityTypeForAKey() {
        Catalog catalog = Catalog.open();

        Problem problem = only(catalog.store().reject(importer()
                .patch(key("Near", "tag", "T-1"), from(2, 1), values()).build()));

        assertProblem(problem, ProblemCode.INVALID_UNIQUE_KEY, "@write.0");
        assertThat(problem.message()).contains("no entity type 'Near'");
    }

    @Test
    void everyWriteThatNamesAKeyReportsItsOwnProblemAtItsPosition() {
        reference.createAccounts();
        EntityId acme = entity(reference.parties, ACME);

        List<Problem> problems = reference.reject(importer()
                .patch(key("Account", "number", "A-9"), from(3, 15), values("balance", money("1")))
                .patch(acme, from(3, 15), values("name", "ACME Holdings"))
                .patch(key("Account", "currency", "EUR"), from(3, 15), values("balance", money("2")))
                .endDate(key("Account", "number", "A-8"), on(6, 1))
                .build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.UNIQUE_KEY_NOT_FOUND, "@write.0"),
                tuple(ProblemCode.INVALID_UNIQUE_KEY, "@write.2"),
                tuple(ProblemCode.UNIQUE_KEY_NOT_FOUND, "@write.3"));
    }

    // ------------------------------------------------------------------ where a key resolves

    @Test
    void aKeyResolvesAtTheStartOfThePeriodAndPeriodsAreHalfOpen() {
        List<EntityId> moved = leiMoves();
        EntityId acme = moved.get(0);
        EntityId gamma = moved.get(1);
        UniqueKey lei = key("Organization", "lei", LEI);
        reference.recordedOn(on(4, 1));

        // The last instant of ACME's period, and the first of Gamma's: the same value, two entities.
        reference.commit(importer().patch(lei, EffectivePeriod.between(on(2, 28), on(3, 1)),
                values("name", "ACME in the last days")).build());
        reference.commit(importer().patch(lei, EffectivePeriod.between(on(3, 1), on(3, 2)),
                values("name", "Gamma in the first days")).build());

        assertThat(reference.view(on(2, 28)).entity(acme).orElseThrow().values())
                .containsEntry("name", Value.of("ACME in the last days"));
        assertThat(reference.view(on(3, 1)).entity(acme).orElseThrow().values()).containsEntry("name", Value.of("ACME"));
        assertThat(reference.view(on(3, 1)).entity(gamma).orElseThrow().values())
                .containsEntry("name", Value.of("Gamma in the first days"));
        Instant lastMoment = on(3, 1).minusNanos(1_000);
        assertThat(reference.view(lastMoment).entity(lei).orElseThrow().id()).as("the microsecond before 1 March")
                .isEqualTo(acme);
        assertThat(reference.view(on(3, 1)).entity(lei).orElseThrow().id()).isEqualTo(gamma);
    }

    @Test
    void aKeyThatNoEntityHasAtTheStartOfThePeriodIsNotFoundEvenIfOneHasItLater() {
        EntityId acme = entity(reference.createParties(), ACME);
        reference.commit(importer().patch(acme, from(3, 1), values("lei", LEI)).build());

        Problem problem = only(reference.reject(importer()
                .patch(key("Organization", "lei", LEI), from(2, 1), values("name", "Early")).build()));

        assertProblem(problem, ProblemCode.UNIQUE_KEY_NOT_FOUND, "@write.0");
        assertThat(problem.message()).contains("Organization(lei: " + LEI + ")").contains(on(2, 1).toString());
        assertThat(reference.view(on(3, 1)).entity(key("Organization", "lei", LEI))).isPresent();
    }

    @Test
    void aKeyDoesNotNameAnEntityThatHasEnded() {
        Catalog catalog = Catalog.open();
        catalog.store().commit(importer().endDate(catalog.car(), on(6, 1)).build());

        Problem problem = only(catalog.store().reject(importer()
                .patch(key("Car", "plate", "B-AB 1"), from(7, 1), values("serial", 1L)).build()));

        assertProblem(problem, ProblemCode.UNIQUE_KEY_NOT_FOUND, "@write.0");
        assertThat(catalog.store().view(on(5, 31)).entity(key("Car", "plate", "B-AB 1"))).isPresent();
        assertThat(catalog.store().view(on(6, 1)).entity(key("Car", "plate", "B-AB 1"))).isEmpty();
    }

    @Test
    void problemsAboutTheEntityNameItsIdWhateverTheWriteNamedItBy() {
        Catalog catalog = Catalog.open();
        catalog.store().commit(importer().endDate(catalog.car(), on(6, 1)).build());

        Problem problem = only(catalog.store().reject(importer()
                .patch(key("Car", "plate", "B-AB 1"), EffectivePeriod.between(on(5, 1), on(7, 1)),
                        values("serial", 1L)).build()));

        assertProblem(problem, ProblemCode.ENTITY_NOT_EFFECTIVE, catalog.car().value());
    }

    @Test
    void aKeyResolvesAgainstTheStoreBeforeTheTransactionNotAgainstItsOwnWrites() {
        EntityId acme = entity(reference.createParties(), ACME);
        reference.commit(importer().patch(acme, from(1, 1), values("lei", LEI)).build());
        reference.recordedOn(on(5, 20));
        Transaction clearsThenRenames = importer()
                .patch(acme, from(1, 1), values(), Set.of("lei"), ConflictCheck.none())
                .patch(key("Organization", "lei", LEI), from(3, 1), values("name", "Renamed")).build();
        Transaction renamesThenClears = importer()
                .patch(key("Organization", "lei", LEI), from(3, 1), values("name", "Renamed"))
                .patch(acme, from(1, 1), values(), Set.of("lei"), ConflictCheck.none()).build();

        assertThat(reference.store.dryRun(clearsThenRenames)).as("the LEI is cleared by an earlier write")
                .isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(reference.store.dryRun(renamesThenClears)).as("the order does not matter")
                .isInstanceOf(DryRunResult.WouldCommit.class);
        reference.commit(clearsThenRenames);

        assertThat(reference.view(on(3, 1)).entity(acme).orElseThrow().values())
                .containsEntry("name", Value.of("Renamed")).doesNotContainKey("lei");
    }

    @Test
    void aKeyCannotNameWhatTheSameTransactionCreates() {
        reference.createParties();

        List<Problem> problems = reference.reject(importer()
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma", "lei", LEI))
                .patch(key("Organization", "lei", LEI), from(2, 1), values("name", "Gamma Holdings")).build());

        assertThat(problems).singleElement().satisfies(problem ->
                assertProblem(problem, ProblemCode.UNIQUE_KEY_NOT_FOUND, "@write.1"));
    }

    @Test
    void aKeyNamesAnEntityOnlyFromTheStartOfItsLifetime() {
        reference.createParties();
        reference.commit(importer().create(new TemporaryId("late"), "Organization", from(6, 1),
                values("name", "Late", "lei", LEI)).build());

        Problem early = only(reference.reject(importer()
                .patch(key("Organization", "lei", LEI), from(5, 1), values("name", "Too early")).build()));
        Receipt later = reference.commit(importer()
                .patch(key("Organization", "lei", LEI), from(6, 1), values("name", "Right on time")).build());

        assertThat(early.code()).isEqualTo(ProblemCode.UNIQUE_KEY_NOT_FOUND);
        assertThat(later.created()).isEmpty();
    }

    // ------------------------------------------------------------------ the writes that take a key

    @Test
    void replaceAndEndDateTakeAKeyToo() {
        Catalog catalog = Catalog.open();
        catalog.store().recordedOn(on(2, 1));

        catalog.store().commit(importer()
                .replace(key("Car", "plate", "B-AB 1"), from(2, 1), values("tag", "T-1", "serial", 70L)).build());
        catalog.store().recordedOn(on(7, 1));
        catalog.store().commit(importer().endDate(key("Car", "tag", "T-1"), on(6, 1)).build());

        View view = catalog.store().view(on(3, 1));
        assertThat(view.entity(catalog.car()).orElseThrow().values()).containsEntry("serial", Value.of(70L))
                .doesNotContainKey("plate");
        assertThat(catalog.store().view(on(6, 1)).entity(catalog.car())).isEmpty();
        assertThat(view.entity(key("Car", "plate", "B-AB 1"))).as("the replace cleared the plate from 1 February")
                .isEmpty();
    }

    @Test
    void relatingNamesBothEndsByKeysEachResolvedAtTheStartOfTheRelationship() {
        Receipt accounts = reference.createAccounts();
        EntityId acme = entity(reference.parties, ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        reference.recordedOn(on(5, 20));

        Receipt receipt = reference.commit(importer()
                .endDate(relationship(accounts, HELD_BY_A1), on(6, 1))
                .relate("HeldBy", key("Account", "number", "A-1"), key("Organization", "lei", LEI), from(6, 1),
                        values())
                .build());

        assertThat(receipt.created()).hasSize(1);
        assertThat(reference.view(on(5, 31)).navigate(acme, "holds"))
                .extracting(link -> link.entity().values().get("number")).containsExactly(Value.of("A-2"));
        assertThat(reference.view(on(6, 1)).navigate(acme, "holds"))
                .extracting(link -> link.entity().values().get("number"))
                .containsExactlyInAnyOrder(Value.of("A-1"), Value.of("A-2"));
        assertThat(entity(accounts, A1)).isEqualTo(reference.view(on(6, 1)).entity(key("Account", "number", "A-1"))
                .orElseThrow().id());
    }

    @Test
    void bothEndsOfARelationshipThatAreNotFoundAreReportedAtTheWrite() {
        reference.createAccounts();
        EntityId acme = entity(reference.parties, ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));

        List<Problem> problems = reference.reject(importer()
                .relate("HeldBy", key("Account", "number", "A-9"), key("Organization", "lei", "NOTANLEI000000000000"),
                        from(7, 1), values())
                .build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.UNIQUE_KEY_NOT_FOUND, "@write.0"), tuple(ProblemCode.UNIQUE_KEY_NOT_FOUND, "@write.0"));
        assertThat(problems.get(0).message()).contains("Account(number: A-9)");
        assertThat(problems.get(1).message()).contains("Organization(lei: NOTANLEI000000000000)");
    }

    // ------------------------------------------------------------------ the receipt

    @Test
    void theReceiptListsEveryKeyInTheOrderOfTheWritesAndTheSourceBeforeTheTarget() {
        Receipt accounts = reference.createAccounts();
        EntityId acme = entity(reference.parties, ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        reference.recordedOn(on(5, 20));

        Receipt receipt = reference.commit(importer()
                .patch(key("Account", "number", "A-2"), from(3, 15), values("balance", money("1")))
                .endDate(relationship(accounts, HELD_BY_A1), on(6, 1))
                .relate("HeldBy", key("Account", "number", "A-1"), key("Organization", "lei", LEI), from(6, 1),
                        values())
                .build());

        assertThat(receipt.keys()).containsExactly(
                new KeyResolution(0, key("Account", "number", "A-2"), entity(accounts, ReferenceStories.A2)),
                new KeyResolution(2, key("Account", "number", "A-1"), entity(accounts, A1)),
                new KeyResolution(2, key("Organization", "lei", LEI), acme));
    }

    @Test
    void theSameKeyInTwoWritesWithDifferentPeriodsIsListedWithTheEntityEachResolvedTo() {
        List<EntityId> moved = leiMoves();
        UniqueKey lei = key("Organization", "lei", LEI);
        reference.recordedOn(on(5, 1));

        Receipt receipt = reference.commit(importer()
                .patch(lei, EffectivePeriod.between(on(2, 1), on(2, 2)), values("name", "ACME in February"))
                .patch(lei, EffectivePeriod.between(on(4, 1), on(4, 2)), values("name", "Gamma in April")).build());

        assertThat(receipt.keys()).containsExactly(new KeyResolution(0, lei, moved.get(0)),
                new KeyResolution(1, lei, moved.get(1)));
    }

    @Test
    void aReceiptOfATransactionThatNamesNoKeyListsNone() {
        reference.createAccounts();
        EntityId acme = entity(reference.parties, ACME);

        Receipt byId = reference.commit(importer().patch(acme, from(3, 15), values("name", "ACME Holdings")).build());
        Receipt creates = reference.commit(importer()
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma")).build());

        assertThat(byId.keys()).isEmpty();
        assertThat(creates.keys()).isEmpty();
    }

    // ------------------------------------------------------------------ dry runs and retries

    @Test
    void aDryRunResolvesKeysLikeACommitAndRecordsNothing() {
        reference.createAccounts();
        Basis before = reference.store.latestBasis();
        Transaction valid = importer()
                .patch(key("Account", "number", "A-1"), from(3, 15), values("balance", money("1500"))).build();
        Transaction missing = importer()
                .patch(key("Account", "number", "A-9"), from(3, 15), values("balance", money("1500"))).build();

        assertThat(reference.store.dryRun(valid)).isInstanceOf(DryRunResult.WouldCommit.class);
        DryRunResult rejected = reference.store.dryRun(missing);

        assertThat(rejected).isInstanceOf(DryRunResult.Rejected.class);
        assertThat(((DryRunResult.Rejected) rejected).problems()).isEqualTo(reference.reject(missing));
        assertThat(reference.store.latestBasis()).isEqualTo(before);
    }

    @Test
    void aRetryWithKeysGetsTheFirstReceiptWithoutResolvingAgain() {
        EntityId acme = entity(reference.createParties(), ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        reference.recordedOn(on(4, 1));
        Transaction rename = importer().idempotencyKey("rename")
                .patch(key("Organization", "lei", LEI), from(4, 1), values("name", "ACME Holdings")).build();
        Receipt first = reference.commit(rename);
        assertThat(first.keys()).as("the receipt names ACME for the key").containsExactly(
                new KeyResolution(0, key("Organization", "lei", LEI), acme));
        // The LEI moves to Gamma for the whole timeline: a new resolution would change Gamma.
        reference.commit(importer().patch(acme, from(1, 1), values(), Set.of("lei"), ConflictCheck.none()).build());
        EntityId gamma = entity(reference.commit(importer()
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma", "lei", LEI)).build()), GAMMA);
        Basis before = reference.store.latestBasis();

        CommitResult retry = reference.store.commit(rename);

        assertThat(retry).isEqualTo(new CommitResult.Committed(first));
        assertThat(((CommitResult.Committed) retry).receipt().keys()).as("the first resolution, not a new one")
                .containsExactly(new KeyResolution(0, key("Organization", "lei", LEI), acme));
        assertThat(reference.store.dryRun(rename)).isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(reference.store.latestBasis()).isEqualTo(before);
        assertThat(reference.view(on(4, 1)).entity(acme).orElseThrow().values())
                .containsEntry("name", Value.of("ACME Holdings"));
        assertThat(reference.view(on(4, 1)).entity(gamma).orElseThrow().values())
                .as("the retry did not rename Gamma").containsEntry("name", Value.of("Gamma"));
    }

    @Test
    void theSameIdempotencyKeyForAnotherKeyOrForTheIdItResolvesToIsAnotherTransaction() {
        Receipt accounts = reference.createAccounts();
        reference.commit(importer().idempotencyKey("balance")
                .patch(key("Account", "number", "A-1"), from(3, 15), values("balance", money("1500"))).build());

        CommitResult anotherKey = reference.store.commit(importer().idempotencyKey("balance")
                .patch(key("Account", "number", "A-2"), from(3, 15), values("balance", money("1500"))).build());
        CommitResult theId = reference.store.commit(importer().idempotencyKey("balance")
                .patch(entity(accounts, A1), from(3, 15), values("balance", money("1500"))).build());

        for (CommitResult result : List.of(anotherKey, theId)) {
            assertThat(result).isInstanceOf(CommitResult.Rejected.class);
            assertThat(((CommitResult.Rejected) result).problems()).singleElement().satisfies(problem ->
                    assertProblem(problem, ProblemCode.IDEMPOTENCY_KEY_REUSED, "@transaction"));
        }
    }

    // ------------------------------------------------------------------ views

    @Test
    void aViewReadsAsOfABasisAndANullKeyIsAProgrammingError() {
        Catalog catalog = Catalog.open();
        Basis created = catalog.store().store.latestBasis();
        catalog.store().commit(importer().patch(catalog.car(), from(1, 1), values("plate", "B-XY 2")).build());

        View old = catalog.store().store.view(Coordinates.effectiveAt(on(2, 1)).knownAt(created));
        View now = catalog.store().view(on(2, 1));

        assertThat(old.entity(key("Car", "plate", "B-AB 1"))).as("as known at the first basis").isPresent();
        assertThat(now.entity(key("Car", "plate", "B-AB 1"))).as("as known now").isEmpty();
        assertThat(now.entity(key("Car", "plate", "B-XY 2"))).isPresent();
        assertThatNullPointerException().isThrownBy(() -> now.entity((UniqueKey) null));
    }

    @Test
    void aViewThatDoesNotKnowAnEntityTypeOrAnAttributeThrows() {
        Catalog catalog = Catalog.open();
        View view = catalog.store().view(on(2, 1));

        assertThatThrownBy(() -> view.entity(key("Cars", "plate", "x"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cars");
        assertThatThrownBy(() -> view.entity(key("Car", "colour", "x"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("colour");
        assertThatThrownBy(() -> view.entity(key("Note", "text", "x"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not unique");
    }

    @Test
    void aKeyOfASupertypeFindsAnEntityOfASubtypeThatWasRegisteredLater() {
        EntityId acme = entity(reference.createParties(), ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        assertThat(reference.store.schema().register(new Origin("banking", "1.0.0"),
                SchemaDefinition.builder().entityType("Bank", bank -> bank.supertype("Organization")).build()))
                .isInstanceOf(RegistrationResult.Applied.class);
        reference.recordedOn(on(6, 1));
        TemporaryId bank = new TemporaryId("bank");
        Receipt receipt = reference.commit(importer()
                .create(bank, "Bank", from(1, 1), values("name", "First Bank", "lei", BANK_LEI)).build());

        Optional<EntityState> found = reference.view(on(7, 1)).entity(key("Organization", "lei", BANK_LEI));

        assertThat(found).hasValueSatisfying(state -> {
            assertThat(state.id()).isEqualTo(entity(receipt, bank));
            assertThat(state.entityType()).isEqualTo("Bank");
        });
        assertThat(reference.view(on(7, 1)).entity(key("Bank", "lei", LEI))).as("ACME is no Bank").isEmpty();
        assertThat(reference.view(on(7, 1)).entity(key("Bank", "lei", BANK_LEI))).isPresent();
        Receipt rename = reference.commit(importer()
                .patch(key("Organization", "lei", BANK_LEI), from(7, 1), values("name", "Second Bank")).build());
        assertThat(rename.created()).isEmpty();
        assertThat(reference.view(on(7, 1)).entity(entity(receipt, bank)).orElseThrow().values())
                .containsEntry("name", Value.of("Second Bank"));
    }

    // ------------------------------------------------------------------ rules

    /** A store in which {@code Employs} names {@link AccountFundedRule}, with RD-1, RD-5 and RD-3 recorded. */
    private record Funded(ReferenceStore store, AccountFundedRule rule, Receipt accounts) {

        static Funded start() {
            AccountFundedRule rule = new AccountFundedRule();
            ReferenceStore store = ReferenceStore.withRules(AccountFundedRule.model(), ReferenceStore.rulesWith(rule),
                    ReferenceModel.declaredRules());
            Receipt accounts = store.createAccounts();
            store.replay(ReferenceStories.rd3Employment(entity(store.parties, ReferenceStories.BETA),
                    entity(store.parties, ReferenceStories.ALICE)));
            store.recordedOn(on(5, 20));
            return new Funded(store, rule, accounts);
        }
    }

    @Test
    void aRuleThatFindsByAKeyRunsAgainForAChangeToAnotherAccountAtTheInstantItRead() {
        Funded funded = Funded.start();
        int checked = funded.rule().checks();

        funded.store().commit(importer()
                .patch(entity(funded.accounts(), ReferenceStories.A2), from(6, 1), values("balance", money("10")))
                .build());
        assertThat(funded.rule().checks()).as("a change after the instant that the rule read").isEqualTo(checked);
        funded.store().commit(importer()
                .patch(entity(funded.accounts(), ReferenceStories.A2), from(3, 1), values("balance", money("20")))
                .build());

        assertThat(funded.rule().checks()).as("a change at that instant: the key read the accounts")
                .isEqualTo(checked + 1);
    }

    @Test
    void aRuleThatFindsByAKeyRunsAgainWhenAnAccountIsCreated() {
        Funded funded = Funded.start();
        int checked = funded.rule().checks();
        TemporaryId a3 = new TemporaryId("a3");

        funded.store().commit(importer()
                .create(a3, "Account", from(1, 1), values("number", "A-3", "currency", "EUR", "balance", money("5")))
                .relate("HeldBy", a3, entity(funded.store().parties, ReferenceStories.BETA), from(1, 1), values())
                .build());

        assertThat(funded.rule().checks()).isEqualTo(checked + 1);
    }

    @Test
    void aRuleThatFindsByAKeyIsNotRunAgainForAChangeToAnotherTypeOrToTheRelationshipsOfAnAccount() {
        Funded funded = Funded.start();
        int checked = funded.rule().checks();

        funded.store().commit(importer().patch(entity(funded.store().parties, ReferenceStories.ALICE), from(1, 1),
                values("name", "Alice B.")).build());

        assertThat(funded.rule().checks()).isEqualTo(checked);
    }

    @Test
    void aRuleThatFindsNoAccountReportsItsProblem() {
        AccountFundedRule rule = new AccountFundedRule();
        ReferenceStore store = ReferenceStore.withRules(AccountFundedRule.model(), ReferenceStore.rulesWith(rule),
                ReferenceModel.declaredRules());
        Receipt parties = store.createParties();
        List<Problem> problems = store.rejectReplay(ReferenceStories.rd3Employment(entity(parties, ReferenceStories.BETA),
                entity(parties, ReferenceStories.ALICE)));

        assertThat(only(problems).ruleCode()).contains("account-a1-funded/underfunded");
        assertThat(rule.checks()).isEqualTo(1);
    }
}
