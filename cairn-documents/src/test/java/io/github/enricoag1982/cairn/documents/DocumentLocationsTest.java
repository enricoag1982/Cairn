package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.document;
import static io.github.enricoag1982.cairn.documents.Documents.valid;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.SchemaRegistries;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import java.io.IOException;
import java.time.InstantSource;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locating the problems that the engine reports for the definition of a document (design D4). */
class DocumentLocationsTest {

    private final SchemaRegistry registry = SchemaRegistries.inMemory(InstantSource.system(), ReferenceModel.rules());

    /** The location of the first line that starts with some text, at the column where the text starts. */
    private static DocumentLocation at(String text, String pointer, String start) {
        return at(text, pointer, start, 0);
    }

    /** The location of the first line that starts with some text, a number of columns after where the text starts. */
    private static DocumentLocation at(String text, String pointer, String start, int columnsAfter) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int column = lines[i].indexOf(start);
            if (column >= 0 && lines[i].substring(0, column).isBlank()) {
                return new DocumentLocation(pointer, i + 1, column + columnsAfter + 1);
            }
        }
        throw new AssertionError("no line starts with '" + start + "'");
    }

    private List<Problem> validationProblems(SchemaDocument document) {
        return document.locate(registry.validate(document.origin(), document.definition()));
    }

    @Test
    void aProblemAboutATypeIsLocatedAtThatType() throws IOException {
        String text = Documents.referenceModelText().replace("  Organization:\n    supertype: Party",
                "  Organization:\n    supertype: Prty");
        SchemaDocument document = valid(text);

        List<Problem> problems = validationProblems(document);

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_REFERENCE);
            assertThat(problem.path()).isEqualTo("Organization");
            assertThat(problem.location()).contains(at(text, "/entityTypes/Organization", "Organization:"));
        });
    }

    @Test
    void aProblemAboutAnAttributeIsLocatedAtThatAttribute() throws IOException {
        String text = Documents.referenceModelText().replace(
                "balance: { type: decimal, required: true }", "balance: { type: decimal, required: true, pattern: \"[0-9]+\" }");
        SchemaDocument document = valid(text);

        assertThat(validationProblems(document)).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.TEXT_CONSTRAINT_NOT_ALLOWED);
            assertThat(problem.path()).isEqualTo("Account.balance");
            assertThat(problem.location()).contains(at(text, "/entityTypes/Account/attributes/balance", "balance:"));
        });
    }

    @Test
    void anAttributeOfATraitIsLocatedInTheTrait() throws IOException {
        String text = Documents.referenceModelText().replace("name: { type: string, required: true }",
                "name: { type: string, required: true, minLength: 5, maxLength: 3 }");
        SchemaDocument document = valid(text);

        assertThat(validationProblems(document)).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_LENGTH);
            assertThat(problem.location()).contains(at(text, "/traits/Named/attributes/name", "name: { type: string, required: true, min"));
        });
    }

    @Test
    void aProblemAboutAnInverseNameIsLocatedAtTheKeyInverseName() throws IOException {
        String text = Documents.referenceModelText().replace("inverseName: employedBy", "inverseName: owns");
        SchemaDocument document = valid(text);

        assertThat(validationProblems(document)).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.DUPLICATE_NAME);
            assertThat(problem.path()).isEqualTo("Employs.owns");
            assertThat(problem.location()).contains(at(text, "/relationshipTypes/Employs/inverseName", "inverseName: owns"));
        });
    }

    @Test
    void aProblemAboutARuleIsLocatedAtItsEntryInRules() throws IOException {
        String text = Documents.referenceModelText();
        SchemaDocument document = valid(text);
        SchemaRegistry withoutRules = SchemaRegistries.inMemory();

        List<Problem> problems = document.locate(withoutRules.validate(document.origin(), document.definition()));

        assertThat(problems).hasSize(2).allSatisfy(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_RULE));
        assertThat(problems.get(0).path()).isEqualTo("Organization.lei-checksum");
        assertThat(problems.get(0).location()).contains(at(text, "/entityTypes/Organization/rules/0", "- { name: lei-checksum", 2));
        assertThat(problems.get(1).path()).isEqualTo("Owns.total-ownership-at-most-100");
        assertThat(problems.get(1).location()).contains(
                at(text, "/relationshipTypes/Owns/rules/0", "- { name: total-ownership-at-most-100", 2));
    }

    @Test
    void aProblemAboutTheOriginIsLocatedAtTheKeyOrigin() {
        String text = "cairn: 1\norigin: { name: reference-model, version: \"one\" }\n";
        SchemaDocument document = valid(text);

        assertThat(validationProblems(document)).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_ORIGIN);
            assertThat(problem.path()).isEqualTo("@origin");
            assertThat(problem.location()).contains(new DocumentLocation("/origin", 2, 1));
        });
    }

    @Test
    @DisplayName("Scenario: Locating a removed attribute")
    void aRejectedRegistrationIsLocatedToo() throws IOException {
        SchemaDocument first = valid(Documents.referenceModelText());
        registry.register(first.origin(), first.definition());
        String text = Documents.referenceModelText().replace("version: \"1.0.0\"", "version: \"1.1.0\"")
                .replace("      street: { type: string, required: true }\n", "");
        SchemaDocument second = valid(text);

        RegistrationResult result = registry.register(second.origin(), second.definition());

        assertThat(result).isInstanceOf(RegistrationResult.Rejected.class);
        List<Problem> problems = second.locate(((RegistrationResult.Rejected) result).problems());
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.BREAKING_CHANGE);
            assertThat(problem.path()).isEqualTo("Address.street");
            // The attribute is gone from the new document, so the problem is located at the type it was in.
            assertThat(problem.location()).contains(at(text, "/entityTypes/Address", "Address:"));
            assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/entityTypes/Address");
        });
    }

    @Test
    void aPathOfAnElementTheDocumentLacksIsLocatedAtTheClosestEnclosingElementItHas() {
        SchemaDocument document = valid(document(
                "entityTypes:",
                "  Account:",
                "    attributes:",
                "      number: { type: string }"));

        List<Problem> located = document.locate(List.of(
                new Problem(ProblemCode.BREAKING_CHANGE, "Account.balance", "Removed attribute."),
                new Problem(ProblemCode.BREAKING_CHANGE, "account.NUMBER", "Present attribute."),
                new Problem(ProblemCode.BREAKING_CHANGE, "Account.number.nothing", "Below an attribute."),
                new Problem(ProblemCode.BREAKING_CHANGE, "Account", "Present type.")));

        assertThat(located).map(problem -> problem.location().orElseThrow().pointer()).containsExactly(
                "/entityTypes/Account", "/entityTypes/Account/attributes/number",
                "/entityTypes/Account/attributes/number", "/entityTypes/Account");
        assertThat(located.get(0).location()).contains(new DocumentLocation("/entityTypes/Account", 4, 3));
    }

    @Test
    void aPathWithNoPartInTheDocumentIsLocatedAtTheRoot() {
        SchemaDocument document = valid(document("entityTypes:", "  Account: {}"));

        List<Problem> located = document.locate(List.of(
                new Problem(ProblemCode.BREAKING_CHANGE, "Party.name", "Removed type."),
                new Problem(ProblemCode.BREAKING_CHANGE, "Party", "Removed type.")));

        assertThat(located).allSatisfy(problem -> assertThat(problem.location()).contains(document.locations().root()));
    }

    @Test
    void aPathThatNamesNoElementIsNotShortened() {
        SchemaDocument document = valid(document("entityTypes:", "  Account: {}"));

        List<Problem> located = document.locate(List.of(
                new Problem(ProblemCode.INVALID_NAME, "@document", "Outside every element."),
                new Problem(ProblemCode.INVALID_NAME, "@transaction.principal", "Not a document path."),
                new Problem(ProblemCode.INVALID_ORIGIN, "@origin", "The origin.")));

        assertThat(located.get(0).location()).contains(document.locations().root());
        assertThat(located.get(1).location()).contains(document.locations().root());
        assertThat(located.get(2).location()).contains(new DocumentLocation("/origin", 2, 1));
    }

    @Test
    void aPathThatMatchesNoElementIsLocatedAtTheRootOfTheDocument() {
        SchemaDocument document = valid(document("entityTypes:", "  Party: {}"));
        Problem problem = new Problem(ProblemCode.INVALID_NAME, "Nothing.here", "No such element.");

        assertThat(document.locate(List.of(problem))).singleElement()
                .satisfies(located -> assertThat(located.location()).contains(new DocumentLocation("", 1, 1)));
        assertThat(document.locations().find("Nothing.here")).isEmpty();
        assertThat(document.locations().of("Nothing.here")).isEqualTo(document.locations().root());
    }

    @Test
    void theRootIsWhereTheFirstKeyIs() throws IOException {
        // The reference document starts with two lines of comments.
        SchemaDocument document = valid(Documents.referenceModelText());

        assertThat(document.locations().root()).isEqualTo(new DocumentLocation("", 3, 1));
    }

    @Test
    void pathsMatchWithoutRegardToCase() {
        SchemaDocument document = valid(document(
                "entityTypes:",
                "  Account:",
                "    attributes:",
                "      Number: { type: string }"));

        assertThat(document.locations().find("account.NUMBER")).contains(
                new DocumentLocation("/entityTypes/Account/attributes/Number", 6, 7));
        assertThat(document.locations().find("ACCOUNT")).contains(new DocumentLocation("/entityTypes/Account", 4, 3));
        assertThat(document.locations().find("@ORIGIN")).contains(new DocumentLocation("/origin", 2, 1));
    }

    @Test
    void aProblemThatIsLocatedAlreadyKeepsItsLocation() {
        SchemaDocument document = valid(document("entityTypes:", "  Party: {}"));
        DocumentLocation elsewhere = new DocumentLocation("/elsewhere", 9, 9);
        Problem problem = new Problem(ProblemCode.INVALID_NAME, "Party", "Located already.").at(elsewhere);

        assertThat(document.locate(List.of(problem))).containsExactly(problem);
    }

    @Test
    void problemsKeepTheirOrderAndEverythingElse() {
        SchemaDocument document = valid(document("entityTypes:", "  Party: {}", "  Person: {}"));
        Problem person = new Problem(ProblemCode.INVALID_NAME, "Person", "Second.");
        Problem party = new Problem(ProblemCode.UNKNOWN_REFERENCE, "Party", "First.");

        List<Problem> located = document.locate(List.of(person, party));

        assertThat(located).map(Problem::path).containsExactly("Person", "Party");
        assertThat(located).map(Problem::message).containsExactly("Second.", "First.");
        assertThat(located.get(0).location()).contains(new DocumentLocation("/entityTypes/Person", 5, 3));
        assertThat(located.get(1).location()).contains(new DocumentLocation("/entityTypes/Party", 4, 3));
    }

    @Test
    void anElementWithAPathOfAnotherSectionIsLocatedAtTheFirstInTheText() {
        SchemaDocument document = valid(document(
                "entityTypes:",
                "  Party: {}",
                "traits:",
                "  party: {}"));

        assertThat(document.locations().of("Party").pointer()).isEqualTo("/entityTypes/Party");
        assertThat(document.locations().of("party").pointer()).isEqualTo("/entityTypes/Party");
    }

    @Test
    void anInverseNameIsLocatedUnderTheNameOfItsRelationshipType() {
        SchemaDocument document = valid(document(
                "relationshipTypes:",
                "  Owns:",
                "    source: Organization",
                "    target: Party",
                "    targetsPerSource: \"0..*\"",
                "    sourcesPerTarget: \"0..*\"",
                "    inverseName: ownedBy"));

        assertThat(document.locations().find("Owns.ownedBy")).contains(
                new DocumentLocation("/relationshipTypes/Owns/inverseName", 9, 5));
    }

    @Test
    void documentsWithTheSameTextAreEqualAndOthersAreNot() {
        String text = document("entityTypes:", "  Party: {}");

        assertThat(valid(text)).isEqualTo(valid(text)).hasSameHashCodeAs(valid(text));
        assertThat(valid(text).locations()).isEqualTo(valid(text).locations());
        assertThat(valid(text)).isNotEqualTo(valid(text + "\n\n  Person: {}\n"));
        assertThat(valid(text).locations().toString()).contains("party");
    }
}
