package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.KeyResolution;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Ref;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Writes and reads a {@link Transaction} as submitted, the parts of a {@link Receipt} that the transaction does not
 * hold (its IDs, and the resolutions of the unique keys that it names), and the entries of the transaction history, in
 * format version 1.
 */
final class TransactionCodec {

    private static final int CREATE = 1;
    private static final int RELATE = 2;
    private static final int PATCH = 3;
    private static final int REPLACE = 4;
    private static final int END_DATE = 5;

    private static final int TEMPORARY = 0;
    private static final int ENTITY = 1;
    private static final int RELATIONSHIP = 2;
    /** A unique key (ADR-0026): the entity type, the attribute and the value, which format version 1 gained. */
    private static final int UNIQUE_KEY = 3;

    private static final int NO_CHECK = 0;
    private static final int PER_ENTITY = 1;
    private static final int PER_ATTRIBUTE = 2;

    private TransactionCodec() {
    }

    // ------------------------------------------------------------------ transaction

    static void write(Encoder out, Transaction transaction) {
        out.writeText(transaction.principal());
        out.writeOptionalText(transaction.onBehalfOf());
        out.writeOptionalText(transaction.reason());
        out.writeOptionalText(transaction.idempotencyKey());
        out.writeAll(transaction.writes(), TransactionCodec::writeWrite);
    }

    static Transaction read(Decoder in) {
        String principal = in.readText();
        Optional<String> onBehalfOf = in.readOptionalText();
        Optional<String> reason = in.readOptionalText();
        Optional<String> idempotencyKey = in.readOptionalText();
        return new Transaction(principal, onBehalfOf, reason, idempotencyKey, in.readAll(TransactionCodec::readWrite));
    }

    private static void writeWrite(Encoder out, Write write) {
        switch (write) {
            case Write.Create create -> {
                out.writeByte(CREATE);
                writeOptionalTemporaryId(out, create.temporaryId());
                out.writeOptionalText(create.id().map(EntityId::value));
                out.writeText(create.entityType());
                writePeriod(out, create.period());
                writeValues(out, create.values());
            }
            case Write.Relate relate -> {
                out.writeByte(RELATE);
                writeOptionalTemporaryId(out, relate.temporaryId());
                out.writeOptionalText(relate.id().map(RelationshipId::value));
                out.writeText(relate.relationshipType());
                writeRef(out, relate.source());
                writeRef(out, relate.target());
                writePeriod(out, relate.period());
                writeValues(out, relate.values());
            }
            case Write.Patch patch -> {
                out.writeByte(PATCH);
                writeRef(out, patch.target());
                writePeriod(out, patch.period());
                writeValues(out, patch.set());
                out.writeAll(patch.clear(), Encoder::writeText);
                writeCheck(out, patch.check());
            }
            case Write.Replace replace -> {
                out.writeByte(REPLACE);
                writeRef(out, replace.target());
                writePeriod(out, replace.period());
                writeValues(out, replace.values());
                writeCheck(out, replace.check());
            }
            case Write.EndDate endDate -> {
                out.writeByte(END_DATE);
                writeRef(out, endDate.target());
                out.writeInstant(endDate.from());
                writeCheck(out, endDate.check());
            }
        }
    }

    private static Write readWrite(Decoder in) {
        int kind = in.readByte();
        return switch (kind) {
            case CREATE -> {
                Optional<TemporaryId> temporaryId = readOptionalTemporaryId(in);
                Optional<EntityId> id = in.readOptionalText().map(EntityId::new);
                String entityType = in.readText();
                EffectivePeriod period = readPeriod(in);
                yield new Write.Create(temporaryId, id, entityType, period, readValues(in));
            }
            case RELATE -> {
                Optional<TemporaryId> temporaryId = readOptionalTemporaryId(in);
                Optional<RelationshipId> id = in.readOptionalText().map(RelationshipId::new);
                String relationshipType = in.readText();
                Ref source = readRef(in);
                Ref target = readRef(in);
                EffectivePeriod period = readPeriod(in);
                yield new Write.Relate(temporaryId, id, relationshipType, source, target, period, readValues(in));
            }
            case PATCH -> {
                Ref target = readRef(in);
                EffectivePeriod period = readPeriod(in);
                Map<String, Value> set = readValues(in);
                Set<String> clear = new LinkedHashSet<>(in.readAll(Decoder::readText));
                yield new Write.Patch(target, period, set, clear, readCheck(in));
            }
            case REPLACE -> {
                Ref target = readRef(in);
                EffectivePeriod period = readPeriod(in);
                Map<String, Value> values = readValues(in);
                yield new Write.Replace(target, period, values, readCheck(in));
            }
            case END_DATE -> {
                Ref target = readRef(in);
                Instant from = in.readInstant();
                yield new Write.EndDate(target, from, readCheck(in));
            }
            default -> throw new CorruptRecordException("The write kind " + kind + " is not known.");
        };
    }

    private static void writeOptionalTemporaryId(Encoder out, Optional<TemporaryId> temporaryId) {
        out.writeOptionalText(temporaryId.map(TemporaryId::name));
    }

    private static Optional<TemporaryId> readOptionalTemporaryId(Decoder in) {
        return in.readOptionalText().map(TemporaryId::new);
    }

    private static void writeRef(Encoder out, Ref ref) {
        switch (ref) {
            case TemporaryId temporaryId -> {
                out.writeByte(TEMPORARY);
                out.writeText(temporaryId.name());
            }
            case EntityId id -> {
                out.writeByte(ENTITY);
                out.writeText(id.value());
            }
            case RelationshipId id -> {
                out.writeByte(RELATIONSHIP);
                out.writeText(id.value());
            }
            case UniqueKey key -> {
                out.writeByte(UNIQUE_KEY);
                writeKey(out, key);
            }
        }
    }

    private static void writeKey(Encoder out, UniqueKey key) {
        out.writeText(key.entityType());
        out.writeText(key.attribute());
        ValueCodec.write(out, key.value());
    }

    private static UniqueKey readKey(Decoder in) {
        String entityType = in.readText();
        String attribute = in.readText();
        return new UniqueKey(entityType, attribute, ValueCodec.read(in));
    }

    private static Ref readRef(Decoder in) {
        int kind = in.readByte();
        return switch (kind) {
            case TEMPORARY -> new TemporaryId(in.readText());
            case ENTITY -> new EntityId(in.readText());
            case RELATIONSHIP -> new RelationshipId(in.readText());
            case UNIQUE_KEY -> readKey(in);
            default -> throw new CorruptRecordException("The reference kind " + kind + " is not known.");
        };
    }

    private static void writePeriod(Encoder out, EffectivePeriod period) {
        out.writeInstant(period.from());
        out.writeOptionalInstant(period.until());
    }

    private static EffectivePeriod readPeriod(Decoder in) {
        Instant from = in.readInstant();
        return new EffectivePeriod(from, in.readOptionalInstant());
    }

    private static void writeValues(Encoder out, Map<String, Value> values) {
        out.writeInt(values.size());
        values.forEach((name, value) -> {
            out.writeText(name);
            ValueCodec.write(out, value);
        });
    }

    private static Map<String, Value> readValues(Decoder in) {
        int count = in.readInt();
        if (count < 0 || count > in.remaining()) {
            throw new CorruptRecordException("The count " + count + " exceeds the " + in.remaining()
                    + " bytes that are left.");
        }
        Map<String, Value> values = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String name = in.readText();
            values.put(name, ValueCodec.read(in));
        }
        return values;
    }

    private static void writeCheck(Encoder out, ConflictCheck check) {
        switch (check) {
            case ConflictCheck.None none -> out.writeByte(NO_CHECK);
            case ConflictCheck.PerEntity perEntity -> {
                out.writeByte(PER_ENTITY);
                out.writeLong(perEntity.readAt().value());
            }
            case ConflictCheck.PerAttribute perAttribute -> {
                out.writeByte(PER_ATTRIBUTE);
                out.writeLong(perAttribute.readAt().value());
            }
        }
    }

    private static ConflictCheck readCheck(Decoder in) {
        int kind = in.readByte();
        return switch (kind) {
            case NO_CHECK -> ConflictCheck.none();
            case PER_ENTITY -> ConflictCheck.perEntity(new Basis(in.readLong()));
            case PER_ATTRIBUTE -> ConflictCheck.perAttribute(new Basis(in.readLong()));
            default -> throw new CorruptRecordException("The conflict check kind " + kind + " is not known.");
        };
    }

    // ------------------------------------------------------------------ receipt

    /**
     * Writes what a receipt holds besides what its transaction holds, its basis and its transaction time: the temporary
     * IDs, and the IDs created and ended.
     */
    static void writeReceiptIds(Encoder out, Receipt receipt) {
        out.writeInt(receipt.temporaryIds().size());
        receipt.temporaryIds().forEach((temporaryId, id) -> {
            out.writeText(temporaryId.name());
            writeId(out, id);
        });
        out.writeAll(receipt.created(), TransactionCodec::writeId);
        out.writeAll(receipt.ended(), TransactionCodec::writeId);
    }

    /**
     * Writes what a receipt holds of the unique keys that its transaction named (ADR-0026): the position of each write,
     * the key and the ID of the entity it resolved to. A receipt without any writes nothing, so that the record of a
     * transaction that names no key is as it was before receipts held keys, and the keys are always the last of a
     * record: {@link #readReceiptKeys} reads them only if bytes are left.
     */
    static void writeReceiptKeys(Encoder out, Receipt receipt) {
        if (!receipt.keys().isEmpty()) {
            out.writeAll(receipt.keys(), (encoder, resolution) -> {
                encoder.writeInt(resolution.write());
                writeKey(encoder, resolution.key());
                encoder.writeText(resolution.entity().value());
            });
        }
    }

    /** Reads what {@link #writeReceiptKeys} wrote: empty, if no bytes are left, as in a record that has none. */
    static List<KeyResolution> readReceiptKeys(Decoder in) {
        if (in.remaining() == 0) {
            return List.of();
        }
        return in.readAll(decoder -> {
            int write = decoder.readInt();
            UniqueKey key = readKey(decoder);
            return new KeyResolution(write, key, new EntityId(decoder.readText()));
        });
    }

    /** A receipt as it is with the key resolutions that were read after it. */
    static Receipt withKeys(Receipt receipt, List<KeyResolution> keys) {
        return keys.isEmpty() ? receipt : new Receipt(receipt.basis(), receipt.transactionTime(),
                receipt.temporaryIds(), receipt.created(), receipt.ended(), keys, receipt.principal(),
                receipt.onBehalfOf(), receipt.reason(), receipt.idempotencyKey());
    }

    /** Reads what {@link #writeReceiptIds} wrote, and builds the receipt of a transaction. */
    static Receipt readReceipt(Decoder in, Basis basis, Instant transactionTime, Transaction transaction) {
        int count = in.readInt();
        if (count < 0 || count > in.remaining()) {
            throw new CorruptRecordException("The count " + count + " exceeds the " + in.remaining()
                    + " bytes that are left.");
        }
        Map<TemporaryId, Id> temporaryIds = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            TemporaryId temporaryId = new TemporaryId(in.readText());
            temporaryIds.put(temporaryId, readId(in));
        }
        var created = in.readAll(TransactionCodec::readId);
        var ended = in.readAll(TransactionCodec::readId);
        return new Receipt(basis, transactionTime, temporaryIds, created, ended, transaction.principal(),
                transaction.onBehalfOf(), transaction.reason(), transaction.idempotencyKey());
    }

    static void writeId(Encoder out, Id id) {
        switch (id) {
            case EntityId entity -> {
                out.writeByte(ENTITY);
                out.writeText(entity.value());
            }
            case RelationshipId relationship -> {
                out.writeByte(RELATIONSHIP);
                out.writeText(relationship.value());
            }
        }
    }

    static Id readId(Decoder in) {
        int kind = in.readByte();
        return switch (kind) {
            case ENTITY -> new EntityId(in.readText());
            case RELATIONSHIP -> new RelationshipId(in.readText());
            default -> throw new CorruptRecordException("The ID kind " + kind + " is not known.");
        };
    }

    // ------------------------------------------------------------------ transaction with an idempotency key

    /**
     * Writes a committed transaction with an idempotency key and its receipt, as a snapshot file and a store over a
     * key-value store keep them: the transaction as submitted, then the basis and the transaction time of the receipt,
     * the IDs that it holds, and the keys that it resolved.
     */
    static void writeKeyed(Encoder out, KeyedTransaction keyed) {
        write(out, keyed.transaction());
        out.writeLong(keyed.receipt().basis().value());
        out.writeInstant(keyed.receipt().transactionTime());
        writeReceiptIds(out, keyed.receipt());
        writeReceiptKeys(out, keyed.receipt());
    }

    /** Reads what {@link #writeKeyed} wrote, up to the end of the decoder. */
    static KeyedTransaction readKeyed(Decoder in) {
        Transaction transaction = read(in);
        Basis basis = new Basis(in.readLong());
        Instant transactionTime = in.readInstant();
        Receipt receipt = readReceipt(in, basis, transactionTime, transaction);
        return new KeyedTransaction(transaction, withKeys(receipt, readReceiptKeys(in)));
    }

    // ------------------------------------------------------------------ history entry

    static void writeEntry(Encoder out, TransactionRecord record) {
        out.writeLong(record.basis().value());
        out.writeInstant(record.transactionTime());
        out.writeText(record.principal());
        out.writeOptionalText(record.onBehalfOf());
        out.writeOptionalText(record.reason());
    }

    static TransactionRecord readEntry(Decoder in) {
        Basis basis = new Basis(in.readLong());
        Instant transactionTime = in.readInstant();
        String principal = in.readText();
        Optional<String> onBehalfOf = in.readOptionalText();
        return new TransactionRecord(basis, transactionTime, principal, onBehalfOf, in.readOptionalText());
    }
}
