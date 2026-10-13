package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.PayloadFixtures.locationOf;
import static io.github.enricoag1982.cairn.documents.PayloadFixtures.payload;
import static io.github.enricoag1982.cairn.documents.PayloadFixtures.problem;
import static io.github.enricoag1982.cairn.documents.PayloadFixtures.problems;
import static io.github.enricoag1982.cairn.documents.PayloadFixtures.read;
import static io.github.enricoag1982.cairn.documents.PayloadFixtures.schemaOf;
import static io.github.enricoag1982.cairn.schema.ValueType.BOOLEAN;
import static io.github.enricoag1982.cairn.schema.ValueType.DATE;
import static io.github.enricoag1982.cairn.schema.ValueType.DECIMAL;
import static io.github.enricoag1982.cairn.schema.ValueType.INSTANT;
import static io.github.enricoag1982.cairn.schema.ValueType.INT;
import static io.github.enricoag1982.cairn.schema.ValueType.LONG;
import static io.github.enricoag1982.cairn.schema.ValueType.STRING;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.documents.PayloadFixtures.ReferenceDomainStore;
import io.github.enricoag1982.cairn.schema.Basis;
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
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reading transaction payloads (design D1 and D2 of {@code add-json-payloads}): every kind of write and every field,
 * references, the types of values, and strict reading with the place of each problem.
 */
class PayloadReadingTest {

    private static final Instant JAN_1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FEB_1 = Instant.parse("2026-02-01T00:00:00Z");

    private final ReferenceDomainStore reference = new ReferenceDomainStore();
    private final Schema schema = reference.schema();

    /** A schema with an attribute of every value type, one of them with an alias, and a relationship type. */
    private static final SchemaDefinition THINGS = SchemaDefinition.builder()
            .entityType("Thing", e -> e
                    .attribute("text", STRING, a -> a.aliases("label"))
                    .attribute("count", INT)
                    .attribute("big", LONG)
                    .attribute("amount", DECIMAL)
                    .attribute("flag", BOOLEAN)
                    .attribute("day", DATE)
                    .attribute("moment", INSTANT))
            .relationshipType("Link", r -> r.from("Thing").to("Thing").inverse("linkedFrom")
                    .attribute("weight", DECIMAL))
            .build();

    private static Map<String, Value> values(Object... namesAndValues) {
        Map<String, Value> values = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            values.put((String) namesAndValues[i], (Value) namesAndValues[i + 1]);
        }
        return values;
    }

    private static Write.Create onlyCreate(TransactionPayload payload) {
        assertThat(payload.transaction().writes()).singleElement().isInstanceOf(Write.Create.class);
        return (Write.Create) payload.transaction().writes().getFirst();
    }

    @Nested
    class EveryKindOfWrite {

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
                    { "replace": { "target": "$acme", "from": "2026-01-01T00:00:00Z",
                        "values": { "name": "ACME Holdings" }, "conflictCheck": { "perEntity": 4 } } },
                    { "endDate": { "target": "$owns", "from": "2026-08-01T00:00:00Z" } }
                  ]
                }
                """;

        private static final Transaction EXPECTED = Transaction.by("agent:onboarding")
                .onBehalfOf("person:alice")
                .reason("Onboarding of ACME")
                .idempotencyKey("onboarding-2026-01-05")
                .write(new Write.Create(Optional.of(new TemporaryId("acme")),
                        Optional.of(new EntityId("00000000-0000-7000-8000-0000000000aa")), "Organization",
                        EffectivePeriod.between(JAN_1, Instant.parse("2027-01-01T00:00:00Z")),
                        values("name", Value.of("ACME"), "lei", Value.of("5493001KJTIIGC8Y1R12"))))
                .write(new Write.Create(Optional.of(new TemporaryId("beta")), Optional.empty(), "Organization",
                        EffectivePeriod.from(JAN_1), values("name", Value.of("Beta"))))
                .write(new Write.Relate(Optional.of(new TemporaryId("owns")),
                        Optional.of(new RelationshipId("00000000-0000-7000-8000-0000000000bb")), "Owns",
                        new TemporaryId("acme"), new TemporaryId("beta"),
                        EffectivePeriod.between(FEB_1, Instant.parse("2026-12-01T00:00:00Z")),
                        values("share", Value.of(new BigDecimal("60")))))
                .write(new Write.Patch(new TemporaryId("owns"),
                        EffectivePeriod.between(FEB_1, Instant.parse("2026-06-01T00:00:00Z")),
                        values("share", Value.of(new BigDecimal("55.5"))), Set.of(),
                        ConflictCheck.perAttribute(new Basis(3))))
                .write(new Write.Replace(new TemporaryId("acme"), EffectivePeriod.from(JAN_1),
                        values("name", Value.of("ACME Holdings")), ConflictCheck.perEntity(new Basis(4))))
                .write(new Write.EndDate(new TemporaryId("owns"), Instant.parse("2026-08-01T00:00:00Z"),
                        ConflictCheck.none()))
                .build();

        @Test
        void aPayloadWithEveryKindOfWriteAndEveryFieldIsReadIntoTheTransaction() {
            TransactionPayload payload = read(ALL_KINDS, schema);

            assertThat(payload.transaction()).isEqualTo(EXPECTED);
        }

        @Test
        void aPayloadWrittenAsYamlInBlockStyleIsReadAsTheSameTransaction() {
            String yaml = """
                    cairn: 1
                    principal: agent:onboarding
                    onBehalfOf: person:alice
                    reason: Onboarding of ACME
                    idempotencyKey: onboarding-2026-01-05
                    writes:
                      - create:
                          temporaryId: "$acme"
                          id: 00000000-0000-7000-8000-0000000000aa
                          entityType: Organization
                          from: 2026-01-01T00:00:00Z
                          until: 2027-01-01T00:00:00Z
                          values:
                            name: ACME
                            lei: 5493001KJTIIGC8Y1R12
                      - create:
                          temporaryId: beta
                          entityType: Organization
                          from: 2026-01-01T00:00:00Z
                          values: { name: Beta }
                      - relate:
                          temporaryId: "$owns"
                          id: 00000000-0000-7000-8000-0000000000bb
                          relationshipType: Owns
                          source: "$acme"
                          target: "$beta"
                          from: 2026-02-01T00:00:00Z
                          until: 2026-12-01T00:00:00Z
                          values: { share: 60 }
                      - patch:
                          target: "$owns"
                          from: 2026-02-01T00:00:00Z
                          until: 2026-06-01T00:00:00Z
                          set: { share: 55.5 }
                          clear: []
                          conflictCheck: { perAttribute: 3 }
                      - replace:
                          target: "$acme"
                          from: 2026-01-01T00:00:00Z
                          values: { name: ACME Holdings }
                          conflictCheck: { perEntity: 4 }
                      - endDate:
                          target: "$owns"
                          from: 2026-08-01T00:00:00Z
                    """;

            assertThat(read(yaml, schema).transaction()).isEqualTo(EXPECTED);
        }

        @Test
        void onlyThePrincipalAndTheWritesAreRequiredOfATransaction() {
            TransactionPayload payload = read("{\"cairn\": 1, \"principal\": \"importer\", \"writes\": []}", schema);

            assertThat(payload.transaction()).isEqualTo(Transaction.by("importer").build());
        }

        @Test
        void anEmptyPrincipalIsReadAndLeftToTheEngineToReject() {
            TransactionPayload payload = read("{\"cairn\": 1, \"principal\": \"\", \"writes\": []}", schema);

            assertThat(payload.transaction().principal()).isEmpty();
        }

        @Test
        void aPatchThatClearsAttributesKeepsTheOrderOfTheNames() {
            TransactionPayload payload = read(payload("""
                    { "patch": { "target": "x", "from": "2026-01-01T00:00:00Z", "clear": ["lei", "name"] } }"""),
                    schema);

            assertThat(payload.transaction().writes()).singleElement().isInstanceOfSatisfying(Write.Patch.class,
                    patch -> assertThat(patch.clear()).containsExactly("lei", "name"));
        }

        @Test
        void aConflictCheckCanBeOfTheWholeEntityOrOfItsAttributesAndIsAbsentMeansNone() {
            TransactionPayload payload = read(payload(
                    "{ \"endDate\": { \"target\": \"x\", \"from\": \"2026-03-01T00:00:00Z\", "
                            + "\"conflictCheck\": { \"perEntity\": 0 } } }",
                    "{ \"endDate\": { \"target\": \"x\", \"from\": \"2026-03-01T00:00:00Z\", "
                            + "\"conflictCheck\": { \"perAttribute\": 12 } } }",
                    "{ \"endDate\": { \"target\": \"x\", \"from\": \"2026-03-01T00:00:00Z\" } }"), schema);

            assertThat(payload.transaction().writes()).extracting(write -> ((Write.EndDate) write).check())
                    .containsExactly(ConflictCheck.perEntity(Basis.NONE), ConflictCheck.perAttribute(new Basis(12)),
                            ConflictCheck.none());
        }

        @Test
        void instantsKeepMicrosecondsAndTruncateFinerOnes() {
            TransactionPayload payload = read(payload("""
                    { "create": { "entityType": "Organization", "from": "2026-01-01T10:20:30.123456789Z",
                        "until": "2026-02-01T00:00:00.5Z", "values": { "name": "ACME" } } }"""), schema);

            assertThat(onlyCreate(payload).period()).isEqualTo(EffectivePeriod.between(
                    Instant.parse("2026-01-01T10:20:30.123456Z"), Instant.parse("2026-02-01T00:00:00.500Z")));
        }
    }

    @Nested
    class References {

        @Test
        void aDollarSignStartsATemporaryIdAndAnythingElseIsAnId() {
            TransactionPayload payload = read(payload(
                    "{ \"relate\": { \"relationshipType\": \"Owns\", \"source\": \"$acme\", \"target\": \"some-id\", "
                            + "\"from\": \"2026-01-01T00:00:00Z\" } }"), schema);

            assertThat(payload.transaction().writes()).singleElement().isInstanceOfSatisfying(Write.Relate.class,
                    relate -> {
                        assertThat(relate.source()).isEqualTo(new TemporaryId("acme"));
                        assertThat(relate.target()).isEqualTo(new EntityId("some-id"));
                    });
        }

        @Test
        void anIdThatNeitherThePayloadNorTheLookupKnowsIsAnEntityIdSoTheEngineReportsIt() {
            TransactionPayload payload = read(payload(
                    "{ \"endDate\": { \"target\": \"nobody\", \"from\": \"2026-03-01T00:00:00Z\" } }"), schema);

            assertThat(payload.transaction().writes()).singleElement().isInstanceOfSatisfying(Write.EndDate.class,
                    end -> assertThat(end.target()).isEqualTo(new EntityId("nobody")));
            assertThat(reference.dryRunProblems(payload.transaction())).singleElement()
                    .satisfies(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_ENTITY));
        }

        @Test
        void aTemporaryIdIsWrittenWithOrWithoutTheDollarSignWhereItIsGiven() {
            TransactionPayload payload = read(payload(
                    "{ \"create\": { \"temporaryId\": \"$acme\", \"entityType\": \"Organization\", "
                            + "\"from\": \"2026-01-01T00:00:00Z\", \"values\": { \"name\": \"ACME\" } } }",
                    "{ \"create\": { \"temporaryId\": \"beta\", \"entityType\": \"Organization\", "
                            + "\"from\": \"2026-01-01T00:00:00Z\", \"values\": { \"name\": \"Beta\" } } }"), schema);

            assertThat(payload.transaction().writes()).extracting(write -> ((Write.Create) write).temporaryId())
                    .containsExactly(Optional.of(new TemporaryId("acme")), Optional.of(new TemporaryId("beta")));
        }

        @Test
        void anIdThatAnEarlierWriteSuppliesIsOfTheKindOfThatWrite() {
            TransactionPayload payload = read(payload(
                    "{ \"create\": { \"id\": \"e-1\", \"entityType\": \"Organization\", "
                            + "\"from\": \"2026-01-01T00:00:00Z\", \"values\": { \"name\": \"ACME\" } } }",
                    "{ \"relate\": { \"id\": \"r-1\", \"relationshipType\": \"Owns\", \"source\": \"e-1\", "
                            + "\"target\": \"e-1\", \"from\": \"2026-01-01T00:00:00Z\", \"values\": { \"share\": 1 } } }",
                    "{ \"patch\": { \"target\": \"r-1\", \"from\": \"2026-01-01T00:00:00Z\", "
                            + "\"set\": { \"share\": 2 } } }",
                    "{ \"patch\": { \"target\": \"e-1\", \"from\": \"2026-01-01T00:00:00Z\", "
                            + "\"set\": { \"lei\": \"5493001KJTIIGC8Y1R12\" } } }"), schema);

            List<Write> writes = payload.transaction().writes();
            assertThat(((Write.Relate) writes.get(1)).source()).isEqualTo(new EntityId("e-1"));
            assertThat(((Write.Patch) writes.get(2)).target()).isEqualTo(new RelationshipId("r-1"));
            assertThat(((Write.Patch) writes.get(2)).set()).containsEntry("share", Value.of(new BigDecimal("2")));
            assertThat(((Write.Patch) writes.get(3)).target()).isEqualTo(new EntityId("e-1"));
        }

        @Test
        void theLookupDecidesWhetherAnIdNamesAnEntityOrARelationship() {
            IdLookup lookup = id -> switch (id) {
                case "an-entity" -> Optional.of(new IdLookup.Known(IdLookup.Kind.ENTITY, "Person"));
                case "a-relationship" -> Optional.of(new IdLookup.Known(IdLookup.Kind.RELATIONSHIP, "Owns"));
                default -> Optional.empty();
            };

            TransactionPayload payload = read(payload(
                    "{ \"patch\": { \"target\": \"an-entity\", \"from\": \"2026-01-01T00:00:00Z\", "
                            + "\"set\": { \"birthDate\": \"1990-05-17\" } } }",
                    "{ \"patch\": { \"target\": \"a-relationship\", \"from\": \"2026-01-01T00:00:00Z\", "
                            + "\"set\": { \"share\": 55 } } }",
                    "{ \"endDate\": { \"target\": \"a-relationship\", \"from\": \"2026-03-01T00:00:00Z\" } }"),
                    schema, lookup);

            List<Write> writes = payload.transaction().writes();
            assertThat(((Write.Patch) writes.get(0)).target()).isEqualTo(new EntityId("an-entity"));
            assertThat(((Write.Patch) writes.get(0)).set())
                    .containsEntry("birthDate", Value.of(LocalDate.of(1990, 5, 17)));
            assertThat(((Write.Patch) writes.get(1)).target()).isEqualTo(new RelationshipId("a-relationship"));
            assertThat(((Write.Patch) writes.get(1)).set()).containsEntry("share", Value.of(new BigDecimal("55")));
            assertThat(((Write.EndDate) writes.get(2)).target()).isEqualTo(new RelationshipId("a-relationship"));
        }

        @Test
        void theValuesOfLaterWritesAreTypedByTheTypeOfTheEntityOrRelationshipATemporaryIdNames() {
            TransactionPayload payload = read(payload(
                    "{ \"create\": { \"temporaryId\": \"a\", \"entityType\": \"Person\", "
                            + "\"from\": \"2026-01-01T00:00:00Z\", \"values\": { \"name\": \"Alice\" } } }",
                    "{ \"patch\": { \"target\": \"$a\", \"from\": \"2026-01-01T00:00:00Z\", "
                            + "\"set\": { \"birthDate\": \"1990-05-17\" } } }",
                    "{ \"relate\": { \"temporaryId\": \"r\", \"relationshipType\": \"Employs\", \"source\": \"x\", "
                            + "\"target\": \"$a\", \"from\": \"2026-01-01T00:00:00Z\" } }",
                    "{ \"replace\": { \"target\": \"$r\", \"from\": \"2026-01-01T00:00:00Z\", "
                            + "\"values\": { \"title\": \"Analyst\" } } }"), schema);

            List<Write> writes = payload.transaction().writes();
            assertThat(((Write.Patch) writes.get(1)).set())
                    .containsEntry("birthDate", Value.of(LocalDate.of(1990, 5, 17)));
            assertThat(((Write.Replace) writes.get(3)).values()).containsEntry("title", Value.of("Analyst"));
        }

        @Test
        void withoutALookupThePatchedValuesOfAnIdThePayloadDoesNotCreateKeepTheirOwnTypes() {
            TransactionPayload payload = read(payload(
                    "{ \"patch\": { \"target\": \"some-id\", \"from\": \"2026-01-01T00:00:00Z\", "
                            + "\"set\": { \"birthDate\": \"1990-05-17\", \"share\": 55 } } }"), schema);

            assertThat(payload.transaction().writes()).singleElement().isInstanceOfSatisfying(Write.Patch.class,
                    patch -> assertThat(patch.set()).containsExactly(
                            Map.entry("birthDate", Value.of("1990-05-17")), Map.entry("share", Value.of(55))));
        }

        @Test
        void aTemporaryIdIsKnownOnlyToTheWritesAfterTheOneThatCreatesIt() {
            TransactionPayload payload = read(payload(
                    "{ \"patch\": { \"target\": \"$a\", \"from\": \"2026-01-01T00:00:00Z\", "
                            + "\"set\": { \"birthDate\": \"1990-05-17\" } } }",
                    "{ \"create\": { \"temporaryId\": \"a\", \"entityType\": \"Person\", "
                            + "\"from\": \"2026-01-01T00:00:00Z\", \"values\": { \"name\": \"Alice\" } } }"), schema);

            assertThat(((Write.Patch) payload.transaction().writes().getFirst()).set())
                    .containsEntry("birthDate", Value.of("1990-05-17"));
        }
    }

    @Nested
    class ValuesByType {

        private final Schema things = schemaOf(THINGS);

        private Map<String, Value> created(String values) {
            return onlyCreate(read(payload("{ \"create\": { \"entityType\": \"Thing\", \"from\": \"2026-01-01T00:00:00Z\", "
                    + "\"values\": " + values + " } }"), things)).values();
        }

        @Test
        void everyValueHasTheTypeOfItsAttribute() {
            Map<String, Value> values = created("""
                    { "text": "abc", "count": 7, "big": 5, "amount": 7, "flag": true, "day": "1990-05-17",
                      "moment": "2026-01-05T09:00:00Z" }""");

            assertThat(values).containsExactly(
                    Map.entry("text", Value.of("abc")),
                    Map.entry("count", Value.of(7)),
                    Map.entry("big", Value.of(5L)),
                    Map.entry("amount", Value.of(new BigDecimal("7"))),
                    Map.entry("flag", Value.of(true)),
                    Map.entry("day", Value.of(LocalDate.of(1990, 5, 17))),
                    Map.entry("moment", Value.of(Instant.parse("2026-01-05T09:00:00Z"))));
        }

        @Test
        void aDateIsAStringOfTheIsoForm() {
            assertThat(created("{ \"day\": \"1990-05-17\" }")).containsExactly(
                    Map.entry("day", Value.of(LocalDate.of(1990, 5, 17))));
        }

        @Test
        void decimalsAreReadFromTheirDigitsAndKeepEveryOne() {
            String digits = "12345678901234567890.123456789012345678901234567890";

            assertThat(created("{ \"amount\": " + digits + " }")).containsExactly(
                    Map.entry("amount", Value.of(new BigDecimal(digits))));
            assertThat(created("{ \"amount\": 0.10 }").get("amount")).isEqualTo(Value.of(new BigDecimal("0.10")));
            assertThat(created("{ \"amount\": 1e3 }").get("amount")).isEqualTo(Value.of(new BigDecimal("1E+3")));
        }

        @Test
        void aWholeNumberIsWidenedToTheTypeOfItsAttribute() {
            Map<String, Value> values = created("{ \"count\": 3, \"big\": 3000000000, \"amount\": 3 }");

            assertThat(values).containsExactly(
                    Map.entry("count", Value.of(3)),
                    Map.entry("big", Value.of(3000000000L)),
                    Map.entry("amount", Value.of(new BigDecimal("3"))));
            assertThat(created("{ \"big\": 3 }").get("big")).isEqualTo(Value.of(3L));
        }

        @Test
        void aValueThatDoesNotFitItsAttributeKeepsItsOwnYamlTypeForTheEngineToReport() {
            Map<String, Value> values = created("""
                    { "day": "tomorrow", "count": 1.5, "text": 5, "flag": "true", "moment": "2026-01-05",
                      "big": "7", "amount": "1.5" }""");

            assertThat(values).containsExactly(
                    Map.entry("day", Value.of("tomorrow")),
                    Map.entry("count", Value.of(new BigDecimal("1.5"))),
                    Map.entry("text", Value.of(5)),
                    Map.entry("flag", Value.of("true")),
                    Map.entry("moment", Value.of("2026-01-05")),
                    Map.entry("big", Value.of("7")),
                    Map.entry("amount", Value.of("1.5")));
        }

        @Test
        void aWholeNumberThatDoesNotFitAnIntIsALongOrADecimal() {
            Map<String, Value> values = created("{ \"count\": 3000000000, \"big\": 99999999999999999999 }");

            assertThat(values).containsExactly(
                    Map.entry("count", Value.of(3000000000L)),
                    Map.entry("big", Value.of(new BigDecimal("99999999999999999999"))));
        }

        @Test
        void aValueOfAnUnknownAttributeIsKeptAsAValueOfItsOwnYamlType() {
            Map<String, Value> values = created("""
                    { "nope": "x", "other": 5, "third": true, "fourth": 1.5, "fifth": "2026-01-05" }""");

            assertThat(values).containsExactly(
                    Map.entry("nope", Value.of("x")),
                    Map.entry("other", Value.of(5)),
                    Map.entry("third", Value.of(true)),
                    Map.entry("fourth", Value.of(new BigDecimal("1.5"))),
                    Map.entry("fifth", Value.of("2026-01-05")));
        }

        @Test
        void aValueOfAnUnknownAttributeIsReportedByTheEngineAtItsName() {
            TransactionPayload payload = read(payload("""
                    { "create": { "temporaryId": "acme", "entityType": "Organization", "from": "2026-01-01T00:00:00Z",
                        "values": { "name": "ACME", "nmae": 5 } } }"""), schema);

            List<Problem> problems = reference.dryRunProblems(payload.transaction());

            assertThat(problems).singleElement().satisfies(problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_ATTRIBUTE);
                assertThat(problem.path()).isEqualTo("$acme.nmae");
            });
        }

        @Test
        void attributeNamesAreMatchedByNameOrAliasWithoutRegardToCaseAndKeptAsWritten() {
            Map<String, Value> values = created("""
                    { "LABEL": "x", "Count": 2, "DAY": "1990-05-17", "Moment": "2026-01-05T09:00:00Z" }""");

            assertThat(values).containsExactly(
                    Map.entry("LABEL", Value.of("x")),
                    Map.entry("Count", Value.of(2)),
                    Map.entry("DAY", Value.of(LocalDate.of(1990, 5, 17))),
                    Map.entry("Moment", Value.of(Instant.parse("2026-01-05T09:00:00Z"))));
        }

        @Test
        void theValuesOfARelationshipAreTypedByTheRelationshipType() {
            TransactionPayload payload = read(payload("""
                    { "relate": { "relationshipType": "Link", "source": "a", "target": "b",
                        "from": "2026-01-01T00:00:00Z", "values": { "weight": 3, "other": 3 } } }"""), things);

            assertThat(payload.transaction().writes()).singleElement().isInstanceOfSatisfying(Write.Relate.class,
                    relate -> assertThat(relate.values()).containsExactly(
                            Map.entry("weight", Value.of(new BigDecimal("3"))), Map.entry("other", Value.of(3))));
        }

        @Test
        void aRelationshipTypeNameOrAliasIsMatchedWithoutRegardToCase() {
            TransactionPayload payload = read(payload("""
                    { "relate": { "relationshipType": "link", "source": "a", "target": "b",
                        "from": "2026-01-01T00:00:00Z", "values": { "weight": 3 } } }"""), things);

            assertThat(payload.transaction().writes()).singleElement().isInstanceOfSatisfying(Write.Relate.class,
                    relate -> assertThat(relate.values()).containsEntry("weight", Value.of(new BigDecimal("3"))));
        }

        @Test
        void anUnknownEntityTypeLeavesItsValuesToTheirOwnTypes() {
            assertThat(onlyCreate(read(payload("""
                    { "create": { "entityType": "Nothing", "from": "2026-01-01T00:00:00Z",
                        "values": { "day": "1990-05-17", "count": 3 } } }"""), things)).values())
                    .containsExactly(Map.entry("day", Value.of("1990-05-17")), Map.entry("count", Value.of(3)));
        }

        @Test
        void aPatchAndAReplaceOfAnEntityAreTypedByTheTypeOfTheEntity() {
            TransactionPayload payload = read(payload(
                    "{ \"create\": { \"temporaryId\": \"t\", \"entityType\": \"Thing\", "
                            + "\"from\": \"2026-01-01T00:00:00Z\" } }",
                    "{ \"patch\": { \"target\": \"$t\", \"from\": \"2026-01-01T00:00:00Z\", "
                            + "\"set\": { \"day\": \"1990-05-17\", \"Label\": \"x\" } } }",
                    "{ \"replace\": { \"target\": \"$t\", \"from\": \"2026-01-01T00:00:00Z\", "
                            + "\"values\": { \"amount\": 7 } } }"), things);

            List<Write> writes = payload.transaction().writes();
            assertThat(((Write.Patch) writes.get(1)).set()).containsExactly(
                    Map.entry("day", Value.of(LocalDate.of(1990, 5, 17))), Map.entry("Label", Value.of("x")));
            assertThat(((Write.Replace) writes.get(2)).values())
                    .containsExactly(Map.entry("amount", Value.of(new BigDecimal("7"))));
        }

        @Test
        void aTypeOfTheLookupThatTheSchemaDoesNotHaveLeavesValuesToTheirOwnTypes() {
            IdLookup lookup = id -> Optional.of(new IdLookup.Known(IdLookup.Kind.ENTITY, "Removed"));

            TransactionPayload payload = read(payload(
                    "{ \"patch\": { \"target\": \"x\", \"from\": \"2026-01-01T00:00:00Z\", "
                            + "\"set\": { \"day\": \"1990-05-17\" } } }"), things, lookup);

            assertThat(((Write.Patch) payload.transaction().writes().getFirst()).set())
                    .containsExactly(Map.entry("day", Value.of("1990-05-17")));
        }
    }

    @Nested
    class StrictReading {

        private static final String CREATE_ACME = "{ \"create\": { \"entityType\": \"Organization\", "
                + "\"from\": \"2026-01-01T00:00:00Z\", \"values\": { \"name\": \"ACME\" } } }";

        private Problem single(String text) {
            return problem(text, schema);
        }

        @Test
        void anUnknownKeyOfThePayloadIsReportedAtTheKeyWithTheClosestKnownKeyAsAHint() {
            String text = "{ \"cairn\": 1, \"principle\": \"importer\", \"writes\": [] }";

            List<Problem> problems = problems(text, schema);

            assertThat(problems).hasSize(2);
            Problem unknown = problems.stream().filter(p -> p.code() == ProblemCode.UNKNOWN_KEY).findFirst().orElseThrow();
            assertThat(unknown.path()).isEqualTo("@transaction");
            assertThat(unknown.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("principal"));
            assertThat(unknown.found()).contains("principle");
            assertThat(unknown.expected()).hasValueSatisfying(expected -> assertThat(expected)
                    .contains("cairn", "principal", "onBehalfOf", "reason", "idempotencyKey", "writes"));
            assertThat(unknown.location()).contains(locationOf(text, "\"principle\"", "/principle"));
            assertThat(problems).anySatisfy(p -> {
                assertThat(p.code()).isEqualTo(ProblemCode.MISSING_KEY);
                assertThat(p.expected()).contains("the key 'principal'");
            });
        }

        @Test
        void anUnknownKeyOfAWriteIsReportedAtTheKeyAndNamesTheWrite() {
            String text = payload(CREATE_ACME.replace("\"entityType\"", "\"entityTpe\""));

            Problem unknown = problems(text, schema).stream().filter(p -> p.code() == ProblemCode.UNKNOWN_KEY)
                    .findFirst().orElseThrow();

            assertThat(unknown.path()).isEqualTo("@write.0");
            assertThat(unknown.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("entityType"));
            assertThat(unknown.location()).contains(locationOf(text, "\"entityTpe\"", "/writes/0/create/entityTpe"));
        }

        @Test
        void aMissingRequiredKeyOfThePayloadIsReportedAtTheRoot() {
            String text = "{ \"cairn\": 1 }";

            List<Problem> problems = problems(text, schema);

            assertThat(problems).extracting(Problem::code).containsOnly(ProblemCode.MISSING_KEY);
            assertThat(problems).extracting(problem -> problem.expected().orElseThrow())
                    .containsExactlyInAnyOrder("the key 'principal'", "the key 'writes'");
            assertThat(pointers(problems)).containsOnly("");
        }

        @Test
        void aMissingRequiredKeyOfAWriteIsReportedAtTheWrite() {
            for (String key : List.of("entityType", "from")) {
                String text = payload(
                        CREATE_ACME,
                        CREATE_ACME.replaceFirst("\"" + key + "\": \"[^\"]*\", ", ""));

                Problem problem = single(text);

                assertThat(problem.code()).isEqualTo(ProblemCode.MISSING_KEY);
                assertThat(problem.path()).isEqualTo("@write.1");
                assertThat(problem.expected()).contains("the key '" + key + "'");
                assertThat(problem.location()).as(key).hasValueSatisfying(at -> {
                    assertThat(at.pointer()).isEqualTo("/writes/1/create");
                    assertThat(at.line()).isEqualTo(3);
                });
            }
        }

        @Test
        void everyWriteKindHasItsRequiredKeys() {
            assertThat(problems(payload("{ \"relate\": { } }"), schema)).extracting(p -> p.expected().orElseThrow())
                    .containsExactlyInAnyOrder("the key 'relationshipType'", "the key 'source'", "the key 'target'",
                            "the key 'from'");
            assertThat(problems(payload("{ \"patch\": { } }"), schema)).extracting(p -> p.expected().orElseThrow())
                    .containsExactlyInAnyOrder("the key 'target'", "the key 'from'");
            assertThat(problems(payload("{ \"replace\": { } }"), schema)).extracting(p -> p.expected().orElseThrow())
                    .containsExactlyInAnyOrder("the key 'target'", "the key 'from'", "the key 'values'");
            assertThat(problems(payload("{ \"endDate\": { } }"), schema)).extracting(p -> p.expected().orElseThrow())
                    .containsExactlyInAnyOrder("the key 'target'", "the key 'from'");
        }

        @Test
        void aValueOfTheWrongKindIsReportedAtTheValue() {
            String text = "{ \"cairn\": 1, \"principal\": 5, \"writes\": {} }";

            List<Problem> problems = problems(text, schema);

            assertThat(problems).extracting(Problem::code).containsOnly(ProblemCode.INVALID_VALUE);
            assertThat(pointers(problems)).containsExactly("/principal", "/writes");
            assertThat(problems.getFirst().hint()).contains("Put the value in quotes: \"5\".");
            assertThat(problems.getFirst().found()).contains("the number 5");
            assertThat(problems.get(1).expected()).contains("a list of writes");
            assertThat(problems.get(1).found()).contains("a mapping");
        }

        @Test
        void aWriteThatIsNoMappingIsReportedAtTheWrite() {
            String text = payload(CREATE_ACME, "\"create\"");

            Problem problem = single(text);

            assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_VALUE);
            assertThat(problem.path()).isEqualTo("@write.1");
            assertThat(problem.location()).contains(locationOf(text, "\"create\"\n", "/writes/1"));
        }

        @Test
        void aWriteKindWhoseValueIsNoMappingIsReportedAtTheValue() {
            Problem problem = single(payload("{ \"create\": [1] }"));

            assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_VALUE);
            assertThat(problem.expected()).contains("a mapping");
            assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/writes/0/create");
        }

        @Test
        void aWriteWithoutAKeyNamingItsKindIsReported() {
            Problem problem = single(payload("{ }"));

            assertThat(problem.code()).isEqualTo(ProblemCode.MISSING_KEY);
            assertThat(problem.path()).isEqualTo("@write.0");
            assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected)
                    .contains("create", "relate", "patch", "replace", "endDate"));
            assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/writes/0");
        }

        @Test
        void aWriteWithTwoKindsIsReportedAndStillReadForItsOtherProblems() {
            String text = payload("{ \"create\": { \"entityType\": 5, \"from\": \"2026-01-01T00:00:00Z\" }, "
                    + "\"endDate\": { \"target\": \"x\", \"from\": \"2026-01-01T00:00:00Z\" } }");

            List<Problem> problems = problems(text, schema);

            assertThat(problems).hasSize(2);
            assertThat(problems).anySatisfy(problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_VALUE);
                assertThat(problem.found()).contains("create, endDate");
                assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/writes/0");
            });
            assertThat(pointers(problems)).contains("/writes/0/create/entityType");
        }

        @Test
        void aNumberThatOnlyYamlKnowsIsNotANumberOfAPayload() {
            for (String number : List.of("0x10", "+1", ".5", "1.", "01", ".inf")) {
                String yaml = "cairn: 1\nprincipal: importer\nwrites:\n  - create:\n      entityType: Organization\n"
                        + "      from: 2026-01-01T00:00:00Z\n      values:\n        name: ACME\n        score: " + number + "\n";

                Problem problem = single(yaml);

                assertThat(problem.code()).as(number).isEqualTo(ProblemCode.INVALID_VALUE);
                assertThat(problem.path()).as(number).isEqualTo("@write.0.score");
                assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/writes/0/create/values/score");
            }
        }

        @Test
        void aNumberThatOnlyYamlKnowsGetsTheJsonFormAsAHint() {
            String yaml = "cairn: 1\nprincipal: importer\nwrites:\n  - patch:\n      target: x\n"
                    + "      from: 2026-01-01T00:00:00Z\n      conflictCheck: { perEntity: 0x10 }\n";

            Problem problem = single(yaml);

            assertThat(problem.hint()).contains("Write it as a JSON number, such as 16.");
            assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/writes/0/patch/conflictCheck/perEntity");
        }

        @Test
        void aValueThatIsNullOrNestedIsNotAValue() {
            String text = payload("{ \"create\": { \"entityType\": \"Organization\", \"from\": \"2026-01-01T00:00:00Z\", "
                    + "\"values\": { \"name\": null, \"lei\": [\"a\"], \"x\": { } } } }");

            List<Problem> problems = problems(text, schema);

            assertThat(problems).extracting(Problem::path)
                    .containsExactly("@write.0.name", "@write.0.lei", "@write.0.x");
            assertThat(pointers(problems)).containsExactly("/writes/0/create/values/name",
                    "/writes/0/create/values/lei", "/writes/0/create/values/x");
        }

        @Test
        void anInstantIsAStringInIsoFormAndADateAloneGetsAHint() {
            String text = payload("{ \"create\": { \"entityType\": \"Organization\", \"from\": \"2026-01-01\", "
                    + "\"until\": 5, \"values\": { \"name\": \"ACME\" } } }");

            List<Problem> problems = problems(text, schema);

            assertThat(problems).hasSize(2);
            assertThat(problems.getFirst().path()).isEqualTo("@write.0");
            assertThat(problems.getFirst().expected()).hasValueSatisfying(expected -> assertThat(expected)
                    .contains("ISO 8601", "2026-01-01T00:00:00Z"));
            assertThat(problems.getFirst().hint()).contains("Add the time of day and the zone: \"2026-01-01T00:00:00Z\".");
            assertThat(pointers(problems)).containsExactly("/writes/0/create/from", "/writes/0/create/until");
            assertThat(problems.get(1).hint()).isEmpty();
        }

        @Test
        void aReferenceThatIsBlankOrOnlyADollarSignIsReported() {
            String text = payload(
                    "{ \"endDate\": { \"target\": \"$\", \"from\": \"2026-01-01T00:00:00Z\" } }",
                    "{ \"endDate\": { \"target\": \"\", \"from\": \"2026-01-01T00:00:00Z\" } }",
                    "{ \"endDate\": { \"target\": 7, \"from\": \"2026-01-01T00:00:00Z\" } }");

            List<Problem> problems = problems(text, schema);

            assertThat(pointers(problems)).containsExactly("/writes/0/endDate/target", "/writes/1/endDate/target",
                    "/writes/2/endDate/target");
            assertThat(problems).extracting(Problem::code).containsOnly(ProblemCode.INVALID_VALUE);
            assertThat(problems.get(2).hint()).contains("Put the value in quotes: \"7\".");
        }

        @Test
        void aConflictCheckHasExactlyOneKeyWithAWholeNumberFromZero() {
            String text = payload(
                    "{ \"endDate\": { \"target\": \"x\", \"from\": \"2026-01-01T00:00:00Z\", \"conflictCheck\": { } } }",
                    "{ \"endDate\": { \"target\": \"x\", \"from\": \"2026-01-01T00:00:00Z\", "
                            + "\"conflictCheck\": { \"perEntity\": 1, \"perAttribute\": 1 } } }",
                    "{ \"endDate\": { \"target\": \"x\", \"from\": \"2026-01-01T00:00:00Z\", "
                            + "\"conflictCheck\": { \"perEntity\": -1 } } }",
                    "{ \"endDate\": { \"target\": \"x\", \"from\": \"2026-01-01T00:00:00Z\", "
                            + "\"conflictCheck\": { \"perEntity\": \"3\" } } }",
                    "{ \"endDate\": { \"target\": \"x\", \"from\": \"2026-01-01T00:00:00Z\", "
                            + "\"conflictCheck\": { \"perEntty\": 3 } } }",
                    "{ \"endDate\": { \"target\": \"x\", \"from\": \"2026-01-01T00:00:00Z\", \"conflictCheck\": 3 } }");

            List<Problem> problems = problems(text, schema);

            assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.MISSING_KEY,
                    ProblemCode.INVALID_VALUE, ProblemCode.INVALID_VALUE, ProblemCode.INVALID_VALUE,
                    ProblemCode.UNKNOWN_KEY, ProblemCode.INVALID_VALUE);
            assertThat(pointers(problems)).containsExactly("/writes/0/endDate/conflictCheck",
                    "/writes/1/endDate/conflictCheck", "/writes/2/endDate/conflictCheck/perEntity",
                    "/writes/3/endDate/conflictCheck/perEntity", "/writes/4/endDate/conflictCheck/perEntty",
                    "/writes/5/endDate/conflictCheck");
            assertThat(problems.get(4).hint()).contains("Did you mean 'perEntity'?");
        }

        @Test
        void theKeysToClearAreAListOfNames() {
            String text = payload(
                    "{ \"patch\": { \"target\": \"x\", \"from\": \"2026-01-01T00:00:00Z\", \"clear\": \"lei\" } }",
                    "{ \"patch\": { \"target\": \"x\", \"from\": \"2026-01-01T00:00:00Z\", \"clear\": [\"lei\", 5] } }");

            assertThat(pointers(problems(text, schema))).containsExactly("/writes/0/patch/clear",
                    "/writes/1/patch/clear/1");
        }

        @Test
        void aFormatVersionOtherThanOneIsTheOnlyProblem() {
            String text = "{ \"principle\": \"x\", \"cairn\": 2, \"writes\": [ { \"upsert\": {} } ] }";

            Problem problem = single(text);

            assertThat(problem.code()).isEqualTo(ProblemCode.UNSUPPORTED_FORMAT_VERSION);
            assertThat(problem.path()).isEqualTo("@transaction");
            assertThat(problem.message()).contains("transaction payloads", "version 2");
            assertThat(problem.location()).contains(locationOf(text, "\"cairn\"", "/cairn"));
        }

        @Test
        void theFormatVersionIsAnInteger() {
            Problem problem = single("{ \"cairn\": \"1\", \"principal\": \"x\", \"writes\": [] }");

            assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_VALUE);
            assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/cairn");
        }

        @Test
        void aSyntaxErrorIsTheOnlyProblem() {
            String text = "{ \"cairn\": 1, \"principal\": \"x\", \"writes\": [ { \"create\": ";

            Problem problem = single(text);

            assertThat(problem.code()).isEqualTo(ProblemCode.DOCUMENT_SYNTAX);
            assertThat(problem.path()).isEqualTo("@transaction");
        }

        @Test
        void aTextWithoutAContentIsMissingItsKeys() {
            List<Problem> problems = problems("", schema);

            assertThat(problems).extracting(Problem::code).containsOnly(ProblemCode.MISSING_KEY);
            assertThat(problems).extracting(problem -> problem.expected().orElseThrow())
                    .containsExactlyInAnyOrder("the key 'cairn'", "the key 'principal'", "the key 'writes'");
            assertThat(problems).extracting(problem -> problem.location().orElseThrow().line()).containsOnly(1);
        }

        @Test
        void aPayloadThatIsNoMappingIsReported() {
            Problem problem = single("[1, 2]");

            assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_VALUE);
            assertThat(problem.path()).isEqualTo("@transaction");
            assertThat(problem.expected()).contains("a mapping");
        }

        @Test
        void yamlFeaturesOutsideTheSubsetAreReportedAtTheWriteTheyAreIn() {
            String anchors = "cairn: 1\nprincipal: x\nwrites:\n  - create: &c\n      entityType: Organization\n"
                    + "      from: 2026-01-01T00:00:00Z\n  - create: *c\n";
            String tag = "cairn: 1\nprincipal: !!str x\nwrites: []\n";
            String merge = "cairn: 1\nprincipal: x\nwrites:\n  - create:\n      <<: {entityType: Organization}\n"
                    + "      from: 2026-01-01T00:00:00Z\n";
            String twoDocuments = "cairn: 1\nprincipal: x\nwrites: []\n---\ncairn: 1\n";

            Problem anchor = single(anchors);
            Problem tagged = single(tag);
            Problem merged = single(merge);
            Problem extra = single(twoDocuments);

            assertThat(anchor.code()).isEqualTo(ProblemCode.UNSUPPORTED_YAML_FEATURE);
            assertThat(anchor.path()).isEqualTo("@write.0");
            assertThat(anchor.location().orElseThrow().pointer()).isEqualTo("/writes/0/create");
            assertThat(tagged.code()).isEqualTo(ProblemCode.UNSUPPORTED_YAML_FEATURE);
            assertThat(tagged.path()).isEqualTo("@transaction");
            assertThat(merged.code()).isEqualTo(ProblemCode.UNSUPPORTED_YAML_FEATURE);
            assertThat(merged.path()).isEqualTo("@write.0");
            assertThat(extra.code()).isEqualTo(ProblemCode.UNSUPPORTED_YAML_FEATURE);
            assertThat(extra.path()).isEqualTo("@transaction");
            assertThat(extra.message()).contains("a transaction payload");
            assertThat(extra.location().orElseThrow().line()).isEqualTo(4);
        }

        @Test
        void aKeyGivenTwiceIsReportedAtTheSecond() {
            String text = "{ \"cairn\": 1, \"principal\": \"x\", \"principal\": \"y\", \"writes\": [] }";

            Problem problem = single(text);

            assertThat(problem.code()).isEqualTo(ProblemCode.DUPLICATE_KEY);
            assertThat(problem.location().orElseThrow().column()).isEqualTo(text.lastIndexOf("\"principal\"") + 1);
        }

        @Test
        void anAttributeGivenTwiceIsReportedAtTheSecondAndAKindGivenTwiceToo() {
            String text = payload(
                    "{ \"create\": { \"entityType\": \"Organization\", \"from\": \"2026-01-01T00:00:00Z\", "
                            + "\"values\": { \"name\": \"a\", \"name\": \"b\" } } }",
                    "{ \"endDate\": { \"target\": \"x\", \"from\": \"2026-01-01T00:00:00Z\" }, "
                            + "\"endDate\": { \"target\": \"y\", \"from\": \"2026-01-01T00:00:00Z\" } }");

            List<Problem> problems = problems(text, schema);

            assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.DUPLICATE_KEY,
                    ProblemCode.DUPLICATE_KEY);
            assertThat(problems.getFirst().path()).isEqualTo("@write.0.name");
            assertThat(pointers(problems)).containsExactly("/writes/0/create/values/name", "/writes/1/endDate");
        }

        @Test
        void everyProblemInOnePassIsReportedInTheOrderOfTheText() {
            String text = payload(
                    "{ \"create\": { \"entityType\": 1, \"from\": \"2026-01-01T00:00:00Z\" } }",
                    "{ \"upsert\": { } }",
                    "{ \"patch\": { \"target\": \"x\" } }");

            List<Problem> problems = problems(text, schema);

            assertThat(pointers(problems)).containsExactly("/writes/0/create/entityType", "/writes/1", "/writes/2/patch");
            assertThat(problems).extracting(problem -> problem.location().orElseThrow().line()).isSorted();
        }

        @Test
        void theSchemaKeyMustBeAStringAndIsIgnored() {
            Problem problem = single("{ \"$schema\": 5, \"cairn\": 1, \"principal\": \"x\", \"writes\": [] }");

            assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/$schema");
        }

        private List<String> pointers(List<Problem> problems) {
            return PayloadFixtures.pointers(problems);
        }
    }
}
