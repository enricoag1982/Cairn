package io.github.enricoag1982.cairn.schema;

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
        assertThat(name.description()).isEmpty();
        assertThat(name.examples()).isEmpty();
        assertThat(name.allowedValues()).isEmpty();
        assertThat(name.pattern()).isEmpty();
        assertThat(name.minLength()).isEmpty();
        assertThat(name.maxLength()).isEmpty();

        RelationshipTypeDefinition knows = definition.relationshipTypes().getFirst();
        assertThat(knows.symmetric()).isTrue();
        assertThat(knows.kind()).isEqualTo(RelationshipKind.ASSOCIATION);
        assertThat(knows.inverseAliases()).containsExactly("acquaintedWith");
        assertThat(knows.supertype()).isEqualTo(Optional.of("Related"));
        assertThat(knows.aliases()).containsExactly("Met");
        assertThat(knows.description()).isEmpty();
        assertThat(knows.rules()).isEmpty();
    }

    @Test
    void buildsDescriptionsExamplesStringConstraintsAndRuleReferences() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .trait("Named", t -> t.description("Something with a name"))
                .entityType("Organization", e -> e
                        .description("A company or another legal entity")
                        .rule("lei-checksum", 1)
                        .rule("name-not-blank", 2)
                        .attribute("lei", ValueType.STRING, a -> a
                                .description("Legal Entity Identifier")
                                .pattern("[A-Z0-9]{18}[0-9]{2}")
                                .minLength(20).maxLength(20)
                                .examples(Literal.of("5493001KJTIIGC8Y1R12"))
                                .examples(Literal.of("529900T8BM49AURSDO55")))
                        .attribute("segment", ValueType.STRING, a -> a.allowedValues("Corporate", "Retail")
                                .allowedValues("Private")))
                .relationshipType("Owns", r -> r.description("Ownership").rule("total-ownership-at-most-100", 1))
                .build();

        assertThat(definition.traits().getFirst().description()).contains("Something with a name");
        EntityTypeDefinition organization = definition.entityTypes().getFirst();
        assertThat(organization.description()).contains("A company or another legal entity");
        assertThat(organization.rules()).containsExactly(
                new RuleReference("lei-checksum", 1), new RuleReference("name-not-blank", 2));
        AttributeDefinition lei = organization.attributes().getFirst();
        assertThat(lei.description()).contains("Legal Entity Identifier");
        assertThat(lei.pattern()).contains("[A-Z0-9]{18}[0-9]{2}");
        assertThat(lei.minLength()).hasValue(20);
        assertThat(lei.maxLength()).hasValue(20);
        assertThat(lei.examples()).containsExactly(Literal.of("5493001KJTIIGC8Y1R12"), Literal.of("529900T8BM49AURSDO55"));
        assertThat(organization.attributes().get(1).allowedValues()).containsExactly("Corporate", "Retail", "Private");
        RelationshipTypeDefinition owns = definition.relationshipTypes().getFirst();
        assertThat(owns.description()).contains("Ownership");
        assertThat(owns.rules()).containsExactly(new RuleReference("total-ownership-at-most-100", 1));
    }

    @Test
    void theBuilderDoesNotCheckLengths() {
        AttributeDefinition attribute = AttributeDefinition.builder("segment", ValueType.STRING)
                .minLength(5).maxLength(3).build();

        assertThat(attribute.minLength()).hasValue(5);
        assertThat(attribute.maxLength()).hasValue(3);
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
