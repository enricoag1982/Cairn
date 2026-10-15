package io.github.enricoag1982.cairn.documents;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.documents.GeneratedDefinitions.Generated;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RelationshipKind;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Round trips over definitions that a seeded generator produces (design D7): what the writer writes, the reader reads
 * back as an equal origin and definition, in both formats, and the documents are valid against the published JSON
 * Schema. The one difference is that a number of a narrower type than its attribute's, such as an int bound of a decimal
 * attribute, comes back in the attribute's type.
 */
class RoundTripTest {

    private static final int SEEDS = 300;

    private static final List<Generated> GENERATED =
            LongStream.range(0, SEEDS).mapToObj(GeneratedDefinitions::generate).toList();

    private static Stream<AttributeDefinition> attributes(SchemaDefinition definition) {
        return Stream.of(
                definition.traits().stream().flatMap(trait -> trait.attributes().stream()),
                definition.entityTypes().stream().flatMap(type -> type.attributes().stream()),
                definition.relationshipTypes().stream().flatMap(type -> type.attributes().stream()))
                .flatMap(attributes -> attributes);
    }

    private static Stream<Bound> bounds(AttributeDefinition attribute) {
        return attribute.range().stream().flatMap(range -> Stream.concat(range.lower().stream(), range.upper().stream()));
    }

    // ------------------------------------------------------------------ round trips

    @Test
    @DisplayName("Generated definitions give back equal records through YAML and through JSON")
    void generatedDefinitionsSurviveARoundTrip() {
        for (int seed = 0; seed < SEEDS; seed++) {
            Generated generated = GeneratedDefinitions.generate(seed);
            for (String text : List.of(
                    SchemaDocuments.writeYaml(generated.origin(), generated.definition()),
                    SchemaDocuments.writeJson(generated.origin(), generated.definition()))) {
                DocumentReadResult result = SchemaDocuments.read(text);

                assertThat(result).as("seed %d reads without problems:%n%s", seed, text).isInstanceOf(DocumentReadResult.Read.class);
                SchemaDocument document = ((DocumentReadResult.Read) result).document();
                assertThat(document.origin()).as("seed %d origin of:%n%s", seed, text).isEqualTo(generated.origin());
                assertThat(document.definition()).as("seed %d definition of:%n%s", seed, text)
                        .isEqualTo(generated.readBack());
            }
        }
    }

    @Test
    @DisplayName("Writing what was read gives the same text")
    void writingWhatWasReadGivesTheSameText() {
        for (int seed = 0; seed < SEEDS; seed++) {
            Generated generated = GeneratedDefinitions.generate(seed);
            String yaml = SchemaDocuments.writeYaml(generated.origin(), generated.definition());
            String json = SchemaDocuments.writeJson(generated.origin(), generated.definition());

            SchemaDocument fromYaml = Documents.valid(yaml);
            SchemaDocument fromJson = Documents.valid(json);

            assertThat(SchemaDocuments.writeYaml(fromYaml.origin(), fromYaml.definition())).as("seed %d", seed).isEqualTo(yaml);
            assertThat(SchemaDocuments.writeJson(fromJson.origin(), fromJson.definition())).as("seed %d", seed).isEqualTo(json);
            assertThat(SchemaDocuments.writeJson(fromYaml.origin(), fromYaml.definition())).as("seed %d", seed).isEqualTo(json);
        }
    }

    @Test
    @DisplayName("Generated documents are valid against the published JSON Schema")
    void generatedDocumentsAreValidAgainstTheJsonSchema() {
        for (int seed = 0; seed < SEEDS; seed++) {
            Generated generated = GeneratedDefinitions.generate(seed);
            String yaml = SchemaDocuments.writeYaml(generated.origin(), generated.definition());
            String json = SchemaDocuments.writeJson(generated.origin(), generated.definition());

            assertThat(PublishedSchemaCheck.errors(yaml)).as("seed %d as YAML:%n%s", seed, yaml).isEmpty();
            assertThat(PublishedSchemaCheck.errors(json)).as("seed %d as JSON, converted:%n%s", seed, json).isEmpty();
            assertThat(PublishedSchemaCheck.errorsOfJson(json)).as("seed %d as JSON:%n%s", seed, json).isEmpty();
        }
    }

    @Test
    @DisplayName("Widened numbers come back in the attribute's type, and everything else is equal")
    void widenedNumbersComeBackInTheAttributesType() {
        int widened = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            Generated generated = GeneratedDefinitions.generate(seed);
            widened += (int) attributes(generated.definition()).filter(RoundTripTest::hasNarrowerNumber).count();
            for (String text : List.of(
                    SchemaDocuments.writeYaml(generated.origin(), generated.definition()),
                    SchemaDocuments.writeJson(generated.origin(), generated.definition()))) {
                SchemaDefinition definition = Documents.valid(text).definition();

                attributes(definition).forEach(attribute -> {
                    assertThat(attribute.examples()).allSatisfy(example ->
                            assertThat(example.valueType()).isEqualTo(attribute.valueType()));
                    assertThat(bounds(attribute).toList()).allSatisfy(bound ->
                            assertThat(bound.value().valueType()).isEqualTo(attribute.valueType()));
                });
                assertThat(definition).as("seed %d", seed).isEqualTo(generated.readBack());
            }
        }
        assertThat(widened).as("attributes with a number of a narrower type").isPositive();
    }

    /** Tells whether an attribute has an example or a bound that is a number of a narrower type than the attribute. */
    private static boolean hasNarrowerNumber(AttributeDefinition attribute) {
        return Stream.concat(attribute.examples().stream(), bounds(attribute).map(Bound::value))
                .anyMatch(value -> value.valueType() != attribute.valueType());
    }

    @Test
    void theGeneratorGivesTheSameDefinitionForTheSameSeed() {
        for (long seed : List.of(0L, 1L, 42L, 299L)) {
            assertThat(GeneratedDefinitions.generate(seed)).isEqualTo(GeneratedDefinitions.generate(seed));
        }
        assertThat(GeneratedDefinitions.generate(1)).isNotEqualTo(GeneratedDefinitions.generate(2));
    }

    // ------------------------------------------------------------------ the generator reaches every property

    private static void assertSome(String what, Predicate<Generated> predicate) {
        assertThat(GENERATED).as("some seed has %s", what).anyMatch(predicate);
    }

    private static void assertSomeAttribute(String what, Predicate<AttributeDefinition> predicate) {
        assertSome(what, generated -> attributes(generated.definition()).anyMatch(predicate));
    }

    @Test
    void theGeneratedTypesUseEveryProperty() {
        assertSome("an empty definition", g -> g.definition().equals(SchemaDefinition.EMPTY));
        assertSome("a trait with attributes, aliases, deprecation and a description", g -> g.definition().traits().stream()
                .anyMatch(t -> !t.attributes().isEmpty() && !t.aliases().isEmpty() && t.deprecated()
                        && t.description().isPresent()));
        assertSome("an abstract entity type", g -> g.definition().entityTypes().stream().anyMatch(t -> t.isAbstract()));
        assertSome("an entity type with a supertype", g -> g.definition().entityTypes().stream()
                .anyMatch(t -> t.supertype().isPresent()));
        assertSome("an entity type with traits", g -> g.definition().entityTypes().stream()
                .anyMatch(t -> !t.traits().isEmpty()));
        assertSome("an entity type with aliases, deprecation, a description and rules", g -> g.definition().entityTypes()
                .stream().anyMatch(t -> !t.aliases().isEmpty() && t.deprecated() && t.description().isPresent()
                        && !t.rules().isEmpty()));
        assertSome("an entity type with all defaults", g -> g.definition().entityTypes().stream().anyMatch(t ->
                !t.isAbstract() && t.supertype().isEmpty() && t.traits().isEmpty() && t.attributes().isEmpty()
                        && t.aliases().isEmpty() && !t.deprecated() && t.description().isEmpty() && t.rules().isEmpty()));
        assertSome("a composition", g -> g.definition().relationshipTypes().stream()
                .anyMatch(t -> t.kind() == RelationshipKind.COMPOSITION));
        assertSome("an association", g -> g.definition().relationshipTypes().stream()
                .anyMatch(t -> t.kind() == RelationshipKind.ASSOCIATION));
        assertSome("a symmetric relationship type", g -> g.definition().relationshipTypes().stream()
                .anyMatch(RelationshipTypeDefinition::symmetric));
        assertSome("a relationship type with inverse aliases, a supertype, aliases, deprecation, a description and rules",
                g -> g.definition().relationshipTypes().stream().anyMatch(t -> !t.inverseAliases().isEmpty()
                        && t.supertype().isPresent() && !t.aliases().isEmpty() && t.deprecated()
                        && t.description().isPresent() && !t.rules().isEmpty()));
        for (Multiplicity multiplicity : List.of(Multiplicity.ZERO_OR_ONE, Multiplicity.EXACTLY_ONE,
                Multiplicity.ZERO_OR_MORE, Multiplicity.ONE_OR_MORE)) {
            assertSome("the multiplicity " + multiplicity + " at both ends", g -> g.definition().relationshipTypes().stream()
                    .anyMatch(t -> t.targetsPerSource().equals(multiplicity))
                    && g.definition().relationshipTypes().stream().anyMatch(t -> t.sourcesPerTarget().equals(multiplicity)));
        }
    }

    @Test
    void theGeneratedAttributesUseEveryProperty() {
        assertSomeAttribute("a required, unique, deprecated attribute", a -> a.required() && a.unique() && a.deprecated());
        assertSomeAttribute("an attribute with aliases and a description", a -> !a.aliases().isEmpty() && a.description().isPresent());
        assertSomeAttribute("an attribute with allowed values", a -> !a.allowedValues().isEmpty());
        assertSomeAttribute("an attribute with a pattern", a -> a.pattern().isPresent());
        assertSomeAttribute("an attribute with both lengths", a -> a.minLength().isPresent() && a.maxLength().isPresent());
        assertSomeAttribute("an attribute with all defaults", a -> !a.required() && !a.unique() && a.range().isEmpty()
                && a.aliases().isEmpty() && !a.deprecated() && a.description().isEmpty() && a.examples().isEmpty()
                && a.allowedValues().isEmpty() && a.pattern().isEmpty() && a.minLength().isEmpty() && a.maxLength().isEmpty());
        assertSomeAttribute("an attribute with an empty range", a -> a.range().equals(Optional.of(new Range(Optional.empty(),
                Optional.empty()))));
        for (ValueType type : ValueType.values()) {
            assertSomeAttribute("examples of the type " + type, a -> a.valueType() == type && !a.examples().isEmpty()
                    && a.examples().stream().allMatch(example -> example.valueType() == type));
            if (type.isOrdered()) {
                for (boolean inclusive : List.of(true, false)) {
                    assertSomeAttribute("a lower bound of the type " + type + (inclusive ? " included" : " excluded"),
                            a -> a.valueType() == type && a.range().flatMap(Range::lower)
                                    .filter(bound -> bound.inclusive() == inclusive).isPresent());
                    assertSomeAttribute("an upper bound of the type " + type + (inclusive ? " included" : " excluded"),
                            a -> a.valueType() == type && a.range().flatMap(Range::upper)
                                    .filter(bound -> bound.inclusive() == inclusive).isPresent());
                }
            }
        }
        assertSomeAttribute("a decimal with a negative scale", a -> decimals(a).anyMatch(d -> d.scale() < 0));
        assertSomeAttribute("a decimal with trailing zeros", a -> decimals(a)
                .anyMatch(d -> d.scale() > 0 && d.stripTrailingZeros().scale() < d.scale()));
    }

    @Test
    void theGeneratedNumbersOfANarrowerTypeAreWidenedByReadBack() {
        for (Generated generated : GENERATED) {
            attributes(generated.readBack()).forEach(attribute -> {
                assertThat(attribute.examples()).allSatisfy(example ->
                        assertThat(example.valueType()).isEqualTo(attribute.valueType()));
                assertThat(bounds(attribute).toList()).allSatisfy(bound ->
                        assertThat(bound.value().valueType()).isEqualTo(attribute.valueType()));
            });
        }
    }

    @Test
    void theGeneratedAttributesHaveNumbersOfNarrowerTypes() {
        // The attribute's type and the type of the number written for it.
        List<List<ValueType>> widenings = List.of(
                List.of(ValueType.LONG, ValueType.INT),
                List.of(ValueType.DECIMAL, ValueType.INT),
                List.of(ValueType.DECIMAL, ValueType.LONG));
        for (List<ValueType> widening : widenings) {
            ValueType attributeType = widening.get(0);
            ValueType literalType = widening.get(1);
            assertSomeAttribute("an example of the type " + literalType + " on an attribute of the type " + attributeType,
                    a -> a.valueType() == attributeType
                            && a.examples().stream().anyMatch(example -> example.valueType() == literalType));
            assertSomeAttribute("a bound of the type " + literalType + " on an attribute of the type " + attributeType,
                    a -> a.valueType() == attributeType
                            && bounds(a).anyMatch(bound -> bound.value().valueType() == literalType));
        }
    }

    private static Stream<BigDecimal> decimals(AttributeDefinition attribute) {
        return Stream.concat(attribute.examples().stream(), bounds(attribute).map(Bound::value))
                .filter(Value.DecimalValue.class::isInstance).map(value -> ((Value.DecimalValue) value).value());
    }
}
