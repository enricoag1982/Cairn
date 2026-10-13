package io.github.enricoag1982.cairn.core.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Design D3: one sequence of bases and transaction times, and one writer lock. */
class CommitSequenceTest {

    private static final Instant NOW = Instant.parse("2026-03-16T10:15:30.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-03-16T10:15:30.123456Z");

    /** A clock that stands where it is told to. */
    private static final class SettableClock implements InstantSource {
        private volatile Instant now;

        SettableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Hands out and publishes one commit under the lock. */
    private static CommitSequence.Commit commit(CommitSequence sequence) {
        return sequence.underWriterLock(() -> {
            CommitSequence.Commit commit = sequence.next();
            sequence.publish(commit);
            return commit;
        });
    }

    @Test
    void theFirstCommitGetsBasisOneAndTheClocksTimeTruncatedToMicroseconds() {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(NOW));

        CommitSequence.Commit commit = commit(sequence);

        assertThat(commit.basis()).isEqualTo(new Basis(1));
        assertThat(commit.transactionTime()).isEqualTo(NOW_MICROS);
    }

    @Test
    void basesCountUpByOne() {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(NOW));

        List<Basis> bases = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            bases.add(commit(sequence).basis());
        }

        assertThat(bases).containsExactly(new Basis(1), new Basis(2), new Basis(3), new Basis(4), new Basis(5));
        assertThat(sequence.latestBasis()).isEqualTo(new Basis(5));
    }

    @Test
    void transactionTimesStrictlyIncreaseWithAFrozenClock() {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(NOW));

        Instant first = commit(sequence).transactionTime();
        Instant second = commit(sequence).transactionTime();
        Instant third = commit(sequence).transactionTime();

        assertThat(first).isEqualTo(NOW_MICROS);
        assertThat(second).isEqualTo(first.plus(1, ChronoUnit.MICROS));
        assertThat(third).isEqualTo(second.plus(1, ChronoUnit.MICROS));
    }

    @Test
    void transactionTimesStrictlyIncreaseWhenTheClockGoesBack() {
        SettableClock clock = new SettableClock(NOW);
        CommitSequence sequence = new CommitSequence(clock);
        Instant first = commit(sequence).transactionTime();

        clock.now = NOW.minusSeconds(3600);
        Instant second = commit(sequence).transactionTime();

        assertThat(second).isEqualTo(first.plus(1, ChronoUnit.MICROS));
    }

    @Test
    void aLaterClockTimeIsUsedAsItIs() {
        SettableClock clock = new SettableClock(NOW);
        CommitSequence sequence = new CommitSequence(clock);
        commit(sequence);

        clock.now = NOW.plusSeconds(60);

        assertThat(commit(sequence).transactionTime()).isEqualTo(NOW_MICROS.plusSeconds(60));
    }

    @Test
    void nothingAdvancesUntilTheCommitIsPublished() {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(NOW));

        sequence.underWriterLock(() -> {
            CommitSequence.Commit first = sequence.next();
            CommitSequence.Commit again = sequence.next();

            assertThat(again).isEqualTo(first);
            assertThat(sequence.latestBasis()).isEqualTo(Basis.NONE);
            return null;
        });
        assertThat(sequence.latestBasis()).isEqualTo(Basis.NONE);
        assertThat(commit(sequence).basis()).as("a commit that was not published burned no basis")
                .isEqualTo(new Basis(1));
    }

    @Test
    void theLatestBasisIsPublishedWithTheCommit() {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(NOW));
        assertThat(sequence.latestBasis()).isEqualTo(Basis.NONE);

        sequence.underWriterLock(() -> {
            CommitSequence.Commit commit = sequence.next();
            assertThat(sequence.latestBasis()).isEqualTo(Basis.NONE);
            sequence.publish(commit);
            assertThat(sequence.latestBasis()).isEqualTo(commit.basis());
            return null;
        });
    }

    @Test
    void nextAndPublishNeedTheWriterLock() {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(NOW));
        CommitSequence.Commit commit = new CommitSequence.Commit(new Basis(1), NOW_MICROS);

        assertThatThrownBy(sequence::next).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sequence.publish(commit)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCommitThatDoesNotFollowTheSequenceIsNotPublished() {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(NOW));
        CommitSequence.Commit first = commit(sequence);

        sequence.underWriterLock(() -> {
            assertThatThrownBy(() -> sequence.publish(first)).as("published twice")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> sequence.publish(new CommitSequence.Commit(new Basis(3), NOW.plusSeconds(1))))
                    .as("skips a basis").isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> sequence.publish(new CommitSequence.Commit(new Basis(2), first.transactionTime())))
                    .as("time does not increase").isInstanceOf(IllegalArgumentException.class);
            return null;
        });
        assertThat(sequence.latestBasis()).isEqualTo(new Basis(1));
    }

    @Test
    void theWriterLockIsReentrantReturnsTheActionsResultAndIsReleasedAfterAFailure() {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(NOW));

        String result = sequence.underWriterLock(() -> sequence.underWriterLock(() -> "inner"));
        assertThat(result).isEqualTo("inner");

        assertThatThrownBy(() -> sequence.underWriterLock(() -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        assertThatThrownBy(sequence::next).as("the lock was released").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theWriterLockExcludesOtherThreads() throws InterruptedException {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(NOW));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> sequence.underWriterLock(() -> {
            holding.countDown();
            await(release);
            return null;
        }));
        holder.start();
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

        Thread other = new Thread(() -> commit(sequence));
        other.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (other.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(other.getState()).as("waits for the lock").isEqualTo(Thread.State.WAITING);
        assertThat(sequence.latestBasis()).isEqualTo(Basis.NONE);

        release.countDown();
        holder.join(10_000);
        other.join(10_000);
        assertThat(sequence.latestBasis()).isEqualTo(new Basis(1));
    }

    @Test
    void whileRulesRunTheWriterThreadCannotTakeTheLockAgain() {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(NOW));

        String result = sequence.underWriterLock(() -> sequence.whileRunningRules(() -> {
            assertThatThrownBy(() -> commit(sequence)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("running rules");
            assertThatThrownBy(() -> sequence.underWriterLock(() -> "inner"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(sequence.latestBasis()).as("reads are not refused").isEqualTo(Basis.NONE);
            return "rules done";
        }));

        assertThat(result).isEqualTo("rules done");
        assertThat(sequence.latestBasis()).isEqualTo(Basis.NONE);
    }

    @Test
    void theRefusalEndsWhenTheRulesReturnOrFail() {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(NOW));

        sequence.underWriterLock(() -> {
            sequence.whileRunningRules(() -> "returned");
            assertThat(sequence.underWriterLock(() -> "inner")).as("after a return").isEqualTo("inner");
            assertThatThrownBy(() -> sequence.whileRunningRules(() -> {
                throw new AssertionError("a rule failed");
            })).isInstanceOf(AssertionError.class);
            assertThat(sequence.underWriterLock(() -> "inner")).as("after a failure").isEqualTo("inner");
            return null;
        });
        assertThat(commit(sequence).basis()).isEqualTo(new Basis(1));
    }

    @Test
    void everyWriteRefusedWhileRulesRunIsCountedEvenIfTheCallerCatchesTheRefusal() {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(NOW));
        assertThatThrownBy(sequence::refusedWrites).isInstanceOf(IllegalStateException.class);

        sequence.underWriterLock(() -> {
            assertThat(sequence.refusedWrites()).as("nothing refused yet").isZero();
            sequence.whileRunningRules(() -> {
                long before = sequence.refusedWrites();
                assertThatThrownBy(() -> sequence.underWriterLock(() -> "inner"))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(sequence.refusedWrites()).as("one refusal").isEqualTo(before + 1);
                assertThatThrownBy(() -> commit(sequence)).isInstanceOf(IllegalStateException.class);
                assertThat(sequence.refusedWrites()).as("two refusals").isEqualTo(before + 2);
                return null;
            });
            assertThat(sequence.underWriterLock(() -> "inner")).as("allowed again").isEqualTo("inner");
            assertThat(sequence.refusedWrites()).as("an allowed write is not counted").isEqualTo(2);
            return null;
        });
    }

    @Test
    void aCallerThatMustTakeAnotherLockFirstRefusesTheThreadThatHoldsTheWriterLockWithoutTakingAnyLock() {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(NOW));

        sequence.refuseWriterLockHolder();

        sequence.underWriterLock(() -> {
            assertThatThrownBy(sequence::refuseWriterLockHolder).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("writer lock");
            assertThat(sequence.refusedWrites()).as("a thread that only commits is not counted as a rule").isZero();
            sequence.whileRunningRules(() -> {
                assertThatThrownBy(sequence::refuseWriterLockHolder).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("running rules");
                assertThat(sequence.refusedWrites()).as("a refusal while rules run is counted").isEqualTo(1);
                return null;
            });
            return null;
        });
        sequence.refuseWriterLockHolder();
    }

    @Test
    void runningRulesNeedsTheWriterLockAndOnlyRefusesTheThreadThatHoldsIt() throws InterruptedException {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(NOW));
        assertThatThrownBy(() -> sequence.whileRunningRules(() -> "unlocked"))
                .isInstanceOf(IllegalStateException.class);

        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> sequence.underWriterLock(() -> sequence.whileRunningRules(() -> {
            running.countDown();
            await(release);
            return null;
        })));
        holder.start();
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
        Thread other = new Thread(() -> commit(sequence));
        other.start();
        release.countDown();
        holder.join(10_000);
        other.join(10_000);

        assertThat(sequence.latestBasis()).as("the other writer waited, and then committed").isEqualTo(new Basis(1));
    }

    @Test
    void concurrentWritersGetDistinctBasesWithoutGapsAndIncreasingTimes() throws InterruptedException {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(NOW));
        int threads = 8;
        int commitsPerThread = 250;
        List<CommitSequence.Commit> commits = Collections.synchronizedList(new ArrayList<>());
        List<Thread> writers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread writer = new Thread(() -> {
                for (int i = 0; i < commitsPerThread; i++) {
                    commits.add(commit(sequence));
                }
            });
            writers.add(writer);
            writer.start();
        }
        for (Thread writer : writers) {
            writer.join(30_000);
        }

        List<CommitSequence.Commit> byBasis = commits.stream()
                .sorted((a, b) -> a.basis().compareTo(b.basis())).toList();
        assertThat(byBasis).hasSize(threads * commitsPerThread);
        for (int i = 0; i < byBasis.size(); i++) {
            assertThat(byBasis.get(i).basis()).isEqualTo(new Basis(i + 1));
            if (i > 0) {
                assertThat(byBasis.get(i).transactionTime()).isAfter(byBasis.get(i - 1).transactionTime());
            }
        }
        assertThat(sequence.latestBasis()).isEqualTo(new Basis(threads * commitsPerThread));
    }

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> new CommitSequence(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CommitSequence.Commit(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CommitSequence.Commit(new Basis(1), null)).isInstanceOf(NullPointerException.class);
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(NOW));
        assertThatThrownBy(() -> sequence.underWriterLock(() -> {
            sequence.publish(null);
            return null;
        })).isInstanceOf(NullPointerException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
