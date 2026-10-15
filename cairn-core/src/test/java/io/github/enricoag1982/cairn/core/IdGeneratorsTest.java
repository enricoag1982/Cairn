package io.github.enricoag1982.cairn.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.IdGenerator;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** ADR-0019 and design D8: the ID generators of the engine. */
class IdGeneratorsTest {

    private static final Pattern UUID_V7 =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    /** The fields of an ID, read from its bits. */
    private record Fields(long timestamp, int version, int counter, int variant) {
        static Fields of(String id) {
            UUID uuid = UUID.fromString(id);
            long high = uuid.getMostSignificantBits();
            return new Fields(high >>> 16, (int) (high >>> 12) & 0xF, (int) high & 0xFFF,
                    (int) (uuid.getLeastSignificantBits() >>> 62));
        }
    }

    /** A clock that stands where it is told to. */
    private static final class MillisClock implements InstantSource {
        private final AtomicLong millis;

        MillisClock(long millis) {
            this.millis = new AtomicLong(millis);
        }

        void set(long value) {
            millis.set(value);
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    private static final long NOON = Instant.parse("2026-03-16T12:00:00Z").toEpochMilli();

    private static List<String> draw(IdGenerator generator, int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(generator.next());
        }
        return ids;
    }

    private static void assertStrictlyAscending(List<String> ids) {
        for (int i = 1; i < ids.size(); i++) {
            assertThat(ids.get(i)).as("ID %d after %d", i, i - 1).isGreaterThan(ids.get(i - 1));
        }
    }

    // ------------------------------------------------------------------ UUIDv7

    @Test
    void uuidV7IdsAreLowercaseHyphenatedVersion7WithVariant10() {
        IdGenerator generator = IdGenerators.uuidV7(new MillisClock(NOON), new Random(1));

        for (String id : draw(generator, 200)) {
            assertThat(id).matches(UUID_V7);
            Fields fields = Fields.of(id);
            assertThat(fields.version()).isEqualTo(7);
            assertThat(fields.variant()).isEqualTo(2);
        }
    }

    @Test
    void uuidV7IdsCarryTheClocksMilliseconds() {
        MillisClock clock = new MillisClock(NOON);
        IdGenerator generator = IdGenerators.uuidV7(clock, new Random(1));

        assertThat(Fields.of(generator.next()).timestamp()).isEqualTo(NOON);
        clock.set(NOON + 5_000);
        assertThat(Fields.of(generator.next()).timestamp()).isEqualTo(NOON + 5_000);
    }

    @Test
    void uuidV7IdsInTheSameMillisecondAreOrderedByACounter() {
        IdGenerator generator = IdGenerators.uuidV7(new MillisClock(NOON), new Random(1));

        List<String> ids = draw(generator, 1_000);

        assertStrictlyAscending(ids);
        assertThat(ids).extracting(id -> Fields.of(id).timestamp()).containsOnly(NOON);
        for (int i = 1; i < ids.size(); i++) {
            assertThat(Fields.of(ids.get(i)).counter()).isEqualTo(Fields.of(ids.get(i - 1)).counter() + 1);
        }
    }

    @Test
    void uuidV7IdsStayOrderedWhenTheCounterOverflowsByAdvancingTheTimestamp() {
        IdGenerator generator = IdGenerators.uuidV7(new MillisClock(NOON), new Random(1));

        List<String> ids = draw(generator, 20_000);

        assertStrictlyAscending(ids);
        assertThat(new HashSet<>(ids)).hasSameSizeAs(ids);
        assertThat(Fields.of(ids.getLast()).timestamp()).isGreaterThan(NOON);
    }

    @Test
    void uuidV7IdsStartTheCounterInTheLowerHalfToLeaveRoomToCount() {
        for (long seed = 0; seed < 200; seed++) {
            IdGenerator generator = IdGenerators.uuidV7(new MillisClock(NOON), new Random(seed));

            assertThat(Fields.of(generator.next()).counter()).isLessThan(2048);
        }
    }

    @Test
    void uuidV7IdsAscendAcrossMillisecondsAndEvenIfTheClockGoesBack() {
        MillisClock clock = new MillisClock(NOON);
        IdGenerator generator = IdGenerators.uuidV7(clock, new Random(1));
        List<String> ids = new ArrayList<>(draw(generator, 3));

        clock.set(NOON + 1);
        ids.addAll(draw(generator, 3));
        clock.set(NOON - 60_000);
        ids.addAll(draw(generator, 3));
        clock.set(NOON + 10);
        ids.addAll(draw(generator, 3));

        assertStrictlyAscending(ids);
    }

    @Test
    void uuidV7IdsAscendThroughManyMillisecondsWithManyIdsEach() {
        MillisClock clock = new MillisClock(NOON);
        IdGenerator generator = IdGenerators.uuidV7(clock, new Random(1));
        List<String> ids = new ArrayList<>();

        for (int millisecond = 0; millisecond < 50; millisecond++) {
            clock.set(NOON + millisecond);
            ids.addAll(draw(generator, 100));
        }

        assertStrictlyAscending(ids);
        assertThat(ids).extracting(id -> Fields.of(id).timestamp()).isSorted()
                .first().isEqualTo(NOON);
        assertThat(Fields.of(ids.getLast()).timestamp()).isEqualTo(NOON + 49);
    }

    @Test
    void uuidV7IdsSortAsTextInTheOrderTheyWereDrawn() {
        MillisClock clock = new MillisClock(NOON);
        IdGenerator generator = IdGenerators.uuidV7(clock, new Random(1));
        List<String> drawn = new ArrayList<>();

        for (long step : new long[] {0, 1, 1, 15, 16, 4_095, 4_096, 65_535, 65_536, 86_400_000, 31_536_000_000L}) {
            clock.set(clock.instant().toEpochMilli() + step);
            drawn.addAll(draw(generator, 50));
        }

        List<String> sorted = new ArrayList<>(drawn);
        Collections.sort(sorted);
        assertThat(sorted).isEqualTo(drawn);
    }

    @Test
    void uuidV7IdsAscendWhenTheClockReachesTheMillisecondsThatACounterOverflowBorrowed() {
        MillisClock clock = new MillisClock(NOON);
        IdGenerator generator = IdGenerators.uuidV7(clock, new Random(1));
        List<String> ids = new ArrayList<>(draw(generator, 3 * 4_096));
        long borrowed = Fields.of(ids.getLast()).timestamp();
        assertThat(borrowed).isGreaterThan(NOON);

        clock.set(NOON + 1);
        ids.addAll(draw(generator, 5));
        clock.set(borrowed);
        ids.addAll(draw(generator, 5));
        clock.set(borrowed + 1);
        ids.addAll(draw(generator, 5));

        assertStrictlyAscending(ids);
        assertThat(Fields.of(ids.getLast()).timestamp()).isEqualTo(borrowed + 1);
    }

    @Test
    void uuidV7TimestampsBeforeTheEpochAreZero() {
        IdGenerator generator = IdGenerators.uuidV7(new MillisClock(-1_000), new Random(1));

        assertThat(Fields.of(generator.next()).timestamp()).isZero();
    }

    @Test
    void uuidV7IdsDifferInTheirRandomBits() {
        MillisClock clock = new MillisClock(NOON);

        String first = IdGenerators.uuidV7(clock, new Random(1)).next();
        String second = IdGenerators.uuidV7(clock, new Random(2)).next();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void uuidV7IsRepeatableWithTheSameClockAndSeed() {
        List<String> first = draw(IdGenerators.uuidV7(new MillisClock(NOON), new Random(42)), 50);
        List<String> second = draw(IdGenerators.uuidV7(new MillisClock(NOON), new Random(42)), 50);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void theDefaultGeneratorReadsTheSystemClock() {
        long before = System.currentTimeMillis();
        IdGenerator generator = IdGenerators.uuidV7();

        List<String> ids = draw(generator, 100);
        long after = System.currentTimeMillis();

        assertStrictlyAscending(ids);
        for (String id : ids) {
            assertThat(generator.accepts(id)).isTrue();
            assertThat(Fields.of(id).version()).isEqualTo(7);
        }
        // The timestamp may run ahead of the clock by the milliseconds that counter overflows borrowed.
        assertThat(Fields.of(ids.getFirst()).timestamp()).isBetween(before, after);
    }

    @Test
    void uuidV7RejectsNulls() {
        assertThatThrownBy(() -> IdGenerators.uuidV7(null, new Random())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IdGenerators.uuidV7(InstantSource.system(), null))
                .isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------ deterministic

    @Test
    void theDeterministicGeneratorCountsFromOneInTheUuidV7Shape() {
        IdGenerator generator = IdGenerators.deterministic();

        assertThat(draw(generator, 3)).containsExactly(
                "00000000-0000-7000-8000-000000000001",
                "00000000-0000-7000-8000-000000000002",
                "00000000-0000-7000-8000-000000000003");
    }

    @Test
    void deterministicIdsHaveTimestampZeroVersion7AndVariant10() {
        for (String id : draw(IdGenerators.deterministic(), 100)) {
            assertThat(id).matches(UUID_V7);
            Fields fields = Fields.of(id);
            assertThat(fields.timestamp()).isZero();
            assertThat(fields.version()).isEqualTo(7);
            assertThat(fields.variant()).isEqualTo(2);
        }
    }

    @Test
    void deterministicIdsAscendAndShowTheCounterInHex() {
        List<String> ids = draw(IdGenerators.deterministic(), 300);

        assertStrictlyAscending(ids);
        assertThat(ids.get(9)).endsWith("00000000000a");
        assertThat(ids.get(299)).endsWith("00000000012c");
    }

    @Test
    void twoDeterministicGeneratorsGiveTheSameIds() {
        assertThat(draw(IdGenerators.deterministic(), 100)).isEqualTo(draw(IdGenerators.deterministic(), 100));
    }

    // ------------------------------------------------------------------ accepts

    @Test
    void bothGeneratorsAcceptTheirOwnIds() {
        IdGenerator uuid = IdGenerators.uuidV7(new MillisClock(NOON), new Random(1));
        IdGenerator deterministic = IdGenerators.deterministic();

        for (IdGenerator generator : List.of(uuid, deterministic)) {
            for (String id : draw(generator, 50)) {
                assertThat(generator.accepts(id)).as(id).isTrue();
            }
        }
        assertThat(uuid.accepts(deterministic.next())).isTrue();
        assertThat(deterministic.accepts(uuid.next())).isTrue();
    }

    @Test
    void acceptsChecksTheTextFormIncludingVersionAndVariant() {
        IdGenerator generator = IdGenerators.deterministic();

        assertThat(generator.accepts("0199c1d2-3e4f-7a5b-8c6d-7e8f9a0b1c2d")).isTrue();
        assertThat(generator.accepts("0199c1d2-3e4f-7a5b-bc6d-7e8f9a0b1c2d")).isTrue();
        assertThat(generator.accepts("0199c1d2-3e4f-7a5b-9c6d-7e8f9a0b1c2d")).isTrue();
        assertThat(generator.accepts("0199c1d2-3e4f-7a5b-ac6d-7e8f9a0b1c2d")).isTrue();

        assertThat(generator.accepts("0199C1D2-3E4F-7A5B-8C6D-7E8F9A0B1C2D")).as("uppercase").isFalse();
        assertThat(generator.accepts("0199c1d23e4f7a5b8c6d7e8f9a0b1c2d")).as("no hyphens").isFalse();
        assertThat(generator.accepts("{0199c1d2-3e4f-7a5b-8c6d-7e8f9a0b1c2d}")).as("braces").isFalse();
        assertThat(generator.accepts(" 0199c1d2-3e4f-7a5b-8c6d-7e8f9a0b1c2d")).as("space").isFalse();
        assertThat(generator.accepts("0199c1d2-3e4f-7a5b-8c6d-7e8f9a0b1c2")).as("too short").isFalse();
        assertThat(generator.accepts("0199c1d2-3e4f-7a5b-8c6d-7e8f9a0b1c2de")).as("too long").isFalse();
        assertThat(generator.accepts("0199c1d2-3e4f-4a5b-8c6d-7e8f9a0b1c2d")).as("version 4").isFalse();
        assertThat(generator.accepts("0199c1d2-3e4f-8a5b-8c6d-7e8f9a0b1c2d")).as("version 8").isFalse();
        assertThat(generator.accepts("0199c1d2-3e4f-7a5b-cc6d-7e8f9a0b1c2d")).as("variant 11").isFalse();
        assertThat(generator.accepts("0199c1d2-3e4f-7a5b-0c6d-7e8f9a0b1c2d")).as("variant 00").isFalse();
        assertThat(generator.accepts("0199c1d2-3e4f-7a5b-8c6d-7e8f9a0b1c2g")).as("not hex").isFalse();
        assertThat(generator.accepts("0199c1d2_3e4f_7a5b_8c6d_7e8f9a0b1c2d")).as("other separators").isFalse();
        assertThat(generator.accepts("0199c1d23-e4f-7a5b-8c6d-7e8f9a0b1c2d")).as("hyphens in the wrong place")
                .isFalse();
        assertThat(generator.accepts("00000000-0000-0000-0000-000000000000")).as("nil UUID").isFalse();
        assertThat(generator.accepts("acme")).isFalse();
        assertThat(generator.accepts("")).isFalse();
        assertThat(generator.accepts(null)).isFalse();
    }

    // ------------------------------------------------------------------ copy

    @Test
    void drawingFromACopyOfTheDeterministicGeneratorReservesNothing() {
        IdGenerator generator = IdGenerators.deterministic();
        generator.next();

        IdGenerator copy = generator.copy();
        List<String> drawnFromCopy = draw(copy, 5);

        assertThat(generator.next()).isEqualTo("00000000-0000-7000-8000-000000000002");
        assertThat(drawnFromCopy.getFirst()).isEqualTo("00000000-0000-7000-8000-000000000002");
        assertThat(drawnFromCopy.getLast()).isEqualTo("00000000-0000-7000-8000-000000000006");
    }

    @Test
    void aCopyOfTheDeterministicGeneratorContinuesWhereTheOriginalWas() {
        IdGenerator generator = IdGenerators.deterministic();
        draw(generator, 3);

        IdGenerator copy = generator.copy();

        assertThat(draw(copy, 4)).isEqualTo(draw(generator, 4));
    }

    @Test
    void drawingFromACopyOfTheUuidV7GeneratorDoesNotAdvanceTheOriginal() {
        IdGenerator generator = IdGenerators.uuidV7(new MillisClock(NOON), new Random(1));
        String first = generator.next();

        IdGenerator copy = generator.copy();
        List<String> drawnFromCopy = draw(copy, 10);
        String second = generator.next();

        assertThat(Fields.of(second).timestamp()).isEqualTo(NOON);
        assertThat(Fields.of(second).counter()).isEqualTo(Fields.of(first).counter() + 1);
        assertThat(Fields.of(drawnFromCopy.getFirst()).counter()).isEqualTo(Fields.of(second).counter());
        assertThat(Fields.of(drawnFromCopy.getLast()).counter()).isEqualTo(Fields.of(first).counter() + 10);
    }

    @Test
    void aCopyOfTheUuidV7GeneratorKeepsTheOrderOfTheOriginal() {
        IdGenerator generator = IdGenerators.uuidV7(new MillisClock(NOON), new Random(1));
        String last = draw(generator, 10).getLast();

        IdGenerator copy = generator.copy();

        assertThat(copy.next()).isGreaterThan(last);
        assertThat(copy.accepts(last)).isTrue();
    }

    @Test
    void drawnIdsAreDistinct() {
        Set<String> ids = new HashSet<>(draw(IdGenerators.uuidV7(), 5_000));

        assertThat(ids).hasSize(5_000);
    }
}
