package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.PayloadFixtures.createOrganization;
import static io.github.enricoag1982.cairn.documents.PayloadFixtures.payload;
import static io.github.enricoag1982.cairn.schema.ValueType.DECIMAL;
import static io.github.enricoag1982.cairn.schema.ValueType.STRING;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.SchemaRegistries;
import io.github.enricoag1982.cairn.documents.PayloadFixtures.ReferenceDomainStore;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The generated JSON Schema for transaction payloads (design D5 of {@code add-json-payloads}): its structure is the one
 * the reader accepts, so the keys and required keys are compared with {@link PayloadKeys} at every level; it checks the
 * values of {@code create} and {@code relate} writes against their types, and those of {@code patch} and
 * {@code replace} by name; and payloads are valid against it when the reader reads them and nothing else is wrong.
 */
class TransactionSchemaTest {

    private static final String FROM = "\"from\": \"2026-01-01T00:00:00Z\"";

    private static final Schema REFERENCE = new ReferenceDomainStore().schema();
    private static final String SCHEMA = JsonSchemas.transactions(REFERENCE);
    private static final Object TREE = DocumentTrees.load(SCHEMA);

    // ------------------------------------------------------------------ navigating the generated schema

    /** Follows a {@code $ref} to a definition of the schema; any other node is returned as it is. */
    private static Object resolve(Object node) {
        if (node instanceof Map<?, ?> map && map.get("$ref") instanceof String reference) {
            assertThat(reference).startsWith("#/$defs/");
            return DocumentTrees.at(TREE, "$defs", reference.substring("#/$defs/".length()));
        }
        return node;
    }

    private static Object definition(String name) {
        return DocumentTrees.at(TREE, "$defs", name);
    }

    private static List<String> strings(Object list) {
        return ((List<?>) list).stream().map(String::valueOf).toList();
    }

    private static List<String> propertyNames(Object schema) {
        return DocumentTrees.keysAt(schema, "properties");
    }

    private static List<String> required(Object schema) {
        return DocumentTrees.mapAt(schema).containsKey("required") ? strings(DocumentTrees.at(schema, "required")) : List.of();
    }

    private static void assertLevel(String level, Object schema, List<String> keys, List<String> requiredKeys) {
        assertThat(propertyNames(schema)).as("properties of the %s", level).containsExactlyInAnyOrderElementsOf(keys);
        assertThat(required(schema)).as("required keys of the %s", level).containsExactlyInAnyOrderElementsOf(requiredKeys);
        assertThat(DocumentTrees.at(schema, "additionalProperties")).as("additionalProperties of the %s", level)
                .isEqualTo(false);
    }

    // ------------------------------------------------------------------ the reader and the schema do not drift apart

    @Test
    void theKeysOfEveryLevelAreTheSameInTheReaderAndInTheJsonSchema() {
        assertLevel("payload", TREE, PayloadKeys.TRANSACTION, PayloadKeys.TRANSACTION_REQUIRED);
        assertLevel("create", resolve(DocumentTrees.at(definition("write"), "properties", "create")), PayloadKeys.CREATE,
                PayloadKeys.CREATE_REQUIRED);
        assertLevel("relate", resolve(DocumentTrees.at(definition("write"), "properties", "relate")), PayloadKeys.RELATE,
                PayloadKeys.RELATE_REQUIRED);
        assertLevel("patch", resolve(DocumentTrees.at(definition("write"), "properties", "patch")), PayloadKeys.PATCH,
                PayloadKeys.PATCH_REQUIRED);
        assertLevel("replace", resolve(DocumentTrees.at(definition("write"), "properties", "replace")),
                PayloadKeys.REPLACE, PayloadKeys.REPLACE_REQUIRED);
        assertLevel("endDate", resolve(DocumentTrees.at(definition("write"), "properties", "endDate")),
                PayloadKeys.END_DATE, PayloadKeys.END_DATE_REQUIRED);
        assertLevel("conflict check", definition("conflictCheck"), PayloadKeys.CONFLICT_CHECK, List.of());
    }

    @Test
    void aWriteHasExactlyOneOfTheKindsOfWritesOfTheReader() {
        Object write = resolve(DocumentTrees.at(TREE, "properties", "writes", "items"));

        assertLevel("write", write, PayloadKeys.KINDS, List.of());
        assertThat(DocumentTrees.at(write, "minProperties")).isEqualTo(1);
        assertThat(DocumentTrees.at(write, "maxProperties")).isEqualTo(1);
        assertThat(DocumentTrees.at(definition("conflictCheck"), "minProperties")).isEqualTo(1);
        assertThat(DocumentTrees.at(definition("conflictCheck"), "maxProperties")).isEqualTo(1);
    }

    @Test
    void theStructureDoesNotDependOnTheSchema() {
        Schema other = PayloadFixtures.schemaOf(SchemaDefinition.builder()
                .entityType("Only", type -> type.attribute("one", STRING))
                .build());
        Object tree = DocumentTrees.load(JsonSchemas.transactions(other));

        for (String name : List.of("write", "conflictCheck", "basis", "instant", "reference", "scalar")) {
            assertThat(DocumentTrees.at(tree, "$defs", name)).as(name).isEqualTo(definition(name));
        }
        assertThat(DocumentTrees.at(tree, "properties")).isEqualTo(DocumentTrees.at(TREE, "properties"));
        assertThat(DocumentTrees.at(tree, "required")).isEqualTo(DocumentTrees.at(TREE, "required"));
    }

    @Test
    void theJsonSchemaIsClosedAndDescribedEverywhere() {
        List<String> openObjects = new ArrayList<>();
        List<String> undescribed = new ArrayList<>();
        walk("", TREE, false, openObjects, undescribed);

        assertThat(openObjects).as("objects with properties but without additionalProperties: false").isEmpty();
        assertThat(undescribed).as("definitions and properties of the structure without a description").isEmpty();
        assertThat(DocumentTrees.at(TREE, "$schema")).isEqualTo("https://json-schema.org/draft/2020-12/schema");
    }

    /**
     * Walks the schema. The branches of a {@code oneOf} refine the object they are in, which is closed, so they are not
     * closed themselves; the properties of the values of a type are described only if the attribute is; and the
     * properties of the values of a patch or replace are only names.
     */
    private static void walk(String path, Object node, boolean refinement, List<String> openObjects,
            List<String> undescribed) {
        if (node instanceof Map<?, ?> map) {
            if (map.containsKey("properties") && !refinement) {
                if (!Boolean.FALSE.equals(map.get("additionalProperties"))) {
                    openObjects.add(path);
                }
                boolean valuesOfAType = path.contains("/entityValues.") || path.contains("/relationshipValues.");
                boolean names = path.endsWith("/attributeValues");
                for (Map.Entry<?, ?> property : ((Map<?, ?>) map.get("properties")).entrySet()) {
                    if (!described(property.getValue()) && !valuesOfAType && !names) {
                        undescribed.add(path + "/properties/" + property.getKey());
                    }
                }
            }
            if (map.get("$defs") instanceof Map<?, ?> definitions) {
                for (Map.Entry<?, ?> definition : definitions.entrySet()) {
                    if (!described(definition.getValue())) {
                        undescribed.add(path + "/$defs/" + definition.getKey());
                    }
                }
            }
            map.forEach((key, value) -> walk(path + "/" + key, value, refinement, openObjects, undescribed));
        } else if (node instanceof List<?> list) {
            boolean branches = path.endsWith("/oneOf");
            for (int i = 0; i < list.size(); i++) {
                walk(path + "/" + i, list.get(i), branches, openObjects, undescribed);
            }
        }
    }

    private static boolean described(Object schema) {
        return schema instanceof Map<?, ?> map && map.get("description") instanceof String text && !text.isBlank();
    }

    @Test
    void theJsonSchemaIsValidInItsDialect() {
        assertThat(GeneratedSchemaCheck.dialectErrors(SCHEMA)).isEmpty();
        assertThat(GeneratedSchemaCheck.dialectErrors(JsonSchemas.transactions(
                SchemaRegistries.inMemory().current()))).as("the schema without types").isEmpty();
    }

    @Test
    void theSameSchemaIsAlwaysGeneratedAsTheSameText() {
        assertThat(JsonSchemas.transactions(REFERENCE)).isEqualTo(SCHEMA).endsWith("}\n");
    }

    // ------------------------------------------------------------------ the types

    @Test
    void everyConcreteEntityTypeAndRelationshipTypeHasItsValuesAsADefinition() {
        List<String> definitions = DocumentTrees.keysAt(TREE, "$defs");

        assertThat(definitions).contains("entityValues.Account", "entityValues.Address", "entityValues.CustomerRole",
                "entityValues.Organization", "entityValues.Person", "relationshipValues.Employs",
                "relationshipValues.HasAddress", "relationshipValues.HeldBy", "relationshipValues.Owns",
                "relationshipValues.PlayedBy");
        assertThat(definitions).as("Party is abstract").doesNotContain("entityValues.Party");
        assertThat(DocumentTrees.listAt(definition("entityValues.Organization"), "required")).containsExactly("name");
    }

    @Test
    void createAndRelateAreAOneOfWithABranchForEachTypeThatRefersToItsValues() {
        Object create = definition("create");
        Object relate = definition("relate");

        List<?> createBranches = (List<?>) DocumentTrees.at(create, "oneOf");
        assertThat(createBranches).hasSize(5);
        assertThat(createBranches).extracting(branch -> DocumentTrees.at(branch, "title")).containsExactly("Account",
                "Address", "CustomerRole", "Organization", "Person");
        for (Object branch : createBranches) {
            String type = String.valueOf(DocumentTrees.at(branch, "title"));
            assertThat(DocumentTrees.listAt(branch, "properties", "entityType", "enum")).containsExactly(type);
            assertThat(DocumentTrees.at(branch, "properties", "values", "$ref")).isEqualTo("#/$defs/entityValues." + type);
        }
        assertThat(((List<?>) DocumentTrees.at(relate, "oneOf"))).extracting(branch -> DocumentTrees.at(branch, "title"))
                .containsExactly("Employs", "HasAddress", "HeldBy", "Owns", "PlayedBy");
        assertThat(DocumentTrees.listAt(create, "properties", "entityType", "enum"))
                .containsExactly("Account", "Address", "CustomerRole", "Organization", "Person");
    }

    @Test
    void theNamesOfATypeIncludeItsAliasesAsTheSchemaWritesThem() {
        Schema aliased = PayloadFixtures.schemaOf(SchemaDefinition.builder()
                .entityType("Thing", type -> type.aliases("Item", "Stuff").attribute("name", STRING, a -> a.aliases("label")))
                .relationshipType("Links", link -> link.from("Thing").to("Thing").inverse("linkedFrom").aliases("Joins"))
                .build());
        String schema = JsonSchemas.transactions(aliased);

        assertThat(GeneratedSchemaCheck.isValid(schema, payload(
                "{ \"create\": { \"entityType\": \"Item\", " + FROM + ", \"values\": { \"name\": \"x\" } } }")))
                .as("an alias of the type").isTrue();
        assertThat(GeneratedSchemaCheck.isValid(schema, payload(
                "{ \"create\": { \"entityType\": \"Stuff\", " + FROM + ", \"values\": { \"nmae\": \"x\" } } }")))
                .as("an alias, with the values of the type").isFalse();
        assertThat(GeneratedSchemaCheck.isValid(schema, payload(
                "{ \"create\": { \"entityType\": \"item\", " + FROM + ", \"values\": { \"name\": \"x\" } } }")))
                .as("the engine also accepts other capitalizations, the schema does not").isFalse();
        assertThat(GeneratedSchemaCheck.isValid(schema, payload(
                "{ \"relate\": { \"relationshipType\": \"Joins\", \"source\": \"a\", \"target\": \"b\", " + FROM + " } }")))
                .as("an alias of a relationship type").isTrue();
        assertThat(GeneratedSchemaCheck.isValid(schema, payload(
                "{ \"patch\": { \"target\": \"a\", " + FROM + ", \"set\": { \"label\": \"x\" } } }")))
                .as("the alias of an attribute in a patch").isTrue();
    }

    @Test
    void aSchemaWithoutTypesAcceptsNoCreateAndNoRelate() {
        String empty = JsonSchemas.transactions(SchemaRegistries.inMemory().current());

        assertThat(GeneratedSchemaCheck.isValid(empty, payload())).isTrue();
        assertThat(GeneratedSchemaCheck.isValid(empty, payload(createOrganization("acme", "ACME")))).isFalse();
        assertThat(GeneratedSchemaCheck.isValid(empty, payload(
                "{ \"relate\": { \"relationshipType\": \"Owns\", \"source\": \"a\", \"target\": \"b\", " + FROM + " } }")))
                .isFalse();
        assertThat(GeneratedSchemaCheck.isValid(empty, payload(
                "{ \"patch\": { \"target\": \"a\", " + FROM + ", \"set\": { \"name\": \"x\" } } }"))).isFalse();
        assertThat(GeneratedSchemaCheck.isValid(empty, payload("{ \"endDate\": { \"target\": \"a\", " + FROM + " } }")))
                .isTrue();
    }

    @Test
    void theNamesOfPatchedAttributesAreTheUnionOfTheAttributesOfAllTypes() {
        Schema two = PayloadFixtures.schemaOf(SchemaDefinition.builder()
                .entityType("First", type -> type.attribute("alpha", STRING))
                .relationshipType("Pairs", link -> link.from("First").to("First").inverse("pairedBy")
                        .attribute("beta", DECIMAL, a -> a.aliases("gamma")))
                .build());
        Object tree = DocumentTrees.load(JsonSchemas.transactions(two));

        assertThat(DocumentTrees.keysAt(tree, "$defs", "attributeValues", "properties"))
                .containsExactly("alpha", "beta", "gamma");
        assertThat(DocumentTrees.listAt(tree, "$defs", "attributeName", "enum"))
                .containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void aBranchRequiresValuesExactlyWhereItsTypeHasRequiredAttributes() {
        for (Object branch : (List<?>) DocumentTrees.at(definition("create"), "oneOf")) {
            assertThat(required(branch)).as("create %s", DocumentTrees.at(branch, "title"))
                    .containsExactly("entityType", "values");
        }
        Map<String, List<String>> relate = new LinkedHashMap<>();
        for (Object branch : (List<?>) DocumentTrees.at(definition("relate"), "oneOf")) {
            relate.put(String.valueOf(DocumentTrees.at(branch, "title")), required(branch));
        }
        assertThat(relate).containsEntry("Owns", List.of("relationshipType", "values"))
                .containsEntry("Employs", List.of("relationshipType"))
                .containsEntry("HeldBy", List.of("relationshipType"))
                .containsEntry("HasAddress", List.of("relationshipType"))
                .containsEntry("PlayedBy", List.of("relationshipType"));
        assertThat(required(definition("create"))).as("the keys of a create").isEqualTo(PayloadKeys.CREATE_REQUIRED);
        assertThat(required(definition("relate"))).as("the keys of a relate").isEqualTo(PayloadKeys.RELATE_REQUIRED);
    }

    @Test
    void aWriteNeedsValuesOnlyIfItsTypeHasRequiredAttributes() {
        Schema mixed = PayloadFixtures.schemaOf(SchemaDefinition.builder()
                .entityType("Needy", type -> type.attribute("name", STRING, a -> a.required()))
                .entityType("Easy", type -> type.attribute("note", STRING))
                .relationshipType("Weighted", link -> link.from("Needy").to("Easy").inverse("weightedBy")
                        .attribute("weight", DECIMAL, a -> a.required()))
                .relationshipType("Plain", link -> link.from("Needy").to("Easy").inverse("plainBy")
                        .attribute("note", STRING))
                .build());
        String schema = JsonSchemas.transactions(mixed);
        String relate = "{ \"relate\": { \"relationshipType\": \"%s\", \"source\": \"a\", \"target\": \"b\", " + FROM
                + "%s } }";

        assertThat(GeneratedSchemaCheck.isValid(schema, payload(
                "{ \"create\": { \"entityType\": \"Easy\", " + FROM + " } }"))).as("no required attribute").isTrue();
        assertThat(GeneratedSchemaCheck.isValid(schema, payload(
                "{ \"create\": { \"entityType\": \"Needy\", " + FROM + " } }"))).as("required name, no values").isFalse();
        assertThat(GeneratedSchemaCheck.isValid(schema, payload(
                "{ \"create\": { \"entityType\": \"Needy\", " + FROM + ", \"values\": {} } }")))
                .as("required name, empty values").isFalse();
        assertThat(GeneratedSchemaCheck.isValid(schema, payload(
                "{ \"create\": { \"entityType\": \"Needy\", " + FROM + ", \"values\": { \"name\": \"x\" } } }")))
                .as("required name, given").isTrue();
        assertThat(GeneratedSchemaCheck.isValid(schema, payload(relate.formatted("Plain", ""))))
                .as("no required attribute of the relationship").isTrue();
        assertThat(GeneratedSchemaCheck.isValid(schema, payload(relate.formatted("Weighted", ""))))
                .as("required weight, no values").isFalse();
        assertThat(GeneratedSchemaCheck.isValid(schema, payload(
                relate.formatted("Weighted", ", \"values\": { \"weight\": 1.5 }")))).as("required weight, given").isTrue();
    }

    @Test
    void theInstantsAndDatesAreCheckedByPatternAsWellAsByFormat() {
        Object instant = definition("instant");

        assertThat(DocumentTrees.at(instant, "type")).isEqualTo("string");
        assertThat(DocumentTrees.at(instant, "format")).isEqualTo("date-time");
        assertThat(DocumentTrees.at(instant, "pattern")).isEqualTo(ValuesSchema.INSTANT_PATTERN);
        assertThat(DocumentTrees.at(instant, "description")).asString().contains("offset", "UTC");
        assertThat(DocumentTrees.at(TREE, "$defs", "entityValues.Person", "properties", "birthDate", "pattern"))
                .isEqualTo("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
    }

    /** Instants in the forms that {@code Instant.parse}, which the reader uses, accepts. */
    static Stream<String> instantsTheReaderAccepts() {
        return Stream.of("2026-01-01T00:00:00Z", "2026-01-01t00:00:00z", "2026-01-01T00:00:00+01:00",
                "2026-01-01T00:00:00-05:30", "2026-01-01T00:00:00-00:00", "2026-01-01T00:00:00+18:00",
                "2026-01-01T00:00:00+01:00:30", "2026-01-01T00:00:00.1Z", "2026-01-01T00:00:00.123456Z",
                "2026-01-01T00:00:00.123456789+01:00", "2026-01-01T00:00:00.Z", "2026-01-01T23:59:60Z",
                "2026-01-01T24:00:00Z", "0001-01-01T00:00:00Z", "-0001-01-01T00:00:00Z", "+12345-01-01T00:00:00Z");
    }

    /** Text in forms that are no instant for the reader, and for the pattern. */
    static Stream<String> textsThatAreNoInstants() {
        return Stream.of("yesterday", "", "2026-01-01", "2026-01-01T00:00:00", "2026-01-01 00:00:00Z", "2026-01-01T00:00Z",
                "2026-1-1T00:00:00Z", "20260101T000000Z", "2026-01-01T00:00:00+0100", "2026-01-01T00:00:00+01",
                "2026-01-01T00:00:00.1234567890Z", "2026-01-01T00:00:00 Z", " 2026-01-01T00:00:00Z",
                "2026-01-01T0:0:0Z", "999-01-01T00:00:00Z");
    }

    private static String createFrom(String from) {
        return payload("{ \"create\": { \"entityType\": \"Organization\", \"from\": \"" + from + "\", "
                + "\"values\": { \"name\": \"ACME\" } } }");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("instantsTheReaderAccepts")
    void anInstantTheReaderAcceptsIsValidAgainstTheJsonSchema(String instant) {
        String text = createFrom(instant);

        assertThat(Payloads.readTransaction(text, REFERENCE)).as("reading").isInstanceOf(PayloadReadResult.Read.class);
        assertThat(GeneratedSchemaCheck.errors(SCHEMA, text)).as("errors of the JSON Schema").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("textsThatAreNoInstants")
    void textThatIsNoInstantIsRejectedByTheReaderAndByTheJsonSchema(String text) {
        String payload = createFrom(text);

        assertThat(Payloads.readTransaction(payload, REFERENCE)).as("reading")
                .isInstanceOf(PayloadReadResult.Invalid.class);
        assertThat(GeneratedSchemaCheck.errors(SCHEMA, payload)).as("errors of the JSON Schema").isNotEmpty();
    }

    // ------------------------------------------------------------------ payloads

    private static final String ALL_KINDS = """
            {
              "$schema": "urn:cairn:transaction-payload:1",
              "cairn": 1,
              "principal": "agent:onboarding",
              "onBehalfOf": "person:alice",
              "reason": "Onboarding of ACME",
              "idempotencyKey": "onboarding-2026-01-05",
              "writes": [
                { "create": { "temporaryId": "$acme", "id": "00000000-0000-7000-8000-0000000000aa",
                    "entityType": "Organization", "from": "2026-01-01T00:00:00Z", "until": "2027-01-01T00:00:00Z",
                    "values": { "name": "ACME", "lei": "5493001KJTIIGC8Y1R12" } } },
                { "create": { "temporaryId": "beta", "entityType": "Organization", "from": "2026-01-01T00:00:00Z",
                    "values": { "name": "Beta" } } },
                { "relate": { "temporaryId": "$owns", "id": "00000000-0000-7000-8000-0000000000bb",
                    "relationshipType": "Owns", "source": "$acme", "target": "$beta",
                    "from": "2026-02-01T00:00:00Z", "until": "2026-12-01T00:00:00Z", "values": { "share": 60 } } },
                { "patch": { "target": "$owns", "from": "2026-02-01T00:00:00Z", "until": "2026-06-01T00:00:00Z",
                    "set": { "share": 55.5 }, "clear": [], "conflictCheck": { "perAttribute": 3 } } },
                { "patch": { "target": "$acme", "from": "2026-03-01T00:00:00Z", "clear": ["lei"] } },
                { "replace": { "target": "$acme", "from": "2026-01-01T00:00:00Z",
                    "values": { "name": "ACME Holdings" }, "conflictCheck": { "perEntity": 4 } } },
                { "endDate": { "target": "$owns", "from": "2026-08-01T00:00:00Z" } }
              ]
            }
            """;

    private static final String ALL_KINDS_AS_YAML = """
            cairn: 1
            principal: agent:onboarding
            writes:
              - create:
                  temporaryId: "$acme"
                  entityType: Organization
                  from: 2026-01-01T00:00:00Z
                  values:
                    name: ACME
                    lei: 5493001KJTIIGC8Y1R12
              - relate:
                  relationshipType: Owns
                  source: "$acme"
                  target: 00000000-0000-7000-8000-0000000000cc
                  from: 2026-02-01T00:00:00Z
                  values: { share: 60.5 }
              - patch:
                  target: "$acme"
                  from: 2026-03-01T00:00:00Z
                  set: { name: ACME Holdings }
                  clear: [lei]
              - endDate:
                  target: "$acme"
                  from: 2026-08-01T00:00:00Z
                  conflictCheck: { perEntity: 4 }
            """;

    /** A payload that the reader reads and that the JSON Schema accepts. */
    static Stream<Arguments> validPayloads() {
        return Stream.of(
                Arguments.of("only the required keys", "{\"cairn\": 1, \"principal\": \"importer\", \"writes\": []}"),
                Arguments.of("every kind of write and every key", ALL_KINDS),
                Arguments.of("a create with values for the required attributes", payload("{ \"create\": { "
                        + "\"entityType\": \"Address\", " + FROM + ", \"values\": { \"street\": \"1 Main Street\", "
                        + "\"city\": \"Rome\", \"country\": \"IT\" } } }")),
                Arguments.of("a create that supplies an ID and ends", payload("{ \"create\": { \"id\": \"x\", "
                        + "\"entityType\": \"Person\", " + FROM + ", \"until\": \"2027-01-01T00:00:00Z\", "
                        + "\"values\": { \"name\": \"Alice\", \"birthDate\": \"1990-05-17\" } } }")),
                Arguments.of("an account", payload("{ \"create\": { \"entityType\": \"Account\", " + FROM + ", \"values\": "
                        + "{ \"number\": \"A-1\", \"currency\": \"EUR\", \"balance\": 1000.50 } } }")),
                Arguments.of("the largest share", payload("{ \"relate\": { \"relationshipType\": \"Owns\", "
                        + "\"source\": \"a\", \"target\": \"b\", " + FROM + ", \"values\": { \"share\": 100 } } }")),
                Arguments.of("a relate without values", payload("{ \"relate\": { \"relationshipType\": \"HeldBy\", "
                        + "\"source\": \"$a1\", \"target\": \"$beta\", " + FROM + " } }")),
                Arguments.of("a relate without values of a type with optional attributes only", payload("{ \"relate\": { "
                        + "\"relationshipType\": \"Employs\", \"source\": \"$beta\", \"target\": \"$alice\", " + FROM
                        + " } }")),
                Arguments.of("instants with a fraction, an offset and lower case letters", payload("{ \"create\": { "
                        + "\"entityType\": \"Person\", \"from\": \"2026-01-01T01:00:00.123456+01:00\", "
                        + "\"until\": \"2026-02-01t00:00:00z\", \"values\": { \"name\": \"Alice\" } } }")),
                Arguments.of("a patch that sets a value of any type", payload("{ \"patch\": { \"target\": \"a\", " + FROM
                        + ", \"set\": { \"balance\": 5, \"name\": \"x\" } } }")),
                Arguments.of("a conflict check at basis 0", payload("{ \"endDate\": { \"target\": \"a\", " + FROM
                        + ", \"conflictCheck\": { \"perAttribute\": 0 } } }")),
                Arguments.of("every kind of write as YAML", DocumentTrees.toJson(DocumentTrees.load(ALL_KINDS_AS_YAML))));
    }

    /** A payload that the reader and the JSON Schema both reject. */
    static Stream<Arguments> invalidPayloads() {
        return Stream.of(
                Arguments.of("an unknown key of the payload", "{\"cairn\": 1, \"principal\": \"p\", \"writes\": [], \"reasons\": \"x\"}"),
                Arguments.of("no principal", "{\"cairn\": 1, \"writes\": []}"),
                Arguments.of("no writes", "{\"cairn\": 1, \"principal\": \"p\"}"),
                Arguments.of("another format version", "{\"cairn\": 2, \"principal\": \"p\", \"writes\": []}"),
                Arguments.of("a principal that is a number", "{\"cairn\": 1, \"principal\": 5, \"writes\": []}"),
                Arguments.of("writes that are a mapping", "{\"cairn\": 1, \"principal\": \"p\", \"writes\": {}}"),
                Arguments.of("a write of an unknown kind", payload("{ \"upsert\": { \"entityType\": \"Organization\", "
                        + FROM + " } }")),
                Arguments.of("a write of two kinds", payload("{ \"endDate\": { \"target\": \"a\", " + FROM + " }, "
                        + "\"patch\": { \"target\": \"a\", " + FROM + " } }")),
                Arguments.of("a write without a kind", payload("{}")),
                Arguments.of("a create without from", payload("{ \"create\": { \"entityType\": \"Organization\" } }")),
                Arguments.of("a create without a type", payload("{ \"create\": { " + FROM + " } }")),
                Arguments.of("a create with an unknown key", payload("{ \"create\": { \"entityType\": \"Address\", "
                        + FROM + ", \"type\": \"x\" } }")),
                Arguments.of("a create with values that are a list", payload("{ \"create\": { \"entityType\": \"Address\", "
                        + FROM + ", \"values\": [] } }")),
                Arguments.of("a from that is no instant", payload("{ \"create\": { \"entityType\": \"Address\", "
                        + "\"from\": \"yesterday\" } }")),
                Arguments.of("a from that is a date", payload("{ \"endDate\": { \"target\": \"a\", "
                        + "\"from\": \"2026-01-01\" } }")),
                Arguments.of("an until without a zone", payload("{ \"patch\": { \"target\": \"a\", " + FROM
                        + ", \"until\": \"2027-01-01T00:00:00\" } }")),
                Arguments.of("a create with a blank temporary ID", payload("{ \"create\": { \"temporaryId\": \"\", "
                        + "\"entityType\": \"Address\", " + FROM + " } }")),
                Arguments.of("a value that is null", payload("{ \"create\": { \"entityType\": \"Address\", " + FROM
                        + ", \"values\": { \"street\": null } } }")),
                Arguments.of("a value that is nested", payload("{ \"patch\": { \"target\": \"a\", " + FROM
                        + ", \"set\": { \"name\": { \"first\": \"x\" } } } }")),
                Arguments.of("a relate without a target", payload("{ \"relate\": { \"relationshipType\": \"Owns\", "
                        + "\"source\": \"a\", " + FROM + " } }")),
                Arguments.of("a relate with a blank source", payload("{ \"relate\": { \"relationshipType\": \"Owns\", "
                        + "\"source\": \"\", \"target\": \"b\", " + FROM + " } }")),
                Arguments.of("a patch without a target", payload("{ \"patch\": { " + FROM + " } }")),
                Arguments.of("a patch with clear that is a string", payload("{ \"patch\": { \"target\": \"a\", " + FROM
                        + ", \"clear\": \"lei\" } }")),
                Arguments.of("a replace without values", payload("{ \"replace\": { \"target\": \"a\", " + FROM + " } }")),
                Arguments.of("an endDate without from", payload("{ \"endDate\": { \"target\": \"a\" } }")),
                Arguments.of("an endDate with until", payload("{ \"endDate\": { \"target\": \"a\", " + FROM
                        + ", \"until\": \"2027-01-01T00:00:00Z\" } }")),
                Arguments.of("a conflict check with both keys", payload("{ \"endDate\": { \"target\": \"a\", " + FROM
                        + ", \"conflictCheck\": { \"perEntity\": 1, \"perAttribute\": 1 } } }")),
                Arguments.of("an empty conflict check", payload("{ \"endDate\": { \"target\": \"a\", " + FROM
                        + ", \"conflictCheck\": {} } }")),
                Arguments.of("a conflict check with a negative basis", payload("{ \"endDate\": { \"target\": \"a\", "
                        + FROM + ", \"conflictCheck\": { \"perEntity\": -1 } } }")),
                Arguments.of("a conflict check with a basis that is a string", payload("{ \"endDate\": { \"target\": \"a\", "
                        + FROM + ", \"conflictCheck\": { \"perEntity\": \"3\" } } }")),
                Arguments.of("an unknown key of a conflict check", payload("{ \"endDate\": { \"target\": \"a\", " + FROM
                        + ", \"conflictCheck\": { \"perField\": 1 } } }")));
    }

    /** A payload that the reader reads, and that the engine would report: the JSON Schema rejects it as well. */
    static Stream<Arguments> payloadsThatAreReadAndThatTheJsonSchemaRejects() {
        return Stream.of(
                Arguments.of("a value of an unknown attribute", payload("{ \"create\": { \"entityType\": \"Organization\", "
                        + FROM + ", \"values\": { \"nmae\": \"ACME\" } } }")),
                Arguments.of("a missing required value", payload("{ \"create\": { \"entityType\": \"Organization\", " + FROM
                        + ", \"values\": { \"lei\": \"5493001KJTIIGC8Y1R12\" } } }")),
                Arguments.of("a create without values, though its type has required attributes", payload("{ \"create\": { "
                        + "\"entityType\": \"Organization\", " + FROM + " } }")),
                Arguments.of("a create of Address without values, though its type has required attributes",
                        payload("{ \"create\": { \"entityType\": \"Address\", " + FROM + " } }")),
                Arguments.of("a relate without values, though its type has required attributes", payload("{ \"relate\": { "
                        + "\"relationshipType\": \"Owns\", \"source\": \"a\", \"target\": \"b\", " + FROM + " } }")),
                Arguments.of("a date that is no date", payload("{ \"create\": { \"entityType\": \"Person\", " + FROM
                        + ", \"values\": { \"name\": \"Alice\", \"birthDate\": \"not-a-date\" } } }")),
                Arguments.of("a date without leading zeros", payload("{ \"create\": { \"entityType\": \"Person\", " + FROM
                        + ", \"values\": { \"name\": \"Alice\", \"birthDate\": \"1990-5-17\" } } }")),
                Arguments.of("a value of the wrong type", payload("{ \"create\": { \"entityType\": \"Organization\", " + FROM
                        + ", \"values\": { \"name\": 5 } } }")),
                Arguments.of("a value that breaks a pattern", payload("{ \"create\": { \"entityType\": \"Account\", " + FROM
                        + ", \"values\": { \"number\": \"A-1\", \"currency\": \"eur\", \"balance\": 1 } } }")),
                Arguments.of("a value that is not allowed", payload("{ \"create\": { \"entityType\": \"CustomerRole\", "
                        + FROM + ", \"values\": { \"segment\": \"Gold\" } } }")),
                Arguments.of("a value outside a range", payload("{ \"relate\": { \"relationshipType\": \"Owns\", "
                        + "\"source\": \"a\", \"target\": \"b\", " + FROM + ", \"values\": { \"share\": 0 } } }")),
                Arguments.of("a value above a range", payload("{ \"relate\": { \"relationshipType\": \"Owns\", "
                        + "\"source\": \"a\", \"target\": \"b\", " + FROM + ", \"values\": { \"share\": 100.5 } } }")),
                Arguments.of("an attribute of another type", payload("{ \"create\": { \"entityType\": \"Address\", " + FROM
                        + ", \"values\": { \"street\": \"x\", \"city\": \"y\", \"country\": \"z\", \"lei\": \"x\" } } }")),
                Arguments.of("an abstract type", payload("{ \"create\": { \"entityType\": \"Party\", " + FROM
                        + ", \"values\": { \"name\": \"x\" } } }")),
                Arguments.of("an unknown type", payload("{ \"create\": { \"entityType\": \"Nothing\", " + FROM + " } }")),
                Arguments.of("a type in another capitalization", payload("{ \"create\": { \"entityType\": \"organization\", "
                        + FROM + ", \"values\": { \"name\": \"x\" } } }")),
                Arguments.of("an unknown relationship type", payload("{ \"relate\": { \"relationshipType\": \"Likes\", "
                        + "\"source\": \"a\", \"target\": \"b\", " + FROM + " } }")),
                Arguments.of("a patch of an unknown attribute", payload("{ \"patch\": { \"target\": \"a\", " + FROM
                        + ", \"set\": { \"nmae\": \"x\" } } }")),
                Arguments.of("a replace of an unknown attribute", payload("{ \"replace\": { \"target\": \"a\", " + FROM
                        + ", \"values\": { \"nmae\": \"x\" } } }")),
                Arguments.of("clearing an unknown attribute", payload("{ \"patch\": { \"target\": \"a\", " + FROM
                        + ", \"clear\": [\"nmae\"] } }")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validPayloads")
    void aPayloadThatTheReaderReadsWithoutProblemsIsValid(String name, String text) {
        assertThat(Payloads.readTransaction(text, REFERENCE)).as("reading").isInstanceOf(PayloadReadResult.Read.class);
        assertThat(GeneratedSchemaCheck.errors(SCHEMA, text)).as("errors of the JSON Schema").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPayloads")
    void aPayloadWithAProblemOfStructureIsInvalidAndTheReaderRejectsIt(String name, String text) {
        assertThat(Payloads.readTransaction(text, REFERENCE)).as("reading").isInstanceOf(PayloadReadResult.Invalid.class);
        assertThat(GeneratedSchemaCheck.errors(SCHEMA, text)).as("errors of the JSON Schema").isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("payloadsThatAreReadAndThatTheJsonSchemaRejects")
    void aPayloadWithAProblemOfNamesOrValuesIsReadAndInvalid(String name, String text) {
        assertThat(Payloads.readTransaction(text, REFERENCE)).as("reading").isInstanceOf(PayloadReadResult.Read.class);
        assertThat(GeneratedSchemaCheck.errors(SCHEMA, text)).as("errors of the JSON Schema").isNotEmpty();
    }

    @Test
    void theErrorNamesTheUnknownAttribute() {
        String text = payload("{ \"create\": { \"entityType\": \"Organization\", " + FROM
                + ", \"values\": { \"nmae\": \"ACME\" } } }");

        assertThat(GeneratedSchemaCheck.errors(SCHEMA, text)).anySatisfy(error -> assertThat(error.getMessage())
                .contains("nmae"));
    }

    @Test
    void theErrorNamesTheUnknownAttributeOfAPatch() {
        String text = payload("{ \"patch\": { \"target\": \"a\", " + FROM + ", \"set\": { \"nmae\": \"x\" } } }");

        assertThat(GeneratedSchemaCheck.errors(SCHEMA, text)).anySatisfy(error -> assertThat(error.getMessage())
                .contains("nmae"));
    }
}
