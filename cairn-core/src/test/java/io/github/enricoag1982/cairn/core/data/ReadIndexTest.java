package io.github.enricoag1982.cairn.core.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.core.data.Timeline.Stretch;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The index of what rules read (design D3 of {@code add-rule-rechecks}): checks per entity or relationship and rule,
 * found from the keys that changes name; a run replaces the older checks it overlaps; writes cut periods off; a purge
 * leaves no purged ID. The index is built here by hand, without a store.
 */
class ReadIndexTest {

    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant MAR = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant APR = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant JUN = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant SEP = Instant.parse("2026-09-01T00:00:00Z");

    private static final EntityId PROBE = new EntityId("probe");
    private static final EntityId OTHER_PROBE = new EntityId("other-probe");
    private static final EntityId ACME = new EntityId("acme");
    private static final EntityId BETA = new EntityId("beta");
    private static final RelationshipId OWNS = new RelationshipId("owns");
    private static final RuleReference RULE = new RuleReference("probe-rule", 1);
    private static final RuleReference OTHER_RULE = new RuleReference("other-rule", 1);
    private static final TypeId ORGANIZATION = new TypeId(7);

    private static final ReadKey ACME_KEY = new ReadKey.Entity(ACME);
    private static final ReadKey BETA_KEY = new ReadKey.Entity(BETA);
    private static final ReadKey OWNS_KEY = new ReadKey.Relationship(OWNS);
    private static final ReadKey ACME_OWNS = new ReadKey.Navigation(ACME, new TypeId(3), true);
    private static final ReadKey ORGANIZATIONS = new ReadKey.OfType(ORGANIZATION);

    private final ReadIndex index = new ReadIndex();

    /** The reads of a run, made by what the consumer reads. */
    private static Reads reads(Consumer<Reads> reading) {
        Reads reads = new Reads();
        reading.accept(reads);
        reads.close();
        return reads;
    }

    private static Reads reading(ReadKey key, Instant at) {
        return reads(reads -> reads.at(key, at));
    }

    /** Applies a stage with one run. */
    private void ran(Id subject, RuleReference rule, Instant from, Instant end, Reads reads) {
        ReadIndex.Stage stage = new ReadIndex.Stage();
        stage.ran(subject, rule, from, end, reads);
        index.apply(stage);
    }

    private static Map<ReadKey, List<Stretch>> changes(ReadKey key, Instant from, Instant end) {
        return Map.of(key, List.of(new Stretch(from, end)));
    }

    private List<Stretch> periodsOf(Id subject, RuleReference rule) {
        return index.checks(subject, rule).stream().flatMap(check -> check.periods().stream()).toList();
    }

    // ------------------------------------------------------------------ finding the checks that a change reaches

    @Test
    void aReadAtAnInstantIsReachedByAChangeInAStretchThatContainsTheInstant() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reading(ACME_KEY, JUN));

        assertThat(index.hits(changes(ACME_KEY, MAR, SEP))).as("inside").hasSize(1);
        assertThat(index.hits(changes(ACME_KEY, JUN, SEP))).as("from the instant").hasSize(1);
        assertThat(index.hits(changes(ACME_KEY, MAR, JUN))).as("until the instant, which is not in it").isEmpty();
        assertThat(index.hits(changes(ACME_KEY, SEP, Segment.OPEN))).as("after").isEmpty();
        assertThat(index.hits(changes(ACME_KEY, JAN, MAR))).as("before").isEmpty();
        assertThat(index.hits(changes(ACME_KEY, JUN, Segment.OPEN))).as("an open stretch").hasSize(1);
    }

    @Test
    void aReadAcrossEffectiveTimeIsReachedByAnyChangeOfItsKeyButNotOfOthers() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reads(reads -> reads.acrossTime(ACME_KEY)));

        assertThat(index.hits(changes(ACME_KEY, SEP, Segment.OPEN))).hasSize(1);
        assertThat(index.hits(changes(ACME_KEY, JAN, MAR))).hasSize(1);
        assertThat(index.hits(changes(BETA_KEY, JAN, MAR))).as("another key").isEmpty();
        assertThat(index.hits(Map.of(ACME_KEY, List.of()))).as("no stretch, no change").isEmpty();
    }

    @Test
    void aKeyThatWasReadAtAnInstantAndLaterAcrossTimeIsReadAcrossTime() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reads(reads -> {
            reads.at(ACME_KEY, JUN);
            reads.acrossTime(ACME_KEY);
            reads.at(ACME_KEY, SEP);
        }));

        assertThat(index.hits(changes(ACME_KEY, JAN, MAR))).hasSize(1);
        Reads.Times times = index.checks(PROBE, RULE).getFirst().reads().get(ACME_KEY);
        assertThat(times.acrossTime()).isTrue();
        assertThat(times.instants()).isEmpty();
    }

    @Test
    void aCheckIsFoundOnceWhateverNumberOfKeysAChangeReaches() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reads(reads -> {
            reads.at(ACME_KEY, JUN);
            reads.at(BETA_KEY, JUN);
        }));

        assertThat(index.hits(Map.of(ACME_KEY, List.of(new Stretch(JAN, Segment.OPEN)),
                BETA_KEY, List.of(new Stretch(JAN, Segment.OPEN))))).hasSize(1);
    }

    @Test
    void everyKindOfKeyIsAKeyOfItsOwn() {
        ReadKey otherDirection = new ReadKey.Navigation(ACME, new TypeId(3), false);
        ReadKey otherType = new ReadKey.Navigation(ACME, new TypeId(4), true);
        ReadKey otherEntity = new ReadKey.Navigation(BETA, new TypeId(3), true);
        ran(PROBE, RULE, JAN, Segment.OPEN, reads(reads -> {
            reads.at(ACME_KEY, JUN);
            reads.at(OWNS_KEY, JUN);
            reads.at(ACME_OWNS, JUN);
            reads.at(ORGANIZATIONS, JUN);
        }));

        assertThat(index.hits(changes(otherDirection, JAN, Segment.OPEN))).as("the direction").isEmpty();
        assertThat(index.hits(changes(otherType, JAN, Segment.OPEN))).as("the relationship type").isEmpty();
        assertThat(index.hits(changes(otherEntity, JAN, Segment.OPEN))).as("the entity").isEmpty();
        assertThat(index.hits(changes(new ReadKey.OfType(new TypeId(8)), JAN, Segment.OPEN))).as("the entity type")
                .isEmpty();
        assertThat(index.hits(changes(new ReadKey.Relationship(new RelationshipId("acme")), JAN, Segment.OPEN)))
                .as("a relationship is not an entity with the same ID text").isEmpty();
        for (ReadKey key : List.of(ACME_KEY, OWNS_KEY, ACME_OWNS, ORGANIZATIONS)) {
            assertThat(index.hits(changes(key, JAN, Segment.OPEN))).as(key.toString()).hasSize(1);
        }
    }

    // ------------------------------------------------------------------ a run replaces the older checks

    @Test
    void aRunThatReadsNothingIsNotKept() {
        ran(PROBE, RULE, JAN, Segment.OPEN, new Reads());

        assertThat(index.size()).isZero();
        assertThat(index.consistent()).isTrue();
    }

    @Test
    void aLaterRunOfTheSameVersionReplacesTheReadsOfTheEarlierOne() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reading(ACME_KEY, JUN));

        ran(PROBE, RULE, JAN, Segment.OPEN, reading(BETA_KEY, JUN));

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.hits(changes(ACME_KEY, JAN, Segment.OPEN))).as("no longer read").isEmpty();
        assertThat(index.hits(changes(BETA_KEY, JAN, Segment.OPEN))).hasSize(1);
        assertThat(index.readers(ACME_KEY)).isEmpty();
        assertThat(index.consistent()).isTrue();
    }

    @Test
    void aLaterRunThatReadsNothingReplacesTheEarlierOneAndLeavesNoCheck() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reading(ACME_KEY, JUN));

        ran(PROBE, RULE, JAN, Segment.OPEN, new Reads());

        assertThat(index.size()).isZero();
        assertThat(index.mentionedIds()).isEmpty();
        assertThat(index.consistent()).isTrue();
    }

    @Test
    void aRunOfPartOfAVersionReplacesOnlyThatPartOfTheOlderCheck() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reading(ACME_KEY, JUN));

        ran(PROBE, RULE, MAR, APR, reading(BETA_KEY, JUN));

        assertThat(index.checks(PROBE, RULE)).hasSize(2);
        assertThat(periodsOf(PROBE, RULE)).containsExactlyInAnyOrder(new Stretch(JAN, MAR),
                new Stretch(APR, Segment.OPEN), new Stretch(MAR, APR));
        assertThat(index.hits(changes(ACME_KEY, JAN, Segment.OPEN))).as("the rest still reads ACME").hasSize(1);
        assertThat(index.hits(changes(BETA_KEY, JAN, Segment.OPEN))).hasSize(1);
        assertThat(index.consistent()).isTrue();
    }

    @Test
    void aRunDoesNotReplaceTheChecksOfOtherRulesOrOtherEntities() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reading(ACME_KEY, JUN));
        ran(PROBE, OTHER_RULE, JAN, Segment.OPEN, reading(ACME_KEY, JUN));
        ran(OTHER_PROBE, RULE, JAN, Segment.OPEN, reading(ACME_KEY, JUN));

        ran(PROBE, RULE, JAN, Segment.OPEN, reading(BETA_KEY, JUN));

        assertThat(index.size()).isEqualTo(3);
        assertThat(index.readers(ACME_KEY)).extracting(ReadIndex.Check::rule, ReadIndex.Check::subject)
                .containsExactlyInAnyOrder(tuple(OTHER_RULE, PROBE),
                        tuple(RULE, OTHER_PROBE));
    }

    // ------------------------------------------------------------------ writes cut off parts of periods

    @Test
    void aWriteThatChangesPartOfATimelineCutsThatPartOffTheOlderChecks() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reading(ACME_KEY, JUN));
        ran(PROBE, OTHER_RULE, JAN, Segment.OPEN, reading(ACME_KEY, JUN));

        ReadIndex.Stage stage = new ReadIndex.Stage();
        stage.cut(Map.of(PROBE, List.of(new Stretch(MAR, APR))));
        index.apply(stage);

        assertThat(periodsOf(PROBE, RULE)).containsExactly(new Stretch(JAN, MAR), new Stretch(APR, Segment.OPEN));
        assertThat(periodsOf(PROBE, OTHER_RULE)).as("every rule of the entity")
                .containsExactly(new Stretch(JAN, MAR), new Stretch(APR, Segment.OPEN));
    }

    @Test
    void aWriteThatEndsAnEntityCutsTheEndOffAndKeepsWhatCameBefore() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reading(ACME_KEY, JUN));

        ReadIndex.Stage stage = new ReadIndex.Stage();
        stage.cut(Map.of(PROBE, List.of(new Stretch(JUN, Segment.OPEN))));
        index.apply(stage);

        assertThat(periodsOf(PROBE, RULE)).containsExactly(new Stretch(JAN, JUN));
        assertThat(index.hits(changes(ACME_KEY, JUN, SEP))).as("the reads stay").hasSize(1);
    }

    @Test
    void aCheckWhosePeriodIsCutOffEntirelyIsDroppedWithItsKeys() {
        ran(PROBE, RULE, MAR, APR, reading(ACME_KEY, JUN));

        ReadIndex.Stage stage = new ReadIndex.Stage();
        stage.cut(Map.of(PROBE, List.of(new Stretch(JAN, SEP))));
        index.apply(stage);

        assertThat(index.size()).isZero();
        assertThat(index.readers(ACME_KEY)).isEmpty();
        assertThat(index.mentionedIds()).isEmpty();
        assertThat(index.consistent()).isTrue();
    }

    @Test
    void aWriteOfOtherEntitiesCutsNothing() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reading(ACME_KEY, JUN));

        ReadIndex.Stage stage = new ReadIndex.Stage();
        stage.cut(Map.of(OTHER_PROBE, List.of(new Stretch(JAN, Segment.OPEN))));
        index.apply(stage);

        assertThat(periodsOf(PROBE, RULE)).containsExactly(new Stretch(JAN, Segment.OPEN));
    }

    @Test
    void aCheckThatIsDroppedLeavesTheOthersAlone() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reading(ACME_KEY, JUN));
        ran(PROBE, OTHER_RULE, JAN, Segment.OPEN, reading(ACME_KEY, JUN));

        ReadIndex.Stage stage = new ReadIndex.Stage();
        stage.drop(index.checks(PROBE, RULE).getFirst());
        index.apply(stage);

        assertThat(index.checks(PROBE, RULE)).isEmpty();
        assertThat(index.checks(PROBE, OTHER_RULE)).hasSize(1);
        assertThat(index.readers(ACME_KEY)).hasSize(1);
        assertThat(index.consistent()).isTrue();
    }

    // ------------------------------------------------------------------ a stage changes nothing until it is applied

    @Test
    void aStageThatIsNotAppliedLeavesTheIndexAsItWas() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reading(ACME_KEY, JUN));
        Set<String> before = index.describe();

        ReadIndex.Stage stage = new ReadIndex.Stage();
        stage.ran(PROBE, RULE, JAN, Segment.OPEN, reading(BETA_KEY, JUN));
        stage.ran(OTHER_PROBE, RULE, JAN, Segment.OPEN, reading(BETA_KEY, JUN));
        stage.cut(Map.of(PROBE, List.of(new Stretch(JAN, Segment.OPEN))));
        stage.drop(index.checks(PROBE, RULE).getFirst());

        assertThat(index.describe()).isEqualTo(before);
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void aStageAppliesItsCutsBeforeItsRuns() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reading(ACME_KEY, JUN));
        ReadIndex.Stage stage = new ReadIndex.Stage();
        stage.ran(PROBE, RULE, MAR, APR, reading(BETA_KEY, JUN));
        stage.cut(Map.of(PROBE, List.of(new Stretch(MAR, Segment.OPEN))));

        index.apply(stage);

        assertThat(periodsOf(PROBE, RULE)).as("the run is not cut by the write that it follows")
                .containsExactlyInAnyOrder(new Stretch(JAN, MAR), new Stretch(MAR, APR));
    }

    // ------------------------------------------------------------------ purges

    @Test
    void aPurgeDropsTheChecksOfWhatItRemovesAndLeavesNoPurgedId() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reads(reads -> {
            reads.at(ACME_KEY, JUN);
            reads.at(OWNS_KEY, JUN);
            reads.at(ACME_OWNS, JUN);
            reads.at(BETA_KEY, JUN);
            reads.at(ORGANIZATIONS, JUN);
        }));
        ran(ACME, RULE, JAN, Segment.OPEN, reading(BETA_KEY, JUN));
        ran(OWNS, RULE, JAN, Segment.OPEN, reading(ORGANIZATIONS, JUN));
        ran(BETA, RULE, JAN, Segment.OPEN, reading(OWNS_KEY, JUN));

        index.purge(List.of(ACME, OWNS));

        assertThat(index.mentionedIds()).as("no purged ID stays").doesNotContain("acme", "owns")
                .containsExactlyInAnyOrder("probe", "beta");
        assertThat(index.checks(ACME, RULE)).as("the checks of purged entities").isEmpty();
        assertThat(index.checks(OWNS, RULE)).as("the checks of purged relationships").isEmpty();
        assertThat(index.checks(PROBE, RULE).getFirst().reads().keySet()).as("the other keys stay")
                .containsExactlyInAnyOrder(BETA_KEY, ORGANIZATIONS);
        assertThat(index.checks(BETA, RULE)).as("a check that read only a purged relationship is dropped").isEmpty();
        assertThat(index.readers(ACME_KEY)).isEmpty();
        assertThat(index.consistent()).isTrue();
    }

    @Test
    void aPurgeThatLeavesACheckWithoutReadsDropsIt() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reads(reads -> {
            reads.at(ACME_KEY, JUN);
            reads.at(ACME_OWNS, JUN);
        }));

        index.purge(List.of(ACME));

        assertThat(index.size()).isZero();
        assertThat(index.mentionedIds()).isEmpty();
        assertThat(index.consistent()).isTrue();
    }

    @Test
    void aPurgeOfSomethingNobodyReadsChangesNothing() {
        ran(PROBE, RULE, JAN, Segment.OPEN, reading(ACME_KEY, JUN));
        Set<String> before = index.describe();

        index.purge(List.of(new EntityId("somebody")));

        assertThat(index.describe()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ the recorder

    @Test
    void aClosedRecorderRecordsNothingMore() {
        Reads reads = new Reads();
        reads.at(ACME_KEY, JUN);
        reads.close();

        reads.at(BETA_KEY, JUN);
        reads.acrossTime(OWNS_KEY);

        assertThat(reads.times()).containsOnlyKeys(ACME_KEY);
    }

    @Test
    void theInstantsOfAReadAreKeptInOrder() {
        Reads reads = new Reads();
        reads.at(ACME_KEY, SEP);
        reads.at(ACME_KEY, JUN);
        reads.at(ACME_KEY, JUN);

        assertThat(reads.times().get(ACME_KEY).instants()).containsExactly(JUN, SEP);
        assertThat(reads.times().get(ACME_KEY).acrossTime()).isFalse();
    }
}
