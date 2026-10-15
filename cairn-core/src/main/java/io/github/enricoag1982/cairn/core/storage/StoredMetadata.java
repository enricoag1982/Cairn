package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.core.kv.Batch;
import io.github.enricoag1982.cairn.core.kv.KeyRange;
import io.github.enricoag1982.cairn.core.kv.KeyValueStore;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The keys that a store over a persistent key-value store writes beside the ledger's (ADR-0028, design D2 of
 * {@code add-rocksdb-store}): what a store keeps outside the key layout of ADR-0027, so that it survives the process
 * without a commit log. The kinds of keys start with a byte above those of {@link LedgerCodec.Kind}.
 *
 * <table class="striped">
 *   <caption>The metadata keys</caption>
 *   <tr><th>Key</th><th>Value</th></tr>
 *   <tr><td>format version</td><td>the format version of the store, written when it is created</td></tr>
 *   <tr><td>latest</td><td>the basis and the transaction time of the latest commit, schema registration or purge</td></tr>
 *   <tr><td>schema, basis</td><td>a registration with the definition it submitted and the identifiers it assigned, as the
 *       commit log writes it</td></tr>
 *   <tr><td>transaction, basis</td><td>an entry of the transaction history</td></tr>
 *   <tr><td>keyed, idempotency key</td><td>the transaction as submitted and its receipt, as a snapshot file writes them</td></tr>
 *   <tr><td>purge, basis</td><td>a purge record</td></tr>
 *   <tr><td>purged ID, digest</td><td>nothing: the {@linkplain IdDigests digest} of the ID of what a purge removed</td></tr>
 *   <tr><td>purged key, idempotency key</td><td>nothing: a key whose transaction a purge removed, which stays unusable</td></tr>
 *   <tr><td>compaction pending</td><td>nothing: a purge wrote this key with its batch, and deletes it once the files of
 *       the key-value store were compacted, so a store that finds it when it opens finishes the compaction</td></tr>
 * </table>
 *
 * <p>The values use the encodings of the commit log and of snapshot files, with the transaction of a commit written
 * without its effects: the ledger's keys hold the state. A commit writes the keys of its metadata in the same batch as the
 * ledger's keys, so that the state and the metadata never disagree after a crash.
 */
public final class StoredMetadata {

    /**
     * The format version that this version of Cairn writes and reads. Version 2 adds the index of the change feed to the
     * ledger's keys (ADR-0029): a store written in version 1 lacks it, and is refused.
     */
    public static final int FORMAT_VERSION = 2;

    private static final byte FORMAT = 16;
    private static final byte LATEST = 17;
    private static final byte SCHEMA = 18;
    private static final byte TRANSACTION = 19;
    private static final byte KEYED = 20;
    private static final byte PURGE = 21;
    private static final byte PURGED_ID = 22;
    private static final byte PURGED_KEY = 23;
    private static final byte COMPACTION_PENDING = 24;

    private static final HexFormat HEX = HexFormat.of();

    /**
     * The basis and the transaction time of the latest commit, schema registration or purge of a store.
     *
     * @param basis           the basis
     * @param transactionTime the transaction time
     */
    public record Latest(Basis basis, Instant transactionTime) {

        /**
         * Rejects null components.
         */
        public Latest {
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(transactionTime, "transactionTime");
        }
    }

    private StoredMetadata() {
    }

    // ------------------------------------------------------------------ keys

    private static byte[] key(byte kind) {
        return new byte[] {kind};
    }

    private static byte[] basisKey(byte kind, Basis basis) {
        Encoder out = Encoder.plain();
        out.writeByte(kind);
        out.writeLong(basis.value());
        return out.toByteArray();
    }

    private static byte[] textKey(byte kind, String text) {
        Encoder out = Encoder.plain();
        out.writeByte(kind);
        out.writeText(text);
        return out.toByteArray();
    }

    private static byte[] digestKey(String digest) {
        Encoder out = Encoder.plain();
        out.writeByte(PURGED_ID);
        PurgeCodec.writeDigest(out, digest);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ writing

    /**
     * Adds the format version to a batch, which a store does when it is created.
     *
     * @param batch   the batch
     * @param version the format version, {@link #FORMAT_VERSION} except in tests
     */
    public static void putFormatVersion(Batch batch, int version) {
        Encoder out = Encoder.plain();
        out.writeInt(version);
        batch.put(key(FORMAT), out.toByteArray());
    }

    /**
     * Adds the basis and the transaction time of the latest commit to a batch.
     *
     * @param batch           the batch
     * @param basis           the basis
     * @param transactionTime the transaction time
     */
    public static void putLatest(Batch batch, Basis basis, Instant transactionTime) {
        Encoder out = Encoder.plain();
        out.writeLong(basis.value());
        out.writeInstant(transactionTime);
        batch.put(key(LATEST), out.toByteArray());
    }

    /**
     * Adds a schema registration to a batch.
     *
     * @param batch  the batch
     * @param record the registration, with the identifiers it assigned
     */
    public static void putSchema(Batch batch, SchemaRecord record) {
        Encoder out = Encoder.plain();
        SchemaRecordWriter.write(out, record);
        batch.put(basisKey(SCHEMA, record.basis()), out.toByteArray());
    }

    /**
     * Adds an entry of the transaction history to a batch.
     *
     * @param batch  the batch
     * @param record the entry
     */
    public static void putTransaction(Batch batch, TransactionRecord record) {
        Encoder out = Encoder.plain();
        TransactionCodec.writeEntry(out, record);
        batch.put(basisKey(TRANSACTION, record.basis()), out.toByteArray());
    }

    /**
     * Adds a transaction with an idempotency key and its receipt to a batch.
     *
     * @param batch the batch
     * @param keyed the transaction and its receipt
     * @throws IllegalArgumentException if the transaction has no idempotency key
     */
    public static void putKeyed(Batch batch, KeyedTransaction keyed) {
        String key = keyed.transaction().idempotencyKey().orElseThrow(() -> new IllegalArgumentException(
                "A transaction without an idempotency key is not kept by key."));
        Encoder out = Encoder.plain();
        TransactionCodec.writeKeyed(out, keyed);
        batch.put(textKey(KEYED, key), out.toByteArray());
    }

    /**
     * Adds the removal of a transaction with an idempotency key to a batch, as a purge removes it. The key stays unusable:
     * {@link #putPurgedKey} says so.
     *
     * @param batch the batch
     * @param key   the idempotency key
     */
    public static void deleteKeyed(Batch batch, String key) {
        batch.delete(textKey(KEYED, key));
    }

    /**
     * Adds an idempotency key whose transaction a purge removed to a batch: the store rejects a transaction that repeats it.
     *
     * @param batch the batch
     * @param key   the idempotency key
     */
    public static void putPurgedKey(Batch batch, String key) {
        batch.put(textKey(PURGED_KEY, key), new byte[0]);
    }

    /**
     * Adds to a batch the note that the files of the key-value store still have to be compacted, which a purge writes with
     * the batch that deletes what it removes: until they are, they may hold what was deleted.
     *
     * @param batch the batch
     */
    public static void putCompactionPending(Batch batch) {
        batch.put(key(COMPACTION_PENDING), new byte[0]);
    }

    /**
     * Adds to a batch the removal of the note that {@link #putCompactionPending} wrote, once the files were compacted.
     *
     * @param batch the batch
     */
    public static void deleteCompactionPending(Batch batch) {
        batch.delete(key(COMPACTION_PENDING));
    }

    /**
     * Adds a purge to a batch: its record, and the digests of the IDs of what it removed.
     *
     * @param batch   the batch
     * @param record  the purge record
     * @param digests the {@linkplain IdDigests digests} of the IDs of the entities and relationships it removed
     */
    public static void putPurge(Batch batch, PurgeRecord record, Iterable<String> digests) {
        Encoder out = Encoder.plain();
        PurgeCodec.write(out, record);
        batch.put(basisKey(PURGE, record.basis()), out.toByteArray());
        for (String digest : digests) {
            batch.put(digestKey(digest), new byte[0]);
        }
    }

    // ------------------------------------------------------------------ reading

    /**
     * The format version that a key-value store holds.
     *
     * @param store the store
     * @return the version; empty if the store has none, which a store that was never opened as a Cairn store has
     * @throws IllegalStateException if the stored version is damaged
     */
    public static OptionalInt formatVersion(KeyValueStore store) {
        Optional<byte[]> value = store.get(key(FORMAT));
        if (value.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(decode("format version", key(FORMAT), value.get(), Decoder::readInt));
    }

    /**
     * Tells whether a purge wrote its batch to a key-value store and did not finish compacting its files.
     *
     * @param store the store
     * @return {@code true} if the note of {@link #putCompactionPending} is there
     */
    public static boolean isCompactionPending(KeyValueStore store) {
        return store.get(key(COMPACTION_PENDING)).isPresent();
    }

    /**
     * Tells whether a key-value store holds no key at all.
     *
     * @param store the store
     * @return {@code true} if it is empty
     */
    public static boolean isEmpty(KeyValueStore store) {
        try (Stream<KeyValueStore.Entry> all = store.scan(KeyRange.all())) {
            return all.findFirst().isEmpty();
        }
    }

    /**
     * The basis and the transaction time of the latest commit that a key-value store holds.
     *
     * @param store the store
     * @return the latest commit; empty if nothing was committed
     * @throws IllegalStateException if the stored value is damaged
     */
    public static Optional<Latest> latest(KeyValueStore store) {
        return store.get(key(LATEST)).map(value -> decode("latest basis", key(LATEST), value,
                in -> new Latest(new Basis(in.readLong()), in.readInstant())));
    }

    /**
     * Hands what a key-value store holds as metadata to a receiver, in the order of the kinds: the schema registrations
     * and the transaction history in the order of their bases, the transactions with an idempotency key, the purge records
     * in the order of their bases with the digests of the IDs they removed, and the purged keys. It never calls
     * {@link Snapshots.Receiver#history}: the ledger holds the state.
     *
     * @param store    the store
     * @param receiver receives the items
     * @throws IllegalStateException if a stored value is damaged; the message names its key
     */
    public static void load(KeyValueStore store, Snapshots.Receiver receiver) {
        each(store, SCHEMA, "schema record", in -> receiver.schema(SchemaRecordReader.read(in)));
        each(store, TRANSACTION, "transaction record", in -> receiver.transaction(TransactionCodec.readEntry(in)));
        each(store, KEYED, "transaction with an idempotency key", in -> receiver.keyed(TransactionCodec.readKeyed(in)));
        each(store, PURGE, "purge record", in -> receiver.purge(PurgeCodec.read(in)));
        eachKey(store, PURGED_ID, "digest of a purged ID", in -> receiver.purgedId(PurgeCodec.readDigest(in)));
        eachKey(store, PURGED_KEY, "purged idempotency key", in -> receiver.purgedKey(in.readText()));
    }

    private static void each(KeyValueStore store, byte kind, String what, Consumer<Decoder> reader) {
        try (Stream<KeyValueStore.Entry> entries = store.scan(KeyRange.prefix(key(kind)))) {
            entries.forEach(entry -> decode(what, entry.key(), entry.value(), in -> {
                reader.accept(in);
                return null;
            }));
        }
    }

    /** Reads the entries whose key holds the item, after the kind byte, and whose value is empty. */
    private static void eachKey(KeyValueStore store, byte kind, String what, Consumer<Decoder> reader) {
        try (Stream<KeyValueStore.Entry> entries = store.scan(KeyRange.prefix(key(kind)))) {
            entries.forEach(entry -> decode(what, entry.key(), entry.key(), 1, in -> {
                reader.accept(in);
                return null;
            }));
        }
    }

    private static <T> T decode(String what, byte[] key, byte[] value, Function<Decoder, T> reader) {
        return decode(what, key, value, 0, reader);
    }

    /** Decodes a value from a position, and checks that all of it was read. */
    private static <T> T decode(String what, byte[] key, byte[] value, int position, Function<Decoder, T> reader) {
        try {
            Decoder in = new Decoder(value, position);
            T read = reader.apply(in);
            in.requireEnd();
            return read;
        } catch (RuntimeException e) {
            throw new IllegalStateException("The " + what + " under the key " + HEX.formatHex(key) + " is damaged: "
                    + e.getMessage(), e);
        }
    }
}
