package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.Change;
import io.github.enricoag1982.cairn.schema.Basis;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The publisher of a store's changes (ADR-0029, design D5 of {@code add-change-feed}): a {@link Flow.Publisher} whose
 * subscriptions read the store's changes at the pace of their subscribers and wait for the next commits when they have
 * delivered them all.
 *
 * <p><b>One thread for each subscription.</b> A subscription has a cursor, the basis of the last change it delivered, and a
 * virtual thread that starts when the subscriber has returned from {@code onSubscribe} and has requested something. The
 * thread makes every signal but {@code onSubscribe}, so signals are serial (Reactive Streams, rule 1.3), and none is made
 * by the thread that commits. It works in a loop: while there is demand, it reads the changes after its cursor, at most as
 * many as the demand and 256, and calls {@code onNext} for each; when it has demand and nothing to read, or no demand, it
 * waits.
 *
 * <p><b>Waiting without blocking the writer.</b> A commit calls {@link #published()} once its basis is published, which
 * adds one to a counter and unparks the thread of each subscription that has one; nothing else, so the writer never waits
 * for a subscriber. The thread reads the counter before it reads the changes, and parks only while the counter and the
 * count of events of its own subscription (requests, cancellations and closing) are as they were when it began: a commit
 * or a request that comes while it reads makes it read again instead of park, and an unpark that comes before it parks
 * leaves a permit, so no wake-up is lost.
 *
 * <p><b>Ending.</b> A subscription ends when the subscriber cancels it, requests fewer than one change ({@code onError} of
 * an {@link IllegalArgumentException}, as rule 3.9 asks), throws from {@code onNext} (which cancels it, and the exception
 * goes nowhere else), when a read fails ({@code onError}), or when the feed is {@linkplain #close() closed}
 * ({@code onComplete}, after the {@code onNext} that is running returns). A subscription to a closed feed completes at
 * once. Closing waits for the reads that are running, which are short and never call a subscriber, so that the store can
 * release what it reads from.
 */
final class ChangeFeed {

    /** The largest number of changes that a subscription reads at once. */
    static final int CHUNK = 256;

    /** Reads the changes after a basis; the store's {@code changes}. */
    @FunctionalInterface
    interface Reader {

        /** The changes at the bases after a basis, at most a number of them, as {@code Store.changes} reads them. */
        List<Change> read(Basis after, int limit);
    }

    private final Reader reader;
    /** The number of commits, schema registrations and purges that were published. */
    private final AtomicLong publications = new AtomicLong();
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    /** Reads hold the read lock; closing takes the write lock, to wait for them. */
    private final ReentrantReadWriteLock reads = new ReentrantReadWriteLock();
    private volatile boolean closed;

    ChangeFeed(Reader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /**
     * The publisher of the changes after a basis.
     *
     * @param after the basis to follow from, which is not included
     * @return the publisher, whose subscribers each get a subscription of their own
     */
    Flow.Publisher<Change> publisher(Basis after) {
        Objects.requireNonNull(after, "after");
        return subscriber -> subscribe(after, Objects.requireNonNull(subscriber, "subscriber"));
    }

    private void subscribe(Basis after, Flow.Subscriber<? super Change> subscriber) {
        Subscription subscription = new Subscription(subscriber, after);
        subscriptions.add(subscription);
        try {
            subscriber.onSubscribe(subscription);
        } catch (Throwable thrown) {
            // Rule 2.13: a subscriber that throws from onSubscribe is treated as cancelled.
            subscription.cancel();
            return;
        }
        subscription.ready();
    }

    /**
     * Tells the feed that a commit, a schema registration or a purge was published: every subscription that waits reads
     * again. Called by the writer, which it never makes wait.
     */
    void published() {
        publications.incrementAndGet();
        for (Subscription subscription : subscriptions) {
            subscription.unpark();
        }
    }

    /**
     * Completes every subscription, after the {@code onNext} that it is in returns, and waits for the reads that are
     * running, so that the store can release what they read from. Subscriptions made later complete at once.
     */
    void close() {
        closed = true;
        for (Subscription subscription : subscriptions) {
            subscription.completeBecauseClosed();
        }
        reads.writeLock().lock();
        reads.writeLock().unlock();
    }

    /** The virtual threads of the subscriptions that have started one, for tests. */
    List<Thread> threads() {
        List<Thread> threads = new ArrayList<>();
        for (Subscription subscription : subscriptions) {
            Thread thread = subscription.thread;
            if (thread != null) {
                threads.add(thread);
            }
        }
        return threads;
    }

    private List<Change> read(Basis after, int limit) {
        reads.readLock().lock();
        try {
            return closed ? List.of() : reader.read(after, limit);
        } finally {
            reads.readLock().unlock();
        }
    }

    private static long add(long demand, long requested) {
        long sum = demand + requested;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    /** One subscriber's subscription, with the thread that makes its signals. */
    private final class Subscription implements Flow.Subscription, Runnable {

        private final Flow.Subscriber<? super Change> subscriber;
        /** The basis of the last change delivered; only the thread of the subscription uses it once it has started. */
        private Basis cursor;
        /** What the subscriber requested and was not delivered yet. */
        private final AtomicLong demand = new AtomicLong();
        /** Counts requests, cancellations and closing, so that the thread notices them while it is about to park. */
        private final AtomicLong events = new AtomicLong();
        private final AtomicBoolean started = new AtomicBoolean();
        private volatile boolean cancelled;
        private volatile boolean badRequest;
        /** Set once the subscriber has returned from {@code onSubscribe}: no thread signals before that. */
        private volatile boolean ready;
        private volatile Thread thread;

        Subscription(Flow.Subscriber<? super Change> subscriber, Basis cursor) {
            this.subscriber = subscriber;
            this.cursor = cursor;
        }

        // ------------------------------------------------------------ the subscriber's calls

        @Override
        public void request(long n) {
            if (cancelled) {
                return;
            }
            if (n < 1) {
                badRequest = true;
            } else {
                demand.accumulateAndGet(n, ChangeFeed::add);
            }
            wake();
        }

        @Override
        public void cancel() {
            cancelled = true;
            subscriptions.remove(this);
            events.incrementAndGet();
            unpark();
        }

        // ------------------------------------------------------------ the feed's calls

        /** The subscriber returned from {@code onSubscribe}: start the thread if there is something for it to do. */
        void ready() {
            ready = true;
            if (closed || badRequest || demand.get() > 0) {
                start();
            }
        }

        /** Something happened to the subscription: let the thread, which is started if it has to be, notice. */
        private void wake() {
            events.incrementAndGet();
            if (ready) {
                start();
            }
            unpark();
        }

        /** The feed was closed: the subscription has to complete, which takes its thread, even without demand. */
        void completeBecauseClosed() {
            events.incrementAndGet();
            if (ready) {
                start();
            }
            unpark();
        }

        void unpark() {
            Thread waiting = thread;
            if (waiting != null) {
                LockSupport.unpark(waiting);
            }
        }

        private void start() {
            if (!cancelled && started.compareAndSet(false, true)) {
                Thread created = Thread.ofVirtual().name("cairn-change-feed").unstarted(this);
                thread = created;
                created.start();
            }
        }

        // ------------------------------------------------------------ the thread

        @Override
        public void run() {
            try {
                loop();
            } finally {
                subscriptions.remove(this);
            }
        }

        private void loop() {
            while (true) {
                long seenEvents = events.get();
                long seenPublications = publications.get();
                if (cancelled) {
                    return;
                }
                if (badRequest) {
                    end(() -> subscriber.onError(new IllegalArgumentException("A subscriber must request at least "
                            + "one change, as rule 3.9 of the Reactive Streams specification says.")));
                    return;
                }
                if (closed) {
                    end(subscriber::onComplete);
                    return;
                }
                long wanted = demand.get();
                if (wanted > 0) {
                    List<Change> changes;
                    try {
                        changes = read(cursor, (int) Math.min(wanted, CHUNK));
                    } catch (RuntimeException | Error failure) {
                        end(() -> subscriber.onError(failure));
                        return;
                    }
                    if (!changes.isEmpty()) {
                        if (!deliver(changes)) {
                            return;
                        }
                        continue;
                    }
                }
                while (events.get() == seenEvents && publications.get() == seenPublications) {
                    LockSupport.park(this);
                }
            }
        }

        /**
         * Delivers changes, as long as the subscription goes on.
         *
         * @return {@code false} if the subscription ended, because the subscriber cancelled it or threw
         */
        private boolean deliver(List<Change> changes) {
            for (Change change : changes) {
                if (cancelled) {
                    return false;
                }
                if (closed) {
                    // Completing is for the loop, after the onNext that was running returned.
                    return true;
                }
                demand.decrementAndGet();
                cursor = change.basis();
                try {
                    subscriber.onNext(change);
                } catch (Throwable thrown) {
                    // The subscription is cancelled, and the exception goes nowhere else: commits go on.
                    cancel();
                    return false;
                }
            }
            return true;
        }

        /** Ends the subscription with a last signal, which a subscriber that cancelled does not get. */
        private void end(Runnable signal) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            subscriptions.remove(this);
            try {
                signal.run();
            } catch (Throwable ignored) {
                // Rule 2.13 asks a subscriber not to throw from these; if it does, nothing is left to tell.
            }
        }
    }
}
