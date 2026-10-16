package io.github.enricoag1982.cairn.core.storage;

/**
 * Writes a {@link DataRecord} in format version 1: the basis, the transaction time, the transaction as submitted, the
 * receipt's temporary IDs and created and ended IDs, every changed entity and relationship with its new segments, and,
 * if the transaction names unique keys, what they resolved to, and, if it has a clearance, the clearance, which is the
 * last of the record.
 */
final class DataRecordWriter {

    private DataRecordWriter() {
    }

    /** Writes the record after its kind byte. */
    static void write(Encoder out, DataRecord record) {
        out.writeLong(record.basis().value());
        out.writeInstant(record.transactionTime());
        TransactionCodec.write(out, record.transaction());
        TransactionCodec.writeReceiptIds(out, record.receipt());
        out.writeAll(record.effects(), EffectCodec::writeEffect);
        TransactionCodec.writeReceiptKeys(out, record.receipt(), record.transaction().clearance().isPresent());
        TransactionCodec.writeClearance(out, record.transaction());
    }
}
