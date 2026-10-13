package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Writes and reads a {@link PurgeRecord} in format version 1: the basis, the transaction time, the principal, the party
 * acted for, the reason, and the IDs of the entities and of the relationships that the purge removed. The log writes it
 * after a kind byte, and a snapshot file writes it in a frame of its own.
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
        out.writeAll(record.entities(), (encoder, id) -> encoder.writeText(id.value()));
        out.writeAll(record.relationships(), (encoder, id) -> encoder.writeText(id.value()));
    }

    static PurgeRecord read(Decoder in) {
        Basis basis = new Basis(in.readLong());
        Instant transactionTime = in.readInstant();
        String principal = in.readText();
        Optional<String> onBehalfOf = in.readOptionalText();
        String reason = in.readText();
        List<EntityId> entities = in.readAll(decoder -> new EntityId(decoder.readText()));
        List<RelationshipId> relationships = in.readAll(decoder -> new RelationshipId(decoder.readText()));
        return new PurgeRecord(basis, transactionTime, principal, onBehalfOf, reason, entities, relationships);
    }
}
