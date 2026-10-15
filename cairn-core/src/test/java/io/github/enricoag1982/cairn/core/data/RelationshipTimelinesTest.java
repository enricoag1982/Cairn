package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Design D2 of {@code add-relationships}: relationships are stored like entities, a type, two ends and a timeline for
 * each basis, and relate, patch, replace, end-date and corrections change their timelines as they change those of
 * entities. The tests read the stored relationships through a {@link Recorder}, as they are stored, not through a view.
 */
class RelationshipTimelinesTest {

    private static final TemporaryId OWNS = new TemporaryId("owns");
    private static final TemporaryId EMPLOYS = new TemporaryId("employs");

    private final Recorder recorder = new Recorder();
    private EntityId acme;
    private EntityId beta;
    private EntityId alice;

    @BeforeEach
    void theParties() {
        recorder.clock.moveTo(on(1, 5));
        Preparation parties = recorder.commit(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .create(BETA, "Organization", from(1, 1), values("name", "Beta"))
                .create(ALICE, "Person", from(1, 1), values("name", "Alice")).build());
        acme = (EntityId) parties.temporaryIds().get(ACME);
        beta = (EntityId) parties.temporaryIds().get(BETA);
        alice = (EntityId) parties.temporaryIds().get(ALICE);
    }

    private RelationshipId owns(Instant recordedOn, Object share) {
        recorder.clock.moveTo(recordedOn);
        return (RelationshipId) recorder.commit(Transaction.by("importer")
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", share)).build())
                .temporaryIds().get(OWNS);
    }

    private List<Timeline> history(RelationshipId id, Basis... bases) {
        StoredRelationship stored = recorder.relationships.get(id);
        return Arrays.stream(bases).map(stored::at).toList();
    }

    /** The only value of the relationship at an instant: its share. */
    private static Value shareAt(Timeline timeline, Instant instant) {
        return timeline.at(instant).orElseThrow().values().values().iterator().next();
    }

    @Test
    void aRelationshipIsStoredWithItsTypeItsEndsAndOneSegmentOfValues() {
        recorder.clock.moveTo(on(2, 10));
        Preparation preparation = recorder.commit(Transaction.by("importer")
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 60)).build());
        RelationshipId id = (RelationshipId) preparation.temporaryIds().get(OWNS);

        StoredRelationship stored = recorder.relationships.get(id);

        RelationshipType owns = recorder.registry.current().relationshipType("Owns").orElseThrow();
        assertThat(stored.type()).isEqualTo(owns.id());
        assertThat(stored.source()).isEqualTo(acme);
        assertThat(stored.target()).isEqualTo(beta);
        assertThat(stored.latest().segments()).singleElement().satisfies(segment -> {
            assertThat(segment.from()).isEqualTo(on(2, 1));
            assertThat(segment.end()).isEqualTo(Segment.OPEN);
            assertThat(segment.values().values()).containsExactly(Value.of(money("60")));
            assertThat(segment.recordedAt()).isEqualTo(on(2, 10));
            assertThat(segment.recordedBasis()).isEqualTo(preparation.commit().basis());
        });
        assertThat(stored.latestBasis()).isEqualTo(preparation.commit().basis());
    }

    @Test
    void onlyTheRelationshipIsRecordedWhenItIsRelated() {
        recorder.clock.moveTo(on(2, 10));
        Preparation preparation = recorder.prepare(Transaction.by("importer")
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 60)).build());

        assertThat(preparation.problems()).isEmpty();
        assertThat(preparation.changes()).singleElement().satisfies(change -> {
            assertThat(change.id()).isInstanceOf(RelationshipId.class);
            assertThat(change.created()).isTrue();
            assertThat(change.source()).isEqualTo(acme);
            assertThat(change.target()).isEqualTo(beta);
        });
    }

    @Test
    void aCorrectionKeepsTheEarlierTimelineAtTheEarlierBasis() {
        RelationshipId id = owns(on(2, 10), 60);
        Basis first = recorder.sequence.latestBasis();
        recorder.clock.moveTo(on(5, 2));
        Basis second = recorder.commit(Transaction.by("importer")
                .patch(id, from(2, 1), values("share", 55)).build()).commit().basis();

        List<Timeline> timelines = history(id, first, second);

        assertThat(shareAt(timelines.get(0), on(3, 1))).isEqualTo(Value.of(money("60")));
        assertThat(shareAt(timelines.get(1), on(3, 1))).isEqualTo(Value.of(money("55")));
        assertThat(timelines.get(1).segments()).singleElement().satisfies(segment -> {
            assertThat(segment.recordedAt()).isEqualTo(on(5, 2));
            assertThat(segment.recordedBasis()).isEqualTo(second);
        });
        assertThat(recorder.relationships.get(id).at(Basis.NONE).isEmpty()).isTrue();
    }

    @Test
    void aPatchSplitsTheTimelineAndLeavesTheRecordingOfWhatItDoesNotChange() {
        RelationshipId id = owns(on(2, 10), 60);
        Basis first = recorder.sequence.latestBasis();
        recorder.clock.moveTo(on(5, 2));
        Basis second = recorder.commit(Transaction.by("importer")
                .patch(id, from(4, 1), values("share", 55)).build()).commit().basis();

        List<Segment> segments = recorder.relationships.get(id).latest().segments();

        assertThat(segments).extracting(Segment::from, Segment::end, Segment::recordedBasis).containsExactly(
                tuple(on(2, 1), on(4, 1), first), tuple(on(4, 1), Segment.OPEN, second));
        assertThat(segments).extracting(segment -> segment.values().values().iterator().next())
                .containsExactly(Value.of(money("60")), Value.of(money("55")));
    }

    @Test
    void aReplaceSetsTheCompleteStateOfARelationship() {
        recorder.clock.moveTo(on(3, 1));
        RelationshipId id = (RelationshipId) recorder.commit(Transaction.by("importer")
                .relate(EMPLOYS, "Employs", beta, alice, from(3, 1), values("title", "Analyst")).build())
                .temporaryIds().get(EMPLOYS);
        recorder.clock.moveTo(on(4, 1));

        recorder.commit(Transaction.by("importer").replace(id, from(4, 1), values()).build());

        List<Segment> segments = recorder.relationships.get(id).latest().segments();
        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).values()).hasSize(1);
        assertThat(segments.get(1).values()).isEmpty();
    }

    @Test
    void anEndDateCutsTheLifetimeOfARelationship() {
        RelationshipId id = owns(on(2, 10), 60);
        Basis first = recorder.sequence.latestBasis();
        recorder.clock.moveTo(on(7, 1));

        recorder.commit(Transaction.by("importer").endDate(id, on(6, 1)).build());

        StoredRelationship stored = recorder.relationships.get(id);
        assertThat(stored.latest().lifetime()).contains(new Timeline.Lifetime(on(2, 1), on(6, 1)));
        assertThat(stored.at(first).lifetime()).contains(new Timeline.Lifetime(on(2, 1), Segment.OPEN));
    }

    @Test
    void aTransactionThatChangesNothingRecordsNoTimelineOfARelationship() {
        RelationshipId id = owns(on(2, 10), 60);
        Basis first = recorder.sequence.latestBasis();
        recorder.clock.moveTo(on(5, 2));

        Preparation preparation = recorder.commit(Transaction.by("importer")
                .patch(id, from(2, 1), values("share", 60)).build());

        assertThat(preparation.changes()).isEmpty();
        assertThat(recorder.relationships.get(id).latestBasis()).isEqualTo(first);
    }

    @Test
    void theCascadeOfAnEndDateIsRecordedLikeTheEndDatesOfTheWrites() {
        recorder.clock.moveTo(on(1, 6));
        Preparation withAddress = recorder.commit(Transaction.by("importer")
                .create(new TemporaryId("home"), "Address", from(1, 1),
                        values("street", "1 Main St", "city", "Springfield", "country", "US"))
                .relate(new TemporaryId("hasAddress"), "HasAddress", alice, new TemporaryId("home"), from(1, 1),
                        values()).build());
        EntityId home = (EntityId) withAddress.temporaryIds().get(new TemporaryId("home"));
        RelationshipId hasAddress = (RelationshipId) withAddress.temporaryIds().get(new TemporaryId("hasAddress"));
        recorder.clock.moveTo(on(7, 1));

        Preparation ending = recorder.commit(Transaction.by("importer").endDate(alice, on(6, 1)).build());

        assertThat(Recorder.changed(ending)).containsExactly(alice.value(), hasAddress.value(), home.value());
        assertThat(ending.changes()).allSatisfy(change -> assertThat(change.created()).isFalse());
        assertThat(recorder.entities.get(home).latest().lifetime())
                .contains(new Timeline.Lifetime(on(1, 1), on(6, 1)));
        assertThat(recorder.relationships.get(hasAddress).latest().lifetime())
                .contains(new Timeline.Lifetime(on(1, 1), on(6, 1)));
        assertThat(recorder.entities.get(alice).latest().lifetime())
                .contains(new Timeline.Lifetime(on(1, 1), on(6, 1)));
    }

    @Test
    void aCascadeIntoARelationshipThatAlreadyEndedChangesNothing() {
        recorder.clock.moveTo(on(1, 6));
        Preparation withAddress = recorder.commit(Transaction.by("importer")
                .create(new TemporaryId("home"), "Address", EffectivePeriod.between(on(1, 1), on(4, 1)),
                        values("street", "1 Main St", "city", "Springfield", "country", "US"))
                .relate(new TemporaryId("hasAddress"), "HasAddress", alice, new TemporaryId("home"),
                        EffectivePeriod.between(on(1, 1), on(4, 1)), values()).build());
        recorder.clock.moveTo(on(7, 1));

        Preparation ending = recorder.commit(Transaction.by("importer").endDate(alice, on(6, 1)).build());

        assertThat(Recorder.changed(ending)).containsExactly(alice.value());
        assertThat(withAddress.created()).hasSize(2);
    }
}
