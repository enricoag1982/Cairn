package io.github.enricoag1982.sysrec.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchemaDefinitionBuilderTest {

    @Test
    void buildsEveryKindOfElement() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .trait("Named", t -> t.attribute("name", ValueType.STRING, a -> a.required()).aliases("Titled"))
                .entityType("Party", e -> e.abstractType().includes("Named").deprecated())
                .entityType("Person", e -> e.supertype("Party").attribute("birthDate", ValueType.DATE))
                .relationshipType("Knows", r -> r.from("Person").to("Person").symmetric().inverse("knownBy")
                        .inverseAliases("acquaintedWith").supertype("Related").aliases("Met"))
                .build();

        EntityTypeDefinition party = definition.entityTypes().getFirst();
        assertThat(party.isAbstract()).isTrue();
        assertThat(party.traits()).containsExactly("Named");
        assertThat(party.deprecated()).isTrue();
        assertThat(definition.entityTypes().get(1).supertype()).contains("Party");

        AttributeDefinition name = definition.traits().getFirst().attributes().getFirst();
        assertThat(name.required()).isTrue();
        assertThat(name.unique()).isFalse();
        assertThat(name.range()).isEmpty();

        RelationshipTypeDefinition knows = definition.relationshipTypes().getFirst();
        assertThat(knows.symmetric()).isTrue();
        assertThat(knows.kind()).isEqualTo(RelationshipKind.ASSOCIATION);
        assertThat(knows.inverseAliases()).containsExactly("acquaintedWith");
        assertThat(knows.supertype()).isEqualTo(Optional.of("Related"));
        assertThat(knows.aliases()).containsExactly("Met");
    }

    @Test
    void relationshipTypesDefaultToAnAssociationWithAnyNumberAtBothEnds() {
        RelationshipTypeDefinition owns = SchemaDefinition.builder()
                .relationshipType("Owns", r -> { })
                .build()
                .relationshipTypes().getFirst();

        assertThat(owns.kind()).isEqualTo(RelationshipKind.ASSOCIATION);
        assertThat(owns.targetsPerSource()).isEqualTo(Multiplicity.ZERO_OR_MORE);
        assertThat(owns.sourcesPerTarget()).isEqualTo(Multiplicity.ZERO_OR_MORE);
        assertThat(owns.source()).isEmpty();
        assertThat(owns.target()).isEmpty();
        assertThat(owns.inverseName()).isEmpty();
    }
}
