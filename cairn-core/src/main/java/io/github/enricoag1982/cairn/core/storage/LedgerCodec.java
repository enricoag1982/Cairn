package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.core.kv.KeyRange;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The keys and values of the engine's ledger on an ordered key-value store (ADR-0027, design D2 of
 * {@code refactor-storage-onto-key-value-store}). Keys start with one byte that names their kind, then have fixed-width
 * or length-prefixed parts, so that every range of interest is a prefix. Bases are 8-byte big-endian numbers, instants
 * are 12 bytes that sort in time order, schema identifiers are 8-byte big-endian numbers, and identifiers of entities
 * and relationships and the encoded values of unique attributes are written with a 4-byte length in front. An entity
 * and a relationship never have the same identifier, so a key names one by its identifier alone.
 *
 * <table class="striped">
 *   <caption>The kinds of keys</caption>
 *   <tr><th>Kind</th><th>Key after the kind byte</th><th>Value</th></tr>
 *   <tr><td>current</td><td>identifier</td><td>entity or relationship, type, for a relationship its ends, the basis of
 *       the last change, and the whole timeline with the basis that inserted each segment</td></tr>
 *   <tr><td>row</td><td>identifier, inserting basis, segment start</td><td>segment end, values, transaction time,
 *       recorded basis</td></tr>
 *   <tr><td>superseded</td><td>identifier, inserting basis, segment start</td><td>the basis that superseded the
 *       row</td></tr>
 *   <tr><td>type</td><td>entity type, entity identifier</td><td>the basis that added the entity</td></tr>
 *   <tr><td>end</td><td>entity identifier, relationship type, direction, relationship identifier</td><td>the basis
 *       that added the relationship</td></tr>
 *   <tr><td>unique</td><td>attribute, encoded value, identifier</td><td>the basis that added the value</td></tr>
 *   <tr><td>change</td><td>basis, identifier</td><td>entity or relationship, type, whether the transaction created it,
 *       and the effective periods in which it changed (ADR-0029, the change feed)</td></tr>
 * </table>
 *
 * <p>The values reuse the encodings of the commit log: segments and values are written as the log writes them, so that
 * they exist in one form. A unique attribute's value is written as the log writes it, except that numbers are first
 * made canonical, so that equal numbers of any numeric type have one key.
 */
public final class LedgerCodec {

    /** The kinds of keys, which the first byte of a key names. */
    public enum Kind {
        /** The current timeline of an entity or relationship. */
        CURRENT(1),
        /** A segment that a transaction inserted. */
        ROW(2),
        /** The marker that says which transaction superseded a row. */
        SUPERSEDED(3),
        /** An entity of a type. */
        TYPE(4),
        /** A relationship at one of its ends. */
        END(5),
        /** An entity or relationship that has or had a value of a unique attribute. */
        UNIQUE(6),
        /** An entity or relationship that a basis changed: the index of the change feed. */
        CHANGE(7);

        private final byte prefix;

        Kind(int prefix) {
            this.prefix = (byte) prefix;
        }

        /**
         * The first byte of the keys of this kind.
         *
         * @return the byte
         */
        public byte prefix() {
            return prefix;
        }
    }

    /** The direction byte of the end index entry of a relationship's source. */
    private static final byte AT_SOURCE = 1;
    /** The direction byte of the end index entry of a relationship's target. */
    private static final byte AT_TARGET = 2;

    private LedgerCodec() {
    }

    /**
     * Tells the kind of a key.
     *
     * @param key the key
     * @return the kind
     * @throws CorruptRecordException if the key starts with a byte that is no kind
     */
    public static Kind kindOf(byte[] key) {
        for (Kind kind : Kind.values()) {
            if (key.length > 0 && key[0] == kind.prefix) {
                return kind;
            }
        }
        throw new CorruptRecordException("The key does not start with the byte of a kind.");
    }

    // ------------------------------------------------------------------ keys

    private static Encoder key(Kind kind) {
        Encoder out = Encoder.plain();
        out.writeByte(kind.prefix);
        return out;
    }

    private static Encoder idKey(Kind kind, String id) {
        Encoder out = key(kind);
        out.writeText(id);
        return out;
    }

    /**
     * The key of the current timeline of an entity or relationship.
     *
     * @param id the identifier
     * @return the key
     */
    public static byte[] currentKey(String id) {
        return idKey(Kind.CURRENT, id).toByteArray();
    }

    /**
     * The keys of every current timeline.
     *
     * @return the range
     */
    public static KeyRange currentKeys() {
        return KeyRange.prefix(new byte[] {Kind.CURRENT.prefix});
    }

    /**
     * Reads the identifier out of a current key.
     *
     * @param key the key
     * @return the identifier
     */
    public static String idOfCurrent(byte[] key) {
        return new Decoder(key, 1).readText();
    }

    private static Encoder rowId(Kind kind, String id, Basis inserted, Instant start) {
        Encoder out = idKey(kind, id);
        out.writeLong(inserted.value());
        out.writeLong(start.getEpochSecond() ^ Long.MIN_VALUE);
        out.writeInt(start.getNano());
        return out;
    }

    /**
     * The key of the row of a segment.
     *
     * @param id       the identifier of the entity or relationship
     * @param inserted the basis of the transaction that inserted the segment
     * @param start    the start of the segment's effective period
     * @return the key
     */
    public static byte[] rowKey(String id, Basis inserted, Instant start) {
        return rowId(Kind.ROW, id, inserted, start).toByteArray();
    }

    /**
     * The key of the marker that says a row was superseded: the key of the row, with the kind of a marker.
     *
     * @param id       the identifier of the entity or relationship
     * @param inserted the basis of the transaction that inserted the segment
     * @param start    the start of the segment's effective period
     * @return the key
     */
    public static byte[] supersededKey(String id, Basis inserted, Instant start) {
        return rowId(Kind.SUPERSEDED, id, inserted, start).toByteArray();
    }

    /**
     * The keys of the rows of an entity or relationship that were inserted at or below a basis.
     *
     * @param id    the identifier
     * @param upTo  the highest inserting basis
     * @return the range
     */
    public static KeyRange rows(String id, Basis upTo) {
        return upTo(Kind.ROW, id, upTo);
    }

    /**
     * The keys of the markers of an entity or relationship whose rows were inserted at or below a basis.
     *
     * @param id    the identifier
     * @param upTo  the highest inserting basis
     * @return the range
     */
    public static KeyRange markers(String id, Basis upTo) {
        return upTo(Kind.SUPERSEDED, id, upTo);
    }

    private static KeyRange upTo(Kind kind, String id, Basis upTo) {
        byte[] prefix = idKey(kind, id).toByteArray();
        if (upTo.value() == Long.MAX_VALUE) {
            return KeyRange.prefix(prefix);
        }
        Encoder end = idKey(kind, id);
        end.writeLong(upTo.value() + 1);
        return KeyRange.between(prefix, end.toByteArray());
    }

    /**
     * What the key of a row or marker names.
     *
     * @param id       the identifier of the entity or relationship
     * @param inserted the basis that inserted the segment
     * @param start    the start of the segment
     */
    public record RowKey(String id, Basis inserted, Instant start) {

        /**
         * Rejects null components.
         */
        public RowKey {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(inserted, "inserted");
            Objects.requireNonNull(start, "start");
        }
    }

    /**
     * Reads a row key or a marker key.
     *
     * @param key the key
     * @return what it names
     */
    public static RowKey parseRowKey(byte[] key) {
        Decoder in = new Decoder(key, 1);
        String id = in.readText();
        Basis inserted = new Basis(in.readLong());
        long seconds = in.readLong() ^ Long.MIN_VALUE;
        int nanos = in.readInt();
        in.requireEnd();
        return new RowKey(id, inserted, Instant.ofEpochSecond(seconds, nanos));
    }

    /**
     * The key of an entity in the index of the entities of a type.
     *
     * @param type   the entity's type
     * @param entity the entity
     * @return the key
     */
    public static byte[] typeKey(TypeId type, EntityId entity) {
        Encoder out = key(Kind.TYPE);
        out.writeLong(type.value());
        out.writeText(entity.value());
        return out.toByteArray();
    }

    /**
     * The keys of the entities of a type.
     *
     * @param type the type, not its subtypes
     * @return the range
     */
    public static KeyRange typeKeys(TypeId type) {
        Encoder out = key(Kind.TYPE);
        out.writeLong(type.value());
        return KeyRange.prefix(out.toByteArray());
    }

    /**
     * Reads the entity out of a key of the index of types.
     *
     * @param key the key
     * @return the entity's identifier
     */
    public static String entityOfTypeKey(byte[] key) {
        Decoder in = new Decoder(key, 1);
        in.readLong();
        return in.readText();
    }

    private static Encoder endPrefix(String entity) {
        return idKey(Kind.END, entity);
    }

    /**
     * The key of a relationship in the index of the relationships of an entity, at one of the relationship's ends.
     *
     * @param end          the entity at the end
     * @param type         the relationship's type
     * @param forward      {@code true} if the entity is the relationship's source, {@code false} if it is its target
     * @param relationship the relationship's identifier
     * @return the key
     */
    public static byte[] endKey(String end, TypeId type, boolean forward, String relationship) {
        Encoder out = endPrefix(end);
        out.writeLong(type.value());
        out.writeByte(forward ? AT_SOURCE : AT_TARGET);
        out.writeText(relationship);
        return out.toByteArray();
    }

    /**
     * The keys of every relationship that has an entity at an end, of any type and in both directions.
     *
     * @param end the entity
     * @return the range
     */
    public static KeyRange endKeys(String end) {
        return KeyRange.prefix(endPrefix(end).toByteArray());
    }

    /**
     * The keys of the relationships of one type that have an entity at an end, in one direction.
     *
     * @param end     the entity
     * @param type    the relationship type, not its subtypes
     * @param forward {@code true} for the relationships that have the entity as their source
     * @return the range
     */
    public static KeyRange endKeys(String end, TypeId type, boolean forward) {
        Encoder out = endPrefix(end);
        out.writeLong(type.value());
        out.writeByte(forward ? AT_SOURCE : AT_TARGET);
        return KeyRange.prefix(out.toByteArray());
    }

    /**
     * What a key of the index of relationship ends names.
     *
     * @param end          the entity at the end
     * @param type         the relationship's type
     * @param forward      whether the entity is the relationship's source
     * @param relationship the relationship's identifier
     */
    public record EndKey(String end, TypeId type, boolean forward, String relationship) {

        /**
         * Rejects null components.
         */
        public EndKey {
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(relationship, "relationship");
        }
    }

    /**
     * Reads a key of the index of relationship ends.
     *
     * @param key the key
     * @return what it names
     */
    public static EndKey parseEndKey(byte[] key) {
        Decoder in = new Decoder(key, 1);
        String end = in.readText();
        TypeId type = new TypeId(in.readLong());
        int direction = in.readByte();
        if (direction != AT_SOURCE && direction != AT_TARGET) {
            throw new CorruptRecordException("The direction " + direction + " of an end key is not known.");
        }
        return new EndKey(end, type, direction == AT_SOURCE, in.readText());
    }

    /**
     * The key of an entity or relationship in the index of the values of a unique attribute.
     *
     * @param attribute the unique attribute
     * @param value     the value
     * @param id        the identifier of the entity or relationship that has or had it
     * @return the key
     */
    public static byte[] uniqueKey(AttributeId attribute, Value value, String id) {
        Encoder out = uniquePrefix(attribute, value);
        out.writeText(id);
        return out.toByteArray();
    }

    /**
     * The keys of the entities or relationships that have or had a value of a unique attribute. Numbers that are equal
     * have the same keys, whatever their numeric type.
     *
     * @param attribute the unique attribute
     * @param value     the value
     * @return the range
     */
    public static KeyRange uniqueKeys(AttributeId attribute, Value value) {
        return KeyRange.prefix(uniquePrefix(attribute, value).toByteArray());
    }

    private static Encoder uniquePrefix(AttributeId attribute, Value value) {
        Encoder encoded = Encoder.plain();
        ValueCodec.write(encoded, canonical(value));
        Encoder out = key(Kind.UNIQUE);
        out.writeLong(attribute.value());
        out.writeBytes(encoded.toByteArray());
        return out;
    }

    /** Numbers of every numeric type become one decimal of the shortest scale, so that equal numbers are equal. */
    private static Value canonical(Value value) {
        BigDecimal number = switch (value) {
            case Value.IntValue(int whole) -> BigDecimal.valueOf(whole);
            case Value.LongValue(long whole) -> BigDecimal.valueOf(whole);
            case Value.DecimalValue(BigDecimal decimal) -> decimal;
            default -> null;
        };
        if (number == null) {
            return value;
        }
        return Value.of(number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros());
    }

    /**
     * Reads the identifier out of a key of the index of unique values.
     *
     * @param key the key
     * @return the identifier of the entity or relationship
     */
    public static String idOfUniqueKey(byte[] key) {
        Decoder in = new Decoder(key, 1);
        in.readLong();
        in.readBytes();
        return in.readText();
    }


    /**
     * The key of an entity or relationship in the index of what each basis changed.
     *
     * @param basis the basis of the transaction that changed it
     * @param id    the identifier of the entity or relationship
     * @return the key
     */
    public static byte[] changeKey(Basis basis, String id) {
        Encoder out = key(Kind.CHANGE);
        out.writeLong(basis.value());
        out.writeText(id);
        return out.toByteArray();
    }

    /**
     * The keys of what the bases after one basis, up to and including another, changed.
     *
     * @param after the basis before the first one that is wanted; its changes are not in the range
     * @param upTo  the last basis that is wanted
     * @return the range, which is empty if {@code upTo} is not above {@code after}
     */
    public static KeyRange changeKeys(Basis after, Basis upTo) {
        Encoder from = key(Kind.CHANGE);
        from.writeLong(after.value() + 1);
        if (upTo.value() == Long.MAX_VALUE) {
            return KeyRange.between(from.toByteArray(), KeyRange.prefix(new byte[] {Kind.CHANGE.prefix}).to());
        }
        Encoder to = key(Kind.CHANGE);
        to.writeLong(upTo.value() + 1);
        return KeyRange.between(from.toByteArray(), to.toByteArray());
    }

    /**
     * What a key of the index of changes names.
     *
     * @param basis the basis of the transaction that changed the entity or relationship
     * @param id    the identifier of the entity or relationship
     */
    public record ChangeKey(Basis basis, String id) {

        /**
         * Rejects null components.
         */
        public ChangeKey {
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * Reads a key of the index of changes.
     *
     * @param key the key
     * @return what it names
     */
    public static ChangeKey parseChangeKey(byte[] key) {
        Decoder in = new Decoder(key, 1);
        Basis basis = new Basis(in.readLong());
        String id = in.readText();
        in.requireEnd();
        return new ChangeKey(basis, id);
    }

    // ------------------------------------------------------------------ values

    /**
     * A segment of a current timeline, with the basis that inserted it.
     *
     * @param insertedAt the basis of the transaction that inserted the segment as a row
     * @param segment    the segment
     */
    public record Placed(Basis insertedAt, SegmentData segment) {

        /**
         * Rejects null components.
         */
        public Placed {
            Objects.requireNonNull(insertedAt, "insertedAt");
            Objects.requireNonNull(segment, "segment");
        }
    }

    /**
     * What a current key holds.
     *
     * @param relationship whether it is a relationship; an entity otherwise
     * @param type         the identifier of its type
     * @param source       the source of a relationship; empty for an entity
     * @param target       the target of a relationship; empty for an entity
     * @param lastBasis    the basis of the last transaction that changed it
     * @param segments     the whole timeline, in order, each segment with the basis that inserted it
     */
    public record CurrentValue(boolean relationship, TypeId type, Optional<EntityId> source, Optional<EntityId> target,
            Basis lastBasis, List<Placed> segments) {

        /**
         * Rejects null components and copies the segments.
         */
        public CurrentValue {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(lastBasis, "lastBasis");
            segments = List.copyOf(segments);
            if (relationship != source.isPresent() || relationship != target.isPresent()) {
                throw new IllegalArgumentException("A relationship has a source and a target, and an entity has none.");
            }
        }
    }

    /**
     * Writes the value of a current key.
     *
     * @param current what the key holds
     * @return the value
     */
    public static byte[] encodeCurrent(CurrentValue current) {
        Encoder out = Encoder.plain();
        out.writeBoolean(current.relationship());
        out.writeLong(current.type().value());
        if (current.relationship()) {
            out.writeText(current.source().orElseThrow().value());
            out.writeText(current.target().orElseThrow().value());
        }
        out.writeLong(current.lastBasis().value());
        out.writeAll(current.segments(), (encoder, placed) -> {
            encoder.writeLong(placed.insertedAt().value());
            EffectCodec.writeSegment(encoder, placed.segment());
        });
        return out.toByteArray();
    }

    /**
     * Reads the value of a current key.
     *
     * @param value the value
     * @return what the key holds
     * @throws CorruptRecordException if the value is not one that {@link #encodeCurrent} wrote
     */
    public static CurrentValue decodeCurrent(byte[] value) {
        Decoder in = new Decoder(value);
        boolean relationship = in.readBoolean();
        TypeId type = new TypeId(in.readLong());
        Optional<EntityId> source = Optional.empty();
        Optional<EntityId> target = Optional.empty();
        if (relationship) {
            source = Optional.of(new EntityId(in.readText()));
            target = Optional.of(new EntityId(in.readText()));
        }
        Basis lastBasis = new Basis(in.readLong());
        List<Placed> segments = in.readAll(decoder -> {
            Basis insertedAt = new Basis(decoder.readLong());
            return new Placed(insertedAt, EffectCodec.readSegment(decoder));
        });
        in.requireEnd();
        return new CurrentValue(relationship, type, source, target, lastBasis, segments);
    }

    /**
     * Tells from the value of a current key whether it is a relationship, without reading the rest.
     *
     * @param value the value
     * @return {@code true} for a relationship, {@code false} for an entity
     */
    public static boolean isRelationship(byte[] value) {
        return new Decoder(value).readBoolean();
    }

    /**
     * Writes the value of a row: the segment without its start, which the key holds.
     *
     * @param segment the segment
     * @return the value
     */
    public static byte[] encodeRow(SegmentData segment) {
        Encoder out = Encoder.plain();
        EffectCodec.writeSegmentBody(out, segment);
        return out.toByteArray();
    }

    /**
     * Reads the value of a row.
     *
     * @param start the start of the segment, from the row's key
     * @param value the value
     * @return the segment
     */
    public static SegmentData decodeRow(Instant start, byte[] value) {
        Decoder in = new Decoder(value);
        SegmentData segment = EffectCodec.readSegmentBody(in, start);
        in.requireEnd();
        return segment;
    }

    /**
     * What an entry of the index of changes holds.
     *
     * @param relationship whether it is a relationship; an entity otherwise
     * @param type         the identifier of its type
     * @param created      whether the transaction created it
     * @param periods      the effective periods in which it changed, in order, not overlapping and not touching
     */
    public record ChangeValue(boolean relationship, TypeId type, boolean created, List<EffectivePeriod> periods) {

        /**
         * Rejects null components and copies the periods.
         */
        public ChangeValue {
            Objects.requireNonNull(type, "type");
            periods = List.copyOf(periods);
        }
    }

    /**
     * Writes the value of an entry of the index of changes.
     *
     * @param change what the entry holds
     * @return the value
     */
    public static byte[] encodeChange(ChangeValue change) {
        Encoder out = Encoder.plain();
        out.writeBoolean(change.relationship());
        out.writeLong(change.type().value());
        out.writeBoolean(change.created());
        out.writeAll(change.periods(), (encoder, period) -> {
            encoder.writeInstant(period.from());
            encoder.writeOptionalInstant(period.until());
        });
        return out.toByteArray();
    }

    /**
     * Reads the value of an entry of the index of changes.
     *
     * @param value the value
     * @return what the entry holds
     * @throws CorruptRecordException if the value is not one that {@link #encodeChange} wrote
     */
    public static ChangeValue decodeChange(byte[] value) {
        Decoder in = new Decoder(value);
        boolean relationship = in.readBoolean();
        TypeId type = new TypeId(in.readLong());
        boolean created = in.readBoolean();
        List<EffectivePeriod> periods = in.readAll(decoder -> new EffectivePeriod(decoder.readInstant(),
                decoder.readOptionalInstant()));
        in.requireEnd();
        return new ChangeValue(relationship, type, created, periods);
    }

    /**
     * Writes the value of a marker or an index entry: a basis.
     *
     * @param basis the basis
     * @return the value
     */
    public static byte[] encodeBasis(Basis basis) {
        Encoder out = Encoder.plain();
        out.writeLong(basis.value());
        return out.toByteArray();
    }

    /**
     * Reads the value of a marker or an index entry.
     *
     * @param value the value
     * @return the basis
     */
    public static Basis decodeBasis(byte[] value) {
        Decoder in = new Decoder(value);
        Basis basis = new Basis(in.readLong());
        in.requireEnd();
        return basis;
    }
}
