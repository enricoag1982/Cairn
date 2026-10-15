package io.github.enricoag1982.cairn.data;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * A half-open period in effective time: from an instant, including it, until a later instant, excluding it, or until
 * further notice (ADR-0008, capability {@code entity-versions}).
 *
 * <p>Instants are UTC with microsecond precision: the constructor truncates finer ones. The constructor does not
 * check that the period ends after it starts, because a period that does not is a problem that a store reports with
 * the code {@code INVALID_PERIOD} when the transaction that carries it is committed, together with every other
 * problem of the transaction. Use {@link #endsAfterStart()} to check a period yourself.
 *
 * @param from  the first instant of the period
 * @param until the first instant after the period; empty for a period without an end
 */
public record EffectivePeriod(Instant from, Optional<Instant> until) {

    /**
     * Rejects null components and truncates the instants to microseconds.
     */
    public EffectivePeriod {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(until, "until");
        from = from.truncatedTo(ChronoUnit.MICROS);
        until = until.map(end -> end.truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * Creates a period without an end: from an instant until further notice.
     *
     * @param from the first instant of the period
     * @return the period
     */
    public static EffectivePeriod from(Instant from) {
        return new EffectivePeriod(from, Optional.empty());
    }

    /**
     * Creates a period with an end. The period is invalid if {@code until} is not after {@code from}; see the class
     * description.
     *
     * @param from  the first instant of the period
     * @param until the first instant after the period
     * @return the period
     */
    public static EffectivePeriod between(Instant from, Instant until) {
        return new EffectivePeriod(from, Optional.of(Objects.requireNonNull(until, "until")));
    }

    /**
     * Tells whether the period is valid: it has no end, or it ends after it starts.
     *
     * @return {@code true} if the period is valid
     */
    public boolean endsAfterStart() {
        return until.isEmpty() || until.get().isAfter(from);
    }

    /**
     * Tells whether an instant lies in the period.
     *
     * @param instant the instant
     * @return {@code true} if the instant is not before the start and, if the period has an end, before it
     */
    public boolean contains(Instant instant) {
        return !instant.isBefore(from) && (until.isEmpty() || instant.isBefore(until.get()));
    }
}
