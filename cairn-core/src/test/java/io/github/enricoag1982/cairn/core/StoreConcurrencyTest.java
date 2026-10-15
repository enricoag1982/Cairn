package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.id;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** ADR-0007 and design D3: one writer, and readers that never wait and never see a partial commit. */
class StoreConcurrencyTest {

    private static final int COMMITS = 150;

    private final ReferenceStore reference = new ReferenceStore();

    private static Transaction pair(int k) {
        return Transaction.by("writer")
                .create("Organization", from(1, 1), values("name", "pair-" + k + "-a"))
                .create("Organization", from(1, 1), values("name", "pair-" + k + "-b")).build();
    }

    @Test
    void readersSeeEveryCommitCompletelyOrNotAtAll() throws Exception {
        AtomicBoolean writing = new AtomicBoolean(true);
        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            List<Future<Integer>> readers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                readers.add(executor.submit(() -> {
                    int reads = 0;
                    do {
                        View view = reference.view(on(2, 1));
                        List<EntityState> organizations = view.entities("Organization");
                        // Schema: basis 1. Every later basis is one transaction of two organizations.
                        assertThat(organizations).hasSize(2 * (int) (view.basis().value() - 1));
                        Set<String> names = new HashSet<>();
                        organizations.forEach(state -> names.add(((Value.StringValue) state.values().get("name")).value()));
                        for (String name : names) {
                            if (name.endsWith("-a")) {
                                assertThat(names).contains(name.substring(0, name.length() - 1) + "b");
                            }
                        }
                        reads++;
                    } while (writing.get());
                    return reads;
                }));
            }
            Future<?> writer = executor.submit(() -> {
                for (int k = 0; k < COMMITS; k++) {
                    assertThat(reference.store.commit(pair(k))).isInstanceOf(CommitResult.Committed.class);
                }
                writing.set(false);
            });
            writer.get(60, TimeUnit.SECONDS);
            for (Future<Integer> reader : readers) {
                assertThat(reader.get(60, TimeUnit.SECONDS)).isPositive();
            }
        } finally {
            writing.set(false);
            executor.shutdownNow();
        }
        assertThat(reference.view(on(2, 1)).entities("Organization")).hasSize(2 * COMMITS);
    }

    @Test
    void aViewKeepsItsAnswerWhileOthersCommit() throws Exception {
        reference.commit(pair(0));
        View view = reference.view(on(2, 1));
        List<EntityState> before = view.entities("Organization");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                for (int k = 1; k <= 50; k++) {
                    reference.store.commit(pair(k));
                }
            }).get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(view.entities("Organization")).isEqualTo(before);
        assertThat(reference.view(on(2, 1)).entities("Organization")).hasSize(2 * 51);
    }

    /** A clock that can be told to stop callers, which the commit sequence calls while the writer lock is held. */
    private static final class BlockingClock implements InstantSource {
        private final TestClock clock = new TestClock();
        private volatile CountDownLatch release = new CountDownLatch(0);
        private volatile CountDownLatch entered = new CountDownLatch(1);

        void block() {
            release = new CountDownLatch(1);
            entered = new CountDownLatch(1);
        }

        @Override
        public Instant instant() {
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return clock.instant();
        }
    }

    @Test
    void readersDoNotWaitForAWriterThatHoldsTheLock() throws Exception {
        BlockingClock clock = new BlockingClock();
        Store store = Stores.inMemory(ReferenceStore.referenceSettings().clock(clock)
                .idGenerator(IdGenerators.deterministic()).build());
        store.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition());
        store.commit(pair(0));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            clock.block();
            Future<CommitResult> writer = executor.submit(() -> store.commit(pair(1)));
            assertThat(clock.entered.await(10, TimeUnit.SECONDS)).as("the writer holds the lock").isTrue();

            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                View view = store.view(Coordinates.effectiveAt(on(2, 1)));
                assertThat(view.entities("Organization")).hasSize(2);
                assertThat(view.timeline(id(1))).hasSize(1);
                assertThat(store.latestBasis()).isEqualTo(new Basis(2));
                assertThat(store.transactions()).hasSize(1);
                assertThat(store.schema().current().entityType("Organization")).isPresent();
            });
            assertThat(writer.isDone()).as("the writer still waits").isFalse();

            clock.release.countDown();
            assertThat(writer.get(10, TimeUnit.SECONDS)).isInstanceOf(CommitResult.Committed.class);
            assertThat(store.view(Coordinates.effectiveAt(on(2, 1))).entities("Organization")).hasSize(4);
        } finally {
            clock.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentWritersAreSerialized() throws Exception {
        int writers = 6;
        int perWriter = 20;
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        List<Receipt> receipts = new ArrayList<>();
        try {
            List<Future<List<Receipt>>> futures = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int writer = w;
                Callable<List<Receipt>> task = () -> {
                    List<Receipt> mine = new ArrayList<>();
                    for (int k = 0; k < perWriter; k++) {
                        mine.add(((CommitResult.Committed) reference.store.commit(Transaction.by("writer-" + writer)
                                .create("Organization", from(1, 1), values("name", "w" + writer + "-" + k)).build()))
                                .receipt());
                    }
                    return mine;
                };
                futures.add(executor.submit(task));
            }
            for (Future<List<Receipt>> future : futures) {
                receipts.addAll(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        int total = writers * perWriter;
        assertThat(receipts).extracting(Receipt::basis).doesNotHaveDuplicates();
        assertThat(receipts).extracting(Receipt::basis).allSatisfy(basis ->
                assertThat(basis.value()).isBetween(2L, 1L + total));
        assertThat(receipts).extracting(r -> r.created().getFirst()).doesNotHaveDuplicates();
        assertThat(reference.store.latestBasis()).isEqualTo(new Basis(1 + total));
        assertThat(reference.store.transactions()).hasSize(total);
        var history = reference.store.transactions();
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i).basis()).isGreaterThan(history.get(i - 1).basis());
            assertThat(history.get(i).transactionTime()).isAfter(history.get(i - 1).transactionTime());
        }
        assertThat(reference.view(on(2, 1)).entities("Organization")).hasSize(total);
    }
}
