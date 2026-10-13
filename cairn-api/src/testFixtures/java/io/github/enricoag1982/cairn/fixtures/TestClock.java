package io.github.enricoag1982.cairn.fixtures;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/**
 * A clock for tests that only moves when told to: {@link #advance} moves it forward by a duration, and
 * {@link #moveTo} to an instant, also an earlier one. It starts at {@link #START}.
 */
public final class TestClock implements InstantSource {

    /** Where a new clock stands: 5 January 2026, in the morning, with nanoseconds, so that truncation shows. */
    public static final Instant START = Instant.parse("2026-01-05T09:00:00.123456789Z");

    private Instant now = START;

    @Override
    public Instant instant() {
        return now;
    }

    /**
     * Moves the clock forward.
     *
     * @param duration how far to move it; a negative duration moves it back
     */
    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    /**
     * Moves the clock to an instant.
     *
     * @param instant the instant the clock reads from now on
     */
    public void moveTo(Instant instant) {
        now = instant;
    }
}
