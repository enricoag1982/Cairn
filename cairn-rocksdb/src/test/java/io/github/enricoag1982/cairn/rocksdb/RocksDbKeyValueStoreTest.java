package io.github.enricoag1982.cairn.rocksdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.core.kv.Batch;
import io.github.enricoag1982.cairn.core.kv.KeyRange;
import io.github.enricoag1982.cairn.core.kv.KeyValueStore;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The key-value store on a RocksDB database (ADR-0028, design D5 of {@code add-rocksdb-store}): it orders and ranges keys as
 * the in-memory store does, applies batches atomically and durably, and releases everything when it is closed, also from
 * under readers.
 */
class RocksDbKeyValueStoreTest {

    @TempDir
    Path directory;

    private final AtomicInteger released = new AtomicInteger();
    private RocksDbKeyValueStore store;

    @BeforeEach
    void setUp() {
        store = open();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private RocksDbKeyValueStore open() {
        return RocksDbKeyValueStore.open(directory.resolve("db"), released::incrementAndGet);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    private static List<List<Integer>> keys(Stream<KeyValueStore.Entry> entries) {
        try (entries) {
            return entries.map(entry -> {
                List<Integer> key = new ArrayList<>();
                for (byte value : entry.key()) {
                    key.add(value & 0xFF);
                }
                return key;
            }).toList();
        }
    }

    private void put(int... key) {
        store.apply(new Batch().put(bytes(key), bytes(1)));
    }

    @Test
    void aMissingKeyHasNoValueAndAPutKeyHasOne() {
        assertThat(store.get(bytes(1, 2))).isEmpty();

        store.apply(new Batch().put(bytes(1, 2), bytes(9)));

        assertThat(store.get(bytes(1, 2))).hasValueSatisfying(value -> assertThat(value).containsExactly(9));
        assertThat(store.get(bytes(1))).as("a prefix of a key is another key").isEmpty();
    }

    @Test
    void keysAreComparedAsUnsignedBytes() {
        put(0xFF);
        put(0x80);
        put(0x7F);
        put(0x00);
        put(0x7F, 0x00);

        assertThat(keys(store.scan(KeyRange.all()))).containsExactly(List.of(0x00), List.of(0x7F),
                List.of(0x7F, 0x00), List.of(0x80), List.of(0xFF));
    }

    @Test
    void aRangeStartsAtItsFirstKeyAndEndsBeforeItsLast() {
        put(1);
        put(2);
        put(3);
        put(4);

        assertThat(keys(store.scan(bytes(2), bytes(4)))).containsExactly(List.of(2), List.of(3));
        assertThat(keys(store.scan(bytes(2), null))).containsExactly(List.of(2), List.of(3), List.of(4));
        assertThat(keys(store.scan(bytes(3), bytes(3)))).as("an empty range").isEmpty();
        assertThat(keys(store.scan(bytes(4), bytes(2)))).as("a range that ends before it starts").isEmpty();
        assertThat(keys(store.scan(bytes(5), bytes(9)))).isEmpty();
    }

    @Test
    void aPrefixRangeHoldsTheKeysThatStartWithThePrefix() {
        put(1, 2);
        put(1, 2, 0);
        put(1, 2, 0xFF, 0xFF);
        put(1, 3);
        put(1, 1, 0xFF);
        put(2);
        put(0xFF, 0xFF, 5);

        assertThat(keys(store.scan(KeyRange.prefix(bytes(1, 2))))).containsExactly(List.of(1, 2),
                List.of(1, 2, 0), List.of(1, 2, 0xFF, 0xFF));
        assertThat(keys(store.scan(KeyRange.prefix(bytes(1))))).hasSize(5);
        assertThat(keys(store.scan(KeyRange.prefix(bytes(1, 0xFF))))).isEmpty();
        assertThat(keys(store.scan(KeyRange.prefix(bytes(0xFF, 0xFF))))).as("a prefix without an end")
                .containsExactly(List.of(0xFF, 0xFF, 5));
    }

    @Test
    void aBatchAppliesItsOperationsInOrder() {
        store.apply(new Batch()
                .put(bytes(1), bytes(1))
                .delete(bytes(1))
                .put(bytes(2), bytes(1))
                .put(bytes(2), bytes(2))
                .put(bytes(3), bytes(1))
                .delete(bytes(3))
                .put(bytes(3), bytes(3)));

        assertThat(store.get(bytes(1))).isEmpty();
        assertThat(store.get(bytes(2))).hasValueSatisfying(value -> assertThat(value).containsExactly(2));
        assertThat(store.get(bytes(3))).hasValueSatisfying(value -> assertThat(value).containsExactly(3));
    }

    @Test
    void deletingAMissingKeyAndApplyingAnEmptyBatchChangeNothing() {
        put(1);

        store.apply(new Batch().delete(bytes(2)));
        store.apply(new Batch());

        assertThat(keys(store.scan(KeyRange.all()))).containsExactly(List.of(1));
    }

    @Test
    void theKeysAreOnDiskAfterTheStoreIsClosedAndOpenedAgain() {
        store.apply(new Batch().put(bytes(1), bytes(7)).put(bytes(2), bytes(8)).delete(bytes(9)));
        store.close();

        store = open();

        assertThat(store.get(bytes(1))).hasValueSatisfying(value -> assertThat(value).containsExactly(7));
        assertThat(keys(store.scan(KeyRange.all()))).containsExactly(List.of(1), List.of(2));
    }

    @Test
    void flushingAndCompactingKeepTheKeys() {
        put(1);
        put(2);

        store.flush();
        put(3);
        store.compactAll();

        assertThat(keys(store.scan(KeyRange.all()))).containsExactly(List.of(1), List.of(2), List.of(3));
    }

    @Test
    void aDeletedValueIsInNoDataFileOnceTheStoreHasFlushedAndCompacted() {
        byte[] secret = "a value that is deleted".getBytes(StandardCharsets.UTF_8);
        store.apply(new Batch().put(bytes(1), secret).put(bytes(2), bytes(2)));
        store.flush();
        store.apply(new Batch().put(bytes(3), secret));
        assertThat(StoreFiles.filesContaining(directory, "a value that is deleted")).as("on disk before the delete")
                .isNotEmpty();

        store.apply(new Batch().delete(bytes(1)).delete(bytes(3)));
        store.flush();
        store.compactAll();

        assertThat(StoreFiles.filesContaining(directory, "a value that is deleted")).isEmpty();
        assertThat(keys(store.scan(KeyRange.all()))).containsExactly(List.of(2));
    }

    @Test
    void aClosedStoreRefusesEveryCallButCloseAndReleasesTheDirectoryOnce() {
        put(1);
        Stream<KeyValueStore.Entry> open = store.scan(KeyRange.all());

        store.close();
        store.close();

        assertThat(released.get()).isEqualTo(1);
        assertThatThrownBy(() -> store.get(bytes(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.scan(KeyRange.all())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.apply(new Batch().put(bytes(1), bytes(1))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::flush).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::compactAll).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(open::toList).as("a scan that was open when the store closed")
                .isInstanceOf(IllegalStateException.class);
        open.close();
        store = open();
    }

    @Test
    void aScanThatIsRunningWhenTheStoreCompactsGoesOnAfterTheKeyItReturnedLast() {
        for (int key = 1; key <= 10; key++) {
            put(key);
        }
        Stream<KeyValueStore.Entry> scan = store.scan(KeyRange.all());
        Iterator<KeyValueStore.Entry> entries = scan.iterator();
        List<Integer> read = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            read.add(entries.next().key()[0] & 0xFF);
        }

        store.apply(new Batch().delete(bytes(3)).delete(bytes(5)).delete(bytes(6)).put(bytes(11), bytes(1)));
        store.flush();
        store.compactAll();
        while (entries.hasNext()) {
            read.add(entries.next().key()[0] & 0xFF);
        }
        scan.close();

        assertThat(read).as("the keys it read before, and the ones that are left after the last it returned")
                .containsExactly(1, 2, 3, 4, 7, 8, 9, 10, 11);
        assertThat(keys(store.scan(KeyRange.all()))).hasSize(8);
    }

    @Test
    void aScanThatHasReturnedNothingYetAndAScanWithAnEndSurviveACompactionToo() {
        for (int key = 1; key <= 6; key++) {
            put(key);
        }
        Stream<KeyValueStore.Entry> fresh = store.scan(KeyRange.all());
        Stream<KeyValueStore.Entry> bounded = store.scan(bytes(2), bytes(5));
        Iterator<KeyValueStore.Entry> bounds = bounded.iterator();
        assertThat(bounds.next().key()).containsExactly(2);

        store.apply(new Batch().delete(bytes(1)).delete(bytes(3)));
        store.flush();
        store.compactAll();

        assertThat(fresh.map(entry -> entry.key()[0] & 0xFF).toList()).containsExactly(2, 4, 5, 6);
        assertThat(bounds.next().key()).as("after the key it returned, the next that is left").containsExactly(4);
        assertThat(bounds.hasNext()).as("and the end of its range holds").isFalse();
        fresh.close();
        bounded.close();
    }

    @Test
    void aStoreWhoseDatabaseCannotBeOpenedAgainAfterACompactionIsUnusableAndClosesOnce() throws IOException {
        Stream<KeyValueStore.Entry> scan = store.scan(KeyRange.all());
        Path database = directory.resolve("db");
        Path moved = directory.resolve("db-moved");
        Files.move(database, moved);
        Files.writeString(database, "not a database");

        assertThatThrownBy(store::compactAll).isInstanceOf(UncheckedIOException.class);

        assertThatThrownBy(() -> store.get(bytes(1))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unusable");
        assertThatThrownBy(store::flush).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::compactAll).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.apply(new Batch().put(bytes(1), bytes(1))))
                .isInstanceOf(IllegalStateException.class);
        scan.close();
        store.close();
        store.close();
        assertThat(released.get()).as("the directory is released once").isEqualTo(1);
        Files.delete(database);
        Files.move(moved, database);
        store = open();
        assertThat(released.get()).isEqualTo(1);
    }

    @Test
    void aSecondDatabaseOnTheSameFilesIsRefusedByRocksDbItself() {
        assertThatThrownBy(this::open).isInstanceOf(UncheckedIOException.class).hasMessageContaining("RocksDB");
    }

    @Test
    void closingWaitsForTheReadersThatAreRunningAndNothingCrashes() throws Exception {
        for (int i = 0; i < 200; i++) {
            put(i / 256, i % 256);
        }
        ExecutorService readers = Executors.newFixedThreadPool(4);
        CountDownLatch started = new CountDownLatch(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int reader = 0; reader < 4; reader++) {
                results.add(readers.submit(() -> {
                    int scans = 0;
                    try {
                        while (true) {
                            assertThat(keys(store.scan(KeyRange.all()))).hasSize(200);
                            assertThat(store.get(bytes(0, 5))).isPresent();
                            if (scans++ == 0) {
                                started.countDown();
                            }
                        }
                    } catch (IllegalStateException closed) {
                        return scans;
                    }
                }));
            }
            assertThat(started.await(1, TimeUnit.MINUTES)).as("every reader scanned once").isTrue();

            store.close();

            for (Future<Integer> result : results) {
                assertThat(result.get(1, TimeUnit.MINUTES)).as("scans that read every key").isPositive();
            }
        } finally {
            readers.shutdownNow();
        }
        store = open();
    }
}
