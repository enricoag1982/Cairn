package io.github.enricoag1982.cairn.core.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.core.commit.CommitSequence;
import io.github.enricoag1982.cairn.core.kv.InMemoryKeyValueStore;
import io.github.enricoag1982.cairn.core.schema.InMemorySchemaRegistry;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The ledger keeps, for an entity or a relationship, the timeline that each transaction left it with, and reads the one
 * that was known at a basis (design D4 of {@code add-entity-versions} and D2 of {@code add-relationships}, as ADR-0027
 * and design D3 and D4 of {@code refactor-storage-onto-key-value-store} keep them): a later write never changes what an
 * earlier basis reads. These tests take the place of those of the maps of timelines that the ledger replaced.
 */
class LedgerTest {

    private static final AttributeId NAME = new AttributeId(1);
    private static final AttributeId BALANCE = new AttributeId(2);
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant MAR = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant RECORDED_1 = Instant.parse("2026-01-05T09:00:00Z");
    private static final Instant RECORDED_2 = Instant.parse("2026-03-16T09:00:00Z");
    private static final EntityId E1 = new EntityId("e1");
    private static final EntityId SOURCE = new EntityId("a");
    private static final EntityId TARGET = new EntityId("b");
    private static final RelationshipId R1 = new RelationshipId("r1");
    private static final TypeId TYPE = new TypeId(1);

    private final Ledger ledger = new Ledger(new InMemoryKeyValueStore());
    private final SchemaIndex schema = new SchemaIndex(new InMemorySchemaRegistry(
            new CommitSequence(new TestClock()), List.of()).current());

    private static Timeline first() {
        return Timeline.created(JAN, Segment.OPEN, Map.of(NAME, Value.of("ACME"), BALANCE, Value.of(1000)),
                RECORDED_1, new Basis(1));
    }

    private static Timeline second(Timeline timeline) {
        return timeline.update(MAR, Segment.OPEN, old -> {
            Map<AttributeId, Value> changed = new HashMap<>(old);
            changed.put(BALANCE, Value.of(1500));
            return changed;
        }, RECORDED_2, new Basis(4));
    }

    private void create(Basis basis, Timeline timeline) {
        ledger.write(basis, List.of(new Preparation.Change(E1, TYPE, null, null, true, timeline)), schema);
    }

    private void change(Basis basis, Timeline timeline) {
        ledger.write(basis, List.of(new Preparation.Change(E1, TYPE, null, null, false, timeline)), schema);
    }

    @Test
    void anEntityHasOneTimelinePerBasisAndALaterWriteNeverChangesAnEarlierOne() {
        Timeline first = first();
        Timeline second = second(first);

        create(new Basis(1), first);
        change(new Basis(4), second);

        assertThat(ledger.timelineAt(E1, Basis.NONE).isEmpty()).isTrue();
        assertThat(ledger.timelineAt(E1, new Basis(1))).isEqualTo(first);
        assertThat(ledger.timelineAt(E1, new Basis(3))).isEqualTo(first);
        assertThat(ledger.timelineAt(E1, new Basis(4))).isEqualTo(second);
        assertThat(ledger.timelineAt(E1, new Basis(99))).isEqualTo(second);
        assertThat(ledger.entity(E1).orElseThrow().timeline()).isEqualTo(second);
        assertThat(ledger.entity(E1).orElseThrow().basis()).isEqualTo(new Basis(4));
        assertThat(ledger.entity(E1, new Basis(3)).orElseThrow().basis()).isEqualTo(new Basis(1));
        assertThat(ledger.entity(E1, Basis.NONE)).as("it was not created yet").isEmpty();
        assertThat(ledger.versions(E1, new Basis(99))).extracting(Ledger.Version::basis, Ledger.Version::timeline)
                .containsExactly(tuple(new Basis(1), first),
                        tuple(new Basis(4), second));
        assertThat(ledger.versions(E1, new Basis(3))).extracting(Ledger.Version::basis)
                .containsExactly(new Basis(1));
    }

    @Test
    void aRelationshipKeepsTheSameHistoryAsAnEntityDoes() {
        Timeline first = first();
        Timeline second = second(first);

        ledger.write(new Basis(1), List.of(new Preparation.Change(R1, TYPE, SOURCE, TARGET, true, first)), schema);
        ledger.write(new Basis(4), List.of(new Preparation.Change(R1, TYPE, SOURCE, TARGET, false, second)), schema);

        Held held = ledger.relationship(R1).orElseThrow();
        assertThat(held.source()).isEqualTo(SOURCE);
        assertThat(held.target()).isEqualTo(TARGET);
        assertThat(held.type()).isEqualTo(TYPE);
        assertThat(held.isRelationship()).isTrue();
        assertThat(ledger.timelineAt(R1, new Basis(3))).isEqualTo(first);
        assertThat(held.timeline()).isEqualTo(second);
        assertThat(held.basis()).isEqualTo(new Basis(4));
        assertThat(ledger.relationship(R1, new Basis(3)).orElseThrow().source()).isEqualTo(SOURCE);
        assertThat(ledger.entity(new EntityId("r1"))).as("an ID names a relationship or an entity, not both").isEmpty();
        assertThat(ledger.holder("r1")).contains(R1);
    }

    @Test
    void anEntityIsNotARelationshipWhateverItsIdentifier() {
        create(new Basis(1), first());

        assertThat(ledger.holder("e1")).contains(E1);
        assertThat(ledger.holder("nothing")).isEmpty();
        assertThat(ledger.relationship(new RelationshipId("e1"))).isEmpty();
        assertThat(ledger.relationship(new RelationshipId("e1"), new Basis(9))).isEmpty();
        assertThat(ledger.held(E1)).isPresent();
        assertThat(ledger.held(new RelationshipId("e1"))).isEmpty();
    }

    @Test
    void aWriteThatLeavesATimelineAsItWasWritesNoKey() {
        Timeline first = first();
        create(new Basis(1), first);

        var batch = ledger.batch(new Basis(2), List.of(new Preparation.Change(E1, TYPE, null, null, false, first)),
                schema);

        assertThat(batch.isEmpty()).isTrue();
        assertThat(ledger.entity(E1).orElseThrow().basis()).isEqualTo(new Basis(1));
    }

    @Test
    void aTransactionCannotCreateWhatTheLedgerHoldsOrChangeWhatItDoesNot() {
        Timeline first = first();

        assertThatThrownBy(() -> change(new Basis(1), first)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("e1");
        create(new Basis(1), first);
        assertThatThrownBy(() -> create(new Basis(2), first)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("e1");
    }

    @Test
    void purgingWhatTheLedgerDoesNotHoldFailsWithoutRemovingAnything() {
        create(new Basis(1), first());

        assertThatThrownBy(() -> ledger.purge(List.of(E1, new EntityId("missing")), List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing");
        assertThat(ledger.entity(E1)).isPresent();
    }
}
