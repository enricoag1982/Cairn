package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.assertProblem;
import static io.github.enricoag1982.cairn.core.ReferenceStore.id;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Design D6: every problem a transaction can have, with its path, and the report of every problem at once. */
class StoreValidationTest {

    private static final TemporaryId G = new TemporaryId("g");

    private final ReferenceStore gadgets = ReferenceStore.with(StoreWritesTest.gadgets());
    private final ReferenceStore reference = new ReferenceStore();

    private List<Problem> rejectGadget(Object... namesAndValues) {
        return gadgets.reject(Transaction.by("tester").create(G, "Gadget", from(1, 1), values(namesAndValues))
                .build());
    }

    private EntityId gadget(Object... namesAndValues) {
        return (EntityId) gadgets.commit(Transaction.by("tester")
                .create(G, "Gadget", from(1, 1), values(namesAndValues)).build()).temporaryIds().get(G);
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    // ------------------------------------------------------------------ types

    @Test
    void aValueOfTheWrongTypeSaysWhatWasExpectedAndFound() {
        Problem problem = only(rejectGadget("code", "G-1", "level", "high"));

        assertProblem(problem, ProblemCode.VALUE_TYPE_MISMATCH, "$g.level");
        assertThat(problem.expected()).contains("an int");
        assertThat(problem.found()).hasValueSatisfying(found -> assertThat(found).contains("a string", "high"));
    }

    @Test
    void anIntFitsALongOrDecimalAttributeAndALongFitsADecimalAttribute() {
        gadgets.commit(Transaction.by("tester").create(G, "Gadget", from(1, 1),
                values("code", "G-1", "count", 5, "weight", 6)).build());
        gadgets.commit(Transaction.by("tester").create(new TemporaryId("h"), "Gadget", from(1, 1),
                values("code", "G-2", "weight", 6L)).build());
    }

    @Test
    void aNumberDoesNotFitANarrowerAttribute() {
        assertProblem(only(rejectGadget("code", "G-1", "level", 5L)), ProblemCode.VALUE_TYPE_MISMATCH, "$g.level");
        assertProblem(only(rejectGadget("code", "G-1", "count", money("5"))), ProblemCode.VALUE_TYPE_MISMATCH,
                "$g.count");
        assertProblem(only(rejectGadget("code", "G-1", "level", money("5"))), ProblemCode.VALUE_TYPE_MISMATCH,
                "$g.level");
    }

    @Test
    void aStringAttributeTakesNoNumbers() {
        assertProblem(only(rejectGadget("code", "G-1", "note", 7)), ProblemCode.VALUE_TYPE_MISMATCH, "$g.note");
        assertProblem(only(rejectGadget("code", 7)), ProblemCode.VALUE_TYPE_MISMATCH, "$g.code");
    }

    @Test
    void aValueOfTheWrongTypeIsNotAlsoReportedAsMissing() {
        List<Problem> problems = rejectGadget("code", 7);

        assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.VALUE_TYPE_MISMATCH);
    }

    // ------------------------------------------------------------------ ranges and string constraints

    @Test
    void aValueOutsideTheRangeIsReported() {
        Problem problem = only(rejectGadget("code", "G-1", "level", 0));

        assertProblem(problem, ProblemCode.VALUE_OUT_OF_RANGE, "$g.level");
        assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected).contains(">= 1", "< 10"));
        assertThat(problem.found()).contains("0");
    }

    @Test
    void rangeBoundsAreInclusiveOrExclusive() {
        assertProblem(only(rejectGadget("code", "G-1", "level", 10)), ProblemCode.VALUE_OUT_OF_RANGE, "$g.level");
        gadget("code", "G-1", "level", 1);
        gadgets.commit(Transaction.by("tester").create(new TemporaryId("h"), "Gadget", from(1, 1),
                values("code", "G-2", "level", 9)).build());
    }

    @Test
    void aValueThatIsNotAllowedIsReported() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(new TemporaryId("role"), "CustomerRole", from(3, 1), values("segment", "Private")).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.VALUE_NOT_ALLOWED, "$role.segment");
        assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected)
                .contains("Corporate", "Retail"));
        assertThat(problem.found()).contains("Private");
    }

    @Test
    void aValueThatBreaksAPatternIsReportedWithThePattern() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME", "lei", "5493001kjtiigc8y1r12"))
                .build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.VALUE_PATTERN_MISMATCH, "$acme.lei");
        assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected)
                .contains("[A-Z0-9]{18}[0-9]{2}"));
        assertThat(problem.found()).contains("5493001kjtiigc8y1r12");
    }

    @Test
    void thePatternMustMatchTheWholeValue() {
        assertProblem(only(reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME", "lei", LEI + "X")).build())),
                ProblemCode.VALUE_PATTERN_MISMATCH, "$acme.lei");
    }

    @Test
    void lengthsAreCountedInCodePoints() {
        assertProblem(only(rejectGadget("code", "A")), ProblemCode.VALUE_LENGTH, "$g.code");
        assertProblem(only(rejectGadget("code", "ABCDEF")), ProblemCode.VALUE_LENGTH, "$g.code");
        gadget("code", "😀😀😀😀😀");
        assertProblem(only(gadgets.reject(Transaction.by("tester").create(new TemporaryId("h"), "Gadget", from(1, 1),
                values("code", "😀".repeat(6))).build())), ProblemCode.VALUE_LENGTH, "$h.code");
    }

    @Test
    void problemsOfAnEntityFollowTheOrderOfTheAttributesInTheSchema() {
        List<Problem> problems = rejectGadget("level", 20, "code", "ABCDEFG");

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.VALUE_LENGTH, "$g.code"), tuple(ProblemCode.VALUE_OUT_OF_RANGE, "$g.level"));
    }

    @Test
    void aBadValueIsReportedOnceEvenIfSeveralVersionsHoldIt() {
        EntityId id = gadget("code", "G-1");

        List<Problem> problems = gadgets.reject(Transaction.by("tester")
                .patch(id, from(2, 1), values("level", 0))
                .patch(id, from(4, 1), values("note", "x")).build());

        assertProblem(only(problems), ProblemCode.VALUE_OUT_OF_RANGE, id.value() + ".level");
    }

    @Test
    void aBadValueThatALaterWriteOverwritesIsNoProblem() {
        gadgets.commit(Transaction.by("tester")
                .create(G, "Gadget", from(1, 1), values("code", "G-1", "level", 0))
                .patch(G, from(1, 1), values("level", 5)).build());
    }

    // ------------------------------------------------------------------ required attributes

    @Test
    void aMissingRequiredValueNamesThePeriodWithoutIt() {
        EntityId id = gadget("code", "G-1");

        List<Problem> problems = gadgets.reject(Transaction.by("tester")
                .replace(id, EffectivePeriod.between(on(3, 1), on(6, 1)), values("level", 2)).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.MISSING_REQUIRED_VALUE, id.value() + ".code");
        assertThat(problem.message()).contains("2026-03-01T00:00:00Z", "2026-06-01T00:00:00Z");
        assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected).contains("string"));
    }

    @Test
    void aClearedRequiredAttributeIsMissing() {
        EntityId id = gadget("code", "G-1");

        List<Problem> problems = gadgets.reject(Transaction.by("tester")
                .patch(id, from(3, 1), values(), Set.of("code"), ConflictCheck.none()).build());

        assertProblem(only(problems), ProblemCode.MISSING_REQUIRED_VALUE, id.value() + ".code");
    }

    @Test
    void adjacentVersionsWithoutARequiredValueAreOneProblem() {
        EntityId id = gadget("code", "G-1");
        gadgets.commit(Transaction.by("tester").patch(id, from(6, 1), values("level", 2)).build());

        List<Problem> problems = gadgets.reject(Transaction.by("tester")
                .patch(id, from(3, 1), values(), Set.of("code"), ConflictCheck.none()).build());

        Problem problem = only(problems);
        assertThat(problem.message()).contains("2026-03-01T00:00:00Z").contains("until further notice");
    }

    @Test
    void separateStretchesWithoutARequiredValueAreSeparateProblems() {
        EntityId id = gadget("code", "G-1");

        List<Problem> problems = gadgets.reject(Transaction.by("tester")
                .patch(id, EffectivePeriod.between(on(2, 1), on(3, 1)), values(), Set.of("code"), ConflictCheck.none())
                .patch(id, EffectivePeriod.between(on(5, 1), on(6, 1)), values(), Set.of("code"), ConflictCheck.none())
                .build());

        assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.MISSING_REQUIRED_VALUE,
                ProblemCode.MISSING_REQUIRED_VALUE);
    }

    @Test
    void aRequiredAttributeIsNotReportedMissingWhenAWriteNamedAnUnknownAttribute() {
        List<Problem> problems = rejectGadget("kode", "G-1");

        assertProblem(only(problems), ProblemCode.UNKNOWN_ATTRIBUTE, "$g.kode");
        assertThat(only(problems).hint()).hasValueSatisfying(hint -> assertThat(hint).contains("code"));
    }

    // ------------------------------------------------------------------ unknown and deprecated attributes

    @Test
    void anUnknownAttributeInAPatchOrAClearIsReported() {
        EntityId id = gadget("code", "G-1");

        List<Problem> problems = gadgets.reject(Transaction.by("tester")
                .patch(id, from(3, 1), values("levle", 4), Set.of("nots"), ConflictCheck.none()).build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.UNKNOWN_ATTRIBUTE, id.value() + ".levle"),
                tuple(ProblemCode.UNKNOWN_ATTRIBUTE, id.value() + ".nots"));
        assertThat(problems.get(0).hint()).hasValueSatisfying(hint -> assertThat(hint).contains("level"));
        assertThat(problems.get(1).hint()).hasValueSatisfying(hint -> assertThat(hint).contains("note"));
    }

    @Test
    void anUnknownAttributeWithoutASimilarNameHasNoHint() {
        Problem problem = only(rejectGadget("code", "G-1", "colour", "red"));

        assertThat(problem.hint()).isEmpty();
        assertThat(problem.found()).contains("colour");
        assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected).contains("code", "level"));
    }

    @Test
    void theHintForAnUnknownAttributeCanBeAnAlias() {
        Problem problem = only(rejectGadget("code", "G-1", "remarks", "x"));

        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("note"));
    }

    private void deprecateBirthDate() {
        var deprecated = Definitions.changeEntityType(ReferenceModel.definition(), "Person", person ->
                Definitions.changeAttribute(person, "birthDate", old ->
                        AttributeDefinition.builder("birthDate", ValueType.DATE).deprecated().build()));
        assertThat(reference.store.schema().register(ReferenceModel.origin("1.1.0"), deprecated))
                .isInstanceOf(RegistrationResult.Applied.class);
    }

    @Test
    void creatingAnEntityWithADeprecatedAttributeIsAProblem() {
        deprecateBirthDate();

        List<Problem> problems = reference.reject(Transaction.by("importer").create(ALICE, "Person", from(1, 1),
                values("name", "Alice", "birthDate", LocalDate.of(1990, 5, 17))).build());

        assertProblem(only(problems), ProblemCode.DEPRECATED_ATTRIBUTE, "$alice.birthDate");
    }

    @Test
    void aStoredDeprecatedValueStaysReadableAndCanBeClearedButNotSet() {
        EntityId alice = (EntityId) reference.commit(Transaction.by("importer").create(ALICE, "Person", from(1, 1),
                values("name", "Alice", "birthDate", LocalDate.of(1990, 5, 17))).build())
                .temporaryIds().get(ALICE);
        deprecateBirthDate();

        reference.commit(Transaction.by("importer").patch(alice, from(3, 1), values("name", "Alice B.")).build());
        assertThat(reference.view(on(4, 1)).entity(alice).orElseThrow().values())
                .containsEntry("birthDate", Value.of(LocalDate.of(1990, 5, 17)));
        reference.commit(Transaction.by("importer")
                .patch(alice, from(5, 1), values(), Set.of("birthDate"), ConflictCheck.none()).build());
        assertThat(reference.view(on(6, 1)).entity(alice).orElseThrow().values()).doesNotContainKey("birthDate");
        assertProblem(only(reference.reject(Transaction.by("importer")
                .patch(alice, from(7, 1), values("birthDate", LocalDate.of(1991, 1, 1))).build())),
                ProblemCode.DEPRECATED_ATTRIBUTE, alice.value() + ".birthDate");
    }

    // ------------------------------------------------------------------ uniqueness

    @Test
    void aValueIsTakenOnlyWhereThePeriodsOverlap() {
        reference.commit(Transaction.by("importer").create(ACME, "Organization",
                EffectivePeriod.between(on(1, 1), on(3, 1)), values("name", "ACME", "lei", LEI)).build());

        assertProblem(only(reference.reject(Transaction.by("importer").create(GAMMA, "Organization",
                EffectivePeriod.between(on(2, 1), on(4, 1)), values("name", "Gamma", "lei", LEI)).build())),
                ProblemCode.UNIQUE_VALUE_TAKEN, "$gamma.lei");
        reference.commit(Transaction.by("importer").create(GAMMA, "Organization",
                EffectivePeriod.between(on(3, 1), on(4, 1)), values("name", "Gamma", "lei", LEI)).build());
    }

    @Test
    void anEntityMayKeepItsOwnUniqueValueAcrossVersions() {
        EntityId acme = (EntityId) reference.commit(Transaction.by("importer").create(ACME, "Organization",
                from(1, 1), values("name", "ACME", "lei", LEI)).build()).temporaryIds().get(ACME);

        reference.commit(Transaction.by("importer").patch(acme, from(3, 1), values("name", "ACME Holdings")).build());
    }

    @Test
    void changingAUniqueValueToOneThatIsTakenIsAProblemOnTheChangedEntity() {
        reference.commit(Transaction.by("importer").create(ACME, "Organization", from(1, 1),
                values("name", "ACME", "lei", LEI)).build());
        EntityId beta = (EntityId) reference.commit(Transaction.by("importer").create(BETA, "Organization",
                from(1, 1), values("name", "Beta")).build()).temporaryIds().get(BETA);

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(beta, from(3, 1), values("lei", LEI)).build());

        assertProblem(only(problems), ProblemCode.UNIQUE_VALUE_TAKEN, beta.value() + ".lei");
        assertThat(only(problems).message()).contains("2026-03-01T00:00:00Z");
    }

    @Test
    void twoEntitiesOfOneTransactionWithTheSameUniqueValueAreReportedOnceOnTheSecond() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME", "lei", LEI))
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma", "lei", LEI)).build());

        assertProblem(only(problems), ProblemCode.UNIQUE_VALUE_TAKEN, "$gamma.lei");
    }

    @Test
    void anEntityThatIsEndedBeforeTheNewValueStartsDoesNotHoldIt() {
        EntityId acme = (EntityId) reference.commit(Transaction.by("importer").create(ACME, "Organization",
                from(1, 1), values("name", "ACME", "lei", LEI)).build()).temporaryIds().get(ACME);
        reference.commit(Transaction.by("importer").endDate(acme, on(3, 1)).build());

        assertProblem(only(reference.reject(Transaction.by("importer").create(GAMMA, "Organization", from(2, 1),
                values("name", "Gamma", "lei", LEI)).build())), ProblemCode.UNIQUE_VALUE_TAKEN, "$gamma.lei");
        reference.commit(Transaction.by("importer").create(GAMMA, "Organization", from(3, 1),
                values("name", "Gamma", "lei", LEI)).build());
    }

    @Test
    void uniquenessCoversTheSubtypesOfTheTypeThatDeclaresTheAttribute() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Base", type -> type.attribute("ref", ValueType.STRING, a -> a.required().unique()))
                .entityType("Derived", type -> type.supertype("Base"))
                .build();
        ReferenceStore store = ReferenceStore.with(definition);
        store.commit(Transaction.by("tester").create(G, "Base", from(1, 1), values("ref", "r1")).build());

        List<Problem> problems = store.reject(Transaction.by("tester")
                .create(new TemporaryId("d"), "Derived", from(1, 1), values("ref", "r1")).build());

        assertProblem(only(problems), ProblemCode.UNIQUE_VALUE_TAKEN, "$d.ref");
    }

    @Test
    void uniqueNumbersAreComparedByTheirNumericValue() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Item", type -> type.attribute("serial", ValueType.DECIMAL, a -> a.unique()))
                .build();
        ReferenceStore store = ReferenceStore.with(definition);
        store.commit(Transaction.by("tester").create(G, "Item", from(1, 1), values("serial", money("10"))).build());

        List<Problem> problems = store.reject(Transaction.by("tester")
                .create(new TemporaryId("i"), "Item", from(1, 1), values("serial", money("10.0"))).build());

        assertProblem(only(problems), ProblemCode.UNIQUE_VALUE_TAKEN, "$i.serial");
    }

    // ------------------------------------------------------------------ entity types

    @Test
    void anUnknownEntityTypeWithoutASimilarNameHasNoHint() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Spaceship", from(1, 1), values("name", "ACME")).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.UNKNOWN_ENTITY_TYPE, "$acme");
        assertThat(problem.hint()).isEmpty();
        assertThat(problem.found()).contains("Spaceship");
    }

    @Test
    void aStoreWithoutASchemaKnowsNoEntityTypes() {
        var empty = Stores.inMemory();

        var result = empty.commit(Transaction.by("tester").create(ACME, "Organization", from(1, 1), values()).build());

        assertThat(result).isInstanceOf(io.github.enricoag1982.cairn.data.CommitResult.Rejected.class);
    }

    @Test
    void anAbstractEntityTypeNamesItsConcreteSubtypes() {
        Problem problem = only(reference.reject(Transaction.by("importer")
                .create(ACME, "Party", from(1, 1), values("name", "ACME")).build()));

        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Organization", "Person"));
    }

    // ------------------------------------------------------------------ references and IDs

    @Test
    void writesToAnUnknownEntityAreReportedWithTheIdAsPath() {
        EntityId missing = id(9999);

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(missing, from(1, 1), values("name", "x"))
                .replace(missing, from(1, 1), values("name", "x"))
                .endDate(missing, on(6, 1)).build());

        assertThat(problems).hasSize(3).allSatisfy(problem -> assertProblem(problem, ProblemCode.UNKNOWN_ENTITY,
                missing.value()));
    }

    @Test
    void aTemporaryIdMustBeCreatedBeforeItIsUsed() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(ACME, from(3, 1), values("name", "ACME Holdings"))
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build());

        assertProblem(only(problems), ProblemCode.UNKNOWN_TEMPORARY_ID, "$acme");
    }

    @Test
    void aTemporaryIdNamesOneEntity() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .create(ACME, "Organization", from(1, 1), values("name", "Other")).build());

        assertProblem(only(problems), ProblemCode.DUPLICATE_TEMPORARY_ID, "$acme");
    }

    @Test
    void aSuppliedIdThatTheTransactionUsesTwiceExistsTheSecondTime() {
        EntityId supplied = id(42);

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(supplied, "Organization", from(1, 1), values("name", "ACME"))
                .create(supplied, "Organization", from(1, 1), values("name", "Beta")).build());

        assertProblem(only(problems), ProblemCode.ENTITY_EXISTS, supplied.value());
    }

    @Test
    void aSuppliedIdMustBeInTheFormatOfTheGenerator() {
        for (String text : List.of("acme", "00000000-0000-4000-8000-000000000001",
                "00000000-0000-7000-8000-00000000000A", "")) {
            List<Problem> problems = reference.reject(Transaction.by("importer")
                    .create(new EntityId(text), "Organization", from(1, 1), values("name", "ACME")).build());

            assertProblem(only(problems), ProblemCode.INVALID_ENTITY_ID, text);
        }
    }

    @Test
    void theProblemOfACreatedEntityIsLocatedAtItsTemporaryIdEvenWithASuppliedId() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, id(7), "Organization", from(1, 1), values()).build());

        assertProblem(only(problems), ProblemCode.MISSING_REQUIRED_VALUE, "$acme.name");
    }

    @Test
    void theProblemOfAnEntityCreatedWithoutAnyIdIsLocatedAtItsWritePosition() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create("Organization", from(1, 1), values()).build());

        assertProblem(only(problems), ProblemCode.MISSING_REQUIRED_VALUE, "@write.0.name");
    }

    @Test
    void theWritePositionCountsEveryWriteOfTheTransactionFromZero() {
        EntityId acme = (EntityId) reference.createParties().temporaryIds().get(ACME);

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(acme, from(3, 1), values("name", "ACME Holdings"))
                .create("Organization", from(1, 1), values("name", "Valid"))
                .endDate(acme, on(6, 1))
                .create("Organization", from(1, 1), values("nmae", "Misspelled"))
                .create("Organization", from(1, 1), values()).build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.UNKNOWN_ATTRIBUTE, "@write.3.nmae"),
                tuple(ProblemCode.MISSING_REQUIRED_VALUE, "@write.4.name"));
    }

    @Test
    void entityProblemsWithoutAnAttributeAreLocatedAtTheWritePositionToo() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create("Party", from(1, 1), values("name", "Abstract"))
                .create("Organisation", from(1, 1), values("name", "Misspelled"))
                .create("Organization", EffectivePeriod.between(on(3, 1), on(2, 1)), values("name", "Backwards"))
                .build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.ABSTRACT_ENTITY_TYPE, "@write.0"),
                tuple(ProblemCode.UNKNOWN_ENTITY_TYPE, "@write.1"),
                tuple(ProblemCode.INVALID_PERIOD, "@write.2"));
    }

    @Test
    void aDryRunAndACommitLocateTheProblemOfAnEntityWithoutAnyIdAtTheSamePath() {
        Transaction invalid = Transaction.by("importer")
                .create("Organization", from(1, 1), values("name", "Valid"))
                .create("Organization", from(1, 1), values()).build();

        var dry = reference.store.dryRun(invalid);
        List<Problem> committing = reference.reject(invalid);

        assertThat(dry).isEqualTo(new DryRunResult.Rejected(committing));
        assertProblem(only(committing), ProblemCode.MISSING_REQUIRED_VALUE, "@write.1.name");
    }

    @Test
    void aDryRunAndACommitReportTheSamePathsEvenIfTheStoreGeneratesRandomIds() {
        ReferenceStore random = new ReferenceStore(IdGenerators.uuidV7());
        Transaction invalid = Transaction.by("importer")
                .create("Organization", from(1, 1), values("name", "Valid"))
                .create("Organization", from(1, 1), values()).build();

        var dry = random.store.dryRun(invalid);
        List<Problem> committing = random.reject(invalid);

        assertThat(dry).isEqualTo(new DryRunResult.Rejected(committing));
        assertProblem(only(committing), ProblemCode.MISSING_REQUIRED_VALUE, "@write.1.name");
    }

    @Test
    void aSuppliedIdOrATemporaryIdStillLocatesTheProblemsOfItsEntity() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(id(7), "Organization", from(1, 1), values())
                .create(BETA, "Organization", from(1, 1), values())
                .create(GAMMA, id(8), "Organization", from(1, 1), values()).build());

        assertThat(problems).extracting(Problem::path).containsExactly(id(7).value() + ".name", "$beta.name",
                "$gamma.name");
    }

    @Test
    void aUniqueValueTakenByAnEntityWithoutAnyIdNamesItsWritePosition() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create("Organization", from(1, 1), values("name", "ACME", "lei", LEI))
                .create("Organization", from(1, 1), values("name", "Gamma", "lei", LEI)).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.UNIQUE_VALUE_TAKEN, "@write.1.lei");
        assertThat(problem.message()).contains("@write.0");
    }

    // ------------------------------------------------------------------ periods

    @Test
    void aPeriodMustEndAfterItStarts() {
        EntityId acme = (EntityId) reference.createParties().temporaryIds().get(ACME);
        EffectivePeriod empty = EffectivePeriod.between(on(3, 1), on(3, 1));
        EffectivePeriod backwards = EffectivePeriod.between(on(3, 1), on(2, 1));

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(GAMMA, "Organization", empty, values("name", "Gamma"))
                .patch(acme, backwards, values("name", "x"))
                .replace(acme, empty, values("name", "x")).build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.INVALID_PERIOD, "$gamma"),
                tuple(ProblemCode.INVALID_PERIOD, acme.value()),
                tuple(ProblemCode.INVALID_PERIOD, acme.value()));
    }

    // ------------------------------------------------------------------ conflict checks

    @Test
    void aPerEntityCheckRejectsAnyChangeSinceTheCallerRead() {
        EntityId acme = (EntityId) reference.createParties().temporaryIds().get(ACME);
        Basis readAt = reference.store.latestBasis();
        reference.clock.advance(Duration.ofMinutes(1));
        reference.commit(Transaction.by("other").patch(acme, from(3, 1), values("lei", LEI)).build());

        List<Problem> problems = reference.reject(Transaction.by("me")
                .patch(acme, from(3, 1), values("name", "ACME Holdings"), ConflictCheck.perEntity(readAt)).build());

        assertProblem(only(problems), ProblemCode.CONFLICT, acme.value());
    }

    @Test
    void aPerEntityCheckPassesWhenNothingChangedSinceTheCallerRead() {
        EntityId acme = (EntityId) reference.createParties().temporaryIds().get(ACME);
        Basis readAt = reference.store.latestBasis();
        reference.clock.advance(Duration.ofMinutes(1));
        reference.commit(Transaction.by("other").patch(id(3), from(3, 1), values("name", "Alice B."))
                .build());

        reference.commit(Transaction.by("me")
                .patch(acme, from(3, 1), values("name", "ACME Holdings"), ConflictCheck.perEntity(readAt)).build());
    }

    @Test
    void aPerAttributeCheckLooksOnlyAtTheAttributesTheWriteSetsOrClears() {
        EntityId acme = (EntityId) reference.createParties().temporaryIds().get(ACME);
        Basis readAt = reference.store.latestBasis();
        reference.clock.advance(Duration.ofMinutes(1));
        reference.commit(Transaction.by("other").patch(acme, from(3, 1), values("lei", LEI)).build());

        reference.commit(Transaction.by("me")
                .patch(acme, from(3, 1), values("name", "ACME Holdings"), ConflictCheck.perAttribute(readAt)).build());
        List<Problem> setsLei = reference.reject(Transaction.by("me")
                .patch(acme, from(4, 1), values("lei", "5493001KJTIIGC8Y1R13"), ConflictCheck.perAttribute(readAt))
                .build());
        List<Problem> clearsLei = reference.reject(Transaction.by("me")
                .patch(acme, from(4, 1), values(), Set.of("lei"), ConflictCheck.perAttribute(readAt)).build());

        assertProblem(only(setsLei), ProblemCode.CONFLICT, acme.value() + ".lei");
        assertProblem(only(clearsLei), ProblemCode.CONFLICT, acme.value() + ".lei");
    }

    @Test
    void aPerAttributeCheckOfAReplaceOrAnEndDateLooksAtTheWholeEntity() {
        EntityId acme = (EntityId) reference.createParties().temporaryIds().get(ACME);
        Basis readAt = reference.store.latestBasis();
        reference.clock.advance(Duration.ofMinutes(1));
        reference.commit(Transaction.by("other").patch(acme, from(3, 1), values("lei", LEI)).build());

        List<Problem> replace = reference.reject(Transaction.by("me")
                .replace(acme, from(6, 1), values("name", "x"), ConflictCheck.perAttribute(readAt)).build());
        List<Problem> endDate = reference.reject(Transaction.by("me")
                .endDate(acme, on(6, 1), ConflictCheck.perAttribute(readAt)).build());

        assertProblem(only(replace), ProblemCode.CONFLICT, acme.value());
        assertProblem(only(endDate), ProblemCode.CONFLICT, acme.value());
    }

    @Test
    void anEntityCreatedAfterTheCallerReadIsChanged() {
        Basis readAt = reference.store.latestBasis();
        reference.clock.advance(Duration.ofMinutes(1));
        EntityId acme = (EntityId) reference.createParties().temporaryIds().get(ACME);

        List<Problem> problems = reference.reject(Transaction.by("me")
                .patch(acme, from(3, 1), values("name", "x"), ConflictCheck.perEntity(readAt)).build());

        assertProblem(only(problems), ProblemCode.CONFLICT, acme.value());
    }

    @Test
    void anEntityCreatedInTheSameTransactionCannotConflict() {
        reference.commit(Transaction.by("me").create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .patch(ACME, from(3, 1), values("name", "x"), ConflictCheck.perEntity(Basis.NONE)).build());
    }

    @Test
    void aCheckAtTheLatestBasisFindsNoConflict() {
        EntityId acme = (EntityId) reference.createParties().temporaryIds().get(ACME);

        reference.commit(Transaction.by("me").patch(acme, from(3, 1), values("name", "x"),
                ConflictCheck.perEntity(reference.store.latestBasis())).build());
        reference.commit(Transaction.by("me").patch(acme, from(4, 1), values("name", "y"),
                ConflictCheck.perEntity(new Basis(9999))).build());
    }

    @Test
    void theConflictCheckComparesWithTheStateBeforeTheTransactionAndNotWithItsOwnWrites() {
        EntityId acme = (EntityId) reference.createParties().temporaryIds().get(ACME);
        Basis readAt = reference.store.latestBasis();

        reference.commit(Transaction.by("me")
                .patch(acme, from(3, 1), values("name", "x"), ConflictCheck.perEntity(readAt))
                .patch(acme, from(4, 1), values("name", "y"), ConflictCheck.perEntity(readAt)).build());
    }

    // ------------------------------------------------------------------ the transaction itself

    @Test
    void aTransactionWithoutAPrincipalIsRejectedAndEveryOtherProblemIsReportedToo() {
        List<Problem> problems = reference.reject(Transaction.by(null)
                .create(ACME, "Organisation", from(1, 1), values("name", "ACME")).build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.MISSING_PRINCIPAL, "@transaction"),
                tuple(ProblemCode.UNKNOWN_ENTITY_TYPE, "$acme"));
    }

    @Test
    void everyProblemIsReportedInTheOrderOfTheWritesAndThenOfTheEntities() {
        EntityId missing = id(500);

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("nmae", "ACME", "lei", "bad"))
                .patch(missing, from(1, 1), values("name", "x"))
                .create(BETA, "Party", from(1, 1), values("name", "Beta"))
                .create(ALICE, "Person", from(1, 1), values("birthDate", 42))
                .build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.UNKNOWN_ATTRIBUTE, "$acme.nmae"),
                tuple(ProblemCode.UNKNOWN_ENTITY, missing.value()),
                tuple(ProblemCode.ABSTRACT_ENTITY_TYPE, "$beta"),
                tuple(ProblemCode.VALUE_PATTERN_MISMATCH, "$acme.lei"),
                tuple(ProblemCode.MISSING_REQUIRED_VALUE, "$alice.name"),
                tuple(ProblemCode.VALUE_TYPE_MISMATCH, "$alice.birthDate"));
    }

    @Test
    void theSameTransactionAlwaysGivesTheProblemsInTheSameOrder() {
        Transaction transaction = Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("lei", "bad", "name", 4))
                .create(BETA, "Organization", from(1, 1), values())
                .create(ALICE, "Person", from(1, 1), values("birthDate", 42)).build();

        List<Problem> first = reference.reject(transaction);
        List<Problem> second = reference.reject(transaction);

        assertThat(second).isEqualTo(first);
        assertThat(first).extracting(Problem::path).containsExactly("$acme.name", "$acme.lei", "$beta.name",
                "$alice.name", "$alice.birthDate");
    }

    @Test
    void writesToAnEntityThatCouldNotBeCreatedAddNoFurtherProblems() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Spaceship", from(1, 1), values("name", "ACME"))
                .patch(ACME, from(3, 1), values("nmae", "x"))
                .replace(ACME, from(4, 1), values())
                .endDate(ACME, on(5, 1)).build());

        assertProblem(only(problems), ProblemCode.UNKNOWN_ENTITY_TYPE, "$acme");
    }

    @Test
    void aRejectedTransactionLeavesNoTrace() {
        Basis before = reference.store.latestBasis();

        reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .create(BETA, "Organization", from(1, 1), values()).build());

        assertThat(reference.store.latestBasis()).isEqualTo(before);
        assertThat(reference.store.transactions()).isEmpty();
        assertThat(reference.view(on(2, 1)).entities("Party")).isEmpty();
        assertThat(reference.createParties().created()).as("no ID was used up").containsExactly(id(1), id(2), id(3));
    }
}
