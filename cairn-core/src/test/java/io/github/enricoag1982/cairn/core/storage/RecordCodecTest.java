package io.github.enricoag1982.cairn.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RelationshipKind;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Submission;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

/**
 * The codec of format version 1 (design D3 of {@code add-durable-commit-log}): every field of a record reads back equal,
 * and what is not a record is refused.
 */
class RecordCodecTest {

    private static final Instant T1 = Instant.parse("2026-01-05T09:00:00.123456Z");
    private static final Instant T2 = Instant.parse("2026-03-01T00:00:00Z");

    private static LogRecord roundTrip(LogRecord record) {
        byte[] frame = RecordCodec.encode(record);
        ByteBuffer header = ByteBuffer.wrap(frame);
        byte[] payload = Arrays.copyOfRange(frame, Encoder.FRAME_HEADER, frame.length);
        CRC32C lengthChecksum = new CRC32C();
        lengthChecksum.update(frame, 0, 4);
        CRC32C payloadChecksum = new CRC32C();
        payloadChecksum.update(payload);
        assertThat(header.getInt()).as("the length of the payload").isEqualTo(payload.length);
        assertThat(header.getInt()).as("the CRC32C of the length").isEqualTo((int) lengthChecksum.getValue());
        assertThat(header.getInt()).as("the CRC32C of the payload").isEqualTo((int) payloadChecksum.getValue());
        assertThat(RecordCodec.peekBasis(payload)).isEqualTo(record.basis().value());
        return RecordCodec.decode(RecordCodec.VERSION, payload);
    }

    private static SchemaRecord schemaRecord(SchemaDefinition definition) {
        return new SchemaRecord(new Basis(3), T1, new Submission("agent:modeler", Optional.of("hr"),
                Optional.of("add the model")), new Origin("reference-model", "1.0.0"), definition,
                new SchemaIdentifiers(List.of(new SchemaIdentifiers.TypeAssignment("Party", new TypeId(4)),
                        new SchemaIdentifiers.TypeAssignment("Named", new TypeId(5))),
                        List.of(new SchemaIdentifiers.AttributeAssignment("Named", "name", new AttributeId(6))), 7));
    }

    // ------------------------------------------------------------------ schema transactions

    @Test
    void theReferenceModelReadsBackEqual() {
        SchemaRecord record = schemaRecord(ReferenceModel.definition());

        assertThat(roundTrip(record)).isEqualTo(record);
    }

    @Test
    void aSchemaRecordWithoutASubmissionDetailOrIdentifiersReadsBackEqual() {
        SchemaRecord record = new SchemaRecord(new Basis(1), T2, Submission.unknown(), new Origin("o", "1"),
                SchemaDefinition.EMPTY, new SchemaIdentifiers(List.of(), List.of(), 1));

        assertThat(roundTrip(record)).isEqualTo(record);
    }

    /** An attribute with every field set, of a value type with a range that fits it. */
    private static AttributeDefinition attribute(String name, ValueType type, Range range, List<Value> examples) {
        return new AttributeDefinition(name, type, true, true, Optional.ofNullable(range), List.of("old" + name, "older"),
                true, Optional.of("The " + name + " with ünïcode and 😀."), examples, List.of("A", "B"),
                Optional.of("[A-Z]+"), OptionalInt.of(1), OptionalInt.of(12));
    }

    private static SchemaDefinition everythingSet() {
        List<AttributeDefinition> attributes = List.of(
                attribute("text", ValueType.STRING, null, List.of(Value.of("A"), Value.of(""))),
                attribute("flag", ValueType.BOOLEAN, null, List.of(Value.of(true), Value.of(false))),
                attribute("small", ValueType.INT, Range.between(Bound.inclusive(Value.of(-5)),
                        Bound.exclusive(Value.of(Integer.MAX_VALUE))), List.of(Value.of(0), Value.of(-1))),
                attribute("big", ValueType.LONG, Range.atLeast(Bound.exclusive(Value.of(Long.MIN_VALUE))),
                        List.of(Value.of(Long.MAX_VALUE))),
                attribute("amount", ValueType.DECIMAL, Range.between(Bound.exclusive(Value.of(new BigDecimal("0"))),
                        Bound.inclusive(Value.of(new BigDecimal("100.50")))),
                        List.of(Value.of(new BigDecimal("1.0")), Value.of(new BigDecimal("1.00")),
                                Value.of(new BigDecimal("-12345678901234567890.123456789")))),
                attribute("day", ValueType.DATE, Range.atMost(Bound.inclusive(Value.of(LocalDate.of(2099, 12, 31)))),
                        List.of(Value.of(LocalDate.of(1970, 1, 1)), Value.of(LocalDate.of(1, 1, 1)))),
                attribute("moment", ValueType.INSTANT, Range.between(
                        Bound.inclusive(Value.of(Instant.parse("2026-01-01T00:00:00.000000001Z"))),
                        Bound.exclusive(Value.of(Instant.parse("2027-01-01T00:00:00Z")))),
                        List.of(Value.of(T1), Value.of(Instant.parse("1969-07-20T20:17:40Z")))));
        TraitDefinition trait = new TraitDefinition("Named", attributes.subList(0, 2), List.of("Labelled", "Titled"),
                true, Optional.of("Things with names"));
        EntityTypeDefinition parent = new EntityTypeDefinition("Party", true, Optional.empty(), List.of("Named"),
                attributes, List.of("Actor"), true, Optional.of("Somebody"), List.of(new RuleReference("rule-a", 2)));
        EntityTypeDefinition child = new EntityTypeDefinition("Person", false, Optional.of("Party"),
                List.of("Named", "Other"), attributes.subList(2, 4), List.of("Human", "Individual"), false,
                Optional.of("A person"), List.of(new RuleReference("rule-b", 1), new RuleReference("rule-c", 3)));
        RelationshipTypeDefinition owns = new RelationshipTypeDefinition("Owns", "Party", "Party",
                Multiplicity.ZERO_OR_MORE, Multiplicity.EXACTLY_ONE, "ownedBy", List.of("ownedFrom", "heldBy"),
                RelationshipKind.COMPOSITION, true, Optional.of("Link"), attributes.subList(4, 7),
                List.of("Possesses", "Holds"), true, Optional.of("Owning"), List.of(new RuleReference("rule-d", 1)));
        RelationshipTypeDefinition link = new RelationshipTypeDefinition("Link", "Party", "Person",
                Multiplicity.ZERO_OR_ONE, Multiplicity.ONE_OR_MORE, "linkedFrom", List.of(),
                RelationshipKind.ASSOCIATION, false, Optional.empty(), List.of(), List.of(), false, Optional.empty(),
                List.of());
        return new SchemaDefinition(List.of(parent, child), List.of(trait), List.of(owns, link));
    }

    @Test
    void aDefinitionWithEveryOptionalFieldSetReadsBackEqual() {
        SchemaDefinition definition = everythingSet();
        SchemaRecord record = schemaRecord(definition);

        SchemaRecord read = (SchemaRecord) roundTrip(record);

        assertThat(read).isEqualTo(record);
        assertThat(read.definition()).isEqualTo(definition);
    }

    @Test
    void theDefinitionOfTheTestSetsEveryComponentOfEveryRecordSoThatTheRoundTripChecksThem() {
        SchemaDefinition definition = everythingSet();

        Map<Class<?>, List<Object>> instances = recordsIn(definition);

        assertThat(instances.keySet()).contains(SchemaDefinition.class, EntityTypeDefinition.class,
                TraitDefinition.class, RelationshipTypeDefinition.class, AttributeDefinition.class,
                RuleReference.class, Range.class, Bound.class, Multiplicity.class);
        assertEveryComponentIsSetSomewhere(instances);
        assertThat(instances.keySet()).as("every kind of value").containsAll(
                Arrays.asList(Value.class.getPermittedSubclasses()));
        assertThat(definition.relationshipTypes()).extracting(RelationshipTypeDefinition::kind)
                .containsExactlyInAnyOrder(RelationshipKind.values());
        assertThat(definition.entityTypes().getFirst().attributes()).extracting(AttributeDefinition::valueType)
                .containsExactlyInAnyOrder(ValueType.values());
        assertThat(instances.get(Range.class)).as("ranges with a lower bound only, an upper bound only, and both")
                .extracting(range -> ((Range) range).lower().isPresent() + "/" + ((Range) range).upper().isPresent())
                .contains("true/false", "false/true", "true/true");
        assertThat(instances.get(Bound.class)).extracting(bound -> ((Bound) bound).inclusive())
                .contains(true, false);
    }

    /** Every record in an object graph, by its class, found through nested records, collections and optionals. */
    private static Map<Class<?>, List<Object>> recordsIn(Object root) {
        Map<Class<?>, List<Object>> instances = new LinkedHashMap<>();
        collect(root, instances);
        return instances;
    }

    private static void collect(Object value, Map<Class<?>, List<Object>> instances) {
        switch (value) {
            case Collection<?> collection -> collection.forEach(element -> collect(element, instances));
            case Map<?, ?> map -> map.forEach((key, element) -> {
                collect(key, instances);
                collect(element, instances);
            });
            case Optional<?> optional -> optional.ifPresent(element -> collect(element, instances));
            case Record record -> {
                instances.computeIfAbsent(record.getClass(), type -> new ArrayList<>()).add(record);
                for (RecordComponent component : record.getClass().getRecordComponents()) {
                    collect(component(record, component), instances);
                }
            }
            default -> { }
        }
    }

    private static Object component(Record record, RecordComponent component) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Fails if a component of a record is empty, false or absent in every instance, because a round trip cannot show
     * that such a field is written.
     */
    private static void assertEveryComponentIsSetSomewhere(Map<Class<?>, List<Object>> instances) {
        instances.forEach((type, records) -> {
            for (RecordComponent component : type.getRecordComponents()) {
                assertThat(records).as(type.getSimpleName() + "." + component.getName() + " is set in some instance")
                        .anyMatch(record -> isSet(component((Record) record, component)));
            }
        });
    }

    private static boolean isSet(Object value) {
        return switch (value) {
            case Collection<?> collection -> !collection.isEmpty();
            case Map<?, ?> map -> !map.isEmpty();
            case Optional<?> optional -> optional.isPresent();
            case OptionalInt optional -> optional.isPresent();
            case Boolean bool -> bool;
            default -> true;
        };
    }

    // ------------------------------------------------------------------ data transactions

    private static Transaction everyWrite() {
        Map<String, Value> values = new LinkedHashMap<>();
        values.put("name", Value.of("ACME"));
        values.put("active", Value.of(true));
        values.put("count", Value.of(7));
        values.put("total", Value.of(-9_000_000_000L));
        values.put("share", Value.of(new BigDecimal("60.50")));
        values.put("since", Value.of(LocalDate.of(2026, 2, 1)));
        values.put("seen", Value.of(Instant.parse("2026-02-01T10:00:00.000000007Z")));
        return Transaction.by("agent:onboarding").onBehalfOf("sales").reason("Onboarding of ACME")
                .idempotencyKey("onboard-1")
                .write(new Write.Create(Optional.of(new TemporaryId("acme")), Optional.of(new EntityId("e-1")),
                        "Organization", EffectivePeriod.between(T2, T1.plusSeconds(86_400 * 400L)), values))
                .write(new Write.Relate(Optional.of(new TemporaryId("owns")), Optional.of(new RelationshipId("r-1")),
                        "Owns", new TemporaryId("acme"), new EntityId("e-2"), EffectivePeriod.from(T2), values))
                .write(new Write.Create(Optional.empty(), Optional.empty(), "Person", EffectivePeriod.from(T1),
                        Map.of()))
                .write(new Write.Relate(Optional.empty(), Optional.empty(), "Employs", new EntityId("e-1"),
                        new TemporaryId("p"), EffectivePeriod.from(T1), Map.of()))
                .write(new Write.Patch(new RelationshipId("r-1"), EffectivePeriod.from(T2), values,
                        new LinkedHashSet<>(List.of("lei", "other")), ConflictCheck.perAttribute(new Basis(4))))
                .write(new Write.Patch(new EntityId("e-1"), EffectivePeriod.from(T2), Map.of(), Set.of(),
                        ConflictCheck.none()))
                .write(new Write.Replace(new EntityId("e-1"), EffectivePeriod.between(T1, T2), values,
                        ConflictCheck.perEntity(new Basis(3))))
                .write(new Write.EndDate(new TemporaryId("acme"), T2, ConflictCheck.none()))
                .write(new Write.EndDate(new RelationshipId("r-1"), T2, ConflictCheck.perEntity(new Basis(1))))
                .build();
    }

    private static SegmentData segment(Instant from, Instant end, Instant recordedAt, long recordedBasis,
            Map<AttributeId, Value> values) {
        return new SegmentData(from, end, values, recordedAt, new Basis(recordedBasis));
    }

    @Test
    void aDataRecordWithEveryKindOfWriteAndEveryFieldSetReadsBackEqual() {
        Transaction transaction = everyWrite();
        Map<TemporaryId, Id> temporaryIds = new LinkedHashMap<>();
        temporaryIds.put(new TemporaryId("acme"), new EntityId("e-1"));
        temporaryIds.put(new TemporaryId("owns"), new RelationshipId("r-1"));
        Receipt receipt = new Receipt(new Basis(9), T1, temporaryIds,
                List.of(new EntityId("e-1"), new RelationshipId("r-1")), List.of(new RelationshipId("r-2")),
                transaction.principal(), transaction.onBehalfOf(), transaction.reason(), transaction.idempotencyKey());
        Map<AttributeId, Value> values = new LinkedHashMap<>();
        values.put(new AttributeId(12), Value.of("ACME"));
        values.put(new AttributeId(3), Value.of(new BigDecimal("1.50")));
        values.put(new AttributeId(40), Value.of(LocalDate.of(2026, 2, 1)));
        Effect entity = new Effect(new EntityId("e-1"), new TypeId(2), true, Optional.empty(), Optional.empty(),
                List.of(segment(T2, T1.plusSeconds(10), T1, 9, values),
                        segment(T1.plusSeconds(10), Instant.MAX, T1, 9, Map.of(new AttributeId(3), Value.of(1)))));
        Effect relationship = new Effect(new RelationshipId("r-1"), new TypeId(8), false,
                Optional.of(new EntityId("e-1")), Optional.of(new EntityId("e-2")),
                List.of(segment(Instant.parse("2026-02-01T00:00:00Z"), Instant.MAX, T2, 5, Map.of())));
        DataRecord record = new DataRecord(transaction, receipt, List.of(entity, relationship));

        DataRecord read = (DataRecord) roundTrip(record);

        assertThat(read).isEqualTo(record);
        Map<Class<?>, List<Object>> instances = recordsIn(record);
        assertThat(instances.keySet()).as("every kind of write").containsAll(
                Arrays.asList(Write.class.getPermittedSubclasses()));
        assertThat(instances.keySet()).as("every kind of conflict check").containsAll(
                Arrays.asList(ConflictCheck.class.getPermittedSubclasses()));
        assertThat(instances.keySet()).as("every kind of reference and ID").contains(TemporaryId.class,
                EntityId.class, RelationshipId.class);
        assertEveryComponentIsSetSomewhere(instances);
        assertThat(read.transaction().writes()).hasSize(9);
        assertThat(read.receipt().temporaryIds().keySet()).containsExactly(new TemporaryId("acme"),
                new TemporaryId("owns"));
    }

    @Test
    void aDataRecordWithoutOptionalFieldsAndEffectsReadsBackEqual() {
        Transaction transaction = Transaction.by("importer").build();
        DataRecord record = new DataRecord(transaction, new Receipt(new Basis(2), T2, Map.of(), List.of(), List.of(),
                "importer", Optional.empty(), Optional.empty(), Optional.empty()), List.of());

        assertThat(roundTrip(record)).isEqualTo(record);
    }

    // ------------------------------------------------------------------ purges

    private static final String DIGEST_1 = IdDigests.of("e-1");
    private static final String DIGEST_2 = IdDigests.of("r-1");

    @Test
    void aPurgeRecordWithEveryFieldSetReadsBackEqual() {
        PurgeLogRecord record = new PurgeLogRecord(new PurgeRecord(new Basis(7), T1, "dpo", Optional.of("DSR-2026-0042"),
                "Erasure request, with ünïcode and 😀", 2, 1), List.of(DIGEST_1, DIGEST_2, IdDigests.of("e-3")));

        LogRecord read = roundTrip(record);

        assertThat(read).isEqualTo(record);
        assertThat(read.basis()).isEqualTo(new Basis(7));
        assertThat(read.transactionTime()).isEqualTo(T1);
        assertEveryComponentIsSetSomewhere(recordsIn(record));
    }

    @Test
    void aPurgeRecordWithoutAPartyActedForOrDigestsReadsBackEqual() {
        PurgeLogRecord record = new PurgeLogRecord(new PurgeRecord(new Basis(2), T2, "dpo", Optional.empty(), "Why", 0,
                0), List.of());

        assertThat(roundTrip(record)).isEqualTo(record);
    }

    @Test
    void aPurgeRecordHoldsNoIdInClearText() {
        EntityId alice = new EntityId("00000000-0000-7000-8000-000000000003");
        PurgeLogRecord record = new PurgeLogRecord(new PurgeRecord(new Basis(2), T2, "dpo", Optional.empty(), "Why", 1,
                0), List.of(IdDigests.of(alice.value())));

        byte[] frame = RecordCodec.encode(record);

        assertThat(new String(frame, StandardCharsets.ISO_8859_1)).doesNotContain(alice.value());
        assertThat(IdDigests.of(alice.value())).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(IdDigests.of(alice.value())).isNotEqualTo(IdDigests.of(alice.value() + " "));
    }

    @Test
    void aPurgeRecordThatIsCutShortOrHasBytesLeftOverIsCorrupt() {
        PurgeLogRecord record = new PurgeLogRecord(new PurgeRecord(new Basis(2), T2, "dpo", Optional.of("DSR-1"),
                "Why", 1, 1), List.of(DIGEST_1, DIGEST_2));
        byte[] frame = RecordCodec.encode(record);
        byte[] payload = Arrays.copyOfRange(frame, Encoder.FRAME_HEADER, frame.length);

        assertThat(payload[0]).as("the kind byte of a purge").isEqualTo((byte) RecordCodec.PURGE);
        assertThatThrownBy(() -> RecordCodec.decode(RecordCodec.VERSION, Arrays.copyOf(payload, payload.length - 1)))
                .isInstanceOf(CorruptRecordException.class);
        assertThatThrownBy(() -> RecordCodec.decode(RecordCodec.VERSION, Arrays.copyOf(payload, payload.length + 1)))
                .isInstanceOf(CorruptRecordException.class);
    }

    @Test
    void aPurgeRecordWithANegativeCountOrADigestCountThatExceedsTheRecordIsCorrupt() {
        Encoder negative = Encoder.plain();
        negative.writeByte(RecordCodec.PURGE);
        PurgeCodec.write(negative, new PurgeRecord(new Basis(2), T2, "dpo", Optional.empty(), "Why", 1, 1));
        byte[] payload = negative.toByteArray();
        // The entity count is the first of the last eight bytes: make it -1.
        payload[payload.length - 8] = (byte) 0xFF;
        payload[payload.length - 7] = (byte) 0xFF;
        payload[payload.length - 6] = (byte) 0xFF;
        payload[payload.length - 5] = (byte) 0xFF;
        assertThatThrownBy(() -> RecordCodec.decode(RecordCodec.VERSION, withNoDigests(payload)))
                .isInstanceOf(CorruptRecordException.class);

        Encoder tooMany = Encoder.plain();
        tooMany.writeByte(RecordCodec.PURGE);
        PurgeCodec.write(tooMany, new PurgeRecord(new Basis(2), T2, "dpo", Optional.empty(), "Why", 1, 1));
        tooMany.writeInt(1_000_000);
        assertThatThrownBy(() -> RecordCodec.decode(RecordCodec.VERSION, tooMany.toByteArray()))
                .isInstanceOf(CorruptRecordException.class);
    }

    private static byte[] withNoDigests(byte[] payload) {
        byte[] extended = Arrays.copyOf(payload, payload.length + 4);
        return extended;
    }

    // ------------------------------------------------------------------ primitives

    @Test
    void instantsAreMicrosecondsAndAreReadBackExactlyWhateverTheyAre() {
        List<Instant> instants = List.of(Instant.EPOCH, T1, Instant.parse("1969-12-31T23:59:59.999999Z"),
                Instant.parse("2026-01-01T00:00:00.000000001Z"), Instant.MAX, Instant.MIN,
                Instant.parse("+300000-01-01T00:00:00Z"), Instant.ofEpochSecond(0, 999_999_999),
                Instant.ofEpochSecond(Long.MAX_VALUE / 1_000_000L, 807_000));

        for (Instant instant : instants) {
            Encoder out = Encoder.plain();
            out.writeInstant(instant);
            Decoder in = new Decoder(out.toByteArray());
            assertThat(in.readInstant()).as(instant.toString()).isEqualTo(instant);
            in.requireEnd();
        }
    }

    @Test
    void anInstantInMicrosecondsTakesEightBytesAndTheOpenEndToo() {
        Encoder micros = Encoder.plain();
        micros.writeInstant(T1);
        Encoder open = Encoder.plain();
        open.writeInstant(Instant.MAX);

        assertThat(micros.size()).isEqualTo(8);
        assertThat(open.size()).isEqualTo(8);
        assertThat(ByteBuffer.wrap(micros.toByteArray()).getLong()).isEqualTo(T1.getEpochSecond() * 1_000_000L
                + 123_456);
    }

    @Test
    void decimalsKeepTheirScale() {
        for (String text : List.of("1", "1.0", "1.00", "0", "0.000", "-0.5", "1E+3", "123456789012345678901234567890.5")) {
            Value value = Value.of(new BigDecimal(text));
            Encoder out = Encoder.plain();
            ValueCodec.write(out, value);
            assertThat(ValueCodec.read(new Decoder(out.toByteArray()))).as(text).isEqualTo(value);
        }
    }

    @Test
    void textIsUtf8WithALengthInBytes() {
        Encoder out = Encoder.plain();
        out.writeText("aé€😀");

        assertThat(out.toByteArray()).hasSize(4 + 1 + 2 + 3 + 4).startsWith(0, 0, 0, 10);
        assertThat(out.toByteArray()).containsSubsequence((byte) 'a', (byte) 0xC3, (byte) 0xA9, (byte) 0xE2,
                (byte) 0x82, (byte) 0xAC, (byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80);
        assertThat(new Decoder(out.toByteArray()).readText()).isEqualTo("aé€😀");
    }

    @Test
    void anUnpairedSurrogateIsWrittenAsItsOwnThreeByteSequenceAndReadsBackEqual() {
        Encoder out = Encoder.plain();
        out.writeText("a\uD800b\uDC00c");

        assertThat(out.toByteArray()).hasSize(4 + 1 + 3 + 1 + 3 + 1).containsSubsequence((byte) 0xED, (byte) 0xA0,
                (byte) 0x80).containsSubsequence((byte) 0xED, (byte) 0xB0, (byte) 0x80);
        assertThat(new Decoder(out.toByteArray()).readText()).isEqualTo("a\uD800b\uDC00c");
    }

    @Test
    void everyStringReadsBackEqualWhatEverItsSurrogatesAre() {
        List<String> texts = new ArrayList<>(List.of("", "\uD800", "\uDC00", "\uDBFF", "\uDFFF", "\uD800\uD800",
                "\uDC00\uDC00", "\uDC00\uD800", "\uD800\uD800\uDC00", "\uD800\uDC00\uDC00", "\uD83D\uDE00",
                "x\uD83D", "\uDE00x", "\u0000\u007F\u0080\u07FF\u0800\uFFFF\uE000"));
        Random random = new Random(7);
        char[] alphabet = {'a', 'z', '\0', 'é', '€', '\uD7FF', '\uE000', '\uFFFF', '\uD800', '\uDBFF', '\uDC00',
                '\uDFFF'};
        for (int i = 0; i < 2_000; i++) {
            char[] chars = new char[random.nextInt(12)];
            for (int j = 0; j < chars.length; j++) {
                chars[j] = random.nextInt(4) == 0 ? (char) random.nextInt(0x10000) : alphabet[random.nextInt(
                        alphabet.length)];
            }
            texts.add(new String(chars));
        }

        for (String text : texts) {
            Encoder out = Encoder.plain();
            out.writeText(text);
            Decoder in = new Decoder(out.toByteArray());
            assertThat(in.readText()).as(text.chars().boxed().map(Integer::toHexString).toList().toString())
                    .isEqualTo(text);
            in.requireEnd();
        }
    }

    @Test
    void validTextIsPlainUtf8() {
        for (String text : List.of("", "plain", "é€", "😀 and 𝄞", "\u0000")) {
            Encoder out = Encoder.plain();
            out.writeText(text);
            byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);

            assertThat(Arrays.copyOfRange(out.toByteArray(), 4, out.size())).isEqualTo(utf8);
        }
    }

    @Test
    void textThatIsNotWellFormedIsCorrupt() {
        List<byte[]> bad = List.of(
                new byte[] {(byte) 0xC0, (byte) 0x80},
                new byte[] {(byte) 0xC1, (byte) 0xBF},
                new byte[] {(byte) 0xE0, (byte) 0x80, (byte) 0x80},
                new byte[] {(byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0x80},
                new byte[] {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
                new byte[] {(byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80},
                new byte[] {(byte) 0x80},
                new byte[] {(byte) 0xC3},
                new byte[] {(byte) 0xE2, (byte) 0x82},
                new byte[] {(byte) 0xC3, 'a'},
                new byte[] {(byte) 0xFF},
                // A high and a low surrogate in two three-byte sequences, which is one four-byte sequence.
                new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80, (byte) 0xED, (byte) 0xB0, (byte) 0x80});

        for (byte[] text : bad) {
            Encoder out = Encoder.plain();
            out.writeBytes(text);
            assertThatThrownBy(() -> new Decoder(out.toByteArray()).readText()).as(Arrays.toString(text))
                    .isInstanceOf(CorruptRecordException.class);
        }
    }

    @Test
    void textWithUnpairedSurrogatesInEveryFieldOfARecordReadsBackEqual() {
        String high = "\uD800";
        String low = "\uDC00";
        EntityTypeDefinition type = new EntityTypeDefinition("Thing", false, Optional.empty(), List.of(),
                List.of(new AttributeDefinition("name", ValueType.STRING, false, false, Optional.empty(),
                        List.of("alias" + high), false, Optional.of("describes " + low), List.of(Value.of(high + "x")),
                        List.of(low + "A"), Optional.empty(), OptionalInt.empty(), OptionalInt.empty())),
                List.of(), false, Optional.of("a " + high + " thing " + low), List.of());
        SchemaRecord schema = new SchemaRecord(new Basis(1), T1, new Submission("agent" + high, Optional.of("for" + low),
                Optional.of("because" + high + "and" + low)), new Origin("things", "1"),
                new SchemaDefinition(List.of(type), List.of(), List.of()),
                new SchemaIdentifiers(List.of(), List.of(), 1));
        Transaction transaction = Transaction.by("agent:" + high + "x" + low).onBehalfOf("hr" + low)
                .reason("why " + high + low + low + high).idempotencyKey("key" + high)
                .create(new TemporaryId("t" + low), "Thing", EffectivePeriod.from(T1),
                        Map.of("name", Value.of("a" + high + "b" + low + "c")))
                .build();
        DataRecord data = new DataRecord(transaction, new Receipt(new Basis(2), T2, Map.of(), List.of(), List.of(),
                transaction.principal(), transaction.onBehalfOf(), transaction.reason(),
                transaction.idempotencyKey()), List.of());

        assertThat(roundTrip(schema)).isEqualTo(schema);
        assertThat(roundTrip(data)).isEqualTo(data);
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    void aPayloadThatIsNotARecordIsCorrupt() {
        byte[] good = Arrays.copyOfRange(RecordCodec.encode(schemaRecord(ReferenceModel.definition())),
                Encoder.FRAME_HEADER, RecordCodec.encode(schemaRecord(ReferenceModel.definition())).length);
        List<byte[]> bad = new ArrayList<>();
        bad.add(new byte[0]);
        bad.add(new byte[] {99});
        bad.add(Arrays.copyOf(good, good.length - 1));
        bad.add(Arrays.copyOf(good, good.length + 1));
        bad.add(Arrays.copyOf(good, 20));
        byte[] garbage = good.clone();
        Arrays.fill(garbage, 9, garbage.length, (byte) 0xFF);
        bad.add(garbage);

        for (byte[] payload : bad) {
            assertThatThrownBy(() -> RecordCodec.decode(RecordCodec.VERSION, payload))
                    .isInstanceOf(CorruptRecordException.class);
        }
    }

    @Test
    void aVersionThatIsNotKnownIsRefused() {
        assertThatThrownBy(() -> RecordCodec.decode(2, new byte[] {1})).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2");
    }

    @Test
    void aPresenceByteThatIsNeitherZeroNorOneIsCorrupt() {
        assertThatThrownBy(() -> new Decoder(new byte[] {2}).readBoolean()).isInstanceOf(CorruptRecordException.class);
        assertThatThrownBy(() -> new Decoder(new byte[] {0, 0, 0, 5, 1}).readText())
                .isInstanceOf(CorruptRecordException.class);
        assertThatThrownBy(() -> new Decoder(new byte[] {0x7F, 0, 0, 0}).readAll(Decoder::readByte))
                .isInstanceOf(CorruptRecordException.class);
    }
}
