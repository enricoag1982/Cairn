package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Writes and reads a {@link PurgeRecord} in format version 1: the basis, the transaction time, the principal, the party
 * acted for, the reason, and the numbers of entities and relationships that the purge removed. The log writes it after a
 * kind byte, followed by the digests of the removed IDs, and a snapshot file writes it in a frame of its own. Digests are
 * written as their 32 bytes each.
 */
final class PurgeCodec {

    private PurgeCodec() {
    }

    static void write(Encoder out, PurgeRecord record) {
        out.writeLong(record.basis().value());
        out.writeInstant(record.transactionTime());
        out.writeText(record.principal());
        out.writeOptionalText(record.onBehalfOf());
        out.writeText(record.reason());
        out.writeInt(record.entities());
        out.writeInt(record.relationships());
    }

    static PurgeRecord read(Decoder in) {
        Basis basis = new Basis(in.readLong());
        Instant transactionTime = in.readInstant();
        String principal = in.readText();
        Optional<String> onBehalfOf = in.readOptionalText();
        String reason = in.readText();
        int entities = in.readInt();
        int relationships = in.readInt();
        if (entities < 0 || relationships < 0) {
            throw new CorruptRecordException("A purge removes a number of entities and relationships that is not "
                    + "negative, but the record says " + entities + " and " + relationships + ".");
        }
        return new PurgeRecord(basis, transactionTime, principal, onBehalfOf, reason, entities, relationships);
    }

    static void writeDigest(Encoder out, String digest) {
        out.writeRaw(IdDigests.toBytes(digest));
    }

    static String readDigest(Decoder in) {
        byte[] bytes = new byte[IdDigests.LENGTH];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) in.readByte();
        }
        return IdDigests.fromBytes(bytes);
    }

    static void writeDigests(Encoder out, List<String> digests) {
        out.writeAll(digests, PurgeCodec::writeDigest);
    }

    static List<String> readDigests(Decoder in) {
        int count = in.readInt();
        if (count < 0 || (long) count * IdDigests.LENGTH > in.remaining()) {
            throw new CorruptRecordException("The count " + count + " of digests exceeds the " + in.remaining()
                    + " bytes that are left.");
        }
        List<String> digests = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            digests.add(readDigest(in));
        }
        return digests;
    }
}
