package io.github.enricoag1982.cairn.core.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.core.RecordingSubscriber;
import io.github.enricoag1982.cairn.data.Change;
import io.github.enricoag1982.cairn.data.PurgeChange;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * The publisher of a store's changes (ADR-0029, design D5 of {@code add-change-feed}), on a source of changes that the
 * test controls, so that what the subscriptions read, how much and when is seen: demand, chunks, waiting and waking,
 * cancelling, errors, closing, and signals that are serial. Nothing sleeps: every wait is a latch, a queue, or a thread
 * that is seen to be parked, with a timeout that only a failure reaches.
 */
class ChangeFeedTest {

    private static final long TIMEOUT = RecordingSubscriber.TIMEOUT_SECONDS;

    /** A source of changes: one change for each basis up to the latest, which the test moves, remembering every read. */
    private static final class Source implements ChangeFeed.Reader {

        final AtomicLong latest = new AtomicLong();
        final List<Integer> limits = new CopyOnWriteArrayList<>();
        final List<Long> afters = new CopyOnWriteArrayList<>();

        Source(long latest) {
            this.latest.set(latest);
        }

        @Override
        public List<Change> read(Basis after, int limit) {
            limits.add(limit);
            afters.add(after.value());
            List<Change> changes = new ArrayList<>();
            for (long basis = after.value() + 1; basis <= latest.get() && changes.size() < limit; basis++) {
                changes.add(change(basis));
            }
            return changes;
        }
    }

    private static Change change(long basis) {
        return new PurgeChange(new PurgeRecord(new Basis(basis), Instant.EPOCH.plusSeconds(basis), "dpo",
                Optional.empty(), "Erasure", 0, 0));
    }

    private static List<Long> bases(List<Change> changes) {
        return changes.stream().map(change -> change.basis().value()).toList();
    }

    /** Waits until the virtual thread of the only subscription is parked: it has nothing to do until it is woken. */
    private static Thread awaitParked(ChangeFeed feed) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT);
        while (System.nanoTime() < deadline) {
            List<Thread> threads = feed.threads();
            if (threads.size() == 1 && threads.getFirst().getState() == Thread.State.WAITING) {
                return threads.getFirst();
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("The thread of the subscription never parked: " + feed.threads());
    }

    private static void awaitEnded(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(thread.isAlive()).as("the thread of the subscription ended").isFalse();
    }

    // ------------------------------------------------------------------ demand

    @Test
    void aSubscriptionReadsInChunksOfAtMost256AndDeliversEveryChangeOnceInOrder() {
        Source source = new Source(1_000);
        ChangeFeed feed = new ChangeFeed(source);
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);

        feed.publisher(Basis.NONE).subscribe(subscriber);

        assertThat(bases(subscriber.next(1_000))).isEqualTo(LongStream.rangeClosed(1, 1_000).boxed().toList());
        awaitParked(feed);
        assertThat(source.limits).as("each read is a chunk").allMatch(limit -> limit <= ChangeFeed.CHUNK)
                .contains(ChangeFeed.CHUNK);
        assertThat(source.afters).as("each read continues after the last change delivered")
                .startsWith(0L, 256L, 512L, 768L, 1_000L);
        assertThat(subscriber.pending()).isZero();
        subscriber.subscription().cancel();
    }

    @Test
    void aSubscriptionReadsNoMoreThanTheSubscriberRequestedAndNothingWithoutDemand() {
        Source source = new Source(1_000);
        ChangeFeed feed = new ChangeFeed(source);
        RecordingSubscriber subscriber = new RecordingSubscriber(0);

        feed.publisher(new Basis(10)).subscribe(subscriber);
        assertThat(feed.threads()).as("no thread before the first request").isEmpty();
        assertThat(source.limits).isEmpty();
        subscriber.subscription().request(3);

        assertThat(bases(subscriber.next(3))).containsExactly(11L, 12L, 13L);
        awaitParked(feed);
        assertThat(source.limits).as("it read three and no more, and did not read again without demand")
                .containsExactly(3);
        subscriber.subscription().request(2);
        assertThat(bases(subscriber.next(2))).containsExactly(14L, 15L);
        awaitParked(feed);
        assertThat(source.limits).containsExactly(3, 2);
        assertThat(subscriber.pending()).isZero();
        subscriber.subscription().cancel();
    }

    @Test
    void demandThatAddsUpPastTheLargestNumberIsUnbounded() {
        Source source = new Source(5);
        ChangeFeed feed = new ChangeFeed(source);
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);
        feed.publisher(Basis.NONE).subscribe(subscriber);
        subscriber.subscription().request(Long.MAX_VALUE);
        subscriber.subscription().request(7);

        assertThat(bases(subscriber.next(5))).containsExactly(1L, 2L, 3L, 4L, 5L);
        awaitParked(feed);
        assertThat(subscriber.isFailed()).isFalse();
        subscriber.subscription().cancel();
    }

    // ------------------------------------------------------------------ waiting for commits

    @Test
    void aSubscriptionThatDeliveredEverythingWaitsForTheNextPublishedBasis() {
        Source source = new Source(0);
        ChangeFeed feed = new ChangeFeed(source);
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);
        feed.publisher(Basis.NONE).subscribe(subscriber);

        for (long basis = 1; basis <= 3; basis++) {
            awaitParked(feed);
            assertThat(subscriber.pending()).as("nothing is delivered before it is published").isZero();
            source.latest.set(basis);
            feed.published();
            assertThat(subscriber.next().basis()).isEqualTo(new Basis(basis));
        }
        subscriber.subscription().cancel();
    }

    @Test
    void aSubscriptionWithoutDemandDeliversWhatWasPublishedWhenItRequestsAgain() {
        Source source = new Source(0);
        ChangeFeed feed = new ChangeFeed(source);
        RecordingSubscriber subscriber = new RecordingSubscriber(1);
        feed.publisher(Basis.NONE).subscribe(subscriber);
        awaitParked(feed);

        source.latest.set(2);
        feed.published();
        assertThat(subscriber.next().basis()).isEqualTo(new Basis(1));
        awaitParked(feed);
        assertThat(subscriber.pending()).isZero();
        subscriber.subscription().request(1);

        assertThat(subscriber.next().basis()).isEqualTo(new Basis(2));
        subscriber.subscription().cancel();
    }

    @Test
    void everySubscriberHasACursorOfItsOwn() {
        Source source = new Source(6);
        ChangeFeed feed = new ChangeFeed(source);
        RecordingSubscriber early = new RecordingSubscriber(Long.MAX_VALUE);
        RecordingSubscriber late = new RecordingSubscriber(Long.MAX_VALUE);

        feed.publisher(Basis.NONE).subscribe(early);
        feed.publisher(new Basis(4)).subscribe(late);

        assertThat(bases(early.next(6))).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(bases(late.next(2))).containsExactly(5L, 6L);
        source.latest.set(7);
        feed.published();
        assertThat(early.next().basis()).isEqualTo(new Basis(7));
        assertThat(late.next().basis()).isEqualTo(new Basis(7));
        early.subscription().cancel();
        late.subscription().cancel();
    }

    @Test
    void aSubscriptionToABasisAfterTheLatestWaitsForIt() {
        Source source = new Source(2);
        ChangeFeed feed = new ChangeFeed(source);
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);
        feed.publisher(new Basis(5)).subscribe(subscriber);
        awaitParked(feed);
        source.latest.set(6);
        feed.published();

        assertThat(subscriber.next().basis()).isEqualTo(new Basis(6));
        subscriber.subscription().cancel();
    }

    // ------------------------------------------------------------------ the signals

    @Test
    void signalsAreSerialAndOnSubscribeIsTheFirstEvenWhenItRequests() {
        Source source = new Source(50);
        ChangeFeed feed = new ChangeFeed(source);
        AtomicBoolean subscribed = new AtomicBoolean();
        AtomicBoolean early = new AtomicBoolean();
        AtomicInteger running = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(50);
        feed.publisher(Basis.NONE).subscribe(new Flow.Subscriber<Change>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
                for (int i = 0; i < 100; i++) {
                    Thread.yield();
                }
                subscribed.set(true);
            }

            @Override
            public void onNext(Change item) {
                if (!subscribed.get()) {
                    early.set(true);
                }
                if (running.incrementAndGet() != 1) {
                    overlapped.set(true);
                }
                Thread.yield();
                running.decrementAndGet();
                done.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                overlapped.set(true);
            }

            @Override
            public void onComplete() {
                overlapped.set(true);
            }
        });
        try {
            assertThat(done.await(TIMEOUT, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(early.get()).as("no signal before onSubscribe returned").isFalse();
        assertThat(overlapped.get()).as("no two signals at once").isFalse();
        awaitParked(feed);
    }

    @Test
    void aSubscriberThatRequestsLessThanOneChangeEndsWithAnIllegalArgumentException() {
        for (long invalid : new long[] {0, -1, Long.MIN_VALUE}) {
            Source source = new Source(10);
            ChangeFeed feed = new ChangeFeed(source);
            RecordingSubscriber subscriber = new RecordingSubscriber(0);
            feed.publisher(Basis.NONE).subscribe(subscriber);
            subscriber.subscription().request(2);
            assertThat(subscriber.next(2)).hasSize(2);
            Thread thread = awaitParked(feed);

            subscriber.subscription().request(invalid);

            assertThat(subscriber.awaitError()).as("request(%d)", invalid).isInstanceOf(IllegalArgumentException.class);
            awaitEnded(thread);
            subscriber.subscription().request(5);
            assertThat(subscriber.calls()).as("nothing more after the error").isEqualTo(2);
            assertThat(subscriber.isCompleted()).isFalse();
        }
    }

    @Test
    void aFirstRequestOfLessThanOneEndsTheSubscriptionOnItsOwnThread() {
        ChangeFeed feed = new ChangeFeed(new Source(10));
        RecordingSubscriber subscriber = new RecordingSubscriber(0);
        feed.publisher(Basis.NONE).subscribe(subscriber);

        subscriber.subscription().request(0);

        assertThat(subscriber.awaitError()).isInstanceOf(IllegalArgumentException.class);
        assertThat(subscriber.calls()).isZero();
        assertThat(subscriber.signalThreads()).doesNotContain(Thread.currentThread());
    }

    @Test
    void cancellingEndsTheThreadAndNothingIsSignalledAfterwards() {
        Source source = new Source(3);
        ChangeFeed feed = new ChangeFeed(source);
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);
        feed.publisher(Basis.NONE).subscribe(subscriber);
        assertThat(subscriber.next(3)).hasSize(3);
        Thread thread = awaitParked(feed);

        subscriber.subscription().cancel();
        awaitEnded(thread);
        source.latest.set(4);
        feed.published();
        subscriber.subscription().request(1);

        assertThat(feed.threads()).isEmpty();
        assertThat(subscriber.calls()).isEqualTo(3);
        assertThat(subscriber.isCompleted()).isFalse();
        assertThat(subscriber.isFailed()).isFalse();
    }

    @Test
    void aSubscriptionThatIsCancelledBeforeItRequestedNeverStartsAThread() {
        ChangeFeed feed = new ChangeFeed(new Source(3));
        RecordingSubscriber subscriber = new RecordingSubscriber(0);
        feed.publisher(Basis.NONE).subscribe(subscriber);

        subscriber.subscription().cancel();
        subscriber.subscription().request(3);

        assertThat(feed.threads()).isEmpty();
        assertThat(subscriber.calls()).isZero();
    }

    @Test
    void aSubscriberThatCancelsInOnNextGetsNoMoreChanges() {
        ChangeFeed feed = new ChangeFeed(new Source(10));
        RecordingSubscriber[] holder = new RecordingSubscriber[1];
        holder[0] = new RecordingSubscriber(Long.MAX_VALUE, change -> holder[0].subscription().cancel());

        feed.publisher(Basis.NONE).subscribe(holder[0]);

        assertThat(holder[0].next().basis()).isEqualTo(new Basis(1));
        awaitEnded(holder[0].signalThreads().getFirst());
        assertThat(holder[0].calls()).isEqualTo(1);
        assertThat(holder[0].isCompleted()).isFalse();
        assertThat(feed.threads()).isEmpty();
    }

    @Test
    void aSubscriberThatThrowsFromOnNextIsCancelledAndItsThreadEndsWhileOthersGoOn() {
        Source source = new Source(5);
        ChangeFeed feed = new ChangeFeed(source);
        RecordingSubscriber thrower = new RecordingSubscriber(Long.MAX_VALUE, change -> {
            throw new IllegalStateException("The subscriber fails.");
        });
        RecordingSubscriber steady = new RecordingSubscriber(Long.MAX_VALUE);
        feed.publisher(Basis.NONE).subscribe(thrower);
        feed.publisher(Basis.NONE).subscribe(steady);
        assertThat(bases(steady.next(5))).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(thrower.next().basis()).isEqualTo(new Basis(1));
        Thread throwerThread = thrower.signalThreads().getFirst();
        awaitEnded(throwerThread);

        source.latest.set(6);
        feed.published();

        assertThat(steady.next().basis()).isEqualTo(new Basis(6));
        assertThat(thrower.calls()).as("the change it threw at, and nothing after").isEqualTo(1);
        assertThat(thrower.isFailed()).isFalse();
        assertThat(thrower.isCompleted()).isFalse();
        steady.subscription().cancel();
    }

    @Test
    void aReadThatFailsEndsTheSubscriptionWithTheFailure() {
        IllegalStateException failure = new IllegalStateException("The store has no record of basis 3.");
        AtomicInteger reads = new AtomicInteger();
        ChangeFeed feed = new ChangeFeed((after, limit) -> {
            if (reads.incrementAndGet() > 1) {
                throw failure;
            }
            return List.of(change(1), change(2));
        });
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);

        feed.publisher(Basis.NONE).subscribe(subscriber);

        assertThat(bases(subscriber.next(2))).containsExactly(1L, 2L);
        assertThat(subscriber.awaitError()).isSameAs(failure);
        assertThat(subscriber.isCompleted()).isFalse();
    }

    @Test
    void aSubscriberThatThrowsFromOnSubscribeIsTreatedAsCancelled() {
        Source source = new Source(5);
        ChangeFeed feed = new ChangeFeed(source);
        AtomicInteger signals = new AtomicInteger();

        feed.publisher(Basis.NONE).subscribe(new Flow.Subscriber<Change>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(5);
                throw new IllegalStateException("The subscriber fails.");
            }

            @Override
            public void onNext(Change item) {
                signals.incrementAndGet();
            }

            @Override
            public void onError(Throwable throwable) {
                signals.incrementAndGet();
            }

            @Override
            public void onComplete() {
                signals.incrementAndGet();
            }
        });

        assertThat(feed.threads()).isEmpty();
        assertThat(source.limits).isEmpty();
        assertThat(signals.get()).isZero();
    }

    @Test
    void thePublisherAndItsSubscribersAreNotNull() {
        ChangeFeed feed = new ChangeFeed(new Source(0));

        assertThatThrownBy(() -> feed.publisher(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> feed.publisher(Basis.NONE).subscribe(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChangeFeed(null)).isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------ closing

    @Test
    void closingCompletesEverySubscriptionWhetherOrNotItRequestedAndSubscriptionsAfterwardsCompleteAtOnce() {
        Source source = new Source(0);
        ChangeFeed feed = new ChangeFeed(source);
        RecordingSubscriber waiting = new RecordingSubscriber(Long.MAX_VALUE);
        RecordingSubscriber silent = new RecordingSubscriber(0);
        feed.publisher(Basis.NONE).subscribe(waiting);
        feed.publisher(Basis.NONE).subscribe(silent);
        awaitParked(feed);

        feed.close();

        waiting.awaitCompleted();
        silent.awaitCompleted();
        RecordingSubscriber later = new RecordingSubscriber(0);
        feed.publisher(Basis.NONE).subscribe(later);
        later.awaitCompleted();
        assertThat(later.signalThreads()).as("on a thread of the subscription").doesNotContain(Thread.currentThread());
        assertThat(waiting.calls() + silent.calls() + later.calls()).isZero();
        feed.close();
    }

    @Test
    void closingCompletesAfterTheOnNextThatIsRunningReturnsAndDeliversNothingMore() throws InterruptedException {
        Source source = new Source(10);
        ChangeFeed feed = new ChangeFeed(source);
        CountDownLatch inOnNext = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE, change -> {
            inOnNext.countDown();
            try {
                release.await(TIMEOUT, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        feed.publisher(Basis.NONE).subscribe(subscriber);
        assertThat(inOnNext.await(TIMEOUT, TimeUnit.SECONDS)).isTrue();

        feed.close();

        assertThat(subscriber.isCompleted()).as("not while onNext is running").isFalse();
        release.countDown();
        subscriber.awaitCompleted();
        assertThat(subscriber.calls()).as("the rest of the chunk is not delivered").isEqualTo(1);
        assertThat(subscriber.isFailed()).isFalse();
    }

    @Test
    void closingWaitsForTheReadsThatAreRunningButNotForTheSubscribers() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChangeFeed feed = new ChangeFeed((after, limit) -> {
            reading.countDown();
            try {
                release.await(TIMEOUT, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of(change(1));
        });
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);
        feed.publisher(Basis.NONE).subscribe(subscriber);
        assertThat(reading.await(TIMEOUT, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> closing = new CompletableFuture<>();
        Thread closer = new Thread(() -> {
            feed.close();
            closing.complete(null);
        });
        closer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT);
        while (closer.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(closer.getState()).as("closing waits for the read").isEqualTo(Thread.State.WAITING);
        assertThat(closing.isDone()).isFalse();
        release.countDown();
        closing.get(TIMEOUT, TimeUnit.SECONDS);

        subscriber.awaitCompleted();
        assertThat(subscriber.calls()).as("the change that was read after closing began is not delivered").isZero();
    }

    @Test
    void aSubscriberThatClosesTheFeedInOnNextDoesNotWaitForItself() {
        ChangeFeed feed = new ChangeFeed(new Source(10));
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE, change -> feed.close());

        feed.publisher(Basis.NONE).subscribe(subscriber);

        subscriber.awaitCompleted();
        assertThat(subscriber.calls()).isEqualTo(1);
    }

    @Test
    void waitingSubscriptionsAreNotLostWhenManyAreStartedAndClosedTogether() throws ExecutionException,
            InterruptedException, TimeoutException {
        ChangeFeed feed = new ChangeFeed(new Source(3));
        List<RecordingSubscriber> subscribers = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> starts = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            starts.add(CompletableFuture.runAsync(() -> {
                RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);
                subscribers.add(subscriber);
                feed.publisher(Basis.NONE).subscribe(subscriber);
            }));
        }
        for (CompletableFuture<Void> start : starts) {
            start.get(TIMEOUT, TimeUnit.SECONDS);
        }

        feed.close();

        for (RecordingSubscriber subscriber : subscribers) {
            subscriber.awaitCompleted();
            assertThat(subscriber.calls()).isLessThanOrEqualTo(3);
        }
    }
}
