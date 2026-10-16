package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.assertProblem;
import static io.github.enricoag1982.cairn.documents.Documents.document;
import static io.github.enricoag1982.cairn.documents.Documents.problem;
import static io.github.enricoag1982.cairn.documents.Documents.valid;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.SchemaRegistries;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The derived attributes of type definitions in schema documents (capability {@code schema-documents}, design D6 of
 * {@code add-derived-attributes}): reading, the problems of reading, writing, the published JSON Schema, and locating the
 * problems of registering them, at the key {@code derived} and in the expression where the text lets the position be
 * found. The registry has the module {@code cairn-cel} on the class path of these tests.
 */
class SchemaDocumentDerivedTest {

    private static final String PERSON = """
            entityTypes:
              Person:
                attributes:
                  givenName: { type: string, required: true }
                  familyName: { type: string, required: true }
            """;

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

    private static String person(String... fullNameLines) {
        return document(PERSON.stripTrailing() + "\n" + String.join("\n", fullNameLines));
    }

    // ------------------------------------------------------------------ reading

    @Test
    void aDerivedAttributeKeepsItsOtherPropertiesAndItsPlaceAmongTheAttributes() {
        SchemaDocument document = valid(person(
                "      fullName:",
                "        type: string",
                "        required: true",
                "        unique: true",
                "        derived: self.givenName + ' ' + self.familyName",
                "        maxLength: 100",
                "        description: The full name.",
                "      nickname: { type: string }"));

        List<AttributeDefinition> attributes = document.definition().entityTypes().getFirst().attributes();
        assertThat(attributes).extracting(AttributeDefinition::name)
                .containsExactly("givenName", "familyName", "fullName", "nickname");
        AttributeDefinition fullName = attributes.get(2);
        assertThat(fullName.derived()).hasValue("self.givenName + ' ' + self.familyName");
        assertThat(fullName.required()).isTrue();
        assertThat(fullName.unique()).isTrue();
        assertThat(fullName.maxLength()).hasValue(100);
        assertThat(fullName.description()).hasValue("The full name.");
        assertThat(attributes.get(0).derived()).isEmpty();
        assertThat(attributes.get(3).derived()).isEmpty();
    }

    @Test
    void attributesOfTraitsAndRelationshipTypesCanBeDerivedToo() {
        SchemaDocument document = valid(document(
                "traits:",
                "  Labelled:",
                "    attributes:",
                "      label: { type: string, derived: \"'x'\" }",
                "entityTypes:",
                "  Account: { traits: [Labelled] }",
                "relationshipTypes:",
                "  Owns:",
                "    source: Account",
                "    target: Account",
                "    targetsPerSource: \"0..*\"",
                "    sourcesPerTarget: \"0..*\"",
                "    inverseName: ownedBy",
                "    attributes:",
                "      heading: { type: string, derived: \"'y'\" }"));

        assertThat(document.definition().traits().getFirst().attributes().getFirst().derived()).hasValue("'x'");
        assertThat(document.definition().relationshipTypes().getFirst().attributes().getFirst().derived())
                .hasValue("'y'");
    }

    @Test
    void aDerivedAttributeWrittenAsJsonIsReadTheSame() {
        SchemaDocument document = valid("{\"cairn\": 1, \"origin\": {\"name\": \"m\", \"version\": \"1\"}, "
                + "\"entityTypes\": {\"Account\": {\"attributes\": {\"label\": {\"type\": \"string\", "
                + "\"derived\": \"self.number + ' ' + self.currency\"}}}}}");

        assertThat(document.definition().entityTypes().getFirst().attributes().getFirst().derived())
                .hasValue("self.number + ' ' + self.currency");
    }

    // ------------------------------------------------------------------ the problems of reading

    @Test
    void theExpressionMustBeAString() {
        Problem number = problem(person("      fullName: { type: string, derived: 5 }"));
        Problem list = problem(person("      fullName: { type: string, derived: [a] }"));

        assertProblem(number, ProblemCode.INVALID_VALUE, "Person.fullName", "/entityTypes/Person/attributes/fullName/derived",
                8, 42);
        assertThat(number.expected()).contains("a string");
        assertThat(list.code()).isEqualTo(ProblemCode.INVALID_VALUE);
        assertThat(list.path()).isEqualTo("Person.fullName");
    }

    @Test
    void aMisspelledKeyHasAHintThatNamesDerived() {
        Problem problem = problem(person("      fullName: { type: string, derive: \"x\" }"));

        assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_KEY);
        assertThat(problem.path()).isEqualTo("Person.fullName");
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("derived"));
    }

    // ------------------------------------------------------------------ writing

    @Test
    void derivedIsWrittenAfterUniqueAndBeforeTheRange() {
        SchemaDocument document = valid(person(
                "      fullName:",
                "        type: string",
                "        required: true",
                "        unique: true",
                "        derived: self.givenName + ' ' + self.familyName",
                "        maxLength: 100"));

        String yaml = SchemaDocuments.writeYaml(document.origin(), document.definition());
        String json = SchemaDocuments.writeJson(document.origin(), document.definition());

        assertThat(yaml).contains("""
                      fullName:
                        type: string
                        required: true
                        unique: true
                        derived: self.givenName + ' ' + self.familyName
                        maxLength: 100
                """);
        assertThat(json).contains("\"derived\": \"self.givenName + ' ' + self.familyName\"");
        assertThat(valid(yaml).definition()).isEqualTo(document.definition());
        assertThat(valid(json).definition()).isEqualTo(document.definition());
    }

    @Test
    void anAwkwardExpressionSurvivesWritingAndReading() {
        String expression = "self.name == \"it's\" && size(self.name) > 0 # not a comment\n  && true";
        SchemaDocument document = valid(document("entityTypes:", "  A: {}"));
        var definition = Definitions.withDerivedAttribute(document.definition(),
                "A", "flag", ValueType.BOOLEAN, false, expression);

        for (String text : List.of(SchemaDocuments.writeYaml(document.origin(), definition),
                SchemaDocuments.writeJson(document.origin(), definition))) {
            assertThat(valid(text).definition().entityTypes().getFirst().attributes().getFirst().derived())
                    .as(text).hasValue(expression);
        }
    }

    @Test
    void anAttributeThatIsNotDerivedHasNoDerivedKey() {
        SchemaDocument document = valid(person());

        assertThat(SchemaDocuments.writeYaml(document.origin(), document.definition())).doesNotContain("derived");
        assertThat(SchemaDocuments.writeJson(document.origin(), document.definition())).doesNotContain("derived");
    }

    // ------------------------------------------------------------------ the published JSON Schema

    @Test
    void aDocumentWithDerivedAttributesIsValidAgainstThePublishedJsonSchema() {
        String text = person("      fullName: { type: string, required: true, derived: \"self.givenName + ' ' + self.familyName\" }");

        assertThat(PublishedSchemaCheck.errors(text)).isEmpty();
    }

    @Test
    void theJsonSchemaRejectsABadDerivedKey() {
        assertThat(PublishedSchemaCheck.errors(person("      x: { type: string, derived: 5 }"))).as("not a string")
                .isNotEmpty();
        assertThat(PublishedSchemaCheck.errors(person("      x: { type: string, derived: \"\" }"))).as("empty")
                .isNotEmpty();
        assertThat(PublishedSchemaCheck.errors(person("      x: { type: string, derived: \"" + "x".repeat(4_001) + "\" }")))
                .as("too long").isNotEmpty();
        assertThat(PublishedSchemaCheck.errors(person("      x: { type: string, derived: \"" + "x".repeat(4_000) + "\" }")))
                .as("long enough").isEmpty();
    }

    // ------------------------------------------------------------------ locating the problems of derived attributes

    @Test
    void aProblemWithThePlainExpressionIsLocatedInTheExpressionAtTheErrorColumn() {
        Problem problem = locatedProblem(person(
                "      fullName:",
                "        type: string",
                "        derived: self.givenName + self.famlyName"));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_DERIVATION);
        assertThat(problem.path()).isEqualTo("Person.fullName");
        assertAt(problem, "/entityTypes/Person/attributes/fullName/derived", 10, 39);
    }

    @Test
    void aProblemWithAQuotedExpressionIsLocatedInsideTheQuotes() {
        Problem problem = locatedProblem(person(
                "      fullName: { type: string, derived: \"self.givenName + self.famlyName\" }"));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_DERIVATION);
        assertAt(problem, "/entityTypes/Person/attributes/fullName/derived", 8, 64);
    }

    @Test
    void aProblemWithAJsonExpressionIsLocatedInTheString() {
        String json = "{\"cairn\": 1, \"origin\": {\"name\": \"m\", \"version\": \"1\"}, \"entityTypes\": {\"Account\": "
                + "{\"attributes\": {\"balance\": {\"type\": \"decimal\"}, \"label\": {\"type\": \"string\", "
                + "\"derived\": \"self.amount\"}}}}}";

        Problem problem = locatedProblem(json);

        int stringStart = json.indexOf("\"self.amount") + 1;
        assertAt(problem, "/entityTypes/Account/attributes/label/derived", 1, stringStart + 4 + 1);
    }

    @Test
    void aProblemInALiteralBlockExpressionIsLocatedOnItsLine() {
        Problem problem = locatedProblem(person(
                "      fullName:",
                "        type: string",
                "        derived: |",
                "          self.givenName +",
                "            self.famlyName"));

        assertAt(problem, "/entityTypes/Person/attributes/fullName/derived", 12, 17);
    }

    @Test
    void aProblemInAnExpressionThatTheTextDoesNotMapIsLocatedAtTheKeyDerived() {
        Problem folded = locatedProblem(person(
                "      fullName:", "        type: string", "        derived: >", "          self.givenName +",
                "          self.famlyName"));
        Problem continued = locatedProblem(person(
                "      fullName:", "        type: string", "        derived: self.givenName +", "          self.famlyName"));

        assertAt(folded, "/entityTypes/Person/attributes/fullName/derived", 10, 9);
        assertAt(continued, "/entityTypes/Person/attributes/fullName/derived", 10, 9);
    }

    @Test
    void aBlankExpressionIsLocatedAtTheKeyDerived() {
        Problem problem = locatedProblem(person("      fullName: { type: string, derived: \" \" }"));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_DERIVATION);
        assertAt(problem, "/entityTypes/Person/attributes/fullName/derived", 8, 33);
    }

    @Test
    void aDerivedAttributeThatReadsADerivedAttributeIsLocatedAtTheExpression() {
        Problem problem = locatedProblem(person(
                "      fullName: { type: string, derived: \"self.givenName + self.familyName\" }",
                "      shout: { type: string, derived: \"self.fullName + '!'\" }"));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_DERIVATION);
        assertThat(problem.path()).isEqualTo("Person.shout");
        assertThat(problem.message()).contains("fullName");
        assertThat(problem.location().orElseThrow().pointer())
                .isEqualTo("/entityTypes/Person/attributes/shout/derived");
    }

    @Test
    void aProblemOfATraitsDerivedAttributeIsLocatedAtTheTraitsAttribute() {
        Problem problem = locatedProblem(document(
                "traits:",
                "  Labelled:",
                "    attributes:",
                "      label: { type: string, derived: \"self.name\" }",
                "entityTypes:",
                "  Account: { traits: [Labelled] }"));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_DERIVATION);
        assertThat(problem.path()).isEqualTo("Labelled.label");
        assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/traits/Labelled/attributes/label/derived");
    }

    @Test
    void aMissingExpressionLanguageIsLocatedAtTheAttribute() {
        SchemaRegistry withoutLanguage = SchemaRegistries.inMemory(InstantSource.system(), List.of(), Optional.empty());
        SchemaDocument document = valid(person("      fullName: { type: string, derived: \"self.givenName\" }"));

        List<Problem> problems = document.locate(withoutLanguage.validate(document.origin(), document.definition()));

        Problem problem = problems.getFirst();
        assertThat(problems).hasSize(1);
        assertThat(problem.code()).isEqualTo(ProblemCode.EXPRESSIONS_UNAVAILABLE);
        assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/entityTypes/Person/attributes/fullName");
    }

    @Test
    void aCheckAndADerivedAttributeInDifferentTypesDoNotMixUpTheirExpressions() {
        Problem problem = locatedProblem(document(
                "entityTypes:",
                "  Account:",
                "    attributes:",
                "      balance: { type: decimal }",
                "      cents: { type: long, derived: \"int(self.balance * 100.0)\" }",
                "    checks:",
                "      balance-ok: { expression: \"self.balanc >= 0.0\" }"));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CHECK);
        assertThat(problem.location().orElseThrow().pointer())
                .isEqualTo("/entityTypes/Account/checks/balance-ok/expression");
    }
}
