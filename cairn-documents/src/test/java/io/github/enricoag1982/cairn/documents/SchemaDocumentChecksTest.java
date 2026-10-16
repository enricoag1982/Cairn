package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.assertProblem;
import static io.github.enricoag1982.cairn.documents.Documents.document;
import static io.github.enricoag1982.cairn.documents.Documents.problem;
import static io.github.enricoag1982.cairn.documents.Documents.valid;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.SchemaRegistries;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The checks of type definitions in schema documents (capability {@code schema-documents}, design D6 of
 * {@code add-cel-expressions}): reading, the problems of reading, writing, the published JSON Schema, and locating the
 * problems of registering them, in the expression where the text lets the position be found. The registry has the
 * module {@code cairn-cel} on the class path of these tests.
 */
class SchemaDocumentChecksTest {

    private static final String ACCOUNT = "entityTypes:\n  Account:\n    attributes:\n      balance: { type: decimal }\n";

    private final SchemaRegistry registry = SchemaRegistries.inMemory(InstantSource.system());

    private List<Problem> located(String text) {
        SchemaDocument document = valid(text);
        return document.locate(registry.validate(document.origin(), document.definition()));
    }

    private Problem locatedProblem(String text) {
        List<Problem> problems = located(text);
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    private static void assertAt(Problem problem, String pointer, int line, int column) {
        assertThat(problem.location()).as("location of %s", problem).contains(new DocumentLocation(pointer, line, column));
    }

    // ------------------------------------------------------------------ reading

    @Test
    void theChecksOfEntityTypesAndRelationshipTypesAreReadInTheOrderOfTheText() {
        SchemaDocument document = valid(document(
                "entityTypes:",
                "  Account:",
                "    checks:",
                "      z-check: { expression: \"true\" }",
                "      a-check:",
                "        expression: self.balance >= 0",
                "        message: No overdrafts.",
                "relationshipTypes:",
                "  Owns:",
                "    source: Account",
                "    target: Account",
                "    targetsPerSource: \"0..*\"",
                "    sourcesPerTarget: \"0..*\"",
                "    inverseName: ownedBy",
                "    checks:",
                "      share-set: { expression: has(self.share) }"));

        assertThat(document.definition().entityTypes().getFirst().checks()).containsExactly(
                new Check("z-check", "true", Optional.empty()),
                new Check("a-check", "self.balance >= 0", Optional.of("No overdrafts.")));
        assertThat(document.definition().relationshipTypes().getFirst().checks())
                .containsExactly(new Check("share-set", "has(self.share)", Optional.empty()));
    }

    @Test
    void aTypeWithoutChecksOrWithAnEmptyMappingHasNone() {
        assertThat(valid(document("entityTypes:", "  A: {}")).definition().entityTypes().getFirst().checks()).isEmpty();
        assertThat(valid(document("entityTypes:", "  A: { checks: {} }")).definition().entityTypes().getFirst().checks())
                .isEmpty();
    }

    @Test
    void checksWrittenAsJsonAreReadTheSame() {
        SchemaDocument document = valid("{\"cairn\": 1, \"origin\": {\"name\": \"m\", \"version\": \"1\"}, "
                + "\"entityTypes\": {\"Account\": {\"checks\": {\"a-check\": {\"expression\": \"self.balance >= 0.0\", "
                + "\"message\": \"No overdrafts.\"}}}}}");

        assertThat(document.definition().entityTypes().getFirst().checks())
                .containsExactly(new Check("a-check", "self.balance >= 0.0", Optional.of("No overdrafts.")));
    }

    // ------------------------------------------------------------------ the problems of reading

    @Test
    void theChecksOfATypeMustBeAMappingFromNamesToChecks() {
        Problem problem = problem(document("entityTypes:", "  Account:", "    checks: [a-check]"));

        assertProblem(problem, ProblemCode.INVALID_VALUE, "Account", "/entityTypes/Account/checks", 5, 13);
        assertThat(problem.expected()).contains("a mapping from check names to checks");
    }

    @Test
    void aCheckMustHaveAnExpression() {
        Problem problem = problem(document("entityTypes:", "  Account:", "    checks:",
                "      a-check: { message: No overdrafts. }"));

        assertProblem(problem, ProblemCode.MISSING_KEY, "Account.a-check", "/entityTypes/Account/checks/a-check", 6, 16);
        assertThat(problem.message()).contains("expression");
    }

    @Test
    void aMisspelledKeyOfACheckHasAHint() {
        List<Problem> problems = Documents.problems(document("entityTypes:", "  Account:", "    checks:",
                "      a-check: { expresion: \"true\" }"));

        assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.MISSING_KEY,
                ProblemCode.UNKNOWN_KEY);
        Problem unknown = problems.get(1);
        assertThat(unknown.path()).isEqualTo("Account.a-check");
        assertThat(unknown.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("expression"));
    }

    @Test
    void theExpressionAndTheMessageMustBeStrings() {
        Problem number = problem(document("entityTypes:", "  Account:", "    checks:",
                "      a-check: { expression: 5 }"));
        Problem message = problem(document("entityTypes:", "  Account:", "    checks:",
                "      a-check: { expression: \"true\", message: [x] }"));

        assertProblem(number, ProblemCode.INVALID_VALUE, "Account.a-check",
                "/entityTypes/Account/checks/a-check/expression", 6, 30);
        assertThat(number.expected()).contains("a string");
        assertThat(message.code()).isEqualTo(ProblemCode.INVALID_VALUE);
        assertThat(message.path()).isEqualTo("Account.a-check");
    }

    @Test
    void aCheckNamedTwiceIsADuplicateKeyAtTheSecondOne() {
        Problem problem = problem(document("entityTypes:", "  Account:", "    checks:",
                "      a-check: { expression: \"true\" }", "      a-check: { expression: \"false\" }"));

        assertProblem(problem, ProblemCode.DUPLICATE_KEY, "Account.a-check", "/entityTypes/Account/checks/a-check", 7, 7);
    }

    @Test
    void anAnchorInACheckIsReportedAtTheCheck() {
        Problem problem = problem(document("entityTypes:", "  Account:", "    checks:",
                "      a-check: { expression: &e \"true\" }"));

        assertThat(problem.code()).isEqualTo(ProblemCode.UNSUPPORTED_YAML_FEATURE);
        assertThat(problem.path()).isEqualTo("Account.a-check");
    }

    // ------------------------------------------------------------------ writing

    @Test
    void checksAreWrittenInTheOrderOfTheirNamesAfterTheRules() {
        SchemaDocument document = valid(document(
                "entityTypes:",
                "  Account:",
                "    rules: [{ name: a-rule, version: 1 }]",
                "    checks:",
                "      b-check: { expression: \"true\", message: Both. }",
                "      a-check: { expression: self.balance >= 0.0 }"));

        String yaml = SchemaDocuments.writeYaml(document.origin(), document.definition());
        String json = SchemaDocuments.writeJson(document.origin(), document.definition());

        assertThat(yaml).contains("""
                    rules:
                      - name: a-rule
                        version: 1
                    checks:
                      a-check:
                        expression: self.balance >= 0.0
                      b-check:
                        expression: "true"
                        message: Both.
                """);
        assertThat(json.indexOf("\"a-check\"")).isPositive().isLessThan(json.indexOf("\"b-check\""));
        assertThat(valid(yaml).definition().entityTypes().getFirst().checks())
                .containsExactly(new Check("a-check", "self.balance >= 0.0"),
                        new Check("b-check", "true", Optional.of("Both.")));
        assertThat(valid(json).definition()).isEqualTo(valid(yaml).definition());
    }

    @Test
    void anAwkwardExpressionAndMessageSurviveWritingAndReading() {
        String expression = "self.name == \"it's\" && size(self.name) > 0 # not a comment\n  && true";
        String message = "Don't: \"quote\" me, ünïcode 😀, back\\slash\ttab";
        SchemaDocument document = valid(document("entityTypes:", "  A: {}"));
        var definition = Definitions.withCheck(document.definition(), "A", "a-check",
                expression, message);

        for (String text : List.of(SchemaDocuments.writeYaml(document.origin(), definition),
                SchemaDocuments.writeJson(document.origin(), definition))) {
            assertThat(valid(text).definition().entityTypes().getFirst().checks())
                    .as(text).containsExactly(new Check("a-check", expression, Optional.of(message)));
        }
    }

    @Test
    void aTypeWithoutChecksHasNoChecksKey() {
        SchemaDocument document = valid(document("entityTypes:", "  A: {}"));

        assertThat(SchemaDocuments.writeYaml(document.origin(), document.definition())).doesNotContain("checks");
        assertThat(SchemaDocuments.writeJson(document.origin(), document.definition())).doesNotContain("checks");
    }

    // ------------------------------------------------------------------ the published JSON Schema

    @Test
    void aDocumentWithChecksIsValidAgainstThePublishedJsonSchema() {
        String text = document(
                "entityTypes:",
                "  Account:",
                "    checks:",
                "      balance-not-negative:",
                "        expression: self.balance >= 0.0",
                "        message: No overdrafts.",
                "      has-currency: { expression: has(self.currency) }");

        assertThat(PublishedSchemaCheck.errors(text)).isEmpty();
    }

    @Test
    void theJsonSchemaRejectsABadCheck() {
        assertThat(PublishedSchemaCheck.errors(document("entityTypes:", "  A:", "    checks:",
                "      a-check: { message: x }"))).as("no expression").isNotEmpty();
        assertThat(PublishedSchemaCheck.errors(document("entityTypes:", "  A:", "    checks:",
                "      a-check: { expression: \"true\", other: 1 }"))).as("another key").isNotEmpty();
        assertThat(PublishedSchemaCheck.errors(document("entityTypes:", "  A:", "    checks:",
                "      A_Check: { expression: \"true\" }"))).as("a name that is no check name").isNotEmpty();
        assertThat(PublishedSchemaCheck.errors(document("entityTypes:", "  A:", "    checks:",
                "      a-check: { expression: \"\" }"))).as("an empty expression").isNotEmpty();
        assertThat(PublishedSchemaCheck.errors(document("entityTypes:", "  A:", "    checks:",
                "      a-check: { expression: \"" + "x".repeat(4_001) + "\" }"))).as("too long").isNotEmpty();
        assertThat(PublishedSchemaCheck.errors(document("entityTypes:", "  A:", "    checks:",
                "      a-check: { expression: \"" + "x".repeat(4_000) + "\" }"))).as("long enough").isEmpty();
        assertThat(PublishedSchemaCheck.errors(document("traits:", "  T:", "    checks:",
                "      a-check: { expression: \"true\" }"))).as("traits have none").isNotEmpty();
    }

    // ------------------------------------------------------------------ locating the problems of checks

    @Test
    void aProblemWithTheExpressionIsLocatedInThePlainExpressionAtTheErrorColumn() {
        Problem problem = locatedProblem(document(ACCOUNT.replace("    attributes:",
                "    checks:\n      balance-not-negative:\n        expression: self.balance >= 0.0 && self.amount > 1\n"
                        + "    attributes:")));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CHECK);
        assertAt(problem, "/entityTypes/Account/checks/balance-not-negative/expression", 7, 48);
    }

    @Test
    void aProblemWithAQuotedExpressionIsLocatedInsideTheQuotes() {
        Problem problem = locatedProblem(document(ACCOUNT.replace("    attributes:",
                "    checks:\n      a-check: { expression: \"self.amount >= 0.0\" }\n    attributes:")));

        assertAt(problem, "/entityTypes/Account/checks/a-check/expression", 6, 35);
    }

    @Test
    void aProblemWithASingleQuotedExpressionIsLocatedInsideTheQuotes() {
        Problem problem = locatedProblem(document(ACCOUNT.replace("    attributes:",
                "    checks:\n      a-check: { expression: 'self.amount >= 0.0' }\n    attributes:")));

        assertAt(problem, "/entityTypes/Account/checks/a-check/expression", 6, 35);
    }

    @Test
    void aProblemWithAJsonExpressionIsLocatedInTheString() {
        String json = "{\"cairn\": 1, \"origin\": {\"name\": \"m\", \"version\": \"1\"}, \"entityTypes\": {\"Account\": "
                + "{\"attributes\": {\"balance\": {\"type\": \"decimal\"}}, \"checks\": {\"a-check\": "
                + "{\"expression\": \"self.amount >= 0.0\"}}}}}";

        Problem problem = locatedProblem(json);

        int stringStart = json.indexOf("\"self.amount") + 1;
        assertAt(problem, "/entityTypes/Account/checks/a-check/expression", 1, stringStart + 4 + 1);
    }

    @Test
    void aProblemInALiteralBlockExpressionIsLocatedOnItsLine() {
        Problem problem = locatedProblem(document(ACCOUNT.replace("    attributes:",
                "    checks:\n      a-check:\n        expression: |\n          self.balance >= 0.0 &&\n"
                        + "            self.amount > 1\n    attributes:")));

        assertAt(problem, "/entityTypes/Account/checks/a-check/expression", 9, 17);
    }

    @Test
    void aProblemInAnExpressionThatTheTextDoesNotMapIsLocatedAtTheKeyExpression() {
        Problem folded = locatedProblem(document(ACCOUNT.replace("    attributes:",
                "    checks:\n      a-check:\n        expression: >\n          self.balance >= 0.0 &&\n"
                        + "          self.amount > 1\n    attributes:")));
        Problem escaped = locatedProblem(document(ACCOUNT.replace("    attributes:",
                "    checks:\n      a-check:\n        expression: \"self.amount >= \\u0030.0\"\n    attributes:")));
        Problem continued = locatedProblem(document(ACCOUNT.replace("    attributes:",
                "    checks:\n      a-check:\n        expression: self.balance >= 0.0\n          && self.amount > 1\n"
                        + "    attributes:")));

        assertAt(folded, "/entityTypes/Account/checks/a-check/expression", 7, 9);
        assertAt(escaped, "/entityTypes/Account/checks/a-check/expression", 7, 9);
        assertAt(continued, "/entityTypes/Account/checks/a-check/expression", 7, 9);
    }

    @Test
    void aBlankExpressionIsLocatedAtTheKeyExpression() {
        Problem problem = locatedProblem(document(ACCOUNT.replace("    attributes:",
                "    checks:\n      a-check: { expression: \" \" }\n    attributes:")));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CHECK);
        assertAt(problem, "/entityTypes/Account/checks/a-check/expression", 6, 18);
    }

    @Test
    void theOtherProblemsOfACheckAreLocatedAtTheCheck() {
        String text = document("entityTypes:", "  Party:", "    attributes:", "      name: { type: string }",
                "    checks:", "      name-set: { expression: \"true\" }", "  Organization:", "    supertype: Party",
                "    checks:", "      name-set: { expression: \"true\" }", "  Person:", "    supertype: Party",
                "    checks:", "      Name_Set: { expression: \"true\" }");

        List<Problem> problems = located(text);

        assertThat(problems).hasSize(2);
        assertThat(problems.get(0).code()).isEqualTo(ProblemCode.DUPLICATE_CHECK);
        assertAt(problems.get(0), "/entityTypes/Organization/checks/name-set", 12, 7);
        assertThat(problems.get(1).code()).isEqualTo(ProblemCode.INVALID_CHECK_NAME);
        assertAt(problems.get(1), "/entityTypes/Person/checks/Name_Set", 16, 7);
    }

    @Test
    void checksThatCannotBeCompiledAreLocatedAtTheChecks() {
        SchemaRegistry without = SchemaRegistries.inMemory(InstantSource.system(),
                List.of(), Optional.empty());
        SchemaDocument document = valid(document(ACCOUNT.replace("    attributes:",
                "    checks:\n      a-check: { expression: \"true\" }\n    attributes:")));

        List<Problem> problems = document.locate(without.validate(document.origin(), document.definition()));

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.EXPRESSIONS_UNAVAILABLE);
            assertAt(problem, "/entityTypes/Account/checks/a-check", 6, 7);
        });
    }

    @Test
    void aCheckOfARelationshipTypeIsLocatedToo() {
        Problem problem = locatedProblem(document(
                "entityTypes:", "  Party:", "    attributes:", "      name: { type: string }",
                "relationshipTypes:", "  Knows:", "    source: Party", "    target: Party",
                "    targetsPerSource: \"0..*\"", "    sourcesPerTarget: \"0..*\"", "    inverseName: knownBy",
                "    checks:", "      since-set: { expression: has(self.since) }"));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CHECK);
        assertAt(problem, "/relationshipTypes/Knows/checks/since-set/expression", 15, 35);
    }
}
