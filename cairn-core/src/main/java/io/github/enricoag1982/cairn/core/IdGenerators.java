package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.IdGenerator;
import java.security.SecureRandom;
import java.time.InstantSource;
import java.util.HexFormat;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Creates the ID generators that the engine provides (ADR-0019, capability {@code transactions}). Both produce
 * UUIDv7-shaped IDs as defined by RFC 9562, in lowercase hyphenated text such as
 * {@code 0199c1d2-3e4f-7a5b-8c6d-7e8f9a0b1c2d}, and accept exactly that form: 36 characters, version 7, variant
 * {@code 10}.
 *
 * <p>The layout is 48 bits of Unix time in milliseconds, the version nibble {@code 7}, a 12-bit {@code rand_a}, the
 * variant bits {@code 10} and 62 bits of {@code rand_b}. The text form of such IDs sorts in the order of their bits.
 *
 * <p>A generator is {@linkplain IdGenerator#copy() copied} to draw IDs without reserving them. Both generators here
 * copy their state exactly; the UUIDv7 generator shares its random number generator with its copies.
 */
public final class IdGenerators {

    private static final long VERSION_7 = 0x7000L;
    private static final long VARIANT = 0x8000_0000_0000_0000L;
    private static final long RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
    private static final long TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL;
    /** The counter in {@code rand_a} has 12 bits. */
    private static final int COUNTER_LIMIT = 1 << 12;
    /** A new counter starts in the lower half, which leaves room to count within a millisecond. */
    private static final int COUNTER_START_LIMIT = COUNTER_LIMIT / 2;
    private static final HexFormat HEX = HexFormat.of();
    private static final int TEXT_LENGTH = 36;

    private IdGenerators() {
    }

    /**
     * Creates a UUIDv7 generator that reads the system clock and draws random bits from a {@link SecureRandom}. This is
     * the default generator of a store.
     *
     * @return the generator
     */
    public static IdGenerator uuidV7() {
        return uuidV7(InstantSource.system(), new SecureRandom());
    }

    /**
     * Creates a UUIDv7 generator.
     *
     * <p>IDs are ordered: every ID is greater than the one before, also within one millisecond and also if the clock
     * stands still or goes back. {@code rand_a} is a counter for this: it starts at a random value in the lower half of
     * its range whenever the clock has moved to a new millisecond, and counts up by one for every further ID. If the
     * counter would overflow, the timestamp is advanced by one millisecond instead, so the order holds however
     * quickly IDs are drawn. The remaining 62 bits are random.
     *
     * <p>The random number generator is shared with every {@linkplain IdGenerator#copy() copy} of the generator, so
     * IDs that a dry run draws use up random numbers: with a seeded random number generator, a dry run changes the
     * random bits of the IDs that follow, though not their order.
     *
     * @param clock  the source of the timestamp
     * @param random the source of the random bits; a cryptographically secure one is advisable
     * @return the generator
     */
    public static IdGenerator uuidV7(InstantSource clock, RandomGenerator random) {
        return new UuidV7(Objects.requireNonNull(clock, "clock"), Objects.requireNonNull(random, "random"), -1, 0);
    }

    /**
     * Creates a deterministic generator, for tests and repeatable imports: the same sequence of calls gives the same
     * IDs, in every run and in every store.
     *
     * <p>The IDs have the UUIDv7 shape with timestamp 0, {@code rand_a} 0, and a counter that starts at 1 in
     * {@code rand_b}, so the first IDs are {@code 00000000-0000-7000-8000-000000000001},
     * {@code 00000000-0000-7000-8000-000000000002} and so on.
     *
     * @return the generator
     */
    public static IdGenerator deterministic() {
        return new Deterministic(0);
    }

    /** Builds the text form from the two halves of the 128 bits. */
    private static EntityId id(long mostSignificantBits, long leastSignificantBits) {
        String high = HEX.toHexDigits(mostSignificantBits);
        String low = HEX.toHexDigits(leastSignificantBits);
        return new EntityId(high.substring(0, 8) + '-' + high.substring(8, 12) + '-' + high.substring(12) + '-'
                + low.substring(0, 4) + '-' + low.substring(4));
    }

    /** Tells whether a text is a lowercase, hyphenated UUID of version 7 and variant {@code 10}. */
    private static boolean isUuidV7(String text) {
        if (text == null || text.length() != TEXT_LENGTH) {
            return false;
        }
        for (int i = 0; i < TEXT_LENGTH; i++) {
            char c = text.charAt(i);
            boolean dash = i == 8 || i == 13 || i == 18 || i == 23;
            if (dash ? c != '-' : !isLowercaseHex(c)) {
                return false;
            }
        }
        return text.charAt(14) == '7' && "89ab".indexOf(text.charAt(19)) >= 0;
    }

    private static boolean isLowercaseHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    /** The generator behind {@link #uuidV7(InstantSource, RandomGenerator)}. */
    private static final class UuidV7 implements IdGenerator {

        private final InstantSource clock;
        private final RandomGenerator random;
        /** The timestamp of the last ID, or -1 before the first. */
        private long millis;
        private int counter;

        UuidV7(InstantSource clock, RandomGenerator random, long millis, int counter) {
            this.clock = clock;
            this.random = random;
            this.millis = millis;
            this.counter = counter;
        }

        @Override
        public synchronized EntityId next() {
            long now = Math.max(0, clock.millis());
            if (now > millis) {
                millis = now;
                counter = random.nextInt(COUNTER_START_LIMIT);
            } else {
                // The clock stands still or went back: keep the last timestamp and count on.
                counter++;
                if (counter == COUNTER_LIMIT) {
                    millis++;
                    counter = random.nextInt(COUNTER_START_LIMIT);
                }
            }
            long high = ((millis & TIMESTAMP_MASK) << 16) | VERSION_7 | counter;
            long low = VARIANT | (random.nextLong() & RAND_B_MASK);
            return id(high, low);
        }

        @Override
        public boolean accepts(String text) {
            return isUuidV7(text);
        }

        @Override
        public synchronized IdGenerator copy() {
            return new UuidV7(clock, random, millis, counter);
        }
    }

    /** The generator behind {@link #deterministic()}. */
    private static final class Deterministic implements IdGenerator {

        private long count;

        Deterministic(long count) {
            this.count = count;
        }

        @Override
        public synchronized EntityId next() {
            if (count == RAND_B_MASK) {
                throw new IllegalStateException("The deterministic generator has no IDs left.");
            }
            count++;
            return id(VERSION_7, VARIANT | count);
        }

        @Override
        public boolean accepts(String text) {
            return isUuidV7(text);
        }

        @Override
        public synchronized IdGenerator copy() {
            return new Deterministic(count);
        }
    }
}
