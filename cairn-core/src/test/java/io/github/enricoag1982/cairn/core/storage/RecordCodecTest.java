package io.github.enricoag1982.cairn.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.KeyResolution;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Check;
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
        // Some are derived and some are not, with an expression that has quotes, a colon and non-ASCII text.
        Optional<String> derived = name.length() % 2 == 0 ? Optional.of("has(self.text) ? self.text : 'ünïcode 😀'")
                : Optional.empty();
        // Some are classified and some are not.
        Optional<String> classification = name.length() % 3 == 0 ? Optional.of("pay-data") : Optional.empty();
        return new AttributeDefinition(name, type, true, true, Optional.ofNullable(range), List.of("old" + name, "older"),
                true, Optional.of("The " + name + " with ünïcode and 😀."), examples, List.of("A", "B"),
                Optional.of("[A-Z]+"), OptionalInt.of(1), OptionalInt.of(12), derived, classification);
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
                attributes, List.of("Actor"), true, Optional.of("Somebody"), List.of(new RuleReference("rule-a", 2)),
                List.of(new Check("name-set", "has(self.text) && size(self.text) > 0",
                        Optional.of("The name is empty, or ünïcode: \ud83d\ude00.")),
                        new Check("quiet", "true", Optional.empty())));
        EntityTypeDefinition child = new EntityTypeDefinition("Person", false, Optional.of("Party"),
                List.of("Named", "Other"), attributes.subList(2, 4), List.of("Human", "Individual"), false,
                Optional.of("A person"), List.of(new RuleReference("rule-b", 1), new RuleReference("rule-c", 3)));
        RelationshipTypeDefinition owns = new RelationshipTypeDefinition("Owns", "Party", "Party",
                Multiplicity.ZERO_OR_MORE, Multiplicity.EXACTLY_ONE, "ownedBy", List.of("ownedFrom", "heldBy"),
                RelationshipKind.COMPOSITION, true, Optional.of("Link"), attributes.subList(4, 7),
                List.of("Possesses", "Holds"), true, Optional.of("Owning"), List.of(new RuleReference("rule-d", 1)),
                List.of(new Check("share-set", "has(self.amount)", Optional.of("A share needs an amount."))));
        RelationshipTypeDefinition link = new RelationshipTypeDefinition("Link", "Party", "Person",
                Multiplicity.ZERO_OR_ONE, Multiplicity.ONE_OR_MORE, "linkedFrom", List.of(),
                RelationshipKind.ASSOCIATION, false, true, Optional.empty(), List.of(), List.of(), false,
                Optional.empty(), List.of(), List.of());
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

    // ------------------------------------------------------------------ derived attributes (D6)

    /** A definition with one derived attribute and no check. */
    private static SchemaDefinition derivedWithoutChecks() {
        AttributeDefinition plain = AttributeDefinition.builder("givenName", ValueType.STRING).required().build();
        AttributeDefinition derived = AttributeDefinition.builder("shout", ValueType.STRING)
                .derived("self.givenName + '!'").build();
        return new SchemaDefinition(List.of(new EntityTypeDefinition("Person", false, Optional.empty(), List.of(),
                List.of(plain, derived), List.of(), false, Optional.empty(), List.of())), List.of(), List.of());
    }

    private static List<AttributeDefinition> plain(List<AttributeDefinition> attributes) {
        return attributes.stream().map(attribute -> new AttributeDefinition(attribute.name(), attribute.valueType(),
                attribute.required(), attribute.unique(), attribute.range(), attribute.aliases(), attribute.deprecated(),
                attribute.description(), attribute.examples(), attribute.allowedValues(), attribute.pattern(),
                attribute.minLength(), attribute.maxLength(), Optional.empty(), attribute.classification())).toList();
    }

    /** The definition with every derived attribute made plain. */
    private static SchemaDefinition withoutDerived(SchemaDefinition definition) {
        return new SchemaDefinition(
                definition.entityTypes().stream().map(type -> new EntityTypeDefinition(type.name(), type.isAbstract(),
                        type.supertype(), type.traits(), plain(type.attributes()), type.aliases(), type.deprecated(),
                        type.description(), type.rules(), type.checks())).toList(),
                definition.traits().stream().map(trait -> new TraitDefinition(trait.name(), plain(trait.attributes()),
                        trait.aliases(), trait.deprecated(), trait.description())).toList(),
                definition.relationshipTypes().stream().map(type -> new RelationshipTypeDefinition(type.name(),
                        type.source(), type.target(), type.targetsPerSource(), type.sourcesPerTarget(),
                        type.inverseName(), type.inverseAliases(), type.kind(), type.symmetric(),
                        type.outlivesTarget(), type.supertype(), plain(type.attributes()), type.aliases(),
                        type.deprecated(), type.description(), type.rules(), type.checks())).toList());
    }

    private static List<AttributeDefinition> unclassified(List<AttributeDefinition> attributes) {
        return attributes.stream().map(attribute -> new AttributeDefinition(attribute.name(), attribute.valueType(),
                attribute.required(), attribute.unique(), attribute.range(), attribute.aliases(), attribute.deprecated(),
                attribute.description(), attribute.examples(), attribute.allowedValues(), attribute.pattern(),
                attribute.minLength(), attribute.maxLength(), attribute.derived(), Optional.empty())).toList();
    }

    /** The definition with no attribute classified. */
    private static SchemaDefinition withoutClassifications(SchemaDefinition definition) {
        return new SchemaDefinition(
                definition.entityTypes().stream().map(type -> new EntityTypeDefinition(type.name(), type.isAbstract(),
                        type.supertype(), type.traits(), unclassified(type.attributes()), type.aliases(),
                        type.deprecated(), type.description(), type.rules(), type.checks())).toList(),
                definition.traits().stream().map(trait -> new TraitDefinition(trait.name(),
                        unclassified(trait.attributes()), trait.aliases(), trait.deprecated(), trait.description()))
                        .toList(),
                definition.relationshipTypes().stream().map(type -> new RelationshipTypeDefinition(type.name(),
                        type.source(), type.target(), type.targetsPerSource(), type.sourcesPerTarget(),
                        type.inverseName(), type.inverseAliases(), type.kind(), type.symmetric(),
                        type.outlivesTarget(), type.supertype(), unclassified(type.attributes()), type.aliases(),
                        type.deprecated(), type.description(), type.rules(), type.checks())).toList());
    }

    /** The definition with no relationship type outliving its targets. */
    private static SchemaDefinition withoutOutliving(SchemaDefinition definition) {
        return new SchemaDefinition(definition.entityTypes(), definition.traits(),
                definition.relationshipTypes().stream().map(type -> new RelationshipTypeDefinition(type.name(),
                        type.source(), type.target(), type.targetsPerSource(), type.sourcesPerTarget(),
                        type.inverseName(), type.inverseAliases(), type.kind(), type.symmetric(), false,
                        type.supertype(), type.attributes(), type.aliases(), type.deprecated(), type.description(),
                        type.rules(), type.checks())).toList());
    }

    /** A definition with one derived attribute, no check and a relationship type that outlives its targets. */
    private static SchemaDefinition derivedAndOutliving() {
        SchemaDefinition derived = derivedWithoutChecks();
        RelationshipTypeDefinition opened = new RelationshipTypeDefinition("OpenedBy", "Person", "Person",
                Multiplicity.ZERO_OR_ONE, Multiplicity.ZERO_OR_MORE, "opened", List.of(), RelationshipKind.ASSOCIATION,
                false, true, Optional.empty(), List.of(), List.of(), false, Optional.empty(), List.of(), List.of());
        RelationshipTypeDefinition kept = new RelationshipTypeDefinition("Kept", "Person", "Person",
                Multiplicity.ZERO_OR_ONE, Multiplicity.ZERO_OR_MORE, "keptBy", List.of(), RelationshipKind.ASSOCIATION,
                false, false, Optional.empty(), List.of(), List.of(), false, Optional.empty(), List.of(), List.of());
        return new SchemaDefinition(derived.entityTypes(), derived.traits(), List.of(kept, opened));
    }

    private static byte[] payload(LogRecord record) {
        byte[] frame = RecordCodec.encode(record);
        return Arrays.copyOfRange(frame, Encoder.FRAME_HEADER, frame.length);
    }

    @Test
    void derivedAttributesWithoutChecksReadBackEqual() {
        SchemaRecord record = schemaRecord(derivedWithoutChecks());

        SchemaRecord read = (SchemaRecord) roundTrip(record);

        assertThat(read).isEqualTo(record);
        assertThat(read.definition().entityTypes().getFirst().attributes().get(1).derived())
                .hasValue("self.givenName + '!'");
        assertThat(read.definition().entityTypes().getFirst().attributes().get(0).derived()).isEmpty();
    }

    @Test
    void theDerivationsFollowTheChecksAsASectionOfTheirOwnThatIsLeftOutWhenNoAttributeIsDerived() {
        SchemaDefinition definition = derivedWithoutChecks();
        byte[] without = payload(schemaRecord(withoutDerived(definition)));
        byte[] with = payload(schemaRecord(definition));

        Encoder section = Encoder.plain();
        section.writeInt(0); // The checks of the entity types: none, but present, so that the sections are told apart.
        section.writeInt(0); // The checks of the relationship types.
        section.writeInt(1);
        section.writeText("Person");
        section.writeText("shout");
        section.writeText("self.givenName + '!'");
        byte[] expected = section.toByteArray();

        assertThat(Arrays.copyOf(with, without.length)).as("the record up to the sections").isEqualTo(without);
        assertThat(Arrays.copyOfRange(with, without.length, with.length)).as("the sections").isEqualTo(expected);
    }

    @Test
    void aRecordWithoutDerivedAttributesIsTheSameAsItWasBeforeTheyExisted() {
        // Checks follow the identifiers, and nothing follows the checks, as in a record written before derived attributes.
        SchemaRecord record = schemaRecord(withoutOutliving(withoutDerived(withoutClassifications(everythingSet()))));
        byte[] payload = payload(record);

        assertThat(RecordCodec.decode(RecordCodec.VERSION, payload)).isEqualTo(record);
        Encoder checks = Encoder.plain();
        DefinitionCodec.writeChecks(checks, record.definition());
        byte[] tail = checks.toByteArray();
        assertThat(Arrays.copyOfRange(payload, payload.length - tail.length, payload.length)).isEqualTo(tail);
    }

    @Test
    void derivedAttributesAndChecksTogetherReadBackEqual() {
        SchemaRecord record = schemaRecord(everythingSet());

        assertThat(roundTrip(record)).isEqualTo(record);
    }

    @Test
    void aDerivedAttributeThatTheDefinitionDoesNotHaveIsCorrupt() {
        Encoder section = Encoder.plain();
        section.writeInt(1);
        section.writeText("Person");
        section.writeText("nothing");
        section.writeText("self.givenName");
        SchemaDefinition definition = withoutDerived(derivedWithoutChecks());

        assertThatThrownBy(() -> DefinitionCodec.readDerivations(new Decoder(section.toByteArray()), definition))
                .isInstanceOf(CorruptRecordException.class).hasMessageContaining("Person.nothing");
    }

    @Test
    void aDerivedAttributeRecordedTwiceIsCorrupt() {
        Encoder section = Encoder.plain();
        section.writeInt(2);
        for (int i = 0; i < 2; i++) {
            section.writeText("Person");
            section.writeText("shout");
            section.writeText("self.givenName");
        }

        assertThatThrownBy(() -> DefinitionCodec.readDerivations(new Decoder(section.toByteArray()),
                withoutDerived(derivedWithoutChecks()))).isInstanceOf(CorruptRecordException.class)
                .hasMessageContaining("twice");
    }

    // ------------------------------------------------------------------ relationships that outlive their targets (D1)

    @Test
    void theRelationshipTypesThatOutliveTheirTargetsFollowTheOtherSectionsAsASectionOfTheirOwn() {
        SchemaDefinition definition = withoutClassifications(everythingSet());
        byte[] without = payload(schemaRecord(withoutOutliving(definition)));
        byte[] with = payload(schemaRecord(definition));

        // The checks of the entity types and of the relationship types, and the derivations, are written by the
        // definition itself, as they are without the section; it adds the names of the types, last.
        Encoder section = Encoder.plain();
        section.writeInt(1);
        section.writeText("Link");
        byte[] names = section.toByteArray();

        assertThat(Arrays.copyOf(with, without.length)).as("the record up to the section").isEqualTo(without);
        assertThat(Arrays.copyOfRange(with, without.length, with.length)).as("the section").isEqualTo(names);
    }

    @Test
    void emptySectionsBeforeItTellItApartFromTheOtherSections() {
        SchemaDefinition definition = new SchemaDefinition(List.of(), List.of(), derivedAndOutliving().relationshipTypes());
        byte[] without = payload(schemaRecord(withoutOutliving(definition)));
        byte[] with = payload(schemaRecord(definition));

        Encoder sections = Encoder.plain();
        sections.writeInt(0); // The checks of the entity types: none, but present, so that the sections are told apart.
        sections.writeInt(0); // The checks of the relationship types.
        sections.writeInt(0); // The derivations.
        sections.writeInt(1);
        sections.writeText("OpenedBy");

        assertThat(without).as("without the section, nothing follows the identifiers").hasSizeLessThan(with.length);
        assertThat(Arrays.copyOf(with, without.length)).isEqualTo(without);
        assertThat(Arrays.copyOfRange(with, without.length, with.length)).isEqualTo(sections.toByteArray());
        assertThat(RecordCodec.decode(RecordCodec.VERSION, with)).isEqualTo(schemaRecord(definition));
    }

    @Test
    void aRecordWithoutTheSectionReadsAsOneWhoseRelationshipsDoNotOutliveTheirTargets() {
        // A record as it was written before the section existed: the definition without the flag, nothing after it.
        SchemaRecord record = schemaRecord(withoutOutliving(withoutClassifications(everythingSet())));
        byte[] payload = payload(record);

        SchemaRecord read = (SchemaRecord) RecordCodec.decode(RecordCodec.VERSION, payload);

        assertThat(read).isEqualTo(record);
        assertThat(read.definition().relationshipTypes()).isNotEmpty()
                .noneMatch(RelationshipTypeDefinition::outlivesTarget);
        SchemaRecord plain = schemaRecord(ReferenceModel.definition());
        assertThat(((SchemaRecord) RecordCodec.decode(RecordCodec.VERSION, payload(plain))).definition()
                .relationshipTypes()).isNotEmpty().noneMatch(RelationshipTypeDefinition::outlivesTarget);
    }

    @Test
    void derivedAttributesAndTypesThatOutliveTheirTargetsReadBackEqual() {
        SchemaRecord record = schemaRecord(derivedAndOutliving());

        SchemaRecord read = (SchemaRecord) roundTrip(record);

        assertThat(read).isEqualTo(record);
        assertThat(read.definition().relationshipTypes()).extracting(RelationshipTypeDefinition::name,
                RelationshipTypeDefinition::outlivesTarget).containsExactly(
                        tuple("Kept", false),
                        tuple("OpenedBy", true));
    }

    @Test
    void aTypeThatTheSectionNamesAndTheDefinitionDoesNotHaveIsCorrupt() {
        Encoder section = Encoder.plain();
        section.writeInt(1);
        section.writeText("Nothing");

        assertThatThrownBy(() -> DefinitionCodec.readOutliving(new Decoder(section.toByteArray()),
                derivedAndOutliving())).isInstanceOf(CorruptRecordException.class).hasMessageContaining("Nothing");
    }

    @Test
    void aTypeThatTheSectionNamesTwiceIsCorrupt() {
        Encoder section = Encoder.plain();
        section.writeInt(2);
        section.writeText("OpenedBy");
        section.writeText("OpenedBy");

        assertThatThrownBy(() -> DefinitionCodec.readOutliving(new Decoder(section.toByteArray()),
                withoutOutliving(derivedAndOutliving()))).isInstanceOf(CorruptRecordException.class)
                .hasMessageContaining("twice");
    }

    // ------------------------------------------------------------------ classifications (D1)

    /** The section of classifications for entries of a declarer, an attribute and a classification. */
    private static byte[] classificationSection(String... entries) {
        Encoder section = Encoder.plain();
        section.writeInt(entries.length / 3);
        for (String entry : entries) {
            section.writeText(entry);
        }
        return section.toByteArray();
    }

    @Test
    void theClassificationsFollowTheOtherSectionsAsASectionOfTheirOwn() {
        SchemaDefinition definition = everythingSet();
        byte[] without = payload(schemaRecord(withoutClassifications(definition)));
        byte[] with = payload(schemaRecord(definition));

        // Traits first, then entity types, then relationship types, each with its attributes in the order of the definition.
        byte[] section = classificationSection(
                "Party", "big", "pay-data", "Party", "amount", "pay-data", "Party", "day", "pay-data",
                "Party", "moment", "pay-data", "Person", "big", "pay-data", "Owns", "amount", "pay-data",
                "Owns", "day", "pay-data", "Owns", "moment", "pay-data");

        assertThat(Arrays.copyOf(with, without.length)).as("the record up to the section").isEqualTo(without);
        assertThat(Arrays.copyOfRange(with, without.length, with.length)).as("the section").isEqualTo(section);
        assertThat(RecordCodec.decode(RecordCodec.VERSION, with)).isEqualTo(schemaRecord(definition));
    }

    @Test
    void emptySectionsBeforeTheClassificationsTellThemApartFromTheOtherSections() {
        AttributeDefinition salary = AttributeDefinition.builder("salary", ValueType.DECIMAL).classification("pay")
                .build();
        SchemaDefinition definition = new SchemaDefinition(List.of(new EntityTypeDefinition("Person", false,
                Optional.empty(), List.of(), List.of(salary), List.of(), false, Optional.empty(), List.of())),
                List.of(), List.of());
        byte[] without = payload(schemaRecord(withoutClassifications(definition)));
        byte[] with = payload(schemaRecord(definition));

        Encoder sections = Encoder.plain();
        sections.writeInt(0); // The checks of the entity types: none, but present, so that the sections are told apart.
        sections.writeInt(0); // The checks of the relationship types.
        sections.writeInt(0); // The derivations.
        sections.writeInt(0); // The relationship types that outlive their targets.
        sections.writeRaw(classificationSection("Person", "salary", "pay"));

        assertThat(Arrays.copyOf(with, without.length)).isEqualTo(without);
        assertThat(Arrays.copyOfRange(with, without.length, with.length)).isEqualTo(sections.toByteArray());
        assertThat(RecordCodec.decode(RecordCodec.VERSION, with)).isEqualTo(schemaRecord(definition));
    }

    @Test
    void aRecordWithoutTheSectionReadsAsAttributesWithoutClassifications() {
        // A record as it was written before the section existed: the definition without classifications, nothing after.
        SchemaRecord record = schemaRecord(withoutClassifications(everythingSet()));

        SchemaRecord read = (SchemaRecord) RecordCodec.decode(RecordCodec.VERSION, payload(record));

        assertThat(read).isEqualTo(record);
        assertThat(recordsIn(read.definition()).get(AttributeDefinition.class)).isNotEmpty()
                .allSatisfy(attribute -> assertThat(((AttributeDefinition) attribute).classification()).isEmpty());
        SchemaRecord plain = schemaRecord(ReferenceModel.definition());
        assertThat(((SchemaRecord) RecordCodec.decode(RecordCodec.VERSION, payload(plain))).definition())
                .isEqualTo(ReferenceModel.definition());
    }

    @Test
    void classificationsOfTraitsEntityTypesAndRelationshipTypesReadBackEqual() {
        SchemaRecord record = schemaRecord(everythingSet());

        SchemaRecord read = (SchemaRecord) roundTrip(record);

        assertThat(read).isEqualTo(record);
        assertThat(read.definition().traits().getFirst().attributes()).extracting(AttributeDefinition::classification)
                .containsOnly(Optional.empty());
        assertThat(read.definition().entityTypes().getFirst().attributes())
                .filteredOn(attribute -> attribute.classification().isPresent())
                .extracting(AttributeDefinition::name).containsExactly("big", "amount", "day", "moment");
        assertThat(read.definition().relationshipTypes().getFirst().attributes())
                .filteredOn(attribute -> attribute.classification().isPresent())
                .extracting(AttributeDefinition::name).containsExactly("amount", "day", "moment");
    }

    @Test
    void anAttributeThatTheClassificationsNameAndTheDefinitionDoesNotHaveIsCorrupt() {
        byte[] section = classificationSection("Person", "nothing", "pay");

        assertThatThrownBy(() -> DefinitionCodec.readClassifications(new Decoder(section),
                withoutDerived(derivedWithoutChecks()))).isInstanceOf(CorruptRecordException.class)
                .hasMessageContaining("Person.nothing");
    }

    @Test
    void anAttributeThatTheClassificationsNameTwiceIsCorrupt() {
        byte[] section = classificationSection("Person", "shout", "pay", "Person", "shout", "pay");

        assertThatThrownBy(() -> DefinitionCodec.readClassifications(new Decoder(section),
                withoutDerived(derivedWithoutChecks()))).isInstanceOf(CorruptRecordException.class)
                .hasMessageContaining("twice");
    }

    @Test
    void theDefinitionOfTheTestSetsEveryComponentOfEveryRecordSoThatTheRoundTripChecksThem() {
        SchemaDefinition definition = everythingSet();

        Map<Class<?>, List<Object>> instances = recordsIn(definition);

        assertThat(instances.keySet()).contains(SchemaDefinition.class, EntityTypeDefinition.class,
                TraitDefinition.class, RelationshipTypeDefinition.class, AttributeDefinition.class,
                RuleReference.class, Check.class, Range.class, Bound.class, Multiplicity.class);
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
                .idempotencyKey("onboard-1").clearance(Clearance.of("pay", "finance"))
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
                List.of(new KeyResolution(5, new UniqueKey("Account", "number", Value.of("A-1")),
                        new EntityId("e-1"))),
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
                EntityId.class, RelationshipId.class, UniqueKey.class, KeyResolution.class);
        assertEveryComponentIsSetSomewhere(instances);
        assertThat(read.transaction().writes()).hasSize(9);
        assertThat(read.receipt().temporaryIds().keySet()).containsExactly(new TemporaryId("acme"),
                new TemporaryId("owns"));
    }

    // ------------------------------------------------------------------ clearances (D4)

    private static DataRecord recordOf(Transaction transaction, List<KeyResolution> keys) {
        return new DataRecord(transaction, new Receipt(new Basis(4), T2, Map.of(), List.of(), List.of(), keys,
                "importer", Optional.empty(), Optional.empty(), transaction.idempotencyKey()), List.of());
    }

    @Test
    void theClearanceOfATransactionIsTheLastOfARecordAndARecordWithoutOneReadsAsBefore() {
        Transaction plain = Transaction.by("importer").endDate(new EntityId("e-1"), T2).build();
        Transaction cleared = Transaction.by("importer").clearance(Clearance.of("pay", "finance"))
                .endDate(new EntityId("e-1"), T2).build();

        byte[] before = payload(RecordCodec.encode(recordOf(plain, List.of())));
        byte[] after = payload(RecordCodec.encode(recordOf(cleared, List.of())));

        // The resolutions of keys are present, and empty, so that the clearance is told apart from them by position.
        Encoder tail = Encoder.plain();
        tail.writeInt(0);
        tail.writeInt(2);
        tail.writeText("pay");
        tail.writeText("finance");
        assertThat(Arrays.copyOf(after, before.length)).as("a record up to the clearance").isEqualTo(before);
        assertThat(Arrays.copyOfRange(after, before.length, after.length)).isEqualTo(tail.toByteArray());
        assertThat(((DataRecord) RecordCodec.decode(RecordCodec.VERSION, before)).transaction().clearance()).isEmpty();
        assertThat(RecordCodec.decode(RecordCodec.VERSION, after)).isEqualTo(recordOf(cleared, List.of()));
        byte[] cut = Arrays.copyOf(after, after.length - 1);
        assertThatThrownBy(() -> RecordCodec.decode(RecordCodec.VERSION, cut)).isInstanceOf(CorruptRecordException.class);
    }

    @Test
    void aClearanceThatCoversNothingIsNotNoClearance() {
        Transaction none = Transaction.by("importer").clearance(Clearance.none()).endDate(new EntityId("e-1"), T2)
                .build();
        Transaction absent = Transaction.by("importer").endDate(new EntityId("e-1"), T2).build();

        DataRecord readNone = (DataRecord) roundTrip(recordOf(none, List.of()));
        DataRecord readAbsent = (DataRecord) roundTrip(recordOf(absent, List.of()));

        assertThat(readNone.transaction().clearance()).hasValue(Clearance.none());
        assertThat(readAbsent.transaction().clearance()).isEmpty();
        assertThat(readNone.transaction()).isNotEqualTo(readAbsent.transaction());
    }

    @Test
    void aClearanceAfterTheResolutionsOfUniqueKeysReadsBackEqual() {
        UniqueKey key = new UniqueKey("Account", "number", Value.of("A-1"));
        Transaction transaction = Transaction.by("importer").clearance(Clearance.of("finance"))
                .patch(key, EffectivePeriod.from(T2), Map.of()).build();
        List<KeyResolution> resolutions = List.of(new KeyResolution(0, key, new EntityId("e-1")));

        DataRecord read = (DataRecord) roundTrip(recordOf(transaction, resolutions));

        assertThat(read).isEqualTo(recordOf(transaction, resolutions));
        assertThat(read.receipt().keys()).containsExactlyElementsOf(resolutions);
        assertThat(read.transaction().clearance()).hasValue(Clearance.of("finance"));
    }

    @Test
    void aDataRecordWhoseWritesNameEntitiesByUniqueKeysReadsBackEqual() {
        Transaction transaction = Transaction.by("importer").idempotencyKey("keys")
                .patch(new UniqueKey("Account", "number", Value.of("A-1")), EffectivePeriod.from(T2),
                        Map.of("balance", Value.of(new BigDecimal("1500.00"))))
                .replace(new UniqueKey("Organization", "lei", Value.of("5493001KJTIIGC8Y1R12")),
                        EffectivePeriod.between(T1, T2), Map.of("name", Value.of("ACME")),
                        ConflictCheck.perEntity(new Basis(3)))
                .endDate(new UniqueKey("Thing", "serial", Value.of(7L)), T2)
                .relate("Employs", new UniqueKey("Organization", "lei", Value.of("5493001KJTIIGC8Y1R12")),
                        new UniqueKey("Person", "badge", Value.of(42)), EffectivePeriod.from(T1), Map.of())
                .patch(new UniqueKey("Thing", "born", Value.of(LocalDate.of(2026, 2, 1))), EffectivePeriod.from(T1),
                        Map.of())
                .patch(new UniqueKey("Thing", "seen", Value.of(T1)), EffectivePeriod.from(T1), Map.of())
                .patch(new UniqueKey("Thing", "flag", Value.of(true)), EffectivePeriod.from(T1), Map.of())
                .patch(new UniqueKey("Thing", "label", Value.of("with ünïcode and 😀")), EffectivePeriod.from(T1),
                        Map.of())
                .build();
        List<KeyResolution> resolutions = List.of(
                new KeyResolution(0, new UniqueKey("Account", "number", Value.of("A-1")), new EntityId("e-1")),
                new KeyResolution(1, new UniqueKey("Organization", "lei", Value.of("5493001KJTIIGC8Y1R12")),
                        new EntityId("e-2")),
                new KeyResolution(3, new UniqueKey("Person", "badge", Value.of(42)), new EntityId("e-3")),
                new KeyResolution(3, new UniqueKey("Organization", "lei", Value.of("5493001KJTIIGC8Y1R12")),
                        new EntityId("e-4")));
        DataRecord record = new DataRecord(transaction, new Receipt(new Basis(4), T2, Map.of(), List.of(), List.of(),
                resolutions, "importer", Optional.empty(), Optional.empty(), Optional.of("keys")), List.of());

        DataRecord read = (DataRecord) roundTrip(record);

        assertThat(read).isEqualTo(record);
        assertThat(read.receipt().keys()).containsExactlyElementsOf(resolutions);
        assertThat(read.transaction().writes()).hasSize(8);
        assertThat(((Write.Patch) read.transaction().writes().getFirst()).target())
                .isEqualTo(new UniqueKey("Account", "number", Value.of("A-1")));
        assertThat(((Write.Relate) read.transaction().writes().get(3)).target())
                .isEqualTo(new UniqueKey("Person", "badge", Value.of(42)));
    }

    @Test
    void theKeyResolutionsOfAReceiptAreTheLastBytesOfARecordAndARecordWithoutThemReadsAsBefore() {
        UniqueKey key = new UniqueKey("Account", "number", Value.of("A-1"));
        Transaction transaction = Transaction.by("importer").patch(key, EffectivePeriod.from(T2), Map.of()).build();
        Effect effect = new Effect(new EntityId("e-1"), new TypeId(2), false, Optional.empty(), Optional.empty(),
                List.of(segment(T2, Instant.MAX, T1, 4, Map.of(new AttributeId(3), Value.of(1)))));
        DataRecord without = new DataRecord(transaction, new Receipt(new Basis(4), T2, Map.of(), List.of(), List.of(),
                "importer", Optional.empty(), Optional.empty(), Optional.empty()), List.of(effect));
        DataRecord with = new DataRecord(transaction, new Receipt(new Basis(4), T2, Map.of(), List.of(), List.of(),
                List.of(new KeyResolution(0, key, new EntityId("e-1"))), "importer", Optional.empty(),
                Optional.empty(), Optional.empty()), List.of(effect));

        byte[] plain = payload(RecordCodec.encode(without));
        byte[] keyed = payload(RecordCodec.encode(with));

        assertThat(Arrays.copyOf(keyed, plain.length)).as("what a receipt without keys is encoded as, unchanged")
                .isEqualTo(plain);
        assertThat(keyed.length).isGreaterThan(plain.length);
        assertThat(((DataRecord) RecordCodec.decode(RecordCodec.VERSION, plain)).receipt().keys()).isEmpty();
        assertThat(RecordCodec.decode(RecordCodec.VERSION, keyed)).isEqualTo(with);
        byte[] cut = Arrays.copyOf(keyed, keyed.length - 1);
        assertThatThrownBy(() -> RecordCodec.decode(RecordCodec.VERSION, cut))
                .isInstanceOf(CorruptRecordException.class);
    }

    private static byte[] payload(byte[] frame) {
        return Arrays.copyOfRange(frame, Encoder.FRAME_HEADER, frame.length);
    }

    @Test
    void aReferenceOfAnUnknownKindIsCorrupt() {
        byte[] frame = RecordCodec.encode(new DataRecord(Transaction.by("importer")
                .endDate(new EntityId("e-1"), T2).build(), new Receipt(new Basis(2), T2, Map.of(), List.of(), List.of(),
                "importer", Optional.empty(), Optional.empty(), Optional.empty()), List.of()));
        byte[] payload = Arrays.copyOfRange(frame, Encoder.FRAME_HEADER, frame.length);
        // The kind of the reference is the byte that is 1 (an entity) before the text "e-1".
        byte[] text = "e-1".getBytes(StandardCharsets.UTF_8);
        int at = indexOf(payload, text) - 5; // the length of the text takes 4 bytes, and the kind 1 before it
        assertThat(payload[at]).isEqualTo((byte) 1);
        payload[at] = 9;

        assertThatThrownBy(() -> RecordCodec.decode(RecordCodec.VERSION, payload))
                .isInstanceOf(CorruptRecordException.class).hasMessageContaining("reference kind 9");
    }

    private static int indexOf(byte[] bytes, byte[] part) {
        for (int i = 0; i + part.length <= bytes.length; i++) {
            if (Arrays.equals(bytes, i, i + part.length, part, 0, part.length)) {
                return i;
            }
        }
        throw new AssertionError("The bytes do not hold the text.");
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
