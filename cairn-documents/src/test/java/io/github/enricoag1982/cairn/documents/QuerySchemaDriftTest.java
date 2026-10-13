package io.github.enricoag1982.cairn.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reader of queries and the published JSON Schema {@code query-1.schema.json} must not drift apart (design D4 of
 * {@code add-queries-and-aggregation}, as the schema document's is kept in step in {@link JsonSchemaDriftTest}): at every
 * level, the keys the reader accepts ({@link QueryKeys}) are the properties the JSON Schema declares, with the same required
 * keys and the same choices; the JSON Schema is closed and described everywhere; and texts that the reader accepts are valid
 * against it, and texts that it rejects for their form are invalid.
 */
class QuerySchemaDriftTest {

    private static final Object SCHEMA = DocumentTrees.load(Queries.jsonSchema());

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

    /** The key sets of the branches of the {@code oneOf} of a schema, each a list of required keys. */
    private static List<List<String>> branches(Object schema) {
        return DocumentTrees.listAt(schema, "oneOf").stream().map(QuerySchemaDriftTest::required).toList();
    }

    @Test
    @DisplayName("The keys of every level are the same in the reader and in the JSON Schema")
    void theKeysOfEveryLevelAreTheSameInTheReaderAndInTheJsonSchema() {
        Object step = schemaAt(SCHEMA, "properties", "steps", "items");
        Object condition = schemaAt(step, "properties", "where");
        Object operand = schemaAt(condition, "properties", "compare", "properties", "left");

        assertLevel("query", SCHEMA, QueryKeys.QUERY, QueryKeys.QUERY_REQUIRED);
        assertLevel("from", schemaAt(SCHEMA, "properties", "from"), QueryKeys.FROM, QueryKeys.FROM_REQUIRED);
        assertLevel("step", step, QueryKeys.STEP_KINDS, List.of());
        assertLevel("follow", schemaAt(step, "properties", "follow"), QueryKeys.FOLLOW, QueryKeys.FOLLOW_REQUIRED);
        assertThat(schemaAt(step, "properties", "optionalFollow")).isEqualTo(schemaAt(step, "properties", "follow"));
        assertLevel("traverse", schemaAt(step, "properties", "traverse"), QueryKeys.TRAVERSE,
                QueryKeys.TRAVERSE_REQUIRED);
        assertLevel("condition", condition, QueryKeys.CONDITION_KINDS, List.of());
        assertLevel("compare", schemaAt(condition, "properties", "compare"), QueryKeys.COMPARE, QueryKeys.COMPARE);
        assertLevel("in", schemaAt(condition, "properties", "in"), QueryKeys.IN, QueryKeys.IN);
        assertLevel("hasValue", schemaAt(condition, "properties", "hasValue"), QueryKeys.HAS_VALUE,
                QueryKeys.HAS_VALUE);
        assertLevel("hasLink", schemaAt(condition, "properties", "hasLink"), QueryKeys.HAS_LINK, QueryKeys.HAS_LINK);
        assertLevel("isA", schemaAt(condition, "properties", "isA"), QueryKeys.IS_A, QueryKeys.IS_A);
        assertLevel("operand", operand, QueryKeys.OPERAND, List.of());
        assertLevel("group key", schemaAt(SCHEMA, "properties", "groupBy", "items"), QueryKeys.GROUP_KEY,
                QueryKeys.GROUP_KEY_REQUIRED);
        assertLevel("aggregate", schemaAt(SCHEMA, "properties", "aggregates", "items"), QueryKeys.AGGREGATE,
                QueryKeys.AGGREGATE_REQUIRED);
        assertLevel("column", schemaAt(SCHEMA, "properties", "columns", "items"), QueryKeys.COLUMN, List.of());
        assertLevel("order", schemaAt(SCHEMA, "properties", "order", "items"), QueryKeys.ORDER, List.of());
    }

    @Test
    @DisplayName("The choices of the JSON Schema are the vocabularies of the reader")
    void theChoicesOfTheJsonSchemaAreTheVocabulariesOfTheReader() {
        Object condition = schemaAt(SCHEMA, "properties", "steps", "items", "properties", "where");
        Object operand = schemaAt(condition, "properties", "compare", "properties", "left");
        Object column = schemaAt(SCHEMA, "properties", "columns", "items");
        Object order = schemaAt(SCHEMA, "properties", "order", "items");

        assertThat(enumOf(schemaAt(condition, "properties", "compare", "properties", "op")))
                .containsExactlyElementsOf(QueryKeys.OPERATORS);
        assertThat(enumOf(schemaAt(SCHEMA, "properties", "aggregates", "items", "properties", "function")))
                .containsExactlyElementsOf(QueryKeys.FUNCTIONS);
        assertThat(branches(operand)).containsExactlyElementsOf(QueryKeys.OPERAND_KINDS.stream().map(List::of).toList());
        assertThat(branches(column)).containsExactlyElementsOf(QueryKeys.COLUMN_KINDS.stream().map(List::of).toList());
        assertThat(branches(order)).containsExactlyElementsOf(QueryKeys.COLUMN_KINDS.stream().map(List::of).toList());
        for (Object schema : List.of(operand, column, order,
                schemaAt(SCHEMA, "properties", "aggregates", "items"))) {
            assertThat(DocumentTrees.at(schema, "dependentRequired")).isEqualTo(Map.of("attribute", List.of("of"),
                    "of", List.of("attribute")));
        }
        assertThat(DocumentTrees.at(schemaAt(SCHEMA, "properties", "steps", "items"), "minProperties")).isEqualTo(1);
        assertThat(DocumentTrees.at(schemaAt(SCHEMA, "properties", "steps", "items"), "maxProperties")).isEqualTo(1);
        assertThat(DocumentTrees.at(condition, "minProperties")).isEqualTo(1);
        assertThat(DocumentTrees.at(condition, "maxProperties")).isEqualTo(1);
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
        assertThat(DocumentTrees.at(SCHEMA, "$id")).isEqualTo("urn:cairn:query:1");
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

    // ------------------------------------------------------------------ the two agree on texts

    /** Queries that the reader reads without problems. */
    private static final List<String> READ = List.of(
            QueryFixtures.TOTAL_BALANCE,
            "{ \"from\": { \"type\": \"Account\", \"as\": \"a\" } }",
            "{ \"$schema\": \"urn:cairn:query:1\", \"cairn\": 1, \"from\": { \"type\": \"A\", \"as\": \"a\", "
                    + "\"exact\": true }, \"steps\": [], \"groupBy\": [], \"aggregates\": [], \"columns\": [], "
                    + "\"order\": [], \"limit\": 0 }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ "
                    + "{ \"optionalFollow\": { \"from\": \"a\", \"name\": \"n\", \"as\": \"b\", \"relationship\": \"r\" } }, "
                    + "{ \"traverse\": { \"from\": \"a\", \"name\": \"n\", \"as\": \"c\", \"minDepth\": 0, "
                    + "\"maxDepth\": 0, \"relationship\": \"v\", \"depth\": \"d\" } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"where\": { \"and\": [ "
                    + "{ \"or\": [] }, { \"not\": { \"isA\": { \"of\": \"a\", \"type\": \"T\" } } }, "
                    + "{ \"hasLink\": { \"of\": \"a\", \"name\": \"n\" } }, "
                    + "{ \"in\": { \"operand\": { \"id\": \"a\" }, \"values\": [ 1, 2.5, \"x\", true ] } }, "
                    + "{ \"compare\": { \"left\": { \"type\": \"a\" }, \"op\": \"ne\", \"right\": { \"value\": \"T\" } } } "
                    + "] } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"groupBy\": [ { \"of\": \"a\", \"attribute\": \"x\", "
                    + "\"as\": \"k\" } ], \"aggregates\": [ { \"function\": \"count\", \"as\": \"n\" }, "
                    + "{ \"function\": \"avg\", \"attribute\": \"x\", \"of\": \"a\", \"as\": \"m\" } ], "
                    + "\"columns\": [ { \"key\": \"k\", \"as\": \"c\" }, { \"aggregate\": \"n\" }, "
                    + "{ \"attribute\": \"x\", \"of\": \"a\" }, { \"binding\": \"a\" }, { \"depth\": \"d\" } ], "
                    + "\"order\": [ { \"key\": \"k\", \"descending\": true }, { \"attribute\": \"x\", \"of\": \"a\" } ] }");

    /** Texts that the reader rejects, for a reason of form that the JSON Schema states too. */
    private static final List<String> NOT_READ = List.of(
            "{}",
            "[]",
            "{ \"from\": { \"type\": \"A\" } }",
            "{ \"from\": { \"as\": \"a\" } }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\", \"extra\": 1 } }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"extra\": 1 }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"cairn\": 2 }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"limit\": -1 }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"limit\": 1.5 }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\", \"exact\": \"yes\" } }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ {} ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"folow\": { } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"follow\": { \"from\": \"a\", \"name\": \"n\", "
                    + "\"as\": \"b\" }, \"optionalFollow\": { \"from\": \"a\", \"name\": \"n\", \"as\": \"b\" } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"follow\": { \"from\": \"a\", \"name\": \"n\" } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"traverse\": { \"from\": \"a\", \"name\": \"n\", "
                    + "\"as\": \"b\" } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"traverse\": { \"from\": \"a\", \"name\": \"n\", "
                    + "\"as\": \"b\", \"minDepth\": -1, \"maxDepth\": 1 } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"where\": { } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"where\": { \"and\": { } } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"where\": { \"compare\": { \"left\": "
                    + "{ \"id\": \"a\" }, \"op\": \"gte\", \"right\": { \"id\": \"a\" } } } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"where\": { \"compare\": { \"left\": "
                    + "{ \"id\": \"a\" }, \"op\": \"eq\" } } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"where\": { \"compare\": { \"left\": "
                    + "{ \"id\": \"a\", \"type\": \"a\" }, \"op\": \"eq\", \"right\": { \"id\": \"a\" } } } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"where\": { \"compare\": { \"left\": "
                    + "{ \"attribute\": \"x\" }, \"op\": \"eq\", \"right\": { \"id\": \"a\" } } } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"where\": { \"compare\": { \"left\": "
                    + "{ \"id\": \"a\", \"of\": \"a\" }, \"op\": \"eq\", \"right\": { \"id\": \"a\" } } } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"where\": { \"compare\": { \"left\": "
                    + "{ \"value\": null }, \"op\": \"eq\", \"right\": { \"id\": \"a\" } } } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"where\": { \"in\": { \"operand\": "
                    + "{ \"id\": \"a\" }, \"values\": [ null ] } } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"where\": { \"hasValue\": { \"of\": \"a\" } } } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"groupBy\": [ { \"attribute\": \"x\" } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"aggregates\": [ { \"function\": \"count\" } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"aggregates\": [ { \"function\": \"total\", \"as\": \"t\" } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"aggregates\": [ { \"function\": \"sum\", \"attribute\": \"x\", "
                    + "\"as\": \"t\" } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"columns\": [ {} ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"columns\": [ { \"attribute\": \"x\" } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"columns\": [ { \"binding\": \"a\", \"of\": \"a\" } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"columns\": [ { \"binding\": \"a\", \"key\": \"k\" } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"columns\": [ { \"binding\": \"a\", \"descending\": true } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"order\": [ { \"binding\": \"a\", \"as\": \"x\" } ] }",
            "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"order\": [ { \"binding\": \"a\", \"descending\": \"yes\" } ] }");

    @Test
    @DisplayName("A query that the reader reads is valid against the JSON Schema")
    void aQueryThatTheReaderReadsIsValidAgainstTheJsonSchema() {
        for (String text : READ) {
            assertThat(Queries.read(text)).as(text).isInstanceOf(QueryReadResult.Read.class);
            assertThat(QuerySchemaCheck.errors(text)).as(text).isEmpty();
        }
    }

    @Test
    @DisplayName("A query that the reader rejects for its form is invalid against the JSON Schema")
    void aQueryThatTheReaderRejectsForItsFormIsInvalidAgainstTheJsonSchema() {
        for (String text : NOT_READ) {
            assertThat(Queries.read(text)).as(text).isInstanceOf(QueryReadResult.Invalid.class);
            assertThat(QuerySchemaCheck.errors(text)).as(text).isNotEmpty();
        }
    }

    @Test
    @DisplayName("A query written as YAML is valid against the JSON Schema")
    void aQueryWrittenAsYamlIsValidAgainstTheJsonSchema() {
        String yaml = """
                from: {type: Account, as: a}
                steps:
                  - follow: {from: a, name: HeldBy, as: holder}
                columns:
                  - {attribute: balance, of: a, as: money}
                limit: 3
                """;

        assertThat(Queries.read(yaml)).isInstanceOf(QueryReadResult.Read.class);
        assertThat(QuerySchemaCheck.errors(yaml)).isEmpty();
    }

    @Test
    @DisplayName("The schema does not state that the maximum depth is at least the minimum, which only the reader checks")
    void theSchemaDoesNotStateThatTheMaximumDepthIsAtLeastTheMinimum() {
        String text = "{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"steps\": [ { \"traverse\": { \"from\": \"a\", "
                + "\"name\": \"n\", \"as\": \"b\", \"minDepth\": 3, \"maxDepth\": 2 } } ] }";

        assertThat(Queries.read(text)).isInstanceOf(QueryReadResult.Invalid.class);
        assertThat(QuerySchemaCheck.errors(text)).as("JSON Schema cannot compare two values").isEmpty();
    }
}
