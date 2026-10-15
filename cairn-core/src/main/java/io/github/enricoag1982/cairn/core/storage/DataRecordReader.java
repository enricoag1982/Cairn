package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.List;

/** Reads a {@link DataRecord} that {@link DataRecordWriter} wrote in format version 1. */
final class DataRecordReader {

    private DataRecordReader() {
    }

    /** Reads the record after its kind byte. */
    static DataRecord read(Decoder in) {
        Basis basis = new Basis(in.readLong());
        Instant transactionTime = in.readInstant();
        Transaction transaction = TransactionCodec.read(in);
        Receipt receipt = TransactionCodec.readReceipt(in, basis, transactionTime, transaction);
        List<Effect> effects = in.readAll(EffectCodec::readEffect);
        return new DataRecord(transaction, TransactionCodec.withKeys(receipt, TransactionCodec.readReceiptKeys(in)),
                effects);
    }
}
