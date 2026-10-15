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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.documents.PayloadFixtures.ReferenceDomainStore;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unique keys in transaction payloads (ADR-0026, design D5 of {@code add-unique-key-references}): reading a reference
 * that is a mapping with {@code entityType} and {@code key}, typing its value, the problems of its shapes and where they
 * are located, what a lookup adds, and locating the problems of a commit about the entities that keys name.
 */
class UniqueKeyPayloadsTest {

    private static final String FROM = "\"from\": \"2026-03-15T00:00:00Z\"";

    /** A thing with a unique attribute of every value type, one of them with an alias. */
    private static final SchemaDefinition THINGS = SchemaDefinition.builder()
            .entityType("Thing", thing -> thing
                    .attribute("text", STRING, a -> a.unique().aliases("label"))
                    .attribute("count", INT, a -> a.unique())
                    .attribute("big", LONG, a -> a.unique())
                    .attribute("amount", DECIMAL, a -> a.unique())
                    .attribute("flag", BOOLEAN, a -> a.unique())
                    .attribute("day", DATE, a -> a.unique())
                    .attribute("moment", INSTANT, a -> a.unique()))
            .build();

    private final ReferenceDomainStore reference = new ReferenceDomainStore();
    private final Schema schema = reference.schema();

    private static String key(String entityType, String attribute, String valueJson) {
        return "{ \"entityType\": \"" + entityType + "\", \"key\": { \"" + attribute + "\": " + valueJson + " } }";
    }

    private static String patchBy(String reference) {
        return "{ \"patch\": { \"target\": " + reference + ", " + FROM + ", \"set\": { \"balance\": 1500 } } }";
    }

    private static Write.Patch onlyPatch(TransactionPayload payload) {
        assertThat(payload.transaction().writes()).singleElement().isInstanceOf(Write.Patch.class);
        return (Write.Patch) payload.transaction().writes().getFirst();
    }

    private static UniqueKey targetOf(TransactionPayload payload) {
        return (UniqueKey) onlyPatch(payload).target();
    }

    // ------------------------------------------------------------------ every place of a reference

    @Test
    void everyPlaceThatTakesAReferenceTakesAKey() {
        UniqueKey account = new UniqueKey("Account", "number", Value.of("A-1"));
        UniqueKey organization = new UniqueKey("Organization", "lei", Value.of("5493001KJTIIGC8Y1R12"));
        String text = payload(
                patchBy(key("Account", "number", "\"A-1\"")),
                "{ \"replace\": { \"target\": " + key("Organization", "lei", "\"5493001KJTIIGC8Y1R12\"") + ", " + FROM
                        + ", \"values\": { \"name\": \"ACME\" } } }",
                "{ \"endDate\": { \"target\": " + key("Account", "number", "\"A-1\"") + ", " + FROM + " } }",
                "{ \"relate\": { \"relationshipType\": \"HeldBy\", \"source\": "
                        + key("Account", "number", "\"A-1\"") + ", \"target\": "
                        + key("Organization", "lei", "\"5493001KJTIIGC8Y1R12\"") + ", " + FROM + " } }");

        List<Write> writes = read(text, schema).transaction().writes();

        assertThat(((Write.Patch) writes.get(0)).target()).isEqualTo(account);
        assertThat(((Write.Replace) writes.get(1)).target()).isEqualTo(organization);
        assertThat(((Write.EndDate) writes.get(2)).target()).isEqualTo(account);
        assertThat(((Write.Relate) writes.get(3)).source()).isEqualTo(account);
        assertThat(((Write.Relate) writes.get(3)).target()).isEqualTo(organization);
    }

    @Test
    void aKeyIsReadFromYamlAsFromJson() {
        String yaml = """
                cairn: 1
                principal: agent:onboarding
                writes:
                  - patch:
                      target:
                        entityType: Account
                        key:
                          number: A-1
                      from: 2026-03-15T00:00:00Z
                      set:
                        balance: 1500
                """;

        Write.Patch patch = onlyPatch(read(yaml, schema));

        assertThat(patch.target()).isEqualTo(new UniqueKey("Account", "number", Value.of("A-1")));
        assertThat(patch.set()).containsEntry("balance", Value.of(new BigDecimal("1500")));
    }

    @Test
    void aTemporaryIdAnIdAndAKeyCanBeMixedInOneTransaction() {
        String text = payload(
                "{ \"create\": { \"temporaryId\": \"acme\", \"entityType\": \"Organization\", "
                        + "\"from\": \"2026-01-01T00:00:00Z\", \"values\": { \"name\": \"ACME\" } } }",
                "{ \"patch\": { \"target\": \"$acme\", " + FROM + ", \"set\": { \"name\": \"ACME Holdings\" } } }",
                patchBy(key("Account", "number", "\"A-1\"")),
                "{ \"patch\": { \"target\": \"some-id\", " + FROM + ", \"set\": { \"name\": \"Other\" } } }");

        List<Write> writes = read(text, schema).transaction().writes();

        assertThat(((Write.Patch) writes.get(1)).target()).isEqualTo(new TemporaryId("acme"));
        assertThat(((Write.Patch) writes.get(2)).target()).isInstanceOf(UniqueKey.class);
        assertThat(((Write.Patch) writes.get(3)).target()).isEqualTo(new EntityId("some-id"));
    }

    // ------------------------------------------------------------------ the value of a key

    @Nested
    class TheValueOfAKey {

        private final Schema things = schemaOf(THINGS);

        private Value valueOf(String attribute, String valueJson) {
            return targetOf(read(payload("{ \"patch\": { \"target\": " + key("Thing", attribute, valueJson) + ", "
                    + FROM + ", \"set\": { \"label\": \"x\" } } }"), things)).value();
        }

        @Test
        void isReadByTheValueTypeOfItsAttribute() {
            assertThat(valueOf("text", "\"A-1\"")).isEqualTo(Value.of("A-1"));
            assertThat(valueOf("count", "7")).isEqualTo(Value.of(7));
            assertThat(valueOf("big", "7")).as("a whole number for a long attribute").isEqualTo(Value.of(7L));
            assertThat(valueOf("big", "9000000000")).isEqualTo(Value.of(9_000_000_000L));
            assertThat(valueOf("amount", "7")).as("a whole number for a decimal attribute")
                    .isEqualTo(Value.of(new BigDecimal("7")));
            assertThat(valueOf("amount", "1500.50")).isEqualTo(Value.of(new BigDecimal("1500.50")));
            assertThat(valueOf("flag", "true")).isEqualTo(Value.of(true));
            assertThat(valueOf("day", "\"2026-05-17\"")).isEqualTo(Value.of(LocalDate.of(2026, 5, 17)));
            assertThat(valueOf("moment", "\"2026-05-17T10:00:00+02:00\""))
                    .isEqualTo(Value.of(Instant.parse("2026-05-17T08:00:00Z")));
        }

        @Test
        void isReadByTheAttributeThatAnAliasOrAnotherCapitalizationNames() {
            assertThat(targetOf(read(payload("{ \"patch\": { \"target\": " + key("thing", "LABEL", "\"x\"")
                    + ", " + FROM + ", \"set\": { \"text\": \"y\" } } }"), things)).value())
                    .isEqualTo(Value.of("x"));
            assertThat(targetOf(read(payload("{ \"patch\": { \"target\": " + key("Thing", "Big", "5") + ", " + FROM
                    + ", \"set\": { \"text\": \"y\" } } }"), things)).value()).isEqualTo(Value.of(5L));
        }

        @Test
        void isKeptAsItIsWrittenAndNotConvertedToAStringThatTheAttributeDoesNotHave() {
            assertThat(valueOf("text", "5")).as("a number for a string attribute is not a string")
                    .isEqualTo(Value.of(5));
            assertThat(valueOf("count", "\"7\"")).isEqualTo(Value.of("7"));
        }

        @Test
        void isReadByItsOwnYamlTypeIfTheSchemaDoesNotHaveTheTypeOrTheAttribute() {
            assertThat(targetOf(read(patchPayload(key("Nothing", "x", "5")), things)).value())
                    .isEqualTo(Value.of(5));
            assertThat(valueOf("nope", "\"a\"")).isEqualTo(Value.of("a"));
            assertThat(valueOf("nope", "true")).isEqualTo(Value.of(true));
            assertThat(valueOf("nope", "1.5")).isEqualTo(Value.of(new BigDecimal("1.5")));
            assertThat(valueOf("nope", "9000000000")).isEqualTo(Value.of(9_000_000_000L));
        }

        private String patchPayload(String reference) {
            return payload("{ \"patch\": { \"target\": " + reference + ", " + FROM
                    + ", \"set\": { \"label\": \"x\" } } }");
        }
    }

    // ------------------------------------------------------------------ problems of reading

    @Nested
    class ProblemsOfReading {

        private Problem aProblemAt(String targetJson, String pointer, ProblemCode code) {
            String text = payload("{ \"patch\": { \"target\": " + targetJson + ", " + FROM
                    + ", \"set\": { \"balance\": 1 } } }");
            Problem problem = problem(text, schema);
            assertThat(problem.code()).isEqualTo(code);
            assertThat(problem.path()).isEqualTo("@write.0");
            assertThat(pointerOf(problem)).isEqualTo(pointer);
            return problem;
        }

        private String pointerOf(Problem problem) {
            return problem.location().orElseThrow().pointer();
        }

        @Test
        void aKeyWithTwoAttributesIsLocatedAtTheKeyAndNamesThem() {
            Problem problem = aProblemAt("{ \"entityType\": \"Account\", \"key\": { \"number\": \"A-1\", "
                    + "\"currency\": \"EUR\" } }", "/writes/0/patch/target/key", ProblemCode.INVALID_VALUE);

            assertThat(problem.found()).contains("number, currency");
            assertThat(problem.expected()).contains("exactly one entry");
            assertThat(problem.message()).contains("2 entries");
        }

        @Test
        void aKeyWithoutAnEntryIsLocatedAtTheKey() {
            aProblemAt("{ \"entityType\": \"Account\", \"key\": {} }", "/writes/0/patch/target/key",
                    ProblemCode.MISSING_KEY);
        }

        @Test
        void aKeyThatIsNoMappingIsLocatedAtItsValue() {
            Problem problem = aProblemAt("{ \"entityType\": \"Account\", \"key\": \"A-1\" }",
                    "/writes/0/patch/target/key", ProblemCode.INVALID_VALUE);

            assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected).contains("one entry"));
        }

        @Test
        void aReferenceWithoutAKeyOrWithoutAnEntityTypeIsLocatedAtTheReference() {
            Problem noKey = aProblemAt("{ \"entityType\": \"Account\" }", "/writes/0/patch/target",
                    ProblemCode.MISSING_KEY);
            Problem noType = aProblemAt("{ \"key\": { \"number\": \"A-1\" } }", "/writes/0/patch/target",
                    ProblemCode.MISSING_KEY);

            assertThat(noKey.expected()).contains("the key 'key'");
            assertThat(noType.expected()).contains("the key 'entityType'");
        }

        @Test
        void anUnknownKeyOfAReferenceIsLocatedAtItAndNamesTheKnownOnes() {
            Problem problem = aProblemAt("{ \"entityType\": \"Account\", \"key\": { \"number\": \"A-1\" }, "
                    + "\"keys\": 1 }", "/writes/0/patch/target/keys", ProblemCode.UNKNOWN_KEY);

            assertThat(problem.expected()).contains("one of entityType, key");
            assertThat(problem.hint()).contains("Did you mean 'key'?");
        }

        @Test
        void anEntityTypeThatIsNoStringIsLocatedAtIt() {
            aProblemAt("{ \"entityType\": 5, \"key\": { \"number\": \"A-1\" } }",
                    "/writes/0/patch/target/entityType", ProblemCode.INVALID_VALUE);
        }

        @Test
        void aValueThatIsNoLiteralIsLocatedAtTheValue() {
            aProblemAt("{ \"entityType\": \"Account\", \"key\": { \"number\": [ \"A-1\" ] } }",
                    "/writes/0/patch/target/key/number", ProblemCode.INVALID_VALUE);
            aProblemAt("{ \"entityType\": \"Account\", \"key\": { \"number\": null } }",
                    "/writes/0/patch/target/key/number", ProblemCode.INVALID_VALUE);
            aProblemAt("{ \"entityType\": \"Account\", \"key\": { \"number\": { \"x\": 1 } } }",
                    "/writes/0/patch/target/key/number", ProblemCode.INVALID_VALUE);
        }

        @Test
        void aKeyThatNamesTheSameAttributeTwiceIsADuplicateKey() {
            String text = "{ \"cairn\": 1, \"principal\": \"agent:onboarding\", \"writes\": [ { \"patch\": { "
                    + "\"target\": { \"entityType\": \"Account\", \"key\": { \"number\": \"A-1\", \"number\": "
                    + "\"A-2\" } }, " + FROM + ", \"set\": { \"balance\": 1 } } } ] }";

            List<Problem> problems = problems(text, schema);

            assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.DUPLICATE_KEY);
        }

        @Test
        void aListWhereAReferenceIsExpectedIsStillAProblemOfTheKindOfValue() {
            String text = payload("{ \"patch\": { \"target\": [ \"a\" ], " + FROM + ", \"set\": { \"balance\": 1 } } }");

            Problem problem = problem(text, schema);

            assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_VALUE);
            assertThat(problem.location()).contains(locationOf(text, "[ \"a\" ]", "/writes/0/patch/target"));
        }

        @Test
        void everyProblemOfAPayloadIsFoundInOnePass() {
            String text = payload(
                    patchBy("{ \"entityType\": \"Account\" }"),
                    patchBy("{ \"entityType\": \"Account\", \"key\": { \"a\": 1, \"b\": 2 } }"),
                    patchBy(key("Account", "number", "\"A-1\"")));

            List<Problem> problems = problems(text, schema);

            assertThat(problems).extracting(Problem::path).containsExactly("@write.0", "@write.1");
        }
    }

    // ------------------------------------------------------------------ the lookup

    /** The reference model with a {@code Bank}, an organization with a decimal {@code capital}, in a store. */
    private ReferenceDomainStore storeWithABank() {
        ReferenceDomainStore store = new ReferenceDomainStore();
        assertThat(store.store.schema().register(new Origin("banking", "1.0.0"), SchemaDefinition.builder()
                .entityType("Bank", bank -> bank.supertype("Organization").attribute("capital", DECIMAL)).build()))
                .isInstanceOf(RegistrationResult.Applied.class);
        store.clock.moveTo(ReferenceStories.on(6, 1));
        store.commit(Transaction.by("importer").create(new TemporaryId("bank"), "Bank",
                ReferenceStories.from(1, 1), ReferenceStories.values("name", "First Bank", "lei",
                        "7245008A9C2K8H1Z7B28")).build());
        return store;
    }

    private static String setCapital(String keyJson) {
        return payload("{ \"patch\": { \"target\": " + keyJson + ", " + FROM + ", \"set\": { \"capital\": 1000 } } }");
    }

    @Test
    void theLookupTypesTheValuesOfAWriteByTheTypeOfTheEntityTheKeyNames() {
        ReferenceDomainStore store = storeWithABank();
        String text = setCapital(key("Organization", "lei", "\"7245008A9C2K8H1Z7B28\""));

        Write.Patch withLookup = onlyPatch(read(text, store.schema(), store.storeLookup()));
        Write.Patch without = onlyPatch(read(text, store.schema(), IdLookup.none()));

        assertThat(withLookup.set()).as("capital is an attribute of the Bank that the key names")
                .containsEntry("capital", Value.of(new BigDecimal("1000")));
        assertThat(without.set()).as("an Organization has no capital, so the value has its own type")
                .containsEntry("capital", Value.of(1000));
    }

    @Test
    void theLookupResolvesAKeyAtTheStartOfTheWrite() {
        ReferenceDomainStore store = storeWithABank();
        String key = key("Organization", "lei", "\"7245008A9C2K8H1Z7B28\"");
        String early = payload("{ \"patch\": { \"target\": " + key
                + ", \"from\": \"2025-12-01T00:00:00Z\", \"set\": { \"capital\": 1000 } } }");

        Write.Patch afterItExists = onlyPatch(read(setCapital(key), store.schema(), store.storeLookup()));
        Write.Patch beforeItExists = onlyPatch(read(early, store.schema(), store.storeLookup()));

        assertThat(afterItExists.set()).containsEntry("capital", Value.of(new BigDecimal("1000")));
        assertThat(beforeItExists.set()).as("no entity has the value on 1 December 2025, so the key's own type decides")
                .containsEntry("capital", Value.of(1000));
    }

    @Test
    void aLookupThatIsWrittenAsALambdaResolvesNoKey() {
        IdLookup lookup = id -> Optional.empty();

        assertThat(lookup.find(new UniqueKey("Account", "number", Value.of("A-1")), Instant.EPOCH)).isEmpty();
    }

    @Test
    void aLookupOfAStoreFindsIdsAndKeysAndNothingForAnInvalidKey() {
        Receipt parties = reference.replay(ReferenceStories.rd1Parties());
        Receipt accounts = reference.replay(ReferenceStories.rd5Accounts(
                parties.temporaryIds().get(ReferenceStories.BETA), parties.temporaryIds().get(ReferenceStories.ACME)));
        EntityId a1 = (EntityId) accounts.temporaryIds().get(ReferenceStories.A1);
        IdLookup lookup = reference.storeLookup();
        Instant march = ReferenceStories.on(3, 1);

        assertThat(lookup.find(new UniqueKey("Account", "number", Value.of("A-1")), march))
                .contains(new IdLookup.Named(a1.value(), "Account"));
        assertThat(lookup.find(new UniqueKey("Account", "number", Value.of("A-9")), march)).isEmpty();
        assertThat(lookup.find(new UniqueKey("Account", "number", Value.of("A-1")), ReferenceStories.on(2025, 1, 1)))
                .as("before the account exists").isEmpty();
        assertThat(lookup.find(new UniqueKey("Account", "currency", Value.of("EUR")), march))
                .as("an invalid key").isEmpty();
        assertThat(lookup.find(new UniqueKey("Nothing", "x", Value.of(1)), march)).isEmpty();
        assertThat(lookup.find(a1.value())).contains(new IdLookup.Known(IdLookup.Kind.ENTITY, "Account"));
        assertThatNullPointerException().isThrownBy(() -> IdLookup.fromStore(null));
        assertThatNullPointerException().isThrownBy(() -> new IdLookup.Named(null, "Account"));
        assertThatNullPointerException().isThrownBy(() -> new IdLookup.Named("a", null));
    }

    // ------------------------------------------------------------------ locating the problems of a commit

    @Nested
    class LocatingProblemsAboutEntitiesNamedByKeys {

        private EntityId a1;
        private EntityId acme;

        private void accounts() {
            Receipt parties = reference.replay(ReferenceStories.rd1Parties());
            acme = (EntityId) parties.temporaryIds().get(ReferenceStories.ACME);
            Receipt accounts = reference.replay(ReferenceStories.rd5Accounts(
                    parties.temporaryIds().get(ReferenceStories.BETA), parties.temporaryIds().get(ReferenceStories.ACME)));
            a1 = (EntityId) accounts.temporaryIds().get(ReferenceStories.A1);
        }

        private List<Problem> locatedDryRun(String text, IdLookup lookup) {
            TransactionPayload payload = read(text, schema, lookup);
            List<Problem> problems = reference.dryRunProblems(payload.transaction());
            return payload.locate(problems);
        }

        @Test
        void aProblemAboutTheEntityNamesItsIdAndIsLocatedAtTheWriteThatNamesItByKey() {
            accounts();
            String text = payload(
                    "{ \"patch\": { \"target\": " + key("Account", "number", "\"A-1\"") + ", \"from\": \"2026-03-15T00:00:00Z\", "
                            + "\"set\": { \"balance\": 1500 } } }",
                    "{ \"patch\": { \"target\": " + key("Account", "number", "\"A-1\"") + ", \"from\": \"2026-03-16T00:00:00Z\", "
                            + "\"set\": { \"currency\": \"eur\" } } }");

            List<Problem> located = locatedDryRun(text, reference.storeLookup());

            assertThat(located).singleElement().satisfies(problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.VALUE_PATTERN_MISMATCH);
                assertThat(problem.path()).as("the entity is named by its ID").isEqualTo(a1.value() + ".currency");
                assertThat(problem.location()).contains(locationOf(text, "\"currency\": \"eur\"",
                        "/writes/1/patch/set/currency"));
            });
        }

        @Test
        void aProblemAboutAnEntityIsLocatedAtTheLastWriteThatNamesItWhateverWayItIsNamed() {
            accounts();
            String byId = "{ \"patch\": { \"target\": \"" + a1.value() + "\", \"from\": \"2025-01-01T00:00:00Z\", "
                    + "\"set\": { \"balance\": 1 } } }";
            String byKey = "{ \"patch\": { \"target\": " + key("Account", "number", "\"A-1\"")
                    + ", \"from\": \"2026-03-15T00:00:00Z\", \"set\": { \"balance\": 2 } } }";

            List<Problem> keyLast = locatedDryRun(payload(byId, byKey), reference.storeLookup());
            List<Problem> idLast = locatedDryRun(payload(byKey, byId), reference.storeLookup());

            assertThat(keyLast).extracting(Problem::code).containsExactly(ProblemCode.ENTITY_NOT_EFFECTIVE);
            assertThat(keyLast.getFirst().path()).isEqualTo(a1.value());
            assertThat(keyLast.getFirst().location().orElseThrow().pointer()).as("the write that names it by key")
                    .isEqualTo("/writes/1");
            assertThat(idLast.getFirst().location().orElseThrow().pointer()).isEqualTo("/writes/1");
        }

        @Test
        void aProblemAboutAKeyThatNamesNothingIsLocatedAtItsWriteByItsPosition() {
            accounts();
            String text = payload(
                    patchBy(key("Account", "number", "\"A-1\"")),
                    patchBy(key("Account", "number", "\"A-9\"")));

            List<Problem> located = locatedDryRun(text, reference.storeLookup());

            assertThat(located).singleElement().satisfies(problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.UNIQUE_KEY_NOT_FOUND);
                assertThat(problem.path()).isEqualTo("@write.1");
                assertThat(problem.location()).contains(new DocumentLocation("/writes/1", 3, 1));
            });
        }

        @Test
        void aProblemAboutAnEntityThatTheLookupDidNotResolveFallsBackToTheRoot() {
            accounts();
            String text = payload(
                    "{ \"patch\": { \"target\": " + key("Account", "number", "\"A-1\"") + ", \"from\": \"2026-03-15T00:00:00Z\", "
                            + "\"set\": { \"currency\": \"eur\" } } }");

            List<Problem> located = locatedDryRun(text, IdLookup.none());

            assertThat(located).singleElement().satisfies(problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.VALUE_PATTERN_MISMATCH);
                assertThat(problem.path()).isEqualTo(a1.value() + ".currency");
                assertThat(problem.location().orElseThrow().pointer()).as("less precise, never wrong").isEmpty();
            });
        }

        @Test
        void theEndsOfARelationshipThatKeysNameAreLocatedAtTheRelate() {
            accounts();
            reference.replay(ReferenceStories.rd9AcmeLei(acme));
            String text = payload(
                    "{ \"relate\": { \"relationshipType\": \"HeldBy\", \"source\": "
                            + key("Account", "number", "\"A-1\"") + ", \"target\": "
                            + key("Organization", "lei", "\"5493001KJTIIGC8Y1R12\"")
                            + ", \"from\": \"2026-03-15T00:00:00Z\" } }");

            List<Problem> located = locatedDryRun(text, reference.storeLookup());

            assertThat(located).singleElement().satisfies(problem -> {
                assertThat(problem.code()).as("A-1 has a holder already").isEqualTo(ProblemCode.TOO_MANY_RELATIONSHIPS);
                assertThat(problem.path()).isEqualTo(a1.value() + "[HeldBy]");
                assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/writes/0");
            });
        }
    }

    // ------------------------------------------------------------------ a key on its own

    @Nested
    class AKeyOnItsOwn {

        private UniqueKeyReadResult read(String text) {
            return Payloads.readUniqueKey(text, schema, "@arguments.key");
        }

        private List<Problem> problemsOf(String text) {
            UniqueKeyReadResult result = read(text);
            assertThat(result).isInstanceOf(UniqueKeyReadResult.Invalid.class);
            return ((UniqueKeyReadResult.Invalid) result).problems();
        }

        @Test
        void isReadFromJsonOrYamlWithTheValueTypedByTheAttribute() {
            assertThat(read("{\"entityType\": \"Account\", \"key\": {\"number\": \"A-1\"}}"))
                    .isEqualTo(new UniqueKeyReadResult.Read(new UniqueKey("Account", "number", Value.of("A-1"))));
            assertThat(read("entityType: Organization\nkey:\n  lei: 5493001KJTIIGC8Y1R12\n"))
                    .isEqualTo(new UniqueKeyReadResult.Read(new UniqueKey("Organization", "lei",
                            Value.of("5493001KJTIIGC8Y1R12"))));
            assertThat(Payloads.readUniqueKey("{\"entityType\": \"Thing\", \"key\": {\"big\": 5}}",
                    schemaOf(THINGS), "@arguments.key")).isEqualTo(new UniqueKeyReadResult.Read(
                            new UniqueKey("Thing", "big", Value.of(5L))));
        }

        @Test
        void hasEveryProblemOfTheShapeAtThePathItWasGiven() {
            List<Problem> problems = problemsOf("{\"entityType\": \"Account\", \"key\": {\"number\": \"A-1\", "
                    + "\"currency\": \"EUR\"}, \"x\": 1}");

            assertThat(problems).extracting(Problem::code).containsExactlyInAnyOrder(ProblemCode.UNKNOWN_KEY,
                    ProblemCode.INVALID_VALUE);
            assertThat(problems).extracting(Problem::path).containsOnly("@arguments.key");
            assertThat(problems).allSatisfy(problem -> assertThat(problem.location()).isPresent());
        }

        @Test
        void isNotCheckedAgainstTheSchemaBecauseTheViewOrTheStoreDoesThat() {
            assertThat(read("{\"entityType\": \"Nothing\", \"key\": {\"x\": 1}}")).isEqualTo(
                    new UniqueKeyReadResult.Read(new UniqueKey("Nothing", "x", Value.of(1))));
        }

        @Test
        void isNotATextOrNotAMappingOrEmpty() {
            assertThat(problemsOf("{")).extracting(Problem::code).containsExactly(ProblemCode.DOCUMENT_SYNTAX);
            assertThat(problemsOf("")).extracting(Problem::code).containsExactly(ProblemCode.MISSING_KEY);
            assertThat(problemsOf("\"A-1\"")).extracting(Problem::code).containsExactly(ProblemCode.INVALID_VALUE);
            assertThat(problemsOf("[1]")).extracting(Problem::code).containsExactly(ProblemCode.INVALID_VALUE);
            assertThat(problemsOf("&a {\"entityType\": \"Account\", \"key\": {\"number\": \"A-1\"}}"))
                    .extracting(Problem::code).containsExactly(ProblemCode.UNSUPPORTED_YAML_FEATURE);
        }

        @Test
        void rejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> Payloads.readUniqueKey(null, schema, "p"));
            assertThatNullPointerException().isThrownBy(() -> Payloads.readUniqueKey("{}", null, "p"));
            assertThatNullPointerException().isThrownBy(() -> Payloads.readUniqueKey("{}", schema, null));
            assertThatNullPointerException().isThrownBy(() -> new UniqueKeyReadResult.Read(null));
        }
    }

    @Test
    void aPayloadWithAKeyReadsAsTheTransactionThatBuildsTheSameKey() {
        Transaction built = Transaction.by("agent:onboarding")
                .patch(new UniqueKey("Account", "number", Value.of("A-1")),
                        EffectivePeriod.from(Instant.parse("2026-03-15T00:00:00Z")),
                        Map.of("balance", Value.of(new BigDecimal("1500")))).build();

        Transaction readBack = read(payload(patchBy(key("Account", "number", "\"A-1\""))), schema).transaction();

        assertThat(readBack.writes()).isEqualTo(built.writes());
    }
}
