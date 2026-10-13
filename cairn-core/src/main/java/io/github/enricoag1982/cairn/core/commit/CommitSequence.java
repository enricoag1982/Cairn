package io.github.enricoag1982.cairn.core.commit;

import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The sequence of bases and transaction times of one store, and the writer lock that serializes its commits (design D3,
 * ADR-0007, ADR-0008; capabilities {@code transactions} and {@code schema-evolution}).
 *
 * <p>Schema transactions and data transactions of a store take their basis and transaction time from the same
 * sequence, so a basis identifies one snapshot of both. A stand-alone schema registry has a sequence of its own.
 *
 * <p>A writer commits like this, all inside {@link #underWriterLock(Supplier)}:
 *
 * <ol>
 *   <li>validate, without touching the sequence (this is all that a dry run does);
 *   <li>ask for the {@linkplain #next() next} basis and transaction time;
 *   <li>put the commit's data in place;
 *   <li>{@linkplain #publish(Commit) publish} the commit, which advances the sequence and makes the basis visible to
 *       {@link #latestBasis()}.
 * </ol>
 *
 * <p>Nothing advances before the data is in place, so a commit that fails half way burns no basis, and a reader that
 * learns of a basis through {@link #latestBasis()} finds all of its data.
 *
 * <p>Transaction times have microsecond precision and strictly increase, even if the clock stands still or goes back.
 */
public final class CommitSequence {

    /**
     * The basis and transaction time of a commit that is about to be recorded.
     *
     * @param basis           the basis the commit produces
     * @param transactionTime the transaction time of the commit, with microsecond precision
     */
    public record Commit(Basis basis, Instant transactionTime) {
        /**
         * Rejects null components.
         */
        public Commit {
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(transactionTime, "transactionTime");
        }
    }

    private final InstantSource clock;
    private final ReentrantLock writerLock = new ReentrantLock();
    /** The transaction time of the latest published commit; guarded by the writer lock. */
    private Instant lastTime;
    private volatile Basis latest = Basis.NONE;

    /**
     * Creates the sequence of an empty store.
     *
     * @param clock the source of transaction times
     */
    public CommitSequence(InstantSource clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs an action while holding the writer lock, which serializes all commits of the store. The lock is reentrant.
     *
     * @param action what to do; commits go here
     * @param <T>    the type of the result
     * @return what the action returns
     */
    public <T> T underWriterLock(Supplier<? extends T> action) {
        writerLock.lock();
        try {
            return action.get();
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * Returns the basis and transaction time that the next commit gets. The sequence does not advance until the commit
     * is {@linkplain #publish(Commit) published}, so asking twice gives the same basis, and a transaction time that
     * can only be later.
     *
     * @return the basis, one above the latest published basis, and the transaction time: the clock's time truncated to
     *         microseconds, or one microsecond after the latest published transaction time if the clock is not past it
     * @throws IllegalStateException if the caller does not hold the writer lock
     */
    public Commit next() {
        requireWriterLock();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant time = lastTime != null && !now.isAfter(lastTime) ? lastTime.plus(1, ChronoUnit.MICROS) : now;
        return new Commit(new Basis(latest.value() + 1), time);
    }

    /**
     * Publishes a commit whose data is in place: the sequence advances, and {@link #latestBasis()} returns the commit's
     * basis from now on.
     *
     * @param commit the commit that {@link #next()} returned
     * @throws IllegalStateException    if the caller does not hold the writer lock
     * @throws IllegalArgumentException if the commit is not the next one in the sequence
     */
    public void publish(Commit commit) {
        requireWriterLock();
        Objects.requireNonNull(commit, "commit");
        boolean wrongBasis = commit.basis().value() != latest.value() + 1;
        boolean notAfterLast = lastTime != null && !commit.transactionTime().isAfter(lastTime);
        if (wrongBasis || notAfterLast) {
            throw new IllegalArgumentException("The commit " + commit + " does not follow basis " + latest.value()
                    + (lastTime == null ? "" : " recorded at " + lastTime) + ".");
        }
        lastTime = commit.transactionTime();
        latest = commit.basis();
    }

    /**
     * The basis of the latest published commit. Readers may call this without the lock.
     *
     * @return the basis; {@link Basis#NONE} before the first commit
     */
    public Basis latestBasis() {
        return latest;
    }

    private void requireWriterLock() {
        if (!writerLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("The writer lock is not held.");
        }
    }
}
