package io.github.enricoag1982.sysrec.core;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/** A clock for tests that only moves when told to. */
final class TestClock implements InstantSource {

    static final Instant START = Instant.parse("2026-01-05T09:00:00.123456789Z");

    private Instant now = START;

    @Override
    public Instant instant() {
        return now;
    }

    void advance(Duration duration) {
        now = now.plus(duration);
    }
}
