package io.github.enricoag1982.cairn.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.core.kv.Batch;
import io.github.enricoag1982.cairn.core.kv.InMemoryKeyValueStore;
import io.github.enricoag1982.cairn.core.kv.KeyRange;
import io.github.enricoag1982.cairn.core.kv.KeyValueStore;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Submission;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The metadata keys of a store over a persistent key-value store (ADR-0028, design D2 of {@code add-rocksdb-store}): what
 * is written in a batch reads back equal, in the order that opening needs, and a value that cannot be read says which key.
 */
class StoredMetadataTest {

    private static final Instant T1 = Instant.parse("2026-01-05T09:00:00.123456Z");
    private static final Instant T2 = Instant.parse("2026-03-01T00:00:00Z");

    /** What a receiver was handed, as strings and records, in order. */
    private static final class Received implements Snapshots.Receiver {
        final List<Object> items = new ArrayList<>();

        @Override
        public void schema(SchemaRecord record) {
            items.add(record);
        }

        @Override
        public void transaction(TransactionRecord record) {
            items.add(record);
        }

        @Override
        public void keyed(KeyedTransaction keyed) {
            items.add(keyed);
        }

        @Override
        public void purge(PurgeRecord record) {
            items.add(record);
        }

        @Override
        public void purgedId(String digest) {
            items.add("id " + digest);
        }

        @Override
        public void purgedKey(String key) {
            items.add("key " + key);
        }

        @Override
        public void history(History history) {
            throw new AssertionError("The ledger holds the state, not the metadata.");
        }
    }

    private static SchemaRecord schema(long basis) {
        return new SchemaRecord(new Basis(basis), T1, new Submission("agent:modeler", Optional.of("hr"),
                Optional.of("add the model")), new Origin("reference-model", "1.0.0"), ReferenceModel.definition(),
                new SchemaIdentifiers(List.of(new SchemaIdentifiers.TypeAssignment("Party", new TypeId(4))),
                        List.of(new SchemaIdentifiers.AttributeAssignment("Named", "name", new AttributeId(6))), 7));
    }

    private static KeyedTransaction keyed(String key, long basis) {
        Transaction transaction = Transaction.by("importer").idempotencyKey(key)
                .create("Person", EffectivePeriod.from(T2), Map.of("name", Value.of("Alice"))).build();
        return new KeyedTransaction(transaction, new Receipt(new Basis(basis), T2, Map.of(),
                List.of(new EntityId("e-1")), List.of(), "importer", Optional.empty(), Optional.empty(),
                Optional.of(key)));
    }

    private static List<Integer> kinds(KeyValueStore store) {
        List<Integer> kinds = new ArrayList<>();
        try (Stream<KeyValueStore.Entry> all = store.scan(KeyRange.all())) {
            all.forEach(entry -> kinds.add(entry.key()[0] & 0xFF));
        }
        return kinds;
    }

    @Test
    void anEmptyKeyValueStoreHasNoFormatVersionNoLatestCommitAndNothingToLoad() {
        KeyValueStore store = new InMemoryKeyValueStore();
        Received received = new Received();

        StoredMetadata.load(store, received);

        assertThat(StoredMetadata.isEmpty(store)).isTrue();
        assertThat(StoredMetadata.formatVersion(store)).isEmpty();
        assertThat(StoredMetadata.latest(store)).isEmpty();
        assertThat(received.items).isEmpty();
    }

    @Test
    void theFormatVersionAndTheLatestCommitReadBack() {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        Batch batch = new Batch();
        StoredMetadata.putFormatVersion(batch, StoredMetadata.FORMAT_VERSION);
        StoredMetadata.putLatest(batch, new Basis(12), T1);
        store.apply(batch);

        assertThat(StoredMetadata.isEmpty(store)).isFalse();
        assertThat(StoredMetadata.formatVersion(store)).isEqualTo(OptionalInt.of(2));
        assertThat(StoredMetadata.latest(store)).contains(new StoredMetadata.Latest(new Basis(12), T1));
    }

    @Test
    void everyKindOfMetadataReadsBackEqualInTheOrderThatOpeningNeeds() {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        SchemaRecord first = schema(1);
        SchemaRecord later = schema(10);
        TransactionRecord entry = new TransactionRecord(new Basis(2), T2, "importer", Optional.of("hr"),
                Optional.of("A reason"));
        KeyedTransaction kept = keyed("import-1", 2);
        PurgeRecord purge = new PurgeRecord(new Basis(3), T2, "dpo", Optional.empty(), "Erasure", 1, 2);
        String digest = IdDigests.of("e-9");
        Batch batch = new Batch();
        // Written in another order than they are read, and a basis above 255 to show that bases sort as numbers.
        StoredMetadata.putSchema(batch, later);
        StoredMetadata.putTransaction(batch, new TransactionRecord(new Basis(300), T2, "importer", Optional.empty(),
                Optional.empty()));
        StoredMetadata.putPurgedKey(batch, "import-0");
        StoredMetadata.putPurge(batch, purge, List.of(digest));
        StoredMetadata.putKeyed(batch, kept);
        StoredMetadata.putTransaction(batch, entry);
        StoredMetadata.putSchema(batch, first);
        store.apply(batch);
        Received received = new Received();

        StoredMetadata.load(store, received);

        assertThat(received.items).containsExactly(first, later, entry,
                new TransactionRecord(new Basis(300), T2, "importer", Optional.empty(), Optional.empty()), kept, purge,
                "id " + digest, "key import-0");
    }

    @Test
    void theKindsOfMetadataKeysAreAboveTheKindsOfTheLedgersKeys() {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        Batch batch = new Batch();
        StoredMetadata.putFormatVersion(batch, 1);
        StoredMetadata.putLatest(batch, new Basis(1), T1);
        StoredMetadata.putSchema(batch, schema(1));
        StoredMetadata.putTransaction(batch, new TransactionRecord(new Basis(2), T2, "p", Optional.empty(),
                Optional.empty()));
        StoredMetadata.putKeyed(batch, keyed("k", 2));
        StoredMetadata.putPurge(batch, new PurgeRecord(new Basis(3), T2, "dpo", Optional.empty(), "r", 0, 0),
                List.of(IdDigests.of("x")));
        StoredMetadata.putPurgedKey(batch, "j");
        StoredMetadata.putCompactionPending(batch);
        store.apply(batch);

        int highestLedgerKind = Stream.of(LedgerCodec.Kind.values()).mapToInt(LedgerCodec.Kind::prefix).max()
                .orElseThrow();
        assertThat(kinds(store)).hasSize(9).allSatisfy(kind -> assertThat(kind).isGreaterThan(highestLedgerKind));
        assertThat(kinds(store)).as("each kind has a byte of its own").doesNotHaveDuplicates();
    }

    @Test
    void aPurgeRemovesTheTransactionOfAKeyAndKeepsTheKeyUnusable() {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        Batch written = new Batch();
        StoredMetadata.putKeyed(written, keyed("import-1", 2));
        StoredMetadata.putKeyed(written, keyed("import-2", 3));
        store.apply(written);
        Batch purge = new Batch();
        StoredMetadata.deleteKeyed(purge, "import-1");
        StoredMetadata.putPurgedKey(purge, "import-1");
        store.apply(purge);
        Received received = new Received();

        StoredMetadata.load(store, received);

        assertThat(received.items).containsExactly(keyed("import-2", 3), "key import-1");
    }

    @Test
    void theNoteThatACompactionIsPendingIsPutAndDeletedAndLoadingIgnoresIt() {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        assertThat(StoredMetadata.isCompactionPending(store)).isFalse();
        Batch pending = new Batch();
        StoredMetadata.putCompactionPending(pending);
        store.apply(pending);
        Received received = new Received();

        StoredMetadata.load(store, received);

        assertThat(StoredMetadata.isCompactionPending(store)).isTrue();
        assertThat(StoredMetadata.isEmpty(store)).isFalse();
        assertThat(received.items).as("it is not metadata that opening loads").isEmpty();
        Batch done = new Batch();
        StoredMetadata.deleteCompactionPending(done);
        store.apply(done);
        assertThat(StoredMetadata.isCompactionPending(store)).isFalse();
        assertThat(StoredMetadata.isEmpty(store)).isTrue();
    }

    @Test
    void aTransactionWithoutAnIdempotencyKeyIsNotKeptByKey() {
        KeyedTransaction without = new KeyedTransaction(Transaction.by("importer").build(),
                new Receipt(new Basis(1), T1, Map.of(), List.of(), List.of(), "importer", Optional.empty(),
                        Optional.empty(), Optional.empty()));

        assertThatThrownBy(() -> StoredMetadata.putKeyed(new Batch(), without))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aValueThatCannotBeReadSaysWhichKindAndKeyIsDamaged() {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        Batch batch = new Batch();
        StoredMetadata.putTransaction(batch, new TransactionRecord(new Basis(2), T2, "p", Optional.empty(),
                Optional.empty()));
        store.apply(batch);
        byte[] key = store.scan(KeyRange.all()).findFirst().orElseThrow().key();
        store.apply(new Batch().put(key, new byte[] {1, 2, 3}));

        assertThatThrownBy(() -> StoredMetadata.load(store, new Received())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction record").hasMessageContaining("00000000000000").hasMessageContaining(
                        "is damaged");
    }

    @Test
    void aValueWithBytesLeftOverIsDamagedToo() {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        Batch batch = new Batch();
        StoredMetadata.putLatest(batch, new Basis(2), T2);
        store.apply(batch);
        byte[] key = store.scan(KeyRange.all()).findFirst().orElseThrow().key();
        byte[] value = store.get(key).orElseThrow();
        byte[] longer = Arrays.copyOf(value, value.length + 1);
        store.apply(new Batch().put(key, longer));

        assertThatThrownBy(() -> StoredMetadata.latest(store)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("latest basis");
    }
}
