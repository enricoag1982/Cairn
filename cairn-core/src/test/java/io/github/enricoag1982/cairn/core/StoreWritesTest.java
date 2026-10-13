package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.assertProblem;
import static io.github.enricoag1982.cairn.core.ReferenceStore.id;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Design D4 and D5: how creates, patches, replaces and end-dates change the timelines of entities. */
class StoreWritesTest {

    private static final TemporaryId GADGET = new TemporaryId("gadget");

    /** A type with one attribute of each kind that the writes convert or constrain. */
    static SchemaDefinition gadgets() {
        return SchemaDefinition.builder()
                .entityType("Gadget", type -> type
                        .attribute("code", ValueType.STRING, a -> a.required().unique().minLength(2).maxLength(5))
                        .attribute("level", ValueType.INT, a -> a.range(Range.between(
                                Bound.inclusive(Value.of(1)), Bound.exclusive(Value.of(10)))))
                        .attribute("weight", ValueType.DECIMAL)
                        .attribute("count", ValueType.LONG)
                        .attribute("note", ValueType.STRING, a -> a.aliases("remark")))
                .build();
    }

    private final ReferenceStore reference = ReferenceStore.with(gadgets());

    private EntityId gadget(Object... namesAndValues) {
        return (EntityId) reference.commit(Transaction.by("tester")
                .create(GADGET, "Gadget", from(1, 1), values(namesAndValues)).build())
                .temporaryIds().get(GADGET);
    }

    private EntityState at(EntityId id, Instant effectiveAt) {
        return reference.view(effectiveAt).entity(id).orElseThrow();
    }

    // ------------------------------------------------------------------ create

    @Test
    void aCreatedEntityHasTheValuesAndThePeriodOfTheCreate() {
        EntityId id = gadget("code", "G-1", "level", 3);

        EntityState state = at(id, on(2, 1));

        assertThat(state.id()).isEqualTo(id);
        assertThat(state.entityType()).isEqualTo("Gadget");
        assertThat(state.values()).containsOnly(Map.entry("code", Value.of("G-1")), Map.entry("level", Value.of(3)));
        assertThat(state.period()).isEqualTo(EffectivePeriod.from(on(1, 1)));
        assertThat(state.recordedBasis()).isEqualTo(reference.store.latestBasis());
    }

    @Test
    void aCreatedEntityCanHaveAClosedPeriod() {
        Receipt receipt = reference.commit(Transaction.by("tester")
                .create(GADGET, "Gadget", EffectivePeriod.between(on(1, 1), on(3, 1)), values("code", "G-1"))
                .build());
        EntityId id = (EntityId) receipt.temporaryIds().get(GADGET);

        assertThat(reference.view(on(2, 28)).entity(id)).isPresent();
        assertThat(reference.view(on(3, 1)).entity(id)).isEmpty();
        assertThat(reference.view(on(3, 1)).timeline(id)).singleElement().satisfies(state ->
                assertThat(state.period()).isEqualTo(EffectivePeriod.between(on(1, 1), on(3, 1))));
    }

    @Test
    void anEntityTypeIsFoundByAliasAndWithoutRegardToCase() {
        Receipt receipt = reference.commit(Transaction.by("tester")
                .create(GADGET, "gADGET", from(1, 1), values("code", "G-1")).build());

        assertThat(reference.view(on(2, 1)).entity((EntityId) receipt.temporaryIds().get(GADGET)))
                .get().extracting(EntityState::entityType).isEqualTo("Gadget");
    }

    @Test
    void attributesAreFoundByAliasAndWithoutRegardToCase() {
        EntityId id = gadget("CODE", "G-1", "remark", "by alias");

        assertThat(at(id, on(2, 1)).values()).containsEntry("code", Value.of("G-1"))
                .containsEntry("note", Value.of("by alias"));
    }

    @Test
    void valuesAreConvertedToTheValueTypeOfTheirAttributeWhenTheyAreWritten() {
        EntityId id = gadget("code", "G-1", "count", 7, "weight", 7L);

        assertThat(at(id, on(2, 1)).values()).containsEntry("count", Value.of(7L))
                .containsEntry("weight", Value.of(new BigDecimal("7")));
    }

    @Test
    void aNameGivenTwiceKeepsTheLaterValue() {
        EntityId id = gadget("note", "first", "remark", "second", "code", "G-1");

        assertThat(at(id, on(2, 1)).values()).containsEntry("note", Value.of("second"));
    }

    // ------------------------------------------------------------------ patch

    @Test
    void aPatchLeavesTheOtherAttributesAlone() {
        EntityId id = gadget("code", "G-1", "level", 3, "note", "x");

        reference.commit(Transaction.by("tester").patch(id, from(3, 1), values("level", 4)).build());

        assertThat(at(id, on(4, 1)).values()).containsEntry("level", Value.of(4))
                .containsEntry("code", Value.of("G-1")).containsEntry("note", Value.of("x"));
        assertThat(at(id, on(2, 1)).values()).containsEntry("level", Value.of(3));
    }

    @Test
    void aPatchWithAClosedPeriodChangesOnlyThatPeriod() {
        EntityId id = gadget("code", "G-1", "level", 3);

        reference.commit(Transaction.by("tester")
                .patch(id, EffectivePeriod.between(on(3, 1), on(6, 1)), values("level", 4)).build());

        assertThat(reference.view(on(2, 1)).timeline(id)).extracting(state -> state.values().get("level"))
                .containsExactly(Value.of(3), Value.of(4), Value.of(3));
        assertThat(reference.view(on(2, 1)).timeline(id)).extracting(EntityState::period).containsExactly(
                EffectivePeriod.between(on(1, 1), on(3, 1)), EffectivePeriod.between(on(3, 1), on(6, 1)),
                EffectivePeriod.from(on(6, 1)));
    }

    @Test
    void aPatchCanClearAnOptionalAttribute() {
        EntityId id = gadget("code", "G-1", "note", "x");

        reference.commit(Transaction.by("tester")
                .patch(id, from(3, 1), values(), Set.of("remark"), ConflictCheck.none()).build());

        assertThat(at(id, on(2, 1)).values()).containsKey("note");
        assertThat(at(id, on(3, 1)).values()).doesNotContainKey("note").containsKey("code");
    }

    @Test
    void aPatchCanSetAndClearInOneWrite() {
        EntityId id = gadget("code", "G-1", "note", "x");

        reference.commit(Transaction.by("tester")
                .patch(id, from(3, 1), values("level", 2), Set.of("note"), ConflictCheck.none()).build());

        assertThat(at(id, on(3, 1)).values()).containsEntry("level", Value.of(2)).doesNotContainKey("note");
    }

    @Test
    void aPatchThatChangesNothingKeepsTheTimelineAndItsRecording() {
        EntityId id = gadget("code", "G-1", "level", 3);
        Receipt first = reference.commit(Transaction.by("tester").patch(id, from(3, 1), values("level", 3)).build());

        assertThat(reference.view(on(2, 1)).timeline(id)).singleElement().satisfies(state -> {
            assertThat(state.period()).isEqualTo(EffectivePeriod.from(on(1, 1)));
            assertThat(state.recordedBasis()).isLessThan(first.basis());
        });
        assertThat(reference.store.latestBasis()).as("the no-op still is a transaction").isEqualTo(first.basis());
        assertThat(reference.store.transactions()).hasSize(2).last().extracting(TransactionRecord::basis)
                .isEqualTo(first.basis());
    }

    @Test
    void aPatchThatRestoresEarlierValuesKeepsTheVersionOfEachTransactionThatRecordedThem() {
        EntityId id = gadget("code", "G-1", "level", 3);
        Basis created = reference.store.latestBasis();
        reference.commit(Transaction.by("tester").patch(id, from(3, 1), values("level", 4)).build());
        assertThat(reference.view(on(2, 1)).timeline(id)).hasSize(2);

        Receipt restored = reference.commit(Transaction.by("tester").patch(id, from(3, 1), values("level", 3)).build());

        List<EntityState> timeline = reference.view(on(2, 1)).timeline(id);
        assertThat(timeline).extracting(EntityState::period).containsExactly(
                EffectivePeriod.between(on(1, 1), on(3, 1)), EffectivePeriod.from(on(3, 1)));
        assertThat(timeline).extracting(state -> state.values().get("level")).containsExactly(Value.of(3),
                Value.of(3));
        assertThat(timeline).extracting(EntityState::recordedBasis).containsExactly(created, restored.basis());
        assertThat(timeline.get(1).recordedAt()).isEqualTo(restored.transactionTime());
    }

    @Test
    void versionsThatOneTransactionRecordedWithEqualValuesAreMerged() {
        EntityId id = gadget("code", "G-1", "level", 3);

        Receipt receipt = reference.commit(Transaction.by("tester")
                .patch(id, EffectivePeriod.between(on(3, 1), on(6, 1)), values("level", 4))
                .patch(id, from(6, 1), values("level", 4)).build());

        List<EntityState> timeline = reference.view(on(2, 1)).timeline(id);
        assertThat(timeline).extracting(EntityState::period).containsExactly(
                EffectivePeriod.between(on(1, 1), on(3, 1)), EffectivePeriod.from(on(3, 1)));
        assertThat(timeline.get(1).recordedBasis()).isEqualTo(receipt.basis());
    }

    @Test
    void aTransactionThatChangesAValueAndChangesItBackRecordsNothingForTheEntity() {
        EntityId id = gadget("code", "G-1", "level", 3);
        Basis created = reference.store.latestBasis();

        Receipt receipt = reference.commit(Transaction.by("tester")
                .patch(id, from(3, 1), values("level", 4))
                .patch(id, from(3, 1), values("level", 3)).build());

        assertThat(receipt.basis()).as("the transaction is recorded").isGreaterThan(created);
        assertThat(reference.view(on(2, 1)).timeline(id)).singleElement().satisfies(state -> {
            assertThat(state.period()).isEqualTo(EffectivePeriod.from(on(1, 1)));
            assertThat(state.recordedBasis()).isEqualTo(created);
        });
    }

    @Test
    void earlierBasesKeepTheirVersions() {
        EntityId id = gadget("code", "G-1", "level", 3);
        var before = reference.store.latestBasis();
        reference.commit(Transaction.by("tester").patch(id, from(1, 1), values("level", 4)).build());

        var earlier = reference.store.view(Coordinates.effectiveAt(on(2, 1))
                .knownAt(before));

        assertThat(earlier.entity(id).orElseThrow().values()).containsEntry("level", Value.of(3));
        assertThat(at(id, on(2, 1)).values()).containsEntry("level", Value.of(4));
    }

    @Test
    void aPatchOfAnEntityCreatedInTheSameTransactionAppliesToTheCreatedState() {
        Receipt receipt = reference.commit(Transaction.by("tester")
                .create(GADGET, "Gadget", from(1, 1), values("code", "G-1", "level", 3))
                .patch(GADGET, from(3, 1), values("level", 4))
                .replace(GADGET, from(6, 1), values("code", "G-1", "note", "late"))
                .endDate(GADGET, on(9, 1))
                .build());
        EntityId id = (EntityId) receipt.temporaryIds().get(GADGET);

        assertThat(reference.view(on(2, 1)).timeline(id)).extracting(EntityState::values).containsExactly(
                Map.of("code", Value.of("G-1"), "level", Value.of(3)),
                Map.of("code", Value.of("G-1"), "level", Value.of(4)),
                Map.of("code", Value.of("G-1"), "note", Value.of("late")));
        assertThat(reference.view(on(9, 1)).entity(id)).isEmpty();
        assertThat(receipt.created()).containsExactly(id);
    }

    @Test
    void anEntityCanBePatchedByItsSuppliedIdInTheTransactionThatCreatesIt() {
        EntityId supplied = id(500);

        reference.commit(Transaction.by("tester")
                .create(supplied, "Gadget", from(1, 1), values("code", "G-1"))
                .patch(supplied, from(3, 1), values("level", 4)).build());

        assertThat(at(supplied, on(4, 1)).values()).containsEntry("level", Value.of(4));
    }

    // ------------------------------------------------------------------ replace

    @Test
    void aReplaceSetsExactlyTheGivenValuesInThePeriod() {
        EntityId id = gadget("code", "G-1", "level", 3, "note", "x");

        reference.commit(Transaction.by("tester").replace(id, EffectivePeriod.between(on(3, 1), on(6, 1)),
                values("code", "G-2", "weight", money("1.50"))).build());

        assertThat(reference.view(on(2, 1)).timeline(id)).extracting(EntityState::values).containsExactly(
                Map.of("code", Value.of("G-1"), "level", Value.of(3), "note", Value.of("x")),
                Map.of("code", Value.of("G-2"), "weight", Value.of(money("1.50"))),
                Map.of("code", Value.of("G-1"), "level", Value.of(3), "note", Value.of("x")));
    }

    @Test
    void aReplaceWithTheSameStateChangesNothing() {
        EntityId id = gadget("code", "G-1", "level", 3);

        reference.commit(Transaction.by("tester").replace(id, from(3, 1), values("code", "G-1", "level", 3)).build());

        assertThat(reference.view(on(2, 1)).timeline(id)).hasSize(1);
    }

    // ------------------------------------------------------------------ end-date

    @Test
    void anEndDateCutsTheLifetimeAndKeepsTheHistory() {
        EntityId id = gadget("code", "G-1", "level", 3);
        reference.commit(Transaction.by("tester").patch(id, from(6, 1), values("level", 4)).build());
        var beforeEnd = reference.store.latestBasis();

        reference.commit(Transaction.by("tester").endDate(id, on(3, 1)).build());

        assertThat(reference.view(on(2, 28)).entity(id)).isPresent();
        assertThat(reference.view(on(3, 1)).entity(id)).isEmpty();
        assertThat(reference.view(on(7, 1)).entity(id)).isEmpty();
        assertThat(reference.view(on(1, 1)).timeline(id)).singleElement().satisfies(state ->
                assertThat(state.period()).isEqualTo(EffectivePeriod.between(on(1, 1), on(3, 1))));
        assertThat(reference.store.view(Coordinates.effectiveAt(on(7, 1))
                .knownAt(beforeEnd)).entity(id)).isPresent();
    }

    @Test
    void endingAnEntityAfterItsEndChangesNothing() {
        EntityId id = (EntityId) reference.commit(Transaction.by("tester")
                .create(GADGET, "Gadget", EffectivePeriod.between(on(1, 1), on(3, 1)), values("code", "G-1")).build())
                .temporaryIds().get(GADGET);

        reference.commit(Transaction.by("tester").endDate(id, on(9, 1)).build());

        assertThat(reference.view(on(1, 1)).timeline(id)).singleElement().satisfies(state ->
                assertThat(state.period()).isEqualTo(EffectivePeriod.between(on(1, 1), on(3, 1))));
    }

    @Test
    void anEntityCannotBeEndedAtOrBeforeItsStart() {
        EntityId id = gadget("code", "G-1");

        List<Problem> problems = reference.reject(Transaction.by("tester").endDate(id, on(1, 1)).build());

        assertProblem(problems.getFirst(), ProblemCode.ENTITY_NOT_EFFECTIVE, id.value());
        assertThat(problems).hasSize(1);
    }

    @Test
    void anEndedEntityCannotBePatchedOrReplacedAfterItsEnd() {
        EntityId id = gadget("code", "G-1");
        reference.commit(Transaction.by("tester").endDate(id, on(3, 1)).build());

        List<Problem> patch = reference.reject(Transaction.by("tester").patch(id, from(4, 1), values("level", 2)).build());
        List<Problem> replace = reference.reject(Transaction.by("tester")
                .replace(id, from(4, 1), values("code", "G-1")).build());
        List<Problem> across = reference.reject(Transaction.by("tester").patch(id, from(2, 1), values("level", 2)).build());

        assertProblem(patch.getFirst(), ProblemCode.ENTITY_NOT_EFFECTIVE, id.value());
        assertProblem(replace.getFirst(), ProblemCode.ENTITY_NOT_EFFECTIVE, id.value());
        assertProblem(across.getFirst(), ProblemCode.ENTITY_NOT_EFFECTIVE, id.value());
    }

    @Test
    void writesAfterAnEndDateInTheSameTransactionSeeTheEnd() {
        EntityId id = gadget("code", "G-1");

        List<Problem> problems = reference.reject(Transaction.by("tester")
                .endDate(id, on(3, 1)).patch(id, from(4, 1), values("level", 2)).build());

        assertProblem(problems.getFirst(), ProblemCode.ENTITY_NOT_EFFECTIVE, id.value());
    }

    @Test
    void writesNeverExtendALifetime() {
        EntityId id = (EntityId) reference.commit(Transaction.by("tester")
                .create(GADGET, "Gadget", EffectivePeriod.between(on(3, 1), on(6, 1)), values("code", "G-1")).build())
                .temporaryIds().get(GADGET);

        assertThat(reference.reject(Transaction.by("tester")
                .patch(id, EffectivePeriod.between(on(2, 1), on(4, 1)), values("level", 2)).build())).isNotEmpty();
        assertThat(reference.reject(Transaction.by("tester")
                .patch(id, EffectivePeriod.between(on(5, 1), on(8, 1)), values("level", 2)).build())).isNotEmpty();
        assertThat(reference.reject(Transaction.by("tester").patch(id, from(4, 1), values("level", 2)).build()))
                .isNotEmpty();
        reference.commit(Transaction.by("tester")
                .patch(id, EffectivePeriod.between(on(4, 1), on(6, 1)), values("level", 2)).build());
    }

    // ------------------------------------------------------------------ values across schema versions

    @Test
    void aWidenedAttributeIsReadInItsNewValueType() {
        EntityId id = gadget("code", "G-1", "level", 3);
        var widened = Definitions.changeEntityType(gadgets(), "Gadget", gadget ->
                Definitions.changeAttribute(gadget, "level", old ->
                        AttributeDefinition.builder("level", ValueType.LONG).range(old.range().orElseThrow())
                                .build()));
        // The range bounds of the old schema are ints, which fit a long attribute.
        assertThat(reference.store.schema().register(ReferenceModel.origin("1.1.0"), widened))
                .isInstanceOf(RegistrationResult.Applied.class);

        assertThat(at(id, on(2, 1)).values()).containsEntry("level", Value.of(3L));
        reference.commit(Transaction.by("tester").patch(id, from(3, 1), values("level", 5)).build());
        assertThat(at(id, on(4, 1)).values()).containsEntry("level", Value.of(5L));
    }

    @Test
    void anAttributeIsFoundByItsAliasAfterARename() {
        EntityId id = gadget("code", "G-1", "note", "x");
        var renamed = Definitions.changeEntityType(gadgets(), "Gadget", gadget ->
                Definitions.changeAttribute(gadget, "note", old ->
                        Definitions.renamed(old, "comment", "note", "remark")));
        assertThat(reference.store.schema().register(ReferenceModel.origin("1.1.0"), renamed))
                .isInstanceOf(RegistrationResult.Applied.class);

        reference.commit(Transaction.by("tester").patch(id, from(3, 1), values("note", "y")).build());

        assertThat(at(id, on(2, 1)).values()).containsEntry("comment", Value.of("x"));
        assertThat(at(id, on(4, 1)).values()).containsEntry("comment", Value.of("y")).doesNotContainKey("note");
    }
}
