package io.github.enricoag1982.cairn.example.hr;

import io.github.enricoag1982.cairn.data.Change;
import io.github.enricoag1982.cairn.data.Changed;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TransactionChange;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.example.hr.model.LeaveRequest;
import io.github.enricoag1982.cairn.example.hr.model.Person;
import io.github.enricoag1982.cairn.schema.Basis;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The notifier of the story HR-15: a subscriber to the change feed of a store (ADR-0029) that tells the manager of a unit
 * that a leave request waits for them. It is what an application writes when a side effect belongs to it and not to the
 * engine (ADR-0018), and it uses Cairn's public API only: the feed says that a {@code LeaveRequest} was created and in
 * which basis, and the notifier reads the request, its requester and the managers through views at that basis, as of
 * the request's first day, which is the day whose managers decide it.
 *
 * <p>It records a {@link Notification} for each manager of the unit that the requester's position belongs to, in a queue
 * that the test reads, and the basis of each change it has handled, in another, so that a test can wait for it without
 * sleeping. It keeps no other state: the basis of the last change handled is all it would need to resume after a restart.
 */
final class LeaveNotifier implements Flow.Subscriber<Change> {

    /** A manager who is told that a request waits for them. */
    record Notification(String manager, String requester, EntityId request, LocalDate firstDay, BigDecimal days) {
    }

    private static final long TIMEOUT_SECONDS = 20;

    private final Store store;
    private final BlockingQueue<Notification> notifications = new LinkedBlockingQueue<>();
    private final BlockingQueue<Basis> handled = new LinkedBlockingQueue<>();
    private final List<Thread> threads = new ArrayList<>();
    private Flow.Subscription subscription;

    LeaveNotifier(Store store) {
        this.store = store;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(Change change) {
        synchronized (threads) {
            threads.add(Thread.currentThread());
        }
        if (change instanceof TransactionChange transaction) {
            for (Changed changed : transaction.changed()) {
                if (changed.created() && changed.type().equals(LeaveRequest.TYPE)
                        && changed.id() instanceof EntityId request) {
                    tellTheManagers(transaction.basis(), changed.periods().getFirst().from(), request);
                }
            }
        }
        handled.add(change.basis());
    }

    private void tellTheManagers(Basis basis, Instant filedAt, EntityId request) {
        View filed = store.view(Coordinates.effectiveAt(filedAt).knownAt(basis));
        LeaveRequest leave = LeaveRequest.from(filed.entity(request).orElseThrow());
        View onTheFirstDay = store.view(Coordinates.effectiveAt(leave.firstDay().atStartOfDay(ZoneOffset.UTC)
                .toInstant()).knownAt(basis));
        for (Link requester : onTheFirstDay.navigate(request, "requester")) {
            EntityId employment = requester.entity().id();
            for (Link position : onTheFirstDay.navigate(employment, "Fills")) {
                for (Link unit : onTheFirstDay.navigate(position.entity().id(), "BelongsTo")) {
                    for (Link manager : onTheFirstDay.navigate(unit.entity().id(), "ManagedBy")) {
                        notifications.add(new Notification(nameOf(onTheFirstDay, manager.entity().id()),
                                nameOf(onTheFirstDay, employment), request, leave.firstDay(), leave.days()));
                    }
                }
            }
        }
    }

    /** The name of the person that an employment is of. */
    private static String nameOf(View view, EntityId employment) {
        Person person = Person.from(view.navigate(employment, "EmploymentOf").getFirst().entity());
        return person.givenName() + " " + person.familyName();
    }

    @Override
    public void onError(Throwable throwable) {
        throw new IllegalStateException("The change feed failed.", throwable);
    }

    @Override
    public void onComplete() {
        // The store was closed: nothing more will come.
    }

    /** Stops following the feed. */
    void stop() {
        subscription.cancel();
    }

    /** The next notification, waiting for it for a while that only a failure reaches. */
    Notification next() throws InterruptedException {
        Notification notification = notifications.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (notification == null) {
            throw new AssertionError("No notification came in " + TIMEOUT_SECONDS + " seconds.");
        }
        return notification;
    }

    /** The notifications that came and were not taken, without waiting. */
    int pending() {
        return notifications.size();
    }

    /** Waits until the notifier has handled the change of a basis. */
    void awaitHandled(Basis basis) throws InterruptedException {
        Basis last;
        do {
            last = handled.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (last == null) {
                throw new AssertionError("The change of basis " + basis.value() + " was not handled in "
                        + TIMEOUT_SECONDS + " seconds.");
            }
        } while (last.compareTo(basis) < 0);
    }

    /** The threads that handled changes. */
    List<Thread> threads() {
        synchronized (threads) {
            return List.copyOf(threads);
        }
    }
}
