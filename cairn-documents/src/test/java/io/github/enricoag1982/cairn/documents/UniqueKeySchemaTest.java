package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.PayloadFixtures.payload;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.documents.PayloadFixtures.ReferenceDomainStore;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The generated JSON Schema of transaction payloads allows a reference to be a unique key (ADR-0026, design D5 of
 * {@code add-unique-key-references}), and the reader and the schema agree on what a key is: the keys of the mapping are
 * those of {@link PayloadKeys}, and a payload that the reader reads without problems is valid, while a key of the wrong
 * shape is neither read nor valid.
 */
class UniqueKeySchemaTest {

    private static final String FROM = "\"from\": \"2026-01-01T00:00:00Z\"";
    private static final String ACCOUNT = "{ \"entityType\": \"Account\", \"key\": { \"number\": \"A-1\" } }";

    private static final Schema REFERENCE = new ReferenceDomainStore().schema();
    private static final String SCHEMA = JsonSchemas.transactions(REFERENCE);
    private static final Object TREE = DocumentTrees.load(SCHEMA);

    private static Object definition(String name) {
        return DocumentTrees.at(TREE, "$defs", name);
    }

    @Test
    void theKeysOfAUniqueKeyAreTheSameInTheReaderAndInTheJsonSchema() {
        Object uniqueKey = definition("uniqueKey");

        assertThat(DocumentTrees.keysAt(uniqueKey, "properties"))
                .containsExactlyInAnyOrderElementsOf(PayloadKeys.UNIQUE_KEY);
        assertThat(DocumentTrees.listAt(uniqueKey, "required"))
                .containsExactlyInAnyOrderElementsOf(PayloadKeys.UNIQUE_KEY_REQUIRED);
        assertThat(DocumentTrees.at(uniqueKey, "additionalProperties")).isEqualTo(false);
        Object key = DocumentTrees.at(uniqueKey, "properties", "key");
        assertThat(DocumentTrees.at(key, "minProperties")).isEqualTo(1);
        assertThat(DocumentTrees.at(key, "maxProperties")).isEqualTo(1);
        assertThat(DocumentTrees.at(key, "description")).asString().contains("exactly one entry");
    }

    @Test
    void aReferenceIsAnIdOrATemporaryIdOrAUniqueKey() {
        Object reference = definition("reference");

        assertThat(DocumentTrees.at(reference, "description")).asString().contains("unique key", "$acme");
        List<?> branches = (List<?>) DocumentTrees.at(reference, "oneOf");
        assertThat(branches).hasSize(2);
        assertThat(DocumentTrees.at(branches.get(0), "type")).isEqualTo("string");
        assertThat(DocumentTrees.at(branches.get(1), "$ref")).isEqualTo("#/$defs/uniqueKey");
    }

    @Test
    void theUniqueKeyDoesNotDependOnTheSchema() {
        Schema other = PayloadFixtures.schemaOf(SchemaDefinition.builder()
                .entityType("Only", type -> type.attribute("one", ValueType.STRING)).build());
        Object tree = DocumentTrees.load(JsonSchemas.transactions(other));

        for (String name : List.of("uniqueKey", "reference")) {
            assertThat(DocumentTrees.at(tree, "$defs", name)).as(name).isEqualTo(definition(name));
        }
    }

    static Stream<Arguments> payloadsWithKeys() {
        return Stream.of(
                Arguments.of("a patch", payload("{ \"patch\": { \"target\": " + ACCOUNT + ", " + FROM
                        + ", \"set\": { \"balance\": 1500 } } }")),
                Arguments.of("a replace", payload("{ \"replace\": { \"target\": " + ACCOUNT + ", " + FROM
                        + ", \"values\": { \"number\": \"A-1\", \"currency\": \"EUR\", \"balance\": 1 } } }")),
                Arguments.of("an endDate", payload("{ \"endDate\": { \"target\": " + ACCOUNT + ", " + FROM + " } }")),
                Arguments.of("both ends of a relate", payload("{ \"relate\": { \"relationshipType\": \"HeldBy\", "
                        + "\"source\": " + ACCOUNT + ", \"target\": { \"entityType\": \"Organization\", "
                        + "\"key\": { \"lei\": \"5493001KJTIIGC8Y1R12\" } }, " + FROM + " } }")),
                Arguments.of("a number, a boolean and a decimal as the value", payload(
                        "{ \"patch\": { \"target\": { \"entityType\": \"Thing\", \"key\": { \"n\": 1 } }, " + FROM
                                + ", \"set\": { \"balance\": 1 } } }",
                        "{ \"patch\": { \"target\": { \"entityType\": \"Thing\", \"key\": { \"n\": true } }, " + FROM
                                + ", \"set\": { \"balance\": 1 } } }",
                        "{ \"patch\": { \"target\": { \"entityType\": \"Thing\", \"key\": { \"n\": 1.5 } }, " + FROM
                                + ", \"set\": { \"balance\": 1 } } }")),
                Arguments.of("an alias and another capitalization, which the store checks", payload(
                        "{ \"patch\": { \"target\": { \"entityType\": \"account\", \"key\": { \"NUMBER\": \"A-1\" } }, "
                                + FROM + ", \"set\": { \"balance\": 1 } } }")),
                Arguments.of("an ID and a temporary ID besides", payload(
                        "{ \"patch\": { \"target\": \"an-id\", " + FROM + ", \"set\": { \"balance\": 1 } } }",
                        "{ \"patch\": { \"target\": \"$temporary\", " + FROM + ", \"set\": { \"balance\": 1 } } }")));
    }

    static Stream<Arguments> keysOfTheWrongShape() {
        return Stream.of(
                Arguments.of("a key with two attributes", "{ \"entityType\": \"Account\", \"key\": { \"number\": "
                        + "\"A-1\", \"currency\": \"EUR\" } }"),
                Arguments.of("a key without an attribute", "{ \"entityType\": \"Account\", \"key\": {} }"),
                Arguments.of("a key that is a string", "{ \"entityType\": \"Account\", \"key\": \"A-1\" }"),
                Arguments.of("a key without an entity type", "{ \"key\": { \"number\": \"A-1\" } }"),
                Arguments.of("an entity type without a key", "{ \"entityType\": \"Account\" }"),
                Arguments.of("another key in the reference", "{ \"entityType\": \"Account\", \"key\": { \"number\": "
                        + "\"A-1\" }, \"at\": \"2026-01-01T00:00:00Z\" }"),
                Arguments.of("an entity type that is no string", "{ \"entityType\": 5, \"key\": { \"number\": "
                        + "\"A-1\" } }"),
                Arguments.of("a value that is a list", "{ \"entityType\": \"Account\", \"key\": { \"number\": "
                        + "[ \"A-1\" ] } }"),
                Arguments.of("a value that is null", "{ \"entityType\": \"Account\", \"key\": { \"number\": null } }"),
                Arguments.of("a value that is a mapping", "{ \"entityType\": \"Account\", \"key\": { \"number\": "
                        + "{ \"x\": 1 } } }"),
                Arguments.of("an empty reference", "{}"),
                Arguments.of("a list as the reference", "[ \"a\" ]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("payloadsWithKeys")
    void aPayloadWithKeysThatTheReaderReadsIsValidAgainstTheJsonSchema(String name, String text) {
        assertThat(Payloads.readTransaction(text, REFERENCE)).as("reading").isInstanceOf(PayloadReadResult.Read.class);
        assertThat(GeneratedSchemaCheck.errors(SCHEMA, text)).as("errors of the JSON Schema").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keysOfTheWrongShape")
    void aKeyOfTheWrongShapeIsNeitherReadNorValid(String name, String reference) {
        String text = payload("{ \"patch\": { \"target\": " + reference + ", " + FROM
                + ", \"set\": { \"balance\": 1 } } }");

        assertThat(Payloads.readTransaction(text, REFERENCE)).as("reading").isInstanceOf(PayloadReadResult.Invalid.class);
        assertThat(GeneratedSchemaCheck.errors(SCHEMA, text)).as("errors of the JSON Schema").isNotEmpty();
    }

    @Test
    void theErrorOfAKeyWithTwoAttributesNamesTheKey() {
        String text = payload("{ \"patch\": { \"target\": { \"entityType\": \"Account\", \"key\": { \"number\": \"A-1\", "
                + "\"currency\": \"EUR\" } }, " + FROM + ", \"set\": { \"balance\": 1 } } }");

        assertThat(GeneratedSchemaCheck.errors(SCHEMA, text)).anySatisfy(error ->
                assertThat(error.getInstanceLocation().toString()).contains("key"));
    }
}
