package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.schema.ValueType.BOOLEAN;
import static io.github.enricoag1982.cairn.schema.ValueType.DATE;
import static io.github.enricoag1982.cairn.schema.ValueType.DECIMAL;
import static io.github.enricoag1982.cairn.schema.ValueType.INSTANT;
import static io.github.enricoag1982.cairn.schema.ValueType.INT;
import static io.github.enricoag1982.cairn.schema.ValueType.LONG;
import static io.github.enricoag1982.cairn.schema.ValueType.STRING;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The JSON forms of results (design D4 of {@code add-json-payloads}): the field names of the records, what is left out,
 * the forms of IDs, instants, dates and decimals, the key order, and values that can be read back.
 */
class JsonResultsTest {

    private static final Instant JAN_1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant DEC_1 = Instant.parse("2026-12-01T00:00:00Z");
    private static final Instant RECORDED = Instant.parse("2026-01-05T09:00:00.123456Z");

    private static Map<String, Value> values(Object... namesAndValues) {
        Map<String, Value> values = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            values.put((String) namesAndValues[i], (Value) namesAndValues[i + 1]);
        }
        return values;
    }

    private static EntityState acme() {
        return new EntityState(new EntityId("e-1"), "Organization",
                values("name", Value.of("ACME"), "lei", Value.of("5493001KJTIIGC8Y1R12")),
                EffectivePeriod.between(JAN_1, DEC_1), RECORDED, new Basis(3));
    }

    private static EntityState beta() {
        return new EntityState(new EntityId("e-2"), "Organization", values("name", Value.of("Beta")),
                EffectivePeriod.from(JAN_1), RECORDED, new Basis(2));
    }

    private static RelationshipState owns() {
        return new RelationshipState(new RelationshipId("r-1"), "Owns", new EntityId("e-1"), new EntityId("e-2"),
                values("share", Value.of(new BigDecimal("55.50"))), EffectivePeriod.from(JAN_1), RECORDED, new Basis(4));
    }

    private static Problem problem() {
        return new Problem(ProblemCode.UNKNOWN_ATTRIBUTE, "$acme.nmae", "The attribute nmae is unknown.",
                Optional.of("an attribute of Organization"), Optional.of("nmae"), Optional.of("Did you mean 'name'?"),
                Optional.of(new DocumentLocation("/writes/0/create/values/nmae", 3, 27)), Optional.of("a-rule/code"));
    }

    // ------------------------------------------------------------------ receipts

    @Nested
    class Receipts {

        @Test
        void aReceiptHasTheFieldsOfTheRecordInTheirOrderWithIdsOfBothKinds() {
            Map<TemporaryId, Id> temporaryIds = new LinkedHashMap<>();
            temporaryIds.put(new TemporaryId("acme"), new EntityId("e-1"));
            temporaryIds.put(new TemporaryId("owns"), new RelationshipId("r-1"));
            Receipt receipt = new Receipt(new Basis(7), RECORDED, temporaryIds,
                    List.of(new EntityId("e-1"), new RelationshipId("r-1")), List.of(new EntityId("e-9")), "importer",
                    Optional.of("person:alice"), Optional.of("Onboarding"), Optional.of("key-1"));

            assertThat(Payloads.toJson(receipt)).isEqualTo("""
                    {
                      "basis": 7,
                      "transactionTime": "2026-01-05T09:00:00.123456Z",
                      "temporaryIds": {
                        "$acme": {
                          "entity": "e-1"
                        },
                        "$owns": {
                          "relationship": "r-1"
                        }
                      },
                      "created": [
                        {
                          "entity": "e-1"
                        },
                        {
                          "relationship": "r-1"
                        }
                      ],
                      "ended": [
                        {
                          "entity": "e-9"
                        }
                      ],
                      "principal": "importer",
                      "onBehalfOf": "person:alice",
                      "reason": "Onboarding",
                      "idempotencyKey": "key-1"
                    }
                    """);
        }

        @Test
        void aReceiptLeavesOutWhatIsEmpty() {
            Receipt receipt = new Receipt(new Basis(1), RECORDED, Map.of(), List.of(), List.of(), "importer",
                    Optional.empty(), Optional.empty(), Optional.empty());

            assertThat(DocumentTrees.keysOf(DocumentTrees.load(Payloads.toJson(receipt))))
                    .containsExactly("basis", "transactionTime", "principal");
        }
    }

    // ------------------------------------------------------------------ states, links and steps

    @Nested
    class States {

        @Test
        void anEntityStateHasItsValuesAndPeriodAndWhenItWasRecorded() {
            assertThat(Payloads.toJson(acme())).isEqualTo("""
                    {
                      "id": "e-1",
                      "entityType": "Organization",
                      "values": {
                        "name": "ACME",
                        "lei": "5493001KJTIIGC8Y1R12"
                      },
                      "period": {
                        "from": "2026-01-01T00:00:00Z",
                        "until": "2026-12-01T00:00:00Z"
                      },
                      "recordedAt": "2026-01-05T09:00:00.123456Z",
                      "recordedBasis": 3
                    }
                    """);
        }

        @Test
        void aStateLeavesOutAnEmptyValuesAndAnEndlessPeriodsEnd() {
            EntityState state = new EntityState(new EntityId("e-3"), "Thing", Map.of(), EffectivePeriod.from(JAN_1),
                    RECORDED, new Basis(1));

            Object json = DocumentTrees.load(Payloads.toJson(state));

            assertThat(DocumentTrees.keysOf(json)).containsExactly("id", "entityType", "period", "recordedAt",
                    "recordedBasis");
            assertThat(DocumentTrees.keysAt(json, "period")).containsExactly("from");
        }

        @Test
        void aRelationshipStateHasItsEndsAsIds() {
            Object json = DocumentTrees.load(Payloads.toJson(owns()));

            assertThat(DocumentTrees.keysOf(json)).containsExactly("id", "relationshipType", "source", "target", "values",
                    "period", "recordedAt", "recordedBasis");
            assertThat(DocumentTrees.at(json, "id")).isEqualTo("r-1");
            assertThat(DocumentTrees.at(json, "relationshipType")).isEqualTo("Owns");
            assertThat(DocumentTrees.at(json, "source")).isEqualTo("e-1");
            assertThat(DocumentTrees.at(json, "target")).isEqualTo("e-2");
            assertThat(DocumentTrees.at(json, "recordedBasis")).isEqualTo(4);
            assertThat(Payloads.toJson(owns())).contains("\"share\": 55.50\n");
        }

        @Test
        void aLinkHasTheRelationshipAndTheEntityAtItsOtherEnd() {
            Object json = DocumentTrees.load(Payloads.toJson(new Link(owns(), beta())));

            assertThat(DocumentTrees.keysOf(json)).containsExactly("relationship", "entity");
            assertThat(DocumentTrees.at(json, "relationship", "id")).isEqualTo("r-1");
            assertThat(DocumentTrees.at(json, "entity", "id")).isEqualTo("e-2");
        }

        @Test
        void aStepHasTheEntityItsDepthAndTheRelationshipItCameBy() {
            Object json = DocumentTrees.load(Payloads.toJson(new Step(beta(), 2, owns())));

            assertThat(DocumentTrees.keysOf(json)).containsExactly("entity", "depth", "via");
            assertThat(DocumentTrees.at(json, "entity", "id")).isEqualTo("e-2");
            assertThat(DocumentTrees.at(json, "depth")).isEqualTo(2);
            assertThat(DocumentTrees.at(json, "via", "id")).isEqualTo("r-1");
        }

        @Test
        void listsKeepTheOrderOfTheirItemsAndAnEmptyListIsAnEmptyList() {
            Object entities = DocumentTrees.load(Payloads.entityStatesToJson(List.of(beta(), acme())));
            Object relationships = DocumentTrees.load(Payloads.relationshipStatesToJson(List.of(owns())));
            Object links = DocumentTrees.load(Payloads.linksToJson(List.of(new Link(owns(), beta()))));
            Object steps = DocumentTrees.load(Payloads.stepsToJson(List.of(new Step(beta(), 1, owns()))));
            Object problems = DocumentTrees.load(Payloads.problemsToJson(List.of(problem(), problem())));

            assertThat(DocumentTrees.listOf(entities)).extracting(item -> DocumentTrees.at(item, "id")).containsExactly("e-2", "e-1");
            assertThat(DocumentTrees.listOf(relationships)).hasSize(1);
            assertThat(DocumentTrees.listOf(links)).hasSize(1);
            assertThat(DocumentTrees.listOf(steps)).hasSize(1);
            assertThat(DocumentTrees.listOf(problems)).hasSize(2);
            assertThat(Payloads.entityStatesToJson(List.of())).isEqualTo("[]\n");
            assertThat(Payloads.relationshipStatesToJson(List.of())).isEqualTo("[]\n");
            assertThat(Payloads.linksToJson(List.of())).isEqualTo("[]\n");
            assertThat(Payloads.stepsToJson(List.of())).isEqualTo("[]\n");
            assertThat(Payloads.problemsToJson(List.of())).isEqualTo("[]\n");
        }

        @Test
        void theSameResultIsAlwaysWrittenAsTheSameText() {
            assertThat(Payloads.toJson(acme())).isEqualTo(Payloads.toJson(acme()));
            assertThat(Payloads.toJson(owns())).isEqualTo(Payloads.toJson(owns()));
        }
    }

    // ------------------------------------------------------------------ problems

    @Nested
    class Problems {

        @Test
        void aProblemHasAllItsDetailsInAFixedOrder() {
            assertThat(Payloads.toJson(problem())).isEqualTo("""
                    {
                      "code": "UNKNOWN_ATTRIBUTE",
                      "path": "$acme.nmae",
                      "message": "The attribute nmae is unknown.",
                      "expected": "an attribute of Organization",
                      "found": "nmae",
                      "hint": "Did you mean 'name'?",
                      "ruleCode": "a-rule/code",
                      "location": {
                        "pointer": "/writes/0/create/values/nmae",
                        "line": 3,
                        "column": 27
                      }
                    }
                    """);
        }

        @Test
        void aProblemLeavesOutTheDetailsItDoesNotHave() {
            Problem bare = new Problem(ProblemCode.MISSING_PRINCIPAL, "@transaction", "No principal.");

            Object json = DocumentTrees.load(Payloads.toJson(bare));

            assertThat(DocumentTrees.keysOf(json)).containsExactly("code", "path", "message");
        }

        @Test
        void aProblemWithoutALocationHasNoLocation() {
            Problem unlocated = problem().at(new DocumentLocation("", 1, 1));
            Problem without = new Problem(ProblemCode.UNKNOWN_ATTRIBUTE, "$acme.nmae", "Unknown.", Optional.empty(),
                    Optional.empty(), Optional.of("Did you mean 'name'?"));

            assertThat(DocumentTrees.keysOf(DocumentTrees.load(Payloads.toJson(without))))
                    .containsExactly("code", "path", "message", "hint");
            assertThat(DocumentTrees.at(DocumentTrees.load(Payloads.toJson(unlocated)), "location", "pointer"))
                    .isEqualTo("");
        }

        @Test
        void textThatJsonEscapesIsWrittenAsJsonEscapesIt() {
            String awkward = "He said \"no\"\\ and\nthen\ttabbed, with $dollars and \u0001 and é";
            Problem problem = new Problem(ProblemCode.INVALID_VALUE, "@write.0", awkward);

            Object json = DocumentTrees.load(Payloads.toJson(problem));

            assertThat(DocumentTrees.at(json, "message")).isEqualTo(awkward);
        }
    }

    // ------------------------------------------------------------------ values

    @Nested
    class Values {

        private static final SchemaDefinition THINGS = SchemaDefinition.builder()
                .entityType("Thing", e -> e
                        .attribute("text", STRING)
                        .attribute("count", INT)
                        .attribute("big", LONG)
                        .attribute("amount", DECIMAL)
                        .attribute("flag", BOOLEAN)
                        .attribute("day", DATE)
                        .attribute("moment", INSTANT))
                .build();

        private final Schema schema = PayloadFixtures.schemaOf(THINGS);
        private final IdLookup lookup = id -> Optional.of(new IdLookup.Known(IdLookup.Kind.ENTITY, "Thing"));

        /**
         * The text of the {@code values} object of a state written as JSON, as it is written: decimals are not read into
         * numbers on the way, which would lose their digits. The text has the format of {@link Payloads}: the key is at
         * two spaces of indentation, and braces in strings are not counted.
         */
        private static String valuesObject(String json) {
            int start = json.indexOf("\n  \"values\": ") + "\n  \"values\": ".length();
            int depth = 0;
            boolean inString = false;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (inString) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        inString = false;
                    }
                } else if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}' && --depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
            throw new AssertionError("No values in " + json);
        }

        private EntityState thing(Map<String, Value> values) {
            return new EntityState(new EntityId("t-1"), "Thing", values, EffectivePeriod.from(JAN_1), RECORDED,
                    new Basis(1));
        }

        /** Writes the state as JSON, uses its values as the values of a replace payload, and reads the payload. */
        private Map<String, Value> readBack(EntityState state) {
            String values = valuesObject(Payloads.toJson(state));
            String payload = "{ \"cairn\": 1, \"principal\": \"agent:test\", \"writes\": [ { \"replace\": { "
                    + "\"target\": \"t-1\", \"from\": \"2026-02-01T00:00:00Z\", \"values\": " + values + " } } ] }";
            TransactionPayload read = PayloadFixtures.read(payload, schema, lookup);
            assertThat(read.transaction().writes()).singleElement().isInstanceOf(Write.Replace.class);
            return ((Write.Replace) read.transaction().writes().getFirst()).values();
        }

        @Test
        void everyValueIsWrittenAsTheJsonScalarOfItsType() {
            EntityState state = thing(values(
                    "text", Value.of("a \"quoted\" word"),
                    "count", Value.of(42),
                    "big", Value.of(9_000_000_000L),
                    "amount", Value.of(new BigDecimal("1000.50")),
                    "flag", Value.of(true),
                    "day", Value.of(LocalDate.of(2026, 5, 17)),
                    "moment", Value.of(Instant.parse("2026-05-17T09:30:00Z"))));

            assertThat(Payloads.toJson(state)).contains("""
                      "values": {
                        "text": "a \\"quoted\\" word",
                        "count": 42,
                        "big": 9000000000,
                        "amount": 1000.50,
                        "flag": true,
                        "day": "2026-05-17",
                        "moment": "2026-05-17T09:30:00Z"
                      },
                    """);
        }

        @Test
        void aDecimalKeepsEveryDigitAndItsScale() {
            EntityState state = thing(values("amount", Value.of(new BigDecimal("12345678901234567890.123456789000"))));

            assertThat(Payloads.toJson(state)).contains("\"amount\": 12345678901234567890.123456789000\n");
            assertThat(readBack(state)).isEqualTo(state.values());
        }

        @Test
        void aDecimalWithANegativeScaleIsWrittenInAFormThatKeepsItsScale() {
            EntityState state = thing(values("amount", Value.of(new BigDecimal("1E+3"))));

            assertThat(Payloads.toJson(state)).contains("\"amount\": 1E+3\n");
            assertThat(readBack(state)).isEqualTo(state.values());
        }

        @Test
        void theValuesOfAStateAreReadBackAsTheValuesOfAReplaceWhateverTheirTypes() {
            EntityState state = thing(values(
                    "text", Value.of("é \"x\" \\ \n"),
                    "count", Value.of(-2_147_483_648),
                    "big", Value.of(Long.MIN_VALUE),
                    "amount", Value.of(new BigDecimal("-0.000010")),
                    "flag", Value.of(false),
                    "day", Value.of(LocalDate.of(2026, 5, 17)),
                    "moment", Value.of(Instant.parse("2026-05-17T09:30:00.123456Z"))));

            assertThat(readBack(state)).isEqualTo(state.values());
        }

        @Test
        void valuesKeepTheOrderOfTheState() {
            EntityState state = thing(values("moment", Value.of(JAN_1), "text", Value.of("x"), "count", Value.of(1)));

            assertThat(DocumentTrees.keysAt(DocumentTrees.load(Payloads.toJson(state)), "values"))
                    .containsExactly("moment", "text", "count");
        }

        @Test
        void aStateWithoutValuesHasNoValuesToReadBack() {
            assertThat(Payloads.toJson(thing(Map.of()))).doesNotContain("values");
        }
    }
}
