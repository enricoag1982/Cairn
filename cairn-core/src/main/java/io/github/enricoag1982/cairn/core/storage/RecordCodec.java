package io.github.enricoag1982.cairn.core.storage;

/**
 * Encodes and decodes the records of the commit log (design D3 of {@code add-durable-commit-log}). A record in a frame
 * is a 4-byte length, a 4-byte CRC32C of those length bytes, a 4-byte CRC32C of the payload, and the payload, which
 * starts with a kind byte: a schema transaction, a data transaction or a purge. Each kind has a writer and a reader of
 * its own for format version 1; a later version adds readers beside these, selected by the version in the file's header.
 */
final class RecordCodec {

    /** The format version this class writes and reads. */
    static final int VERSION = 1;

    /** The kind byte of a schema transaction. */
    static final int SCHEMA = 1;

    /** The kind byte of a data transaction. */
    static final int DATA = 2;

    /** The kind byte of a purge. */
    static final int PURGE = 3;

    private RecordCodec() {
    }

    /**
     * Encodes a record in a frame, ready to be appended to a segment.
     *
     * @throws IllegalArgumentException if the record cannot be written, for example because it is larger than 2 GiB
     */
    static byte[] encode(LogRecord record) {
        Encoder out = Encoder.framed();
        switch (record) {
            case SchemaRecord schema -> {
                out.writeByte(SCHEMA);
                SchemaRecordWriter.write(out, schema);
            }
            case DataRecord data -> {
                out.writeByte(DATA);
                DataRecordWriter.write(out, data);
            }
            case PurgeLogRecord purge -> {
                out.writeByte(PURGE);
                PurgeCodec.write(out, purge.purge());
                PurgeCodec.writeDigests(out, purge.removed());
            }
        }
        return out.finishFrame();
    }

    /**
     * Decodes the payload of a record that a file of the given format version holds.
     *
     * @throws CorruptRecordException if the payload is not a well-formed record
     * @throws IllegalArgumentException if the format version is not known
     */
    static LogRecord decode(int version, byte[] payload) {
        if (version != VERSION) {
            throw new IllegalArgumentException("The format version " + version + " is not known.");
        }
        try {
            Decoder in = new Decoder(payload);
            int kind = in.readByte();
            LogRecord record = switch (kind) {
                case SCHEMA -> SchemaRecordReader.read(in);
                case DATA -> DataRecordReader.read(in);
                case PURGE -> new PurgeLogRecord(PurgeCodec.read(in), PurgeCodec.readDigests(in));
                default -> throw new CorruptRecordException("The record kind " + kind + " is not known.");
            };
            in.requireEnd();
            return record;
        } catch (CorruptRecordException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CorruptRecordException(e.toString(), e);
        }
    }

    /**
     * The basis of a record, read without decoding the rest, for skipping the records that a snapshot file holds.
     *
     * @throws CorruptRecordException if the payload is too short
     */
    static long peekBasis(byte[] payload) {
        Decoder in = new Decoder(payload);
        in.readByte();
        return in.readLong();
    }
}
