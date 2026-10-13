package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Writes and reads the effects of a data transaction and the histories of a snapshot file in format version 1: segments
 * with their effective periods and their values keyed by attribute identifier, the entities and relationships they belong
 * to, and for a relationship its ends. Values are written in the order of their attribute identifiers, so that equal
 * data gives equal bytes.
 */
final class EffectCodec {

    private EffectCodec() {
    }

    static void writeSegment(Encoder out, SegmentData segment) {
        out.writeInstant(segment.from());
        out.writeInstant(segment.end());
        out.writeInstant(segment.recordedAt());
        out.writeLong(segment.recordedBasis().value());
        out.writeInt(segment.values().size());
        segment.values().entrySet().stream()
                .sorted(Map.Entry.comparingByKey((first, second) -> Long.compare(first.value(), second.value())))
                .forEach(entry -> {
                    out.writeLong(entry.getKey().value());
                    ValueCodec.write(out, entry.getValue());
                });
    }

    static SegmentData readSegment(Decoder in) {
        Instant from = in.readInstant();
        Instant end = in.readInstant();
        Instant recordedAt = in.readInstant();
        Basis recordedBasis = new Basis(in.readLong());
        int count = in.readInt();
        if (count < 0 || count > in.remaining()) {
            throw new CorruptRecordException("The count " + count + " exceeds the " + in.remaining()
                    + " bytes that are left.");
        }
        Map<AttributeId, Value> values = new HashMap<>();
        for (int i = 0; i < count; i++) {
            AttributeId attribute = new AttributeId(in.readLong());
            if (values.put(attribute, ValueCodec.read(in)) != null) {
                throw new CorruptRecordException("The attribute " + attribute.value() + " has two values.");
            }
        }
        return new SegmentData(from, end, values, recordedAt, recordedBasis);
    }

    static void writeEffect(Encoder out, Effect effect) {
        TransactionCodec.writeId(out, effect.id());
        out.writeLong(effect.type().value());
        out.writeBoolean(effect.created());
        writeEnds(out, effect.id(), effect.source(), effect.target());
        out.writeAll(effect.segments(), EffectCodec::writeSegment);
    }

    static Effect readEffect(Decoder in) {
        Id id = TransactionCodec.readId(in);
        TypeId type = new TypeId(in.readLong());
        boolean created = in.readBoolean();
        Ends ends = readEnds(in, id);
        return new Effect(id, type, created, ends.source(), ends.target(), in.readAll(EffectCodec::readSegment));
    }

    static void writeHistory(Encoder out, History history) {
        TransactionCodec.writeId(out, history.id());
        out.writeLong(history.type().value());
        writeEnds(out, history.id(), history.source(), history.target());
        out.writeAll(history.versions(), EffectCodec::writeVersion);
    }

    static History readHistory(Decoder in) {
        Id id = TransactionCodec.readId(in);
        TypeId type = new TypeId(in.readLong());
        Ends ends = readEnds(in, id);
        return new History(id, type, ends.source(), ends.target(), in.readAll(EffectCodec::readVersion));
    }

    private static void writeVersion(Encoder out, History.Version version) {
        out.writeLong(version.basis().value());
        out.writeAll(version.segments(), EffectCodec::writeSegment);
    }

    private static History.Version readVersion(Decoder in) {
        Basis basis = new Basis(in.readLong());
        return new History.Version(basis, in.readAll(EffectCodec::readSegment));
    }

    /** The ends of a relationship; both are empty for an entity. */
    private record Ends(Optional<EntityId> source, Optional<EntityId> target) {
    }

    private static void writeEnds(Encoder out, Id id, Optional<EntityId> source, Optional<EntityId> target) {
        if (id instanceof RelationshipId) {
            out.writeText(source.orElseThrow(() -> new IllegalArgumentException("A relationship has no source."))
                    .value());
            out.writeText(target.orElseThrow(() -> new IllegalArgumentException("A relationship has no target."))
                    .value());
        } else if (source.isPresent() || target.isPresent()) {
            throw new IllegalArgumentException("An entity has no source or target.");
        }
    }

    private static Ends readEnds(Decoder in, Id id) {
        if (id instanceof RelationshipId) {
            EntityId source = new EntityId(in.readText());
            return new Ends(Optional.of(source), Optional.of(new EntityId(in.readText())));
        }
        return new Ends(Optional.empty(), Optional.empty());
    }
}
