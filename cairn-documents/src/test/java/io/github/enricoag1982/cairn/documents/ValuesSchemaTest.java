package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.schema.ValueType.BOOLEAN;
import static io.github.enricoag1982.cairn.schema.ValueType.DATE;
import static io.github.enricoag1982.cairn.schema.ValueType.DECIMAL;
import static io.github.enricoag1982.cairn.schema.ValueType.INSTANT;
import static io.github.enricoag1982.cairn.schema.ValueType.INT;
import static io.github.enricoag1982.cairn.schema.ValueType.LONG;
import static io.github.enricoag1982.cairn.schema.ValueType.STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.documents.PayloadFixtures.ReferenceDomainStore;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The generated JSON Schema for the values of a type (design D5 of {@code add-json-payloads}): the attributes, their
 * value types and constraints, what is required, and what is copied.
 */
class ValuesSchemaTest {

    private static final SchemaDefinition DEFINITION = SchemaDefinition.builder()
            .trait("Named", trait -> trait
                    .attribute("name", STRING, a -> a.required().description("What it is called.")
                            .examples(Value.of("ACME"), Value.of("Beta"))))
            .entityType("Base", base -> base.abstractType().includes("Named")
                    .attribute("note", STRING, a -> a.minLength(1).maxLength(200)))
            .entityType("Thing", thing -> thing.supertype("Base").description("Something that can be counted.")
                    .aliases("Item")
                    .attribute("kind", STRING, a -> a.required().allowedValues("big", "small"))
                    .attribute("code", STRING, a -> a.pattern("[A-Z]{2}[0-9]{3}").aliases("sku"))
                    .attribute("count", INT, a -> a.range(Range.between(
                            Bound.inclusive(Value.of(0)), Bound.exclusive(Value.of(1000)))))
                    .attribute("anyCount", INT)
                    .attribute("big", LONG, a -> a.range(Range.atLeast(Bound.exclusive(Value.of(5L)))))
                    .attribute("anyBig", LONG)
                    .attribute("amount", DECIMAL, a -> a.range(Range.between(
                            Bound.exclusive(Value.of(new BigDecimal("0.5"))), Bound.inclusive(Value.of(100)))))
                    .attribute("anyAmount", DECIMAL)
                    .attribute("flag", BOOLEAN)
                    .attribute("day", DATE, a -> a.range(Range.between(
                            Bound.inclusive(Value.of(LocalDate.of(2026, 1, 1))),
                            Bound.exclusive(Value.of(LocalDate.of(2027, 1, 1))))).description("A day of 2026."))
                    .attribute("moment", INSTANT, a -> a.range(Range.atMost(
                            Bound.inclusive(Value.of(Instant.parse("2030-01-01T00:00:00Z"))))))
                    .attribute("old", STRING, a -> a.deprecated()))
            .relationshipType("Links", link -> link.from("Thing").to("Thing").inverse("linkedFrom")
                    .description("Two things that belong together.")
                    .attribute("weight", DECIMAL, a -> a.required()))
            .build();

    private final Schema schema = PayloadFixtures.schemaOf(DEFINITION);

    private Object values(String typeName) {
        return DocumentTrees.load(JsonSchemas.values(schema, typeName));
    }

    private Object property(String name) {
        return DocumentTrees.at(values("Thing"), "properties", name);
    }

    private boolean isValid(String typeName, String json) {
        return GeneratedSchemaCheck.isValid(JsonSchemas.values(schema, typeName), json);
    }

    // ------------------------------------------------------------------ the object

    @Test
    void theSchemaIsAClosedObjectInTheDialectOfJsonSchema2020() {
        Object json = values("Thing");

        assertThat(DocumentTrees.at(json, "$schema")).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(DocumentTrees.at(json, "type")).isEqualTo("object");
        assertThat(DocumentTrees.at(json, "additionalProperties")).isEqualTo(false);
        assertThat(DocumentTrees.at(json, "title")).isEqualTo("Values of Thing");
        assertThat(DocumentTrees.at(json, "description")).isEqualTo("The values of the entity type Thing. Something that "
                + "can be counted.");
        assertThat(GeneratedSchemaCheck.dialectErrors(JsonSchemas.values(schema, "Thing"))).isEmpty();
    }

    @Test
    void everyAttributeIsAPropertyIncludingThoseThatTheTypeInherits() {
        assertThat(DocumentTrees.keysAt(values("Thing"), "properties")).containsExactlyInAnyOrder("name", "note", "kind",
                "code", "count", "anyCount", "big", "anyBig", "amount", "anyAmount", "flag", "day", "moment", "old");
        assertThat(DocumentTrees.keysAt(values("Base"), "properties")).containsExactlyInAnyOrder("name", "note");
    }

    @Test
    void theRequiredAttributesAreRequiredAndTheOthersAreNot() {
        assertThat(DocumentTrees.listAt(values("Thing"), "required")).containsExactlyInAnyOrder("name", "kind");
        assertThat(DocumentTrees.listAt(values("Base"), "required")).containsExactly("name");
    }

    @Test
    void aTypeWithoutRequiredAttributesHasNoRequiredList() {
        Schema plain = PayloadFixtures.schemaOf(SchemaDefinition.builder()
                .entityType("Plain", type -> type.attribute("value", STRING)).build());

        assertThat(DocumentTrees.keysOf(DocumentTrees.load(JsonSchemas.values(plain, "Plain")))).doesNotContain("required");
    }

    @Test
    void aTypeIsFoundByNameOrAliasWithoutRegardToCaseAndNamedAsTheSchemaNamesIt() {
        assertThat(JsonSchemas.values(schema, "item")).isEqualTo(JsonSchemas.values(schema, "Thing"));
        assertThat(DocumentTrees.at(values("ITEM"), "title")).isEqualTo("Values of Thing");
    }

    @Test
    void anAbstractTypeHasASchemaToo() {
        assertThat(DocumentTrees.keysAt(values("Base"), "properties")).contains("name");
    }

    @Test
    void aTypeThatTheSchemaDoesNotHaveIsRefused() {
        assertThatThrownBy(() -> JsonSchemas.values(schema, "Nothing")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nothing");
    }

    @Test
    void aRelationshipTypeHasItsAttributes() {
        Object json = values("Links");

        assertThat(DocumentTrees.at(json, "description")).isEqualTo("The values of the relationship type Links. Two "
                + "things that belong together.");
        assertThat(DocumentTrees.keysAt(json, "properties")).containsExactly("weight");
        assertThat(DocumentTrees.listAt(json, "required")).containsExactly("weight");
        assertThat(DocumentTrees.at(json, "properties", "weight", "type")).isEqualTo("number");
        assertThat(isValid("Links", "{\"weight\": 1.5}")).isTrue();
        assertThat(isValid("Links", "{}")).isFalse();
        assertThat(isValid("Links", "{\"weight\": 1, \"other\": 2}")).isFalse();
    }

    @Test
    void theSameSchemaIsAlwaysGeneratedAsTheSameText() {
        assertThat(JsonSchemas.values(schema, "Thing")).isEqualTo(JsonSchemas.values(schema, "Thing"));
        assertThat(JsonSchemas.values(schema, "Thing")).endsWith("}\n");
    }

    // ------------------------------------------------------------------ strings

    @Test
    void aStringHasItsAllowedValuesPatternAndLengths() {
        assertThat(DocumentTrees.at(property("kind"), "type")).isEqualTo("string");
        assertThat(DocumentTrees.listAt(property("kind"), "enum")).containsExactly("big", "small");
        assertThat(DocumentTrees.at(property("code"), "pattern")).isEqualTo("^(?:[A-Z]{2}[0-9]{3})$");
        assertThat(DocumentTrees.at(values("Base"), "properties", "note", "minLength")).isEqualTo(1);
        assertThat(DocumentTrees.at(values("Base"), "properties", "note", "maxLength")).isEqualTo(200);
        assertThat(DocumentTrees.keysOf(property("code"))).doesNotContain("enum", "minLength", "maxLength");
    }

    @Test
    void valuesOfStringsAreCheckedByTheValidator() {
        assertThat(isValid("Thing", "{\"name\": \"x\", \"kind\": \"big\", \"code\": \"AB123\"}")).isTrue();
        assertThat(isValid("Thing", "{\"name\": \"x\", \"kind\": \"huge\"}")).as("not an allowed value").isFalse();
        assertThat(isValid("Thing", "{\"name\": \"x\", \"kind\": \"big\", \"code\": \"ab123\"}")).as("pattern").isFalse();
        assertThat(isValid("Thing", "{\"name\": \"x\", \"kind\": \"big\", \"code\": 5}")).as("no string").isFalse();
        assertThat(isValid("Base", "{\"name\": \"x\", \"note\": \"\"}")).as("shorter than the minimum").isFalse();
        assertThat(isValid("Base", "{\"name\": \"x\", \"note\": \"" + "n".repeat(200) + "\"}")).isTrue();
        assertThat(isValid("Base", "{\"name\": \"x\", \"note\": \"" + "n".repeat(201) + "\"}")).as("longer").isFalse();
    }

    // ------------------------------------------------------------------ numbers

    @Test
    void anIntFitsThirtyTwoBitsAndItsRange() {
        assertThat(DocumentTrees.at(property("anyCount"), "type")).isEqualTo("integer");
        assertThat(DocumentTrees.at(property("anyCount"), "minimum")).isEqualTo(-2147483648);
        assertThat(DocumentTrees.at(property("anyCount"), "maximum")).isEqualTo(2147483647);
        assertThat(DocumentTrees.at(property("count"), "minimum")).as("the inclusive lower bound").isEqualTo(0);
        assertThat(DocumentTrees.at(property("count"), "maximum")).as("the type's own").isEqualTo(2147483647);
        assertThat(DocumentTrees.at(property("count"), "exclusiveMaximum")).isEqualTo(1000);
        assertThat(DocumentTrees.keysOf(property("count"))).doesNotContain("exclusiveMinimum");
    }

    @Test
    void aLongFitsSixtyFourBitsAndItsRange() {
        assertThat(DocumentTrees.at(property("anyBig"), "type")).isEqualTo("integer");
        assertThat(DocumentTrees.at(property("anyBig"), "minimum")).isEqualTo(Long.MIN_VALUE);
        assertThat(DocumentTrees.at(property("anyBig"), "maximum")).isEqualTo(Long.MAX_VALUE);
        assertThat(DocumentTrees.at(property("big"), "minimum")).as("the type's own").isEqualTo(Long.MIN_VALUE);
        assertThat(DocumentTrees.at(property("big"), "exclusiveMinimum")).isEqualTo(5);
    }

    @Test
    void aDecimalIsANumberWithItsRangeAndNoBoundsOfItsOwn() {
        assertThat(DocumentTrees.keysOf(property("anyAmount"))).containsExactly("type");
        assertThat(DocumentTrees.at(property("anyAmount"), "type")).isEqualTo("number");
        assertThat(DocumentTrees.keysOf(property("amount"))).containsExactly("type", "exclusiveMinimum", "maximum");
        assertThat(JsonSchemas.values(schema, "Thing")).contains("\"exclusiveMinimum\": 0.5").contains("\"maximum\": 100\n");
    }

    @Test
    void valuesOfNumbersAreCheckedByTheValidator() {
        String required = "\"name\": \"x\", \"kind\": \"big\", ";

        assertThat(isValid("Thing", "{" + required + "\"count\": 0}")).isTrue();
        assertThat(isValid("Thing", "{" + required + "\"count\": -1}")).as("below the range").isFalse();
        assertThat(isValid("Thing", "{" + required + "\"count\": 999}")).isTrue();
        assertThat(isValid("Thing", "{" + required + "\"count\": 1000}")).as("the bound is excluded").isFalse();
        assertThat(isValid("Thing", "{" + required + "\"count\": 1.5}")).as("no integer").isFalse();
        assertThat(isValid("Thing", "{" + required + "\"anyCount\": 2147483647}")).isTrue();
        assertThat(isValid("Thing", "{" + required + "\"anyCount\": 2147483648}")).as("beyond 32 bits").isFalse();
        assertThat(isValid("Thing", "{" + required + "\"anyBig\": 2147483648}")).isTrue();
        assertThat(isValid("Thing", "{" + required + "\"anyBig\": 9223372036854775807}")).isTrue();
        assertThat(isValid("Thing", "{" + required + "\"anyBig\": 9223372036854775808}")).as("beyond 64 bits").isFalse();
        assertThat(isValid("Thing", "{" + required + "\"big\": 5}")).as("the bound is excluded").isFalse();
        assertThat(isValid("Thing", "{" + required + "\"big\": 6}")).isTrue();
        assertThat(isValid("Thing", "{" + required + "\"amount\": 0.5}")).as("the bound is excluded").isFalse();
        assertThat(isValid("Thing", "{" + required + "\"amount\": 0.50001}")).isTrue();
        assertThat(isValid("Thing", "{" + required + "\"amount\": 100}")).isTrue();
        assertThat(isValid("Thing", "{" + required + "\"amount\": 100.01}")).isFalse();
        assertThat(isValid("Thing", "{" + required + "\"anyAmount\": 12345678901234567890.123456789}")).isTrue();
        assertThat(isValid("Thing", "{" + required + "\"anyAmount\": \"1\"}")).as("no number").isFalse();
    }

    // ------------------------------------------------------------------ the other value types

    @Test
    void aBooleanIsABoolean() {
        assertThat(DocumentTrees.keysOf(property("flag"))).containsExactly("type");
        assertThat(DocumentTrees.at(property("flag"), "type")).isEqualTo("boolean");
        assertThat(isValid("Thing", "{\"name\": \"x\", \"kind\": \"big\", \"flag\": true}")).isTrue();
        assertThat(isValid("Thing", "{\"name\": \"x\", \"kind\": \"big\", \"flag\": \"true\"}")).isFalse();
    }

    @Test
    void aDateAndAnInstantAreStringsWithTheirFormatsAndPatterns() {
        assertThat(DocumentTrees.at(property("day"), "type")).isEqualTo("string");
        assertThat(DocumentTrees.at(property("day"), "format")).isEqualTo("date");
        assertThat(DocumentTrees.at(property("day"), "pattern")).isEqualTo(ValuesSchema.DATE_PATTERN);
        assertThat(DocumentTrees.at(property("moment"), "type")).isEqualTo("string");
        assertThat(DocumentTrees.at(property("moment"), "format")).isEqualTo("date-time");
        assertThat(DocumentTrees.at(property("moment"), "pattern")).isEqualTo(ValuesSchema.INSTANT_PATTERN);
    }

    @Test
    void aDateOrAnInstantInTheWrongFormIsInvalidWithoutCheckingFormats() {
        String required = "\"name\": \"x\", \"kind\": \"big\", ";

        assertThat(isValid("Thing", "{" + required + "\"day\": \"2026-05-17\"}")).isTrue();
        assertThat(isValid("Thing", "{" + required + "\"day\": \"not-a-date\"}")).as("no date").isFalse();
        assertThat(isValid("Thing", "{" + required + "\"day\": \"2026-5-17\"}")).as("no leading zero").isFalse();
        assertThat(isValid("Thing", "{" + required + "\"day\": \"2026-05-17T00:00:00Z\"}")).as("an instant").isFalse();
        assertThat(isValid("Thing", "{" + required + "\"day\": 20260517}")).as("no string").isFalse();
        assertThat(isValid("Thing", "{" + required + "\"moment\": \"2026-05-17T09:30:00Z\"}")).isTrue();
        assertThat(isValid("Thing", "{" + required + "\"moment\": \"yesterday\"}")).as("no instant").isFalse();
        assertThat(isValid("Thing", "{" + required + "\"moment\": \"2026-05-17\"}")).as("a date").isFalse();
        assertThat(isValid("Thing", "{" + required + "\"moment\": \"2026-05-17T09:30:00\"}")).as("no zone").isFalse();
        assertThat(isValid("Thing", "{" + required + "\"moment\": 5}")).as("no string").isFalse();
    }

    @Test
    void aDateInEveryFormThatTheReaderAcceptsIsValid() {
        String required = "\"name\": \"x\", \"kind\": \"big\", ";

        for (String date : List.of("2026-05-17", "0001-01-01", "-0001-01-01", "-12345-01-01", "+12345-01-01",
                "+999999999-12-31", "-999999999-01-01")) {
            assertThat(LocalDate.parse(date)).as("the reader reads %s", date).isNotNull();
            assertThat(isValid("Thing", "{" + required + "\"day\": \"" + date + "\"}")).as(date).isTrue();
        }
    }

    @Test
    void aDateInAFormThatTheReaderDoesNotAcceptIsInvalidIfItIsClearlyMalformed() {
        String required = "\"name\": \"x\", \"kind\": \"big\", ";

        for (String date : List.of("2026-5-17", "2026-05-7", "yesterday", "", "26-05-17", "20260517", "2026/05/17",
                "+2026-01-01", "12345-01-01", "-123-01-01", "+1234-01-01", "2026-05-17 ", " 2026-05-17",
                "2026-05-17T00:00:00Z")) {
            assertThat(isValid("Thing", "{" + required + "\"day\": \"" + date + "\"}")).as("'" + date + "'").isFalse();
        }
    }

    @Test
    void anInstantMayHaveAFractionAndAnOffsetOtherThanZ() {
        String required = "\"name\": \"x\", \"kind\": \"big\", ";

        assertThat(isValid("Thing", "{" + required + "\"moment\": \"2026-05-17T09:30:00.123456Z\"}")).isTrue();
        assertThat(isValid("Thing", "{" + required + "\"moment\": \"2026-05-17T10:30:00+01:00\"}")).isTrue();
        assertThat(isValid("Thing", "{" + required + "\"moment\": \"2026-05-17T04:30:00.5-05:00\"}")).isTrue();
        assertThat(isValid("Thing", "{" + required + "\"moment\": \"2026-05-17T10:30:00+0100\"}")).isFalse();
    }

    @Test
    void theRangeOfADateOrInstantIsSaidInItsDescriptionBecauseJsonSchemaCannotOrderThem() {
        assertThat(DocumentTrees.at(property("day"), "description"))
                .isEqualTo("A day of 2026. Range: at least 2026-01-01 and less than 2027-01-01.");
        assertThat(DocumentTrees.at(property("moment"), "description"))
                .isEqualTo("Range: at most 2030-01-01T00:00:00Z.");
    }

    // ------------------------------------------------------------------ copied

    @Test
    void descriptionsAndExamplesAreCopiedAndDeprecatedAttributesAreMarked() {
        Object name = DocumentTrees.at(values("Thing"), "properties", "name");

        assertThat(DocumentTrees.at(name, "description")).isEqualTo("What it is called.");
        assertThat(DocumentTrees.listAt(name, "examples")).containsExactly("ACME", "Beta");
        assertThat(DocumentTrees.at(property("old"), "deprecated")).isEqualTo(true);
        assertThat(DocumentTrees.keysOf(property("code"))).doesNotContain("description", "examples", "deprecated");
    }

    @Test
    void examplesAreWrittenByTheirTypes() {
        Schema typed = PayloadFixtures.schemaOf(SchemaDefinition.builder()
                .entityType("Typed", type -> type
                        .attribute("n", INT, a -> a.examples(Value.of(1), Value.of(2)))
                        .attribute("d", DECIMAL, a -> a.examples(Value.of(new BigDecimal("1.50"))))
                        .attribute("b", BOOLEAN, a -> a.examples(Value.of(true)))
                        .attribute("day", DATE, a -> a.examples(Value.of(LocalDate.of(2026, 5, 17))))
                        .attribute("at", INSTANT, a -> a.examples(Value.of(Instant.parse("2026-05-17T09:30:00Z")))))
                .build());

        String json = JsonSchemas.values(typed, "Typed");

        assertThat(json).contains("\"examples\": [\n        1,\n        2\n      ]")
                .contains("\"examples\": [\n        1.50\n      ]")
                .contains("\"examples\": [\n        true\n      ]")
                .contains("\"examples\": [\n        \"2026-05-17\"\n      ]")
                .contains("\"examples\": [\n        \"2026-05-17T09:30:00Z\"\n      ]");
        assertThat(GeneratedSchemaCheck.dialectErrors(json)).isEmpty();
    }

    // ------------------------------------------------------------------ the reference domain

    @Test
    void everyTypeOfTheReferenceDomainHasASchemaInTheDialect() {
        Schema reference = new ReferenceDomainStore().schema();
        List<String> typeNames = Stream.concat(
                reference.entityTypes().stream().map(EntityType::name),
                reference.relationshipTypes().stream().map(RelationshipType::name)).toList();

        for (String name : typeNames) {
            assertThat(GeneratedSchemaCheck.dialectErrors(JsonSchemas.values(reference, name))).as(name).isEmpty();
        }
        assertThat(typeNames).contains("Party", "Organization", "Owns");
    }

    @Test
    void theRangeOfShareInTheReferenceDomainIsExclusiveAtZeroAndInclusiveAtOneHundred() {
        Object share = DocumentTrees.at(DocumentTrees.load(JsonSchemas.values(new ReferenceDomainStore().schema(), "Owns")),
                "properties", "share");

        assertThat(DocumentTrees.at(share, "type")).isEqualTo("number");
        assertThat(DocumentTrees.at(share, "exclusiveMinimum")).isEqualTo(0);
        assertThat(DocumentTrees.at(share, "maximum")).isEqualTo(100);
    }
}
