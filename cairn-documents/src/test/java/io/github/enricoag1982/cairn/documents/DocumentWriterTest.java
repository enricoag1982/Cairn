package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RelationshipKind;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** How documents are written: the order of keys, what is left out, quoting, literals, and the two formats (design D7). */
class DocumentWriterTest {

    private static final Origin ORIGIN = new Origin("test-model", "1.0.0");

    private static SchemaDefinition definition(AttributeDefinition... attributes) {
        return new SchemaDefinition(
                List.of(new EntityTypeDefinition("Thing", false, Optional.empty(), List.of(), List.of(attributes),
                        List.of(), false, Optional.empty(), List.of())),
                List.of(), List.of());
    }

    private static AttributeDefinition attribute(String name, ValueType type) {
        return AttributeDefinition.builder(name, type).build();
    }

    private static void assertRoundTrip(Origin origin, SchemaDefinition definition) {
        for (String text : List.of(SchemaDocuments.writeYaml(origin, definition), SchemaDocuments.writeJson(origin, definition))) {
            SchemaDocument document = valid(text);
            assertThat(document.origin()).as(text).isEqualTo(origin);
            assertThat(document.definition()).as(text).isEqualTo(definition);
        }
    }

    // ------------------------------------------------------------------ order

    @Test
    void theKeysOfTheDocumentAreInTheOrderOfTheFormat() {
        for (String text : List.of(
                SchemaDocuments.writeYaml(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()),
                SchemaDocuments.writeJson(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))) {
            assertThat(DocumentTrees.keysAt(DocumentTrees.load(text))).containsExactly(
                    "cairn", "origin", "traits", "entityTypes", "relationshipTypes");
            assertThat(DocumentTrees.keysAt(DocumentTrees.load(text), "entityTypes")).containsExactly(
                    "Party", "Person", "Organization", "Address", "Account", "CustomerRole");
            assertThat(DocumentTrees.keysAt(DocumentTrees.load(text), "relationshipTypes")).containsExactly(
                    "Owns", "Employs", "HeldBy", "HasAddress", "PlayedBy");
            assertThat(DocumentTrees.keysAt(DocumentTrees.load(text), "entityTypes", "Account", "attributes"))
                    .containsExactly("number", "currency", "balance");
        }
    }

    @Test
    void everyPropertyIsWrittenInTheOrderOfKeys() {
        AttributeDefinition attribute = new AttributeDefinition("code", ValueType.STRING, true, true,
                Optional.of(Range.between(Bound.inclusive(Value.of("a")), Bound.exclusive(Value.of("z")))),
                List.of("old"), true, Optional.of("A code."), List.of(Value.of("x")), List.of("x", "y"),
                Optional.of("[a-z]+"), OptionalInt.of(1), OptionalInt.of(9), Optional.of("has(self.id) ? self.id : 'x'"),
                Optional.of("pay"));
        SchemaDefinition definition = new SchemaDefinition(
                List.of(new EntityTypeDefinition("Full", true, Optional.of("Base"), List.of("Named"), List.of(attribute),
                        List.of("Old"), true, Optional.of("An entity type."), List.of(new RuleReference("a-rule", 2)),
                        List.of(new Check("a-check", "self.code != ''", Optional.of("The code is empty.")),
                                new Check("b-check", "true", Optional.empty())))),
                List.of(new TraitDefinition("Named", List.of(attribute), List.of("Old"), true, Optional.of("A trait."))),
                List.of(new RelationshipTypeDefinition("Links", "Full", "Full", Multiplicity.ZERO_OR_ONE,
                        Multiplicity.ONE_OR_MORE, "linkedBy", List.of("oldLinkedBy"), RelationshipKind.COMPOSITION, true, true,
                        Optional.of("Super"), List.of(attribute), List.of("Old"), true, Optional.of("A relationship type."),
                        List.of(new RuleReference("a-rule", 2)),
                        List.of(new Check("link-check", "has(self.code)", Optional.of("A link has a code."))))));

        for (String text : List.of(SchemaDocuments.writeYaml(ORIGIN, definition), SchemaDocuments.writeJson(ORIGIN, definition))) {
            Object tree = DocumentTrees.load(text);

            assertThat(DocumentTrees.keysAt(tree, "origin")).as(text).containsExactlyElementsOf(Keys.ORIGIN);
            assertThat(DocumentTrees.keysAt(tree, "traits", "Named")).as(text).containsExactlyElementsOf(Keys.TRAIT);
            assertThat(DocumentTrees.keysAt(tree, "entityTypes", "Full")).as(text)
                    .containsExactlyElementsOf(Keys.ENTITY_TYPE);
            assertThat(DocumentTrees.keysAt(tree, "relationshipTypes", "Links")).as(text)
                    .containsExactlyElementsOf(Keys.RELATIONSHIP_TYPE);
            assertThat(DocumentTrees.keysAt(tree, "entityTypes", "Full", "attributes", "code")).as(text)
                    .containsExactlyElementsOf(Keys.ATTRIBUTE);
            assertThat(DocumentTrees.keysAt(tree, "traits", "Named", "attributes", "code")).as(text)
                    .containsExactlyElementsOf(Keys.ATTRIBUTE);
            assertThat(DocumentTrees.keysAt(tree, "relationshipTypes", "Links", "attributes", "code")).as(text)
                    .containsExactlyElementsOf(Keys.ATTRIBUTE);
            assertThat(DocumentTrees.keysAt(tree, "entityTypes", "Full", "attributes", "code", "range")).as(text)
                    .containsExactly("atLeast", "lessThan");
            assertThat(DocumentTrees.at(tree, "entityTypes", "Full", "rules")).as(text)
                    .isEqualTo(List.of(Map.of("name", "a-rule", "version", 2)));
            assertThat(DocumentTrees.keysOf(((List<?>) DocumentTrees.at(tree, "entityTypes", "Full", "rules")).getFirst()))
                    .as(text).containsExactlyElementsOf(Keys.RULE);
            assertThat(DocumentTrees.at(tree, "relationshipTypes", "Links", "kind")).as(text).isEqualTo("composition");
            assertThat(DocumentTrees.keysAt(tree, "entityTypes", "Full", "checks")).as(text)
                    .containsExactly("a-check", "b-check");
            assertThat(DocumentTrees.keysAt(tree, "entityTypes", "Full", "checks", "a-check")).as(text)
                    .containsExactlyElementsOf(Keys.CHECK);
            assertThat(DocumentTrees.keysAt(tree, "entityTypes", "Full", "checks", "b-check")).as(text)
                    .containsExactly("expression");
            assertThat(DocumentTrees.keysAt(tree, "relationshipTypes", "Links", "checks", "link-check")).as(text)
                    .containsExactlyElementsOf(Keys.CHECK);
        }
        assertRoundTrip(ORIGIN, definition);
    }

    @Test
    void aRangeIsWrittenWithTheLowerBoundFirst() {
        AttributeDefinition ranged = AttributeDefinition.builder("n", ValueType.INT)
                .range(Range.between(Bound.exclusive(Value.of(1)), Bound.inclusive(Value.of(9)))).build();

        Object tree = DocumentTrees.load(SchemaDocuments.writeYaml(ORIGIN, definition(ranged)));

        assertThat(DocumentTrees.keysAt(tree, "entityTypes", "Thing", "attributes", "n", "range"))
                .containsExactly("greaterThan", "atMost");
    }

    // ------------------------------------------------------------------ defaults

    @Test
    void aTypeWithOnlyDefaultsIsAnEmptyMapping() {
        SchemaDefinition definition = new SchemaDefinition(
                List.of(new EntityTypeDefinition("Thing", false, Optional.empty(), List.of(), List.of(), List.of(), false,
                        Optional.empty(), List.of())),
                List.of(new TraitDefinition("Named", List.of(), List.of(), false, Optional.empty())),
                List.of());

        String yaml = SchemaDocuments.writeYaml(ORIGIN, definition);

        assertThat(yaml).contains("traits:\n  Named: {}\nentityTypes:\n  Thing: {}\n");
        assertThat(SchemaDocuments.writeJson(ORIGIN, definition)).contains("\"Named\": {}").contains("\"Thing\": {}");
        assertRoundTrip(ORIGIN, definition);
    }

    @Test
    void aRangeWithoutBoundsIsAnEmptyMapping() {
        AttributeDefinition ranged = AttributeDefinition.builder("n", ValueType.INT)
                .range(new Range(Optional.empty(), Optional.empty())).build();

        assertThat(SchemaDocuments.writeYaml(ORIGIN, definition(ranged))).contains("        range: {}\n");
        assertRoundTrip(ORIGIN, definition(ranged));
    }

    @Test
    void anEmptyDefinitionHasOnlyTheOrigin() {
        assertThat(SchemaDocuments.writeYaml(ORIGIN, SchemaDefinition.EMPTY)).isEqualTo(
                "cairn: 1\norigin:\n  name: test-model\n  version: \"1.0.0\"\n");
        assertThat(SchemaDocuments.writeJson(ORIGIN, SchemaDefinition.EMPTY)).isEqualTo("""
                {
                  "cairn": 1,
                  "origin": {
                    "name": "test-model",
                    "version": "1.0.0"
                  }
                }
                """);
        assertRoundTrip(ORIGIN, SchemaDefinition.EMPTY);
    }

    @Test
    void anAssociationIsLeftOutAndACompositionIsWritten() {
        String yaml = SchemaDocuments.writeYaml(ReferenceModel.origin("1.0.0"), ReferenceModel.definition());

        assertThat(yaml).contains("    kind: composition\n").doesNotContain("association").doesNotContain("symmetric");
    }

    @Test
    void outlivesTargetIsWrittenWhenTrueAndLeftOutWhenFalse() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .relationshipType("OpenedBy", r -> r.from("Account").to("Person").outlivesTarget().inverse("opened"))
                .relationshipType("HeldBy", r -> r.from("Account").to("Party").inverse("holds"))
                .build();

        for (String text : List.of(SchemaDocuments.writeYaml(ORIGIN, definition),
                SchemaDocuments.writeJson(ORIGIN, definition))) {
            Object tree = DocumentTrees.load(text);

            assertThat(DocumentTrees.keysAt(tree, "relationshipTypes", "OpenedBy")).as(text).contains("outlivesTarget");
            assertThat(DocumentTrees.at(tree, "relationshipTypes", "OpenedBy", "outlivesTarget")).isEqualTo(true);
            assertThat(DocumentTrees.keysAt(tree, "relationshipTypes", "HeldBy")).as(text)
                    .doesNotContain("outlivesTarget");
        }
        assertThat(SchemaDocuments.writeYaml(ORIGIN, definition)).contains("    outlivesTarget: true\n");
        assertRoundTrip(ORIGIN, definition);
    }

    // ------------------------------------------------------------------ the two formats

    @Test
    void jsonHasTwoSpaceIndentationAndOneItemPerLine() {
        SchemaDefinition definition = new SchemaDefinition(
                List.of(new EntityTypeDefinition("Thing", true, Optional.empty(), List.of("Named", "Dated"),
                        List.of(), List.of(), false, Optional.empty(), List.of(new RuleReference("a-rule", 1)))),
                List.of(), List.of());

        assertThat(SchemaDocuments.writeJson(ORIGIN, definition)).isEqualTo("""
                {
                  "cairn": 1,
                  "origin": {
                    "name": "test-model",
                    "version": "1.0.0"
                  },
                  "entityTypes": {
                    "Thing": {
                      "abstract": true,
                      "traits": [
                        "Named",
                        "Dated"
                      ],
                      "rules": [
                        {
                          "name": "a-rule",
                          "version": 1
                        }
                      ]
                    }
                  }
                }
                """);
    }

    @Test
    void yamlIsInBlockStyle() {
        SchemaDefinition definition = new SchemaDefinition(
                List.of(new EntityTypeDefinition("Thing", true, Optional.empty(), List.of("Named", "Dated"),
                        List.of(), List.of(), false, Optional.empty(), List.of(new RuleReference("a-rule", 1)))),
                List.of(), List.of());

        assertThat(SchemaDocuments.writeYaml(ORIGIN, definition)).isEqualTo("""
                cairn: 1
                origin:
                  name: test-model
                  version: "1.0.0"
                entityTypes:
                  Thing:
                    abstract: true
                    traits:
                      - Named
                      - Dated
                    rules:
                      - name: a-rule
                        version: 1
                """);
    }

    @Test
    void writingIsDeterministicAndReadingWhatWasWrittenWritesTheSameText() {
        Origin origin = ReferenceModel.origin("1.0.0");
        SchemaDefinition definition = ReferenceModel.definition();

        String yaml = SchemaDocuments.writeYaml(origin, definition);
        String json = SchemaDocuments.writeJson(origin, definition);

        assertThat(SchemaDocuments.writeYaml(origin, definition)).isEqualTo(yaml);
        assertThat(SchemaDocuments.writeJson(origin, definition)).isEqualTo(json);
        SchemaDocument fromYaml = valid(yaml);
        assertThat(SchemaDocuments.writeYaml(fromYaml.origin(), fromYaml.definition())).isEqualTo(yaml);
        assertThat(SchemaDocuments.writeJson(fromYaml.origin(), fromYaml.definition())).isEqualTo(json);
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatNullPointerException().isThrownBy(() -> SchemaDocuments.writeYaml(null, SchemaDefinition.EMPTY))
                .withMessage("origin");
        assertThatNullPointerException().isThrownBy(() -> SchemaDocuments.writeJson(ORIGIN, null))
                .withMessage("definition");
    }

    @Test
    void twoTypesWithTheSameNameAreWrittenAsTheyAreAndReadingReportsTheDuplicate() {
        EntityTypeDefinition thing = new EntityTypeDefinition("Thing", false, Optional.empty(), List.of(), List.of(),
                List.of(), false, Optional.empty(), List.of());
        SchemaDefinition definition = new SchemaDefinition(List.of(thing, thing), List.of(), List.of());

        for (String text : List.of(SchemaDocuments.writeYaml(ORIGIN, definition), SchemaDocuments.writeJson(ORIGIN, definition))) {
            List<Problem> problems = Documents.problems(text);

            assertThat(problems).singleElement().satisfies(problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.DUPLICATE_KEY);
                assertThat(problem.path()).isEqualTo("Thing");
            });
        }
    }

    // ------------------------------------------------------------------ strings

    @Test
    void theVersionOfTheOriginIsAlwaysAString() {
        for (String version : List.of("1.0.0", "2", "1.10", "true", "null", "", " 1 ")) {
            Origin origin = new Origin("test-model", version);

            assertThat(SchemaDocuments.writeYaml(origin, SchemaDefinition.EMPTY))
                    .contains("  version: " + Quoting.doubleQuoted(version) + "\n");
            assertRoundTrip(origin, SchemaDefinition.EMPTY);
        }
    }

    @Test
    void multiplicitiesAreAlwaysInQuotes() {
        String yaml = SchemaDocuments.writeYaml(ReferenceModel.origin("1.0.0"), ReferenceModel.definition());

        assertThat(yaml).contains("targetsPerSource: \"0..*\"\n").contains("targetsPerSource: \"1..1\"\n")
                .contains("sourcesPerTarget: \"1..1\"\n").doesNotContain(": 0..");
    }

    static Stream<Arguments> writtenStrings() {
        return Stream.of(
                Arguments.of("Names the party", "Names the party"),
                Arguments.of("Legal Entity Identifier (ISO 17442), as issued", "Legal Entity Identifier (ISO 17442), as issued"),
                Arguments.of("total-ownership-at-most-100", "total-ownership-at-most-100"),
                Arguments.of("1.0.0", "1.0.0"),
                Arguments.of("2026-01-05", "2026-01-05"),
                Arguments.of("2026-01-05T09:00:00Z", "2026-01-05T09:00:00Z"),
                Arguments.of("yesterday", "yesterday"),
                Arguments.of("no one", "no one"),
                Arguments.of("Offset", "Offset"),
                Arguments.of("yEs", "yEs"),
                Arguments.of("1_000", "1_000"),
                Arguments.of("é and 日本語", "é and 日本語"),
                Arguments.of("", "\"\""),
                Arguments.of("null", "\"null\""),
                Arguments.of("Null", "\"Null\""),
                Arguments.of("~", "\"~\""),
                Arguments.of("true", "\"true\""),
                Arguments.of("FALSE", "\"FALSE\""),
                Arguments.of("y", "\"y\""),
                Arguments.of("Y", "\"Y\""),
                Arguments.of("yes", "\"yes\""),
                Arguments.of("Yes", "\"Yes\""),
                Arguments.of("YES", "\"YES\""),
                Arguments.of("n", "\"n\""),
                Arguments.of("N", "\"N\""),
                Arguments.of("no", "\"no\""),
                Arguments.of("No", "\"No\""),
                Arguments.of("NO", "\"NO\""),
                Arguments.of("on", "\"on\""),
                Arguments.of("On", "\"On\""),
                Arguments.of("ON", "\"ON\""),
                Arguments.of("off", "\"off\""),
                Arguments.of("Off", "\"Off\""),
                Arguments.of("OFF", "\"OFF\""),
                Arguments.of("123", "\"123\""),
                Arguments.of("-5", "\"-5\""),
                Arguments.of("1.5", "\"1.5\""),
                Arguments.of("1e3", "\"1e3\""),
                Arguments.of("0x1F", "\"0x1F\""),
                Arguments.of("0o17", "\"0o17\""),
                Arguments.of(".inf", "\".inf\""),
                Arguments.of(".nan", "\".nan\""),
                Arguments.of(" leading", "\" leading\""),
                Arguments.of("trailing ", "\"trailing \""),
                Arguments.of("a: b", "\"a: b\""),
                Arguments.of("a:", "\"a:\""),
                Arguments.of("a #b", "\"a #b\""),
                Arguments.of("# comment", "\"# comment\""),
                Arguments.of("- item", "\"- item\""),
                Arguments.of("[A-Z]{3}", "\"[A-Z]{3}\""),
                Arguments.of("{a: b}", "\"{a: b}\""),
                Arguments.of("*alias", "\"*alias\""),
                Arguments.of("&anchor", "\"&anchor\""),
                Arguments.of("!tag", "\"!tag\""),
                Arguments.of("|", "\"|\""),
                Arguments.of("<<", "\"<<\""),
                Arguments.of("\\d{3}", "\"\\\\d{3}\""),
                Arguments.of("say \"hi\"", "\"say \\\"hi\\\"\""),
                Arguments.of("two\nlines", "\"two\\nlines\""),
                Arguments.of("a\tb", "\"a\\tb\""),
                Arguments.of("\u0000\u007f\u0085\u2028\u2029\ufeff", "\"\\u0000\\u007f\\u0085\\u2028\\u2029\\ufeff\""),
                Arguments.of("smile \uD83D\uDE00", "\"smile \uD83D\uDE00\""));
    }

    @ParameterizedTest
    @MethodSource("writtenStrings")
    void aStringIsQuotedOnlyWhereYamlWouldReadItAsAnotherTypeOrAsSyntax(String text, String written) {
        AttributeDefinition described = AttributeDefinition.builder("note", ValueType.STRING).description(text).build();
        SchemaDefinition definition = definition(described);

        assertThat(SchemaDocuments.writeYaml(ORIGIN, definition)).contains("        description: " + written + "\n");
        assertRoundTrip(ORIGIN, definition);
    }

    @ParameterizedTest
    @MethodSource("writtenStrings")
    void aNameIsQuotedLikeAString(String text, String written) {
        SchemaDefinition definition = definition(attribute(text, ValueType.STRING));

        assertThat(SchemaDocuments.writeYaml(ORIGIN, definition)).contains("      " + written + ":\n");
        assertRoundTrip(ORIGIN, definition);
    }

    @Test
    void wordsThatYaml11ReadsAsBooleansAreQuotedEverywhereAStringIsWritten() {
        for (String word : List.of("y", "Y", "yes", "Yes", "YES", "n", "N", "no", "No", "NO", "true", "True", "TRUE",
                "false", "False", "FALSE", "on", "On", "ON", "off", "Off", "OFF")) {
            AttributeDefinition note = AttributeDefinition.builder("note", ValueType.STRING).description(word)
                    .examples(Value.of(word)).allowedValues(word).aliases(word).build();
            SchemaDefinition definition = definition(note);

            assertThat(SchemaDocuments.writeYaml(ORIGIN, definition))
                    .as(word).contains("description: \"" + word + "\"\n").contains("- \"" + word + "\"\n")
                    .doesNotContain("- " + word + "\n").doesNotContain("description: " + word + "\n");
            assertRoundTrip(ORIGIN, definition);
        }
    }

    // ------------------------------------------------------------------ literals

    @Test
    void literalsAreWrittenByTheirValueType() {
        AttributeDefinition numbers = AttributeDefinition.builder("n", ValueType.DECIMAL).examples(
                Value.of(new BigDecimal("1.50")), Value.of(new BigDecimal("0.00")), Value.of(new BigDecimal("-0.5")),
                Value.of(new BigDecimal("100")), Value.of(new BigDecimal("12345678901234567890.123456789")),
                Value.of(new BigDecimal("1E+3")), Value.of(new BigDecimal("0E+2")), Value.of(new BigDecimal("1E-9")),
                Value.of(new BigDecimal("0.0000001"))).build();
        AttributeDefinition integers = AttributeDefinition.builder("i", ValueType.INT)
                .examples(Value.of(0), Value.of(-7), Value.of(Integer.MAX_VALUE)).build();
        AttributeDefinition longs = AttributeDefinition.builder("l", ValueType.LONG)
                .examples(Value.of(3_000_000_000L), Value.of(Long.MIN_VALUE)).build();
        AttributeDefinition booleans = AttributeDefinition.builder("b", ValueType.BOOLEAN)
                .examples(Value.of(true), Value.of(false)).build();
        AttributeDefinition dates = AttributeDefinition.builder("d", ValueType.DATE)
                .examples(Value.of(LocalDate.of(2026, 1, 5)), Value.of(LocalDate.of(-5, 1, 1)),
                        Value.of(LocalDate.of(10000, 1, 1))).build();
        AttributeDefinition instants = AttributeDefinition.builder("t", ValueType.INSTANT)
                .examples(Value.of(Instant.parse("2026-01-05T09:00:00Z")),
                        Value.of(Instant.parse("2026-01-05T09:00:00.123456Z"))).build();
        SchemaDefinition definition = definition(numbers, integers, longs, booleans, dates, instants);

        String yaml = SchemaDocuments.writeYaml(ORIGIN, definition);

        assertThat(yaml)
                .contains("- 1.50\n").contains("- 0.00\n").contains("- -0.5\n").contains("- 100\n")
                .contains("- 12345678901234567890.123456789\n").contains("- 1E+3\n").contains("- 0E+2\n")
                .contains("- 0.000000001\n").contains("- 0.0000001\n")
                .contains("- -7\n").contains("- 2147483647\n").contains("- 3000000000\n")
                .contains("- -9223372036854775808\n").contains("- true\n").contains("- false\n")
                .contains("- 2026-01-05\n").contains("- \"-0005-01-01\"\n").contains("- \"+10000-01-01\"\n")
                .contains("- 2026-01-05T09:00:00Z\n").contains("- 2026-01-05T09:00:00.123456Z\n");
        assertThat(SchemaDocuments.writeJson(ORIGIN, definition))
                .contains("1.50,").contains("1E+3,").contains("\"2026-01-05\"").contains("\"2026-01-05T09:00:00Z\"");
        assertRoundTrip(ORIGIN, definition);
    }

    @Test
    void rangeBoundsAreWrittenByTheValueTypeOfTheirAttribute() {
        AttributeDefinition share = AttributeDefinition.builder("share", ValueType.DECIMAL).range(Range.between(
                Bound.exclusive(Value.of(new BigDecimal("0"))), Bound.inclusive(Value.of(new BigDecimal("100.0")))))
                .build();
        AttributeDefinition born = AttributeDefinition.builder("born", ValueType.DATE).range(Range.atLeast(
                Bound.inclusive(Value.of(LocalDate.of(1900, 1, 1))))).build();
        AttributeDefinition seen = AttributeDefinition.builder("seen", ValueType.INSTANT).range(Range.atMost(
                Bound.exclusive(Value.of(Instant.parse("2030-01-01T00:00:00Z"))))).build();
        SchemaDefinition definition = definition(share, born, seen);

        String yaml = SchemaDocuments.writeYaml(ORIGIN, definition);

        assertThat(yaml).contains("greaterThan: 0\n").contains("atMost: 100.0\n").contains("atLeast: 1900-01-01\n")
                .contains("lessThan: 2030-01-01T00:00:00Z\n");
        assertRoundTrip(ORIGIN, definition);
    }

    @Test
    void aStringValueThatLooksLikeAnotherTypeStaysAString() {
        AttributeDefinition strings = AttributeDefinition.builder("s", ValueType.STRING)
                .examples(Value.of("true"), Value.of("5"), Value.of("1.5"), Value.of("null"), Value.of(" x"))
                .build();

        assertThat(SchemaDocuments.writeYaml(ORIGIN, definition(strings)))
                .contains("- \"true\"\n").contains("- \"5\"\n").contains("- \"1.5\"\n").contains("- \"null\"\n")
                .contains("- \" x\"\n");
        assertRoundTrip(ORIGIN, definition(strings));
    }
}
