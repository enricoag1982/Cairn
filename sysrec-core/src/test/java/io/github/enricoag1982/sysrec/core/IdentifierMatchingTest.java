package io.github.enricoag1982.sysrec.core;

import static io.github.enricoag1982.sysrec.core.Results.applied;
import static io.github.enricoag1982.sysrec.fixtures.Definitions.changeEntityType;
import static io.github.enricoag1982.sysrec.fixtures.Definitions.renamed;
import static io.github.enricoag1982.sysrec.fixtures.Definitions.withAttributes;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.sysrec.fixtures.ReferenceModel;
import io.github.enricoag1982.sysrec.schema.Attribute;
import io.github.enricoag1982.sysrec.schema.AttributeDefinition;
import io.github.enricoag1982.sysrec.schema.Schema;
import io.github.enricoag1982.sysrec.schema.SchemaDefinition;
import io.github.enricoag1982.sysrec.schema.SchemaRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Design D5: identifiers survive moves to a supertype and renames with an alias. */
class IdentifierMatchingTest {

    private final SchemaRegistry registry = SchemaRegistries.inMemory(new TestClock());

    @Test
    void anAttributeMovedToASupertypeKeepsItsIdentifier() {
        Attribute before = birthDate(applied(registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition())).schema());

        Schema after = applied(registry.register(ReferenceModel.origin("1.1.0"), moveBirthDateToParty(null))).schema();

        Attribute moved = after.entityType("Party").orElseThrow().attribute("birthDate").orElseThrow();
        assertThat(moved.declaredBy()).isEqualTo("Party");
        assertThat(moved.id()).isEqualTo(before.id());
        assertThat(after.entityType("Organization").orElseThrow().attribute("birthDate")).isPresent();
    }

    @Test
    void anAttributeRenamedWithAnAliasKeepsItsIdentifier() {
        Attribute before = birthDate(applied(registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition())).schema());
        SchemaDefinition renamedBirthDate = changeEntityType(ReferenceModel.definition(), "Person", type ->
                withAttributes(type, List.of(renamed(type.attributes().getFirst(), "dateOfBirth", "birthDate"))));

        Schema after = applied(registry.register(ReferenceModel.origin("1.1.0"), renamedBirthDate)).schema();

        Attribute renamedAttribute = after.entityType("Person").orElseThrow().attribute("dateOfBirth").orElseThrow();
        assertThat(renamedAttribute.id()).isEqualTo(before.id());
        assertThat(renamedAttribute.aliases()).containsExactly("birthDate");
    }

    @Test
    void anAttributeRenamedAndMovedKeepsItsIdentifier() {
        Attribute before = birthDate(applied(registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition())).schema());

        Schema after = applied(registry.register(ReferenceModel.origin("1.1.0"), moveBirthDateToParty("dateOfBirth"))).schema();

        Attribute moved = after.entityType("Party").orElseThrow().attribute("dateOfBirth").orElseThrow();
        assertThat(moved.id()).isEqualTo(before.id());
        assertThat(after.entityType("Person").orElseThrow().attribute("birthDate").orElseThrow().name())
                .isEqualTo("dateOfBirth");
    }

    @Test
    void aTypeRenamedWithAnAliasKeepsItsIdentifier() {
        Schema before = applied(registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition())).schema();
        SchemaDefinition renamedAccount = changeEntityType(ReferenceModel.definition(), "Account", type ->
                new io.github.enricoag1982.sysrec.schema.EntityTypeDefinition("Ledger", type.isAbstract(),
                        type.supertype(), type.traits(), type.attributes(), List.of("Account"), type.deprecated()));

        Schema after = applied(registry.register(ReferenceModel.origin("1.1.0"), renamedAccount)).schema();

        assertThat(after.entityType("Ledger").orElseThrow().id()).isEqualTo(before.entityType("Account").orElseThrow().id());
        assertThat(after.entityType("Account").orElseThrow().name()).isEqualTo("Ledger");
        assertThat(after.relationshipType("HeldBy").orElseThrow().source()).isEqualTo("Ledger");
        assertThat(after.entityType("Ledger").orElseThrow().attribute("number").orElseThrow().id())
                .isEqualTo(before.entityType("Account").orElseThrow().attribute("number").orElseThrow().id());
    }

    private static Attribute birthDate(Schema schema) {
        return schema.entityType("Person").orElseThrow().attribute("birthDate").orElseThrow();
    }

    /** Moves the optional attribute birthDate from Person to Party, renaming it if a new name is given. */
    private static SchemaDefinition moveBirthDateToParty(String newName) {
        SchemaDefinition withoutOnPerson = changeEntityType(ReferenceModel.definition(), "Person", type ->
                withAttributes(type, List.of()));
        return changeEntityType(withoutOnPerson, "Party", type -> {
            AttributeDefinition birthDate = ReferenceModel.definition().entityTypes().stream()
                    .filter(entity -> entity.name().equals("Person")).findFirst().orElseThrow().attributes().getFirst();
            return withAttributes(type, List.of(newName == null ? birthDate : renamed(birthDate, newName, "birthDate")));
        });
    }
}
