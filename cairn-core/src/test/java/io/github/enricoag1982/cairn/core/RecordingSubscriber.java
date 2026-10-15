package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.data.Change;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * A subscriber to a store's change feed for tests: it keeps what it was signalled, the thread of each signal, and lets a
 * test wait for the next change, the completion or the error without sleeping: every wait is a blocking queue or a latch,
 * with a timeout that only a failure reaches.
 */
public final class RecordingSubscriber implements Flow.Subscriber<Change> {

    /** How long a test waits for a signal that has to come. */
    public static final long TIMEOUT_SECONDS = 20;

    private final long initialDemand;
    private final Consumer<Change> action;
    private final BlockingQueue<Change> received = new LinkedBlockingQueue<>();
    private final List<Thread> signalThreads = new CopyOnWriteArrayList<>();
    private final CountDownLatch completed = new CountDownLatch(1);
    private final CountDownLatch failed = new CountDownLatch(1);
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Flow.Subscription subscription;
    private volatile Thread subscribingThread;

    /**
     * A subscriber that requests a number of changes when it is subscribed and does nothing else with a change.
     *
     * @param initialDemand how many changes to request in {@code onSubscribe}; 0 requests none
     */
    public RecordingSubscriber(long initialDemand) {
        this(initialDemand, change -> { });
    }

    /**
     * A subscriber that requests a number of changes when it is subscribed and runs an action in {@code onNext}, after it
     * recorded the change; an action that throws makes {@code onNext} throw.
     *
     * @param initialDemand how many changes to request in {@code onSubscribe}; 0 requests none
     * @param action        what to do with each change
     */
    public RecordingSubscriber(long initialDemand, Consumer<Change> action) {
        this.initialDemand = initialDemand;
        this.action = action;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        this.subscribingThread = Thread.currentThread();
        if (initialDemand > 0) {
            subscription.request(initialDemand);
        }
    }

    @Override
    public void onNext(Change change) {
        signalThreads.add(Thread.currentThread());
        calls.incrementAndGet();
        received.add(change);
        action.accept(change);
    }

    @Override
    public void onError(Throwable throwable) {
        signalThreads.add(Thread.currentThread());
        error.set(throwable);
        failed.countDown();
    }

    @Override
    public void onComplete() {
        signalThreads.add(Thread.currentThread());
        completed.countDown();
    }

    /** The subscription, once {@code onSubscribe} was called. */
    public Flow.Subscription subscription() {
        return subscription;
    }

    /** The thread that called {@code onSubscribe}. */
    public Thread subscribingThread() {
        return subscribingThread;
    }

    /**
     * Waits for the next change that was not taken yet.
     *
     * @return the change
     * @throws AssertionError if none comes in time
     */
    public Change next() {
        try {
            Change change = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (change == null) {
                throw new AssertionError("No change was delivered in " + TIMEOUT_SECONDS + " seconds.");
            }
            return change;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for a change.", e);
        }
    }

    /**
     * The changes taken in turn.
     *
     * @param count how many to wait for
     * @return the changes, in the order of delivery
     */
    public List<Change> next(int count) {
        return IntStream.range(0, count).mapToObj(i -> next()).toList();
    }

    /** The changes that were delivered and not taken yet, without waiting. */
    public int pending() {
        return received.size();
    }

    /** How many times {@code onNext} was called, taken or not. */
    public int calls() {
        return calls.get();
    }

    /**
     * Waits for {@code onComplete}.
     *
     * @throws AssertionError if it does not come in time
     */
    public void awaitCompleted() {
        if (!await(completed)) {
            throw new AssertionError("The subscription did not complete in " + TIMEOUT_SECONDS + " seconds.");
        }
    }

    /**
     * Waits for {@code onError}.
     *
     * @return the error
     * @throws AssertionError if it does not come in time
     */
    public Throwable awaitError() {
        if (!await(failed)) {
            throw new AssertionError("The subscription did not fail in " + TIMEOUT_SECONDS + " seconds.");
        }
        return error.get();
    }

    /** Tells whether {@code onComplete} was called. */
    public boolean isCompleted() {
        return completed.getCount() == 0;
    }

    /** Tells whether {@code onError} was called. */
    public boolean isFailed() {
        return failed.getCount() == 0;
    }

    /** The threads that made signals other than {@code onSubscribe}, one entry for each signal. */
    public List<Thread> signalThreads() {
        return List.copyOf(signalThreads);
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
