package io.github.enricoag1982.cairn.core.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.core.storage.Journal;
import io.github.enricoag1982.cairn.core.storage.LogRecord;
import io.github.enricoag1982.cairn.core.storage.SchemaIdentifiers;
import io.github.enricoag1982.cairn.core.storage.SchemaRecord;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Submission;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Design D3 and D4 of {@code add-durable-commit-log}: a registration is logged with the identifiers it assigned, and
 * restoring it compiles the definition with those identifiers instead of numbering new elements.
 */
class SchemaRestoreTest {

    /** Keeps the records that a registry writes. */
    private static final class Recording implements Journal {
        final List<SchemaRecord> records = new ArrayList<>();

        @Override
        public void requireWritable() {
        }

        @Override
        public void append(LogRecord record) {
            records.add((SchemaRecord) record);
        }
    }

    private static InMemorySchemaRegistry registry() {
        return new InMemorySchemaRegistry(new TestClock(), ReferenceModel.rules());
    }

    private static SchemaDefinition version2() {
        AttributeDefinition nickname = AttributeDefinition.builder("nickname", ValueType.STRING).build();
        SchemaDefinition withNickname = Definitions.changeEntityType(ReferenceModel.definition(), "Person",
                person -> Definitions.withAttributes(person, List.of(person.attributes().getFirst(), nickname)));
        return Definitions.plus(withNickname, SchemaDefinition.builder().entityType("Vehicle",
                vehicle -> vehicle.attribute("plate", ValueType.STRING).attribute("seats", ValueType.INT)).build());
    }

    /** A registry that registered the reference model and then added to it, with the records it wrote. */
    private static Recording registerTwoVersions(InMemorySchemaRegistry registry) {
        Recording recording = new Recording();
        registry.attach(recording);
        assertThat(registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition(),
                Submission.by("agent:modeler"))).isInstanceOf(RegistrationResult.Applied.class);
        assertThat(registry.register(ReferenceModel.origin("1.1.0"), version2(), Submission.by("agent:modeler")))
                .isInstanceOf(RegistrationResult.Applied.class);
        return recording;
    }

    @Test
    void aRegistrationIsWrittenWithTheIdentifierOfEveryElementOfTheSchemaAndTheNextFreeOne() {
        InMemorySchemaRegistry registry = registry();

        Recording recording = registerTwoVersions(registry);

        SchemaRecord first = recording.records.get(0);
        SchemaRecord second = recording.records.get(1);
        List<String> firstTypes = List.of("Named", "Party", "Person", "Organization", "Address", "Account",
                "CustomerRole", "Owns", "Employs", "HeldBy", "HasAddress", "PlayedBy");
        assertThat(first.identifiers().types()).extracting(SchemaIdentifiers.TypeAssignment::name)
                .containsExactlyInAnyOrderElementsOf(firstTypes);
        assertThat(second.identifiers().types()).as("the types the first registration added, and the new one")
                .extracting(SchemaIdentifiers.TypeAssignment::name)
                .containsExactlyInAnyOrderElementsOf(Stream.concat(firstTypes.stream(), Stream.of("Vehicle")).toList());
        assertThat(second.identifiers().types()).as("kept types keep their identifiers")
                .containsAll(first.identifiers().types());
        assertThat(second.identifiers().attributes()).as("kept attributes keep theirs")
                .containsAll(first.identifiers().attributes());
        assertThat(second.identifiers().attributes()).extracting(SchemaIdentifiers.AttributeAssignment::name)
                .contains("name", "birthDate", "lei", "share", "nickname", "plate", "seats");
        assertThat(second.identifiers().attributes()).hasSize(first.identifiers().attributes().size() + 3);
        assertThat(second.identifiers().attributes()).extracting(SchemaIdentifiers.AttributeAssignment::declarer)
                .contains("Named", "Person", "Vehicle");
        assertThat(second.identifiers().nextId()).isEqualTo(first.identifiers().nextId() + 1 + 3);
        assertThat(second.definition()).isEqualTo(version2());
        assertThat(second.origin()).isEqualTo(ReferenceModel.origin("1.1.0"));
        assertThat(second.submission()).isEqualTo(Submission.by("agent:modeler"));
        assertThat(registry.records()).isEqualTo(recording.records);
        EntityType person = registry.current().entityType("Person").orElseThrow();
        assertThat(second.identifiers().types()).contains(new SchemaIdentifiers.TypeAssignment("Person", person.id()));
        assertThat(person.attributes()).allSatisfy(attribute -> assertThat(second.identifiers().attributes())
                .contains(new SchemaIdentifiers.AttributeAssignment(attribute.declaredBy(), attribute.name(),
                        attribute.id())));
    }

    @Test
    void restoringTheRecordsGivesTheSameSchemasAndHistory() {
        InMemorySchemaRegistry original = registry();
        Recording recording = registerTwoVersions(original);

        InMemorySchemaRegistry restored = registry();
        recording.records.forEach(restored::restore);

        assertThat(restored.history()).hasSize(2);
        for (int i = 0; i < 2; i++) {
            var expected = original.history().get(i);
            var actual = restored.history().get(i);
            assertThat(actual.basis()).isEqualTo(expected.basis());
            assertThat(actual.transactionTime()).isEqualTo(expected.transactionTime());
            assertThat(actual.origin()).isEqualTo(expected.origin());
            assertThat(actual.principal()).isEqualTo(expected.principal());
            assertThat(actual.schema().entityTypes()).isEqualTo(expected.schema().entityTypes());
            assertThat(actual.schema().traits()).isEqualTo(expected.schema().traits());
            assertThat(actual.schema().relationshipTypes()).isEqualTo(expected.schema().relationshipTypes());
            assertThat(actual.schema().origins()).isEqualTo(expected.schema().origins());
        }
        assertThat(restored.storedDefinition("reference-model")).isEqualTo(original.storedDefinition("reference-model"));
        assertThat(restored.records()).isEqualTo(recording.records);
    }

    @Test
    void theRegistrationThatFollowsARestoredOneNumbersFromTheLoggedNextIdentifier() {
        InMemorySchemaRegistry original = registry();
        Recording recording = registerTwoVersions(original);
        long nextAfterVersion2 = recording.records.get(1).identifiers().nextId();
        InMemorySchemaRegistry restored = registry();
        recording.records.forEach(restored::restore);
        SchemaDefinition version3 = Definitions.plus(version2(), SchemaDefinition.builder()
                .entityType("Boat", boat -> boat.attribute("length", ValueType.INT)).build());

        original.register(ReferenceModel.origin("1.2.0"), version3);
        restored.register(ReferenceModel.origin("1.2.0"), version3);

        EntityType boat = restored.current().entityType("Boat").orElseThrow();
        assertThat(boat.id()).isEqualTo(original.current().entityType("Boat").orElseThrow().id());
        assertThat(boat.attributes()).extracting(attribute -> attribute.id()).isEqualTo(
                original.current().entityType("Boat").orElseThrow().attributes().stream()
                        .map(attribute -> attribute.id()).toList());
        assertThat(boat.id().value()).as("the first identifier that the logged record left free")
                .isEqualTo(nextAfterVersion2);
    }

    @Test
    void restoringUsesTheLoggedIdentifiersAndDoesNotNumberAnything() {
        InMemorySchemaRegistry original = registry();
        Recording recording = registerTwoVersions(original);
        SchemaRecord first = recording.records.getFirst();
        long shift = 1_000;
        SchemaIdentifiers shifted = new SchemaIdentifiers(
                first.identifiers().types().stream().map(type -> new SchemaIdentifiers.TypeAssignment(type.name(),
                        new TypeId(type.id().value() + shift))).toList(),
                first.identifiers().attributes().stream().map(attribute -> new SchemaIdentifiers.AttributeAssignment(
                        attribute.declarer(), attribute.name(), new AttributeId(attribute.id().value() + shift)))
                        .toList(),
                first.identifiers().nextId() + shift);
        SchemaRecord logged = new SchemaRecord(first.basis(), first.transactionTime(), first.submission(),
                first.origin(), first.definition(), shifted);

        InMemorySchemaRegistry restored = registry();
        restored.restore(logged);

        assertThat(restored.current().entityType("Party").orElseThrow().id().value()).isEqualTo(
                original.history().getFirst().schema().entityType("Party").orElseThrow().id().value() + shift);
        assertThat(restored.current().entityType("Person").orElseThrow().attributes())
                .extracting(attribute -> attribute.id().value())
                .allSatisfy(id -> assertThat(id).isGreaterThan(shift));
    }

    private static SchemaRecord with(SchemaRecord record, SchemaIdentifiers identifiers) {
        return new SchemaRecord(record.basis(), record.transactionTime(), record.submission(), record.origin(),
                record.definition(), identifiers);
    }

    @Test
    void restoringTakesTheIdentifierOfAKeptElementFromTheRecordAndDoesNotMatchByName() {
        InMemorySchemaRegistry original = registry();
        Recording recording = registerTwoVersions(original);
        SchemaRecord first = recording.records.getFirst();
        SchemaRecord second = recording.records.get(1);
        long unusedParty = second.identifiers().nextId() + 10;
        long unusedName = second.identifiers().nextId() + 11;
        SchemaIdentifiers other = new SchemaIdentifiers(
                second.identifiers().types().stream().map(type -> type.name().equals("Party")
                        ? new SchemaIdentifiers.TypeAssignment("Party", new TypeId(unusedParty)) : type).toList(),
                second.identifiers().attributes().stream().map(attribute -> attribute.name().equals("name")
                        ? new SchemaIdentifiers.AttributeAssignment(attribute.declarer(), "name",
                                new AttributeId(unusedName)) : attribute).toList(),
                second.identifiers().nextId() + 20);

        InMemorySchemaRegistry restored = registry();
        restored.restore(first);
        restored.restore(with(second, other));

        EntityType party = restored.current().entityType("Party").orElseThrow();
        assertThat(original.history().getFirst().schema().entityType("Party").orElseThrow().id())
                .as("name matching would keep this one").isNotEqualTo(party.id());
        assertThat(party.id()).isEqualTo(new TypeId(unusedParty));
        assertThat(restored.current().entityType("Person").orElseThrow().attributes())
                .filteredOn(attribute -> attribute.name().equals("name")).singleElement()
                .satisfies(attribute -> assertThat(attribute.id()).isEqualTo(new AttributeId(unusedName)));
        assertThat(restored.history().getFirst().schema().entityType("Party").orElseThrow().id())
                .as("the first schema is as it was").isEqualTo(original.history().getFirst().schema()
                        .entityType("Party").orElseThrow().id());
    }

    @Test
    void restoringTakesTheIdentifierOfARenamedElementFromTheRecord() {
        InMemorySchemaRegistry original = registry();
        Recording recording = new Recording();
        original.attach(recording);
        Origin things = new Origin("things", "1");
        original.register(things, SchemaDefinition.builder().entityType("Thing",
                thing -> thing.attribute("size", ValueType.INT)).build());
        original.register(new Origin("things", "2"), SchemaDefinition.builder().entityType("Item",
                item -> item.aliases("Thing").attribute("size", ValueType.INT)).build());
        SchemaRecord first = recording.records.get(0);
        SchemaRecord second = recording.records.get(1);
        assertThat(second.identifiers().types()).extracting(SchemaIdentifiers.TypeAssignment::id)
                .as("matching by alias kept the identifier of Thing")
                .containsExactly(first.identifiers().types().getFirst().id());
        long other = second.identifiers().nextId() + 5;
        SchemaIdentifiers renumbered = new SchemaIdentifiers(
                List.of(new SchemaIdentifiers.TypeAssignment("Item", new TypeId(other))),
                second.identifiers().attributes(), other + 1);

        InMemorySchemaRegistry restored = registry();
        restored.restore(first);
        restored.restore(with(second, renumbered));

        assertThat(restored.current().entityType("Item").orElseThrow().id()).isEqualTo(new TypeId(other));
    }

    @Test
    void aRecordWithoutTheIdentifierOfAnyElementIsRefused() {
        InMemorySchemaRegistry original = registry();
        Recording recording = registerTwoVersions(original);
        SchemaRecord first = recording.records.getFirst();
        SchemaRecord second = recording.records.get(1);
        SchemaIdentifiers withoutParty = new SchemaIdentifiers(first.identifiers().types().stream()
                .filter(type -> !type.name().equals("Party")).toList(), first.identifiers().attributes(),
                first.identifiers().nextId());
        SchemaIdentifiers withoutBirthDate = new SchemaIdentifiers(first.identifiers().types(),
                first.identifiers().attributes().stream().filter(attribute -> !attribute.name().equals("birthDate"))
                        .toList(), first.identifiers().nextId());
        SchemaIdentifiers withoutKeptType = new SchemaIdentifiers(second.identifiers().types().stream()
                .filter(type -> !type.name().equals("Person")).toList(), second.identifiers().attributes(),
                second.identifiers().nextId());
        SchemaIdentifiers withoutKeptAttribute = new SchemaIdentifiers(second.identifiers().types(),
                second.identifiers().attributes().stream().filter(attribute -> !attribute.name().equals("lei"))
                        .toList(), second.identifiers().nextId());

        assertThatThrownBy(() -> registry().restore(with(first, withoutParty)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Party");
        assertThatThrownBy(() -> registry().restore(with(first, withoutBirthDate)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("birthDate");
        InMemorySchemaRegistry restored = registry();
        restored.restore(first);
        assertThatThrownBy(() -> restored.restore(with(second, withoutKeptType)))
                .as("a kept type needs its identifier too").isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Person");
        assertThatThrownBy(() -> restored.restore(with(second, withoutKeptAttribute)))
                .as("and a kept attribute").isInstanceOf(IllegalStateException.class).hasMessageContaining("lei");
        assertThat(restored.history()).as("nothing was added by the refused records").hasSize(1);
    }

    @Test
    void aRecordThatGivesOneIdentifierToTwoElementsOrOneBeyondItsNextIdentifierIsRefused() {
        InMemorySchemaRegistry original = registry();
        SchemaRecord first = registerTwoVersions(original).records.getFirst();
        List<SchemaIdentifiers.TypeAssignment> types = new ArrayList<>(first.identifiers().types());
        types.set(1, new SchemaIdentifiers.TypeAssignment(types.get(1).name(), types.get(0).id()));
        SchemaIdentifiers twice = new SchemaIdentifiers(types, first.identifiers().attributes(),
                first.identifiers().nextId());
        SchemaIdentifiers beyond = new SchemaIdentifiers(first.identifiers().types(), first.identifiers().attributes(),
                3);

        assertThatThrownBy(() -> registry().restore(with(first, twice))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than one element");
        assertThatThrownBy(() -> registry().restore(with(first, beyond))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("next identifier");
    }

    @Test
    void restoringRecordsOfAnotherOriginKeepsTheTypesOfTheFirst() {
        InMemorySchemaRegistry original = registry();
        Recording recording = new Recording();
        original.attach(recording);
        original.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition());
        original.register(new Origin("garage", "1"), SchemaDefinition.builder().entityType("Car",
                car -> car.attribute("plate", ValueType.STRING)).build());

        InMemorySchemaRegistry restored = registry();
        recording.records.forEach(restored::restore);

        assertThat(restored.current().origins()).extracting(Origin::name).containsExactly("garage", "reference-model");
        assertThat(restored.current().entityType("Car").orElseThrow().id())
                .isEqualTo(original.current().entityType("Car").orElseThrow().id());
        assertThat(restored.current().entityType("Party")).isPresent();
        assertThat(restored.history()).extracting(transaction -> transaction.basis()).containsExactly(new Basis(1),
                new Basis(2));
    }
}
