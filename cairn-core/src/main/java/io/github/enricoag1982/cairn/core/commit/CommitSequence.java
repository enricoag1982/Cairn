package io.github.enricoag1982.cairn.core.commit;

import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
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
 * <p>While the writer {@linkplain #whileRunningRules(Supplier) runs rules}, the sequence refuses all writers: a rule
 * must not write, and the writer lock is reentrant, so without this a rule that holds the store could commit inside a
 * commit. The sequence {@linkplain #refusedWrites() counts} the writes it refuses, so that the writer can tell that a
 * rule tried to write even if the rule caught the exception and returned.
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
    /** Tells whether the writer is running rules; guarded by the writer lock. */
    private boolean runningRules;
    /** The number of writes refused while rules ran; guarded by the writer lock, as only the lock's holder is refused. */
    private long refusedWrites;
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
     * Runs an action while holding the writer lock, which serializes all commits of the store. The lock is reentrant,
     * except while the holder {@linkplain #whileRunningRules(Supplier) runs rules}: then taking it again is refused.
     *
     * @param action what to do; commits go here
     * @param <T>    the type of the result
     * @return what the action returns
     * @throws IllegalStateException if the calling thread is running rules, that is, if a rule tries to write
     */
    public <T> T underWriterLock(Supplier<? extends T> action) {
        writerLock.lock();
        try {
            if (runningRules) {
                refusedWrites++;
                throw new IllegalStateException("The store is running rules, and a rule must not write: commits, dry "
                        + "runs and schema registrations are refused until the rules have returned.");
            }
            return action.get();
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * Refuses the calling thread, without taking any lock, if it holds the writer lock: it is committing, registering a
     * schema, or running rules. A caller that has to take a lock of its own before the writer lock, in an order that
     * others keep, calls it first, because the thread that holds the writer lock must not take that lock afterwards. A
     * refusal while the thread runs rules is {@linkplain #refusedWrites() counted}, like that of
     * {@link #underWriterLock(Supplier)}.
     *
     * @throws IllegalStateException if the calling thread holds the writer lock
     */
    public void refuseWriterLockHolder() {
        if (writerLock.isHeldByCurrentThread()) {
            if (runningRules) {
                refusedWrites++;
                throw new IllegalStateException("The store is running rules, and a rule must not write: commits, dry "
                        + "runs, schema registrations and purges are refused until the rules have returned.");
            }
            throw new IllegalStateException("The calling thread holds the writer lock of the store, because it is "
                    + "committing or registering a schema, so it cannot start a purge.");
        }
    }

    /**
     * Runs an action in which the writer runs rules. While it runs, {@link #underWriterLock(Supplier)} throws an
     * {@code IllegalStateException} in the writer thread, so that a commit, a dry run or a schema registration that a
     * rule starts fails. Other threads wait for the writer lock as before. Reads are not affected: they take no lock.
     * The refusal ends when the action returns or throws. Each refusal is {@linkplain #refusedWrites() counted}, whether
     * or not the rule that caused it catches the exception.
     *
     * @param action what to do while the rules run
     * @param <T>    the type of the result
     * @return what the action returns
     * @throws IllegalStateException if the caller does not hold the writer lock
     */
    public <T> T whileRunningRules(Supplier<? extends T> action) {
        requireWriterLock();
        boolean before = runningRules;
        runningRules = true;
        try {
            return action.get();
        } finally {
            runningRules = before;
        }
    }

    /**
     * Returns the number of writes that {@link #underWriterLock(Supplier)} refused because rules were running, since the
     * sequence was created. Only the thread that holds the writer lock can be refused, so a writer that reads the count
     * before and after a rule runs, and finds that it grew, knows that the rule tried to write, whatever the rule did
     * with the exception.
     *
     * @return the number of refused writes; it never decreases
     * @throws IllegalStateException if the caller does not hold the writer lock
     */
    public long refusedWrites() {
        requireWriterLock();
        return refusedWrites;
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
     * Makes a sequence that recovery rebuilt continue after the latest commit that was recovered: the next basis is the
     * one above it, and the next transaction time is later than the one given, even if the clock went back since. It
     * publishes nothing. Call it once, on a sequence that has published nothing, before the store is used.
     *
     * @param latest              the basis of the latest recovered commit
     * @param lastTransactionTime the transaction time of that commit; empty if there is none
     * @throws IllegalStateException    if the caller does not hold the writer lock, or if the sequence has published a
     *                                  commit already
     * @throws IllegalArgumentException if the basis is negative
     */
    public void resume(Basis latest, Optional<Instant> lastTransactionTime) {
        requireWriterLock();
        Objects.requireNonNull(latest, "latest");
        Objects.requireNonNull(lastTransactionTime, "lastTransactionTime");
        if (this.latest.value() != Basis.NONE.value() || lastTime != null) {
            throw new IllegalStateException("The sequence has published commits already: basis " + this.latest.value()
                    + ".");
        }
        if (latest.value() < 0) {
            throw new IllegalArgumentException("A basis is not negative: " + latest.value() + ".");
        }
        this.lastTime = lastTransactionTime.orElse(null);
        this.latest = latest;
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
