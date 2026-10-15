package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * What a {@link View} is bound to: an effective time, and the point in transaction time to read as of (ADR-0008,
 * capability {@code views}).
 *
 * <p>Start with {@link #effectiveAt(Instant)}, which reads as of the latest basis, and add {@code knownAt} to read as
 * of an earlier basis or transaction time:
 *
 * <pre>{@code
 * Coordinates.effectiveAt(march31)                       // known now
 * Coordinates.effectiveAt(march31).knownAt(april5)        // known at a transaction time
 * Coordinates.effectiveAt(march31).knownAt(basisOfRd5)    // known at a basis
 * }</pre>
 *
 * @param effectiveAt the effective time; truncated to microseconds
 * @param knownAt     the point in transaction time to read as of
 */
public record Coordinates(Instant effectiveAt, KnownAt knownAt) {

    /**
     * Rejects null components and truncates the effective time to microseconds.
     */
    public Coordinates {
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        Objects.requireNonNull(knownAt, "knownAt");
        effectiveAt = effectiveAt.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Creates coordinates at an effective time, read as of the latest basis.
     *
     * @param effectiveAt the effective time
     * @return the coordinates
     */
    public static Coordinates effectiveAt(Instant effectiveAt) {
        return new Coordinates(effectiveAt, KnownAt.latest());
    }

    /**
     * Returns these coordinates read as of another point in transaction time.
     *
     * @param knownAt the point
     * @return the new coordinates
     */
    public Coordinates knownAt(KnownAt knownAt) {
        return new Coordinates(effectiveAt, knownAt);
    }

    /**
     * Returns these coordinates read as of a basis.
     *
     * @param basis the basis
     * @return the new coordinates
     */
    public Coordinates knownAt(Basis basis) {
        return knownAt(KnownAt.basis(basis));
    }

    /**
     * Returns these coordinates read as of a transaction time: the latest transaction recorded at or before it.
     *
     * @param transactionTime the transaction time
     * @return the new coordinates
     */
    public Coordinates knownAt(Instant transactionTime) {
        return knownAt(KnownAt.transactionTime(transactionTime));
    }
}
