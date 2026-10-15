package io.github.enricoag1982.cairn.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reader and the published JSON Schema must not drift apart (design D8): at every level of a document, the keys the
 * reader accepts ({@link Keys}) are the properties the JSON Schema declares, with the same required keys and the same
 * choices, and the JSON Schema is closed and described everywhere.
 */
class JsonSchemaDriftTest {

    private static final Object SCHEMA = DocumentTrees.load(SchemaDocuments.jsonSchema());

    /** Follows a {@code $ref} to a definition of the schema; any other node is returned as it is. */
    private static Object resolve(Object node) {
        if (node instanceof Map<?, ?> map && map.get("$ref") instanceof String reference) {
            assertThat(reference).startsWith("#/$defs/");
            return DocumentTrees.at(SCHEMA, "$defs", reference.substring("#/$defs/".length()));
        }
        return node;
    }

    /** The schema at the end of a path, following references on the way. */
    private static Object schemaAt(Object from, String... path) {
        Object node = resolve(from);
        for (String key : path) {
            node = resolve(DocumentTrees.at(node, key));
        }
        return node;
    }

    private static List<String> propertyNames(Object schema) {
        return DocumentTrees.keysAt(schema, "properties");
    }

    private static List<String> required(Object schema) {
        return DocumentTrees.mapAt(schema).containsKey("required")
                ? ((List<?>) DocumentTrees.at(schema, "required")).stream().map(String::valueOf).toList() : List.of();
    }

    private static List<String> enumOf(Object schema) {
        return ((List<?>) DocumentTrees.at(schema, "enum")).stream().map(String::valueOf).toList();
    }

    private static void assertLevel(String level, Object schema, List<String> keys, List<String> requiredKeys) {
        assertThat(propertyNames(schema)).as("properties of the %s", level).containsExactlyInAnyOrderElementsOf(keys);
        assertThat(required(schema)).as("required keys of the %s", level).containsExactlyInAnyOrderElementsOf(requiredKeys);
        assertThat(DocumentTrees.at(schema, "additionalProperties")).as("additionalProperties of the %s", level)
                .isEqualTo(false);
    }

    @Test
    @DisplayName("The keys of every level are the same in the reader and in the JSON Schema")
    void theKeysOfEveryLevelAreTheSameInTheReaderAndInTheJsonSchema() {
        Object trait = schemaAt(SCHEMA, "properties", "traits", "additionalProperties");
        Object entityType = schemaAt(SCHEMA, "properties", "entityTypes", "additionalProperties");
        Object relationshipType = schemaAt(SCHEMA, "properties", "relationshipTypes", "additionalProperties");

        assertLevel("document", SCHEMA, Keys.DOCUMENT, Keys.DOCUMENT_REQUIRED);
        assertLevel("origin", schemaAt(SCHEMA, "properties", "origin"), Keys.ORIGIN, Keys.ORIGIN_REQUIRED);
        assertLevel("trait", trait, Keys.TRAIT, List.of());
        assertLevel("entity type", entityType, Keys.ENTITY_TYPE, List.of());
        assertLevel("relationship type", relationshipType, Keys.RELATIONSHIP_TYPE, Keys.RELATIONSHIP_TYPE_REQUIRED);

        List<Object> attributes = List.of(
                schemaAt(trait, "properties", "attributes", "additionalProperties"),
                schemaAt(entityType, "properties", "attributes", "additionalProperties"),
                schemaAt(relationshipType, "properties", "attributes", "additionalProperties"));
        for (Object attribute : attributes) {
            assertLevel("attribute", attribute, Keys.ATTRIBUTE, Keys.ATTRIBUTE_REQUIRED);
            assertLevel("range", schemaAt(attribute, "properties", "range"), Keys.RANGE, List.of());
        }

        List<Object> rules = List.of(
                schemaAt(entityType, "properties", "rules", "items"),
                schemaAt(relationshipType, "properties", "rules", "items"));
        for (Object rule : rules) {
            assertLevel("rule reference", rule, Keys.RULE, Keys.RULE_REQUIRED);
        }
    }

    @Test
    @DisplayName("The choices of the JSON Schema are the vocabularies of the reader")
    void theChoicesOfTheJsonSchemaAreTheVocabulariesOfTheReader() {
        Object attribute = schemaAt(SCHEMA, "properties", "entityTypes", "additionalProperties", "properties", "attributes",
                "additionalProperties");
        Object relationshipType = schemaAt(SCHEMA, "properties", "relationshipTypes", "additionalProperties");

        assertThat(enumOf(schemaAt(attribute, "properties", "type"))).containsExactlyElementsOf(Keys.VALUE_TYPES);
        assertThat(enumOf(schemaAt(relationshipType, "properties", "kind"))).containsExactlyElementsOf(Keys.KINDS);
        assertThat(enumOf(schemaAt(relationshipType, "properties", "targetsPerSource")))
                .containsExactlyElementsOf(Keys.MULTIPLICITIES);
        assertThat(enumOf(schemaAt(relationshipType, "properties", "sourcesPerTarget")))
                .containsExactlyElementsOf(Keys.MULTIPLICITIES);
    }

    @Test
    @DisplayName("The JSON Schema is closed and described everywhere")
    void theJsonSchemaIsClosedAndDescribedEverywhere() {
        List<String> openObjects = new ArrayList<>();
        List<String> undescribed = new ArrayList<>();
        walk("", SCHEMA, openObjects, undescribed);

        assertThat(openObjects).as("objects with properties but without additionalProperties: false").isEmpty();
        assertThat(undescribed).as("properties and definitions without a description").isEmpty();
        assertThat(DocumentTrees.at(SCHEMA, "$schema")).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(DocumentTrees.at(SCHEMA, "$id")).isEqualTo("urn:cairn:schema-document:1");
    }

    private static void walk(String path, Object node, List<String> openObjects, List<String> undescribed) {
        if (node instanceof Map<?, ?> map) {
            if (map.containsKey("properties")) {
                if (!Boolean.FALSE.equals(map.get("additionalProperties"))) {
                    openObjects.add(path);
                }
                for (Map.Entry<?, ?> property : ((Map<?, ?>) map.get("properties")).entrySet()) {
                    if (!(property.getValue() instanceof Map<?, ?> schema) || !(schema.get("description") instanceof String text)
                            || text.isBlank()) {
                        undescribed.add(path + "/properties/" + property.getKey());
                    }
                }
            }
            if (map.get("$defs") instanceof Map<?, ?> definitions) {
                for (Map.Entry<?, ?> definition : definitions.entrySet()) {
                    if (!(definition.getValue() instanceof Map<?, ?> schema) || !(schema.get("description") instanceof String text)
                            || text.isBlank()) {
                        undescribed.add(path + "/$defs/" + definition.getKey());
                    }
                }
            }
            map.forEach((key, value) -> walk(path + "/" + key, value, openObjects, undescribed));
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                walk(path + "/" + i, list.get(i), openObjects, undescribed);
            }
        }
    }
}
