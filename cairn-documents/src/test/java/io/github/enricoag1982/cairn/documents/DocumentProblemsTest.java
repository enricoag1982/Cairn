package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.assertProblem;
import static io.github.enricoag1982.cairn.documents.Documents.document;
import static io.github.enricoag1982.cairn.documents.Documents.problem;
import static io.github.enricoag1982.cairn.documents.Documents.problems;
import static io.github.enricoag1982.cairn.schema.ProblemCode.DOCUMENT_SYNTAX;
import static io.github.enricoag1982.cairn.schema.ProblemCode.DUPLICATE_KEY;
import static io.github.enricoag1982.cairn.schema.ProblemCode.INVALID_VALUE;
import static io.github.enricoag1982.cairn.schema.ProblemCode.MISSING_KEY;
import static io.github.enricoag1982.cairn.schema.ProblemCode.UNKNOWN_KEY;
import static io.github.enricoag1982.cairn.schema.ProblemCode.UNSUPPORTED_FORMAT_VERSION;
import static io.github.enricoag1982.cairn.schema.ProblemCode.UNSUPPORTED_YAML_FEATURE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.Problem;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The problems of a document itself, each with the place it is reported at (design D5). */
class DocumentProblemsTest {

    @Nested
    class SyntaxErrors {

        @Test
        void unbalancedBracketsAreReportedWhereTheBracketIsOpened() {
            Problem problem = problem("cairn: 1\norigin: { name: a, version: \"1\" }\nentityTypes: [Party\n");

            assertProblem(problem, DOCUMENT_SYNTAX, "@document", "", 3, 14);
            assertThat(problem.message()).contains("expected ',' or ']'");
        }

        @Test
        void aTabIsReportedWhereItIs() {
            assertProblem(problem(document("entityTypes:", "\tParty: {}")), DOCUMENT_SYNTAX, "@document", "", 4, 1);
        }

        @Test
        void anUnterminatedStringIsReportedWhereItStarts() {
            assertProblem(problem(document("entityTypes:", "  Party:", "    description: \"unterminated")),
                    DOCUMENT_SYNTAX, "@document", "", 5, 18);
        }

        @Test
        void anAliasWithoutAnAnchorIsASyntaxError() {
            assertProblem(problem(document("entityTypes:", "  Party: *missing")), DOCUMENT_SYNTAX, "@document", "", 4, 10);
        }

        @Test
        void aDocumentNestedBeyondWhatCanBeReadIsASyntaxError() {
            String nested = "[".repeat(200_000) + "]".repeat(200_000);

            Problem problem = problem(document("entityTypes: " + nested));

            assertProblem(problem, DOCUMENT_SYNTAX, "@document", "", 1, 1);
            assertThat(problem.message()).contains("nested too deeply");
        }

        @Test
        void aSyntaxErrorStopsReadingSoItIsTheOnlyProblem() {
            List<Problem> problems = problems("unknown: 1\ncairn: 1\norigin: { name: a, version: \"1\" }\nentityTypes: [Party\n");

            assertThat(problems).singleElement().satisfies(problem -> assertThat(problem.code()).isEqualTo(DOCUMENT_SYNTAX));
        }
    }

    @Nested
    class FormatVersion {

        @Test
        void aVersionOtherThanOneIsReportedAtTheKey() {
            assertProblem(problem("cairn: 2\norigin: { name: a, version: \"1\" }\n"), UNSUPPORTED_FORMAT_VERSION,
                    "@document", "/cairn", 1, 1);
            assertProblem(problem("origin: { name: a, version: \"1\" }\ncairn: 0\n"), UNSUPPORTED_FORMAT_VERSION,
                    "@document", "/cairn", 2, 1);
        }

        @Test
        void aVersionOtherThanOneStopsReadingBecauseTheKeysOfOtherVersionsAreNotKnown() {
            List<Problem> problems = problems("cairn: 2\nsomething: new\nentityTypes: []\n");

            assertThat(problems).singleElement().satisfies(problem -> {
                assertThat(problem.code()).isEqualTo(UNSUPPORTED_FORMAT_VERSION);
                assertThat(problem.expected()).contains("cairn: 1");
                assertThat(problem.found()).contains("cairn: 2");
            });
        }

        @Test
        void theVersionMustBeTheInteger() {
            assertProblem(problem("cairn: \"1\"\norigin: { name: a, version: \"1\" }\n"), INVALID_VALUE, "@document",
                    "/cairn", 1, 8);
            assertProblem(problem("cairn: 1.0\norigin: { name: a, version: \"1\" }\n"), INVALID_VALUE, "@document",
                    "/cairn", 1, 8);
            assertProblem(problem("cairn: [1]\norigin: { name: a, version: \"1\" }\n"), INVALID_VALUE, "@document",
                    "/cairn", 1, 8);
        }

        @Test
        void aMissingVersionIsAMissingKey() {
            Problem problem = problem("origin: { name: a, version: \"1\" }\n");

            assertProblem(problem, MISSING_KEY, "@document", "", 1, 1);
            assertThat(problem.expected()).contains("the key 'cairn'");
        }
    }

    @Nested
    class YamlFeatures {

        @Test
        void anAnchorIsReportedAtTheAnchor() {
            Problem problem = problem(document(
                    "entityTypes:",
                    "  Address: &address",
                    "    attributes:",
                    "      street: { type: string }",
                    "  Warehouse: *address"));

            assertProblem(problem, UNSUPPORTED_YAML_FEATURE, "Address", "/entityTypes/Address", 4, 12);
            assertThat(problem.message()).contains("&address");
        }

        @Test
        void anAnchorOnAScalarIsReportedToo() {
            Problem problem = problem(document(
                    "entityTypes:",
                    "  Party: { description: &d Anybody }",
                    "  Person: { description: *d }"));

            assertProblem(problem, UNSUPPORTED_YAML_FEATURE, "Party", "/entityTypes/Party/description", 4, 25);
        }

        @Test
        void theProblemsOfAliasedContentAreReportedOnce() {
            List<Problem> problems = problems(document(
                    "entityTypes:",
                    "  Party: &base",
                    "    colour: red",
                    "  Person: *base"));

            assertThat(problems).extracting(Problem::code).containsExactly(UNSUPPORTED_YAML_FEATURE, UNKNOWN_KEY);
        }

        @Test
        void anExplicitTagIsReportedEvenIfItNamesTheTypeTheValueWouldHaveAnyway() {
            assertProblem(problem(document("entityTypes:", "  Party: { description: !!str Anybody }")),
                    UNSUPPORTED_YAML_FEATURE, "Party", "/entityTypes/Party/description", 4, 25);
            assertProblem(problem(document("entityTypes:", "  Party: !!map { abstract: true }")),
                    UNSUPPORTED_YAML_FEATURE, "Party", "/entityTypes/Party", 4, 10);
            assertProblem(problem(document("entityTypes:", "  Party: { aliases: !!seq [Actor] }")),
                    UNSUPPORTED_YAML_FEATURE, "Party", "/entityTypes/Party/aliases", 4, 21);
            assertProblem(problem(document("entityTypes:", "  Party: { abstract: !!bool true }")),
                    UNSUPPORTED_YAML_FEATURE, "Party", "/entityTypes/Party/abstract", 4, 22);
        }

        @Test
        void aTagOfYourOwnIsReported() {
            Problem problem = problem(document("entityTypes:", "  Party: { description: !party Anybody }"));

            assertProblem(problem, UNSUPPORTED_YAML_FEATURE, "Party", "/entityTypes/Party/description", 4, 25);
            assertThat(problem.found()).contains("!party");
        }

        @Test
        void aTagOnAKeyIsReported() {
            assertProblem(problem(document("entityTypes:", "  !!str Party: {}")), UNSUPPORTED_YAML_FEATURE,
                    "Party", "/entityTypes/Party", 4, 3);
        }

        @Test
        void aTagOnAnAttributeIsReportedAtTheAttribute() {
            assertProblem(problem(document(
                    "entityTypes:",
                    "  Account:",
                    "    attributes:",
                    "      balance: { type: !!str decimal }")),
                    UNSUPPORTED_YAML_FEATURE, "Account.balance", "/entityTypes/Account/attributes/balance/type", 6, 24);
        }

        @Test
        void aMergeKeyIsReported() {
            Problem problem = problem(document(
                    "entityTypes:",
                    "  Party: { abstract: true }",
                    "  Person:",
                    "    <<: { abstract: true }"));

            assertProblem(problem, UNSUPPORTED_YAML_FEATURE, "Person", "/entityTypes/Person/<<", 6, 5);
        }

        @Test
        void aSecondDocumentIsReportedAtItsStart() {
            Problem problem = problem("cairn: 1\norigin: { name: a, version: \"1\" }\n---\ncairn: 1\n");

            assertProblem(problem, UNSUPPORTED_YAML_FEATURE, "@document", "", 3, 1);
            assertThat(problem.message()).contains("more than one");
        }

        @Test
        void anExplicitDocumentStartOfTheOnlyDocumentIsFine() {
            DocumentReadResult result = SchemaDocuments.read("---\ncairn: 1\norigin: { name: a, version: \"1\" }\n...\n");

            assertThat(result).isInstanceOf(DocumentReadResult.Read.class);
        }
    }

    @Nested
    class DuplicateKeys {

        @Test
        void aTypeDefinedTwiceIsReportedAtTheSecondDefinition() {
            Problem problem = problem(document(
                    "entityTypes:",
                    "  Party: {}",
                    "  Person: {}",
                    "  Party: {}"));

            assertProblem(problem, DUPLICATE_KEY, "Party", "/entityTypes/Party", 6, 3);
        }

        @Test
        void aPropertyGivenTwiceIsReportedAtTheSecondKey() {
            assertProblem(problem(document(
                    "entityTypes:",
                    "  Party:",
                    "    abstract: true",
                    "    abstract: false")), DUPLICATE_KEY, "Party", "/entityTypes/Party/abstract", 6, 5);
        }

        @Test
        void anAttributeDefinedTwiceIsReportedWithItsPath() {
            assertProblem(problem(document(
                    "entityTypes:",
                    "  Account:",
                    "    attributes:",
                    "      currency: { type: string }",
                    "      currency: { type: string }")), DUPLICATE_KEY, "Account.currency",
                    "/entityTypes/Account/attributes/currency", 7, 7);
        }

        @Test
        void aKeyOfTheDocumentGivenTwiceIsReported() {
            assertProblem(problem(document("origin: { name: b, version: \"2\" }")), DUPLICATE_KEY, "@document", "/origin", 3, 1);
        }

        @Test
        void aDuplicateUnknownKeyIsReportedOnce() {
            List<Problem> problems = problems(document("entityTypes:", "  Party: { colour: red, colour: blue }"));

            assertThat(problems).extracting(Problem::code).containsExactly(UNKNOWN_KEY, DUPLICATE_KEY);
        }

        @Test
        void keysThatDifferOnlyInCaseAreNotDuplicatesOfTheDocument() {
            // Names are compared without regard to case when the definition is validated, not when it is read.
            DocumentReadResult result = SchemaDocuments.read(document("entityTypes:", "  Party: {}", "  party: {}"));

            assertThat(result).isInstanceOf(DocumentReadResult.Read.class);
        }
    }

    @Nested
    class UnknownKeys {

        @Test
        void aMisspelledKeyGetsTheClosestKnownKeyAsItsHint() {
            Problem problem = problem(document(
                    "entityTypes:",
                    "  Account:",
                    "    attributes:",
                    "      number: { type: string, requird: true }"));

            assertProblem(problem, UNKNOWN_KEY, "Account.number", "/entityTypes/Account/attributes/number/requird", 6, 31);
            assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("'required'"));
            assertThat(problem.found()).contains("requird");
            assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected)
                    .startsWith("one of type, required, unique,"));
        }

        @Test
        void hintsComeFromTheKeysKnownAtThatPosition() {
            assertThat(unknownKeyHint(document("entitytypes:", "  Party: {}"))).contains("'entityTypes'");
            assertThat(unknownKeyHint("cairn: 1\norigin: { nme: a, version: \"1\" }\n")).contains("'name'");
            assertThat(unknownKeyHint(document("relationshipTypes:", "  R: { sourc: A, target: A, targetsPerSource: '0..*', "
                    + "sourcesPerTarget: '0..*', inverseName: back }"))).contains("'source'");
            assertThat(unknownKeyHint(document("entityTypes:", "  Party:", "    attributes:", "      a: { type: int, "
                    + "range: { atLeat: 1 } }"))).contains("'atLeast'");
            assertThat(unknownKeyHint(document("entityTypes:", "  Organization:", "    rules:", "      - { nam: a, version: 1 }")))
                    .contains("'name'");
        }

        private String unknownKeyHint(String text) {
            return problems(text).stream().filter(problem -> problem.code() == UNKNOWN_KEY).findFirst().orElseThrow()
                    .hint().orElseThrow();
        }

        @Test
        void aKeyThatIsFarFromAllKnownKeysGetsNoSuggestion() {
            Problem problem = problem(document("entityTypes:", "  Party: { colour: red }"));

            assertProblem(problem, UNKNOWN_KEY, "Party", "/entityTypes/Party/colour", 4, 12);
            assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).doesNotContain("Did you mean"));
        }

        @Test
        void aKeyOfAnotherKindOfTypeIsUnknown() {
            Problem problem = problem(document("entityTypes:", "  Party: { source: Party }"));

            assertProblem(problem, UNKNOWN_KEY, "Party", "/entityTypes/Party/source", 4, 12);
        }

        @Test
        void aTraitHasNoAbstractFlagOrRules() {
            List<Problem> problems = problems(document("traits:", "  Named: { abstract: true, rules: [] }"));

            assertThat(problems).extracting(Problem::code).containsExactly(UNKNOWN_KEY, UNKNOWN_KEY);
            assertThat(problems).extracting(Problem::path).containsOnly("Named");
        }

        @Test
        void unknownKeysInTheOriginAreLocatedAtTheOrigin() {
            assertProblem(problem("cairn: 1\norigin: { name: a, version: \"1\", owner: me }\n"), UNKNOWN_KEY, "@origin",
                    "/origin/owner", 2, 34);
        }
    }

    @Nested
    class MissingKeys {

        @Test
        void aRelationshipTypeNeedsItsEndsMultiplicitiesAndInverseName() {
            List<Problem> problems = problems(document("relationshipTypes:", "  Owns: {}"));

            assertThat(problems).hasSize(5).allSatisfy(problem -> {
                assertThat(problem.code()).isEqualTo(MISSING_KEY);
                assertThat(problem.path()).isEqualTo("Owns");
                assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/relationshipTypes/Owns");
                assertThat(problem.location().orElseThrow().line()).isEqualTo(4);
                assertThat(problem.location().orElseThrow().column()).isEqualTo(9);
            });
            assertThat(problems).extracting(problem -> problem.expected().orElseThrow()).containsExactly(
                    "the key 'source'", "the key 'target'", "the key 'targetsPerSource'", "the key 'sourcesPerTarget'",
                    "the key 'inverseName'");
        }

        @Test
        void anAttributeNeedsAType() {
            assertProblem(problem(document("entityTypes:", "  Account:", "    attributes:", "      number: { required: true }")),
                    MISSING_KEY, "Account.number", "/entityTypes/Account/attributes/number", 6, 15);
        }

        @Test
        void theOriginNeedsANameAndAVersion() {
            List<Problem> problems = problems("cairn: 1\norigin: {}\n");

            assertThat(problems).extracting(Problem::code).containsExactly(MISSING_KEY, MISSING_KEY);
            assertThat(problems).extracting(Problem::path).containsOnly("@origin");
            assertThat(problems).extracting(problem -> problem.location().orElseThrow().pointer()).containsOnly("/origin");
        }

        @Test
        void theDocumentNeedsAnOrigin() {
            assertProblem(problem("cairn: 1\n"), MISSING_KEY, "@document", "", 1, 1);
        }

        @Test
        void aRuleReferenceNeedsANameAndAVersion() {
            List<Problem> problems = problems(document("entityTypes:", "  Organization:", "    rules:", "      - { name: lei-checksum }",
                    "      - { version: 1 }"));

            assertThat(problems).extracting(Problem::code).containsExactly(MISSING_KEY, MISSING_KEY);
            assertThat(problems.get(0).path()).isEqualTo("Organization.lei-checksum");
            assertThat(problems.get(0).location().orElseThrow().pointer()).isEqualTo("/entityTypes/Organization/rules/0");
            assertThat(problems.get(1).path()).isEqualTo("Organization");
            assertThat(problems.get(1).location().orElseThrow().pointer()).isEqualTo("/entityTypes/Organization/rules/1");
        }

        @Test
        void anEmptyTextHasNothingOfWhatIsRequired() {
            List<Problem> problems = problems("");

            assertThat(problems).extracting(Problem::code).containsExactly(MISSING_KEY, MISSING_KEY);
            assertThat(problems).extracting(problem -> problem.expected().orElseThrow())
                    .containsExactly("the key 'cairn'", "the key 'origin'");
            assertThat(problems(" # only a comment\n")).hasSize(2);
        }
    }

    @Nested
    class InvalidValues {

        @Test
        void aValueOfTheWrongKindIsReportedWithWhatWasExpected() {
            Problem problem = problem(document("entityTypes:", "  Party: { abstract: yes please }"));

            assertProblem(problem, INVALID_VALUE, "Party", "/entityTypes/Party/abstract", 4, 22);
            assertThat(problem.expected()).contains("true or false");
            assertThat(problem.found()).contains("the string 'yes please'");
        }

        @Test
        void textThatYamlReadsAsAnotherKindNeedsQuotes() {
            Problem problem = problem("cairn: 1\norigin: { name: a, version: 1.10 }\n");

            assertProblem(problem, INVALID_VALUE, "@origin", "/origin/version", 2, 29);
            assertThat(problem.expected()).contains("a string");
            assertThat(problem.found()).contains("the number 1.10");
            assertThat(problem.hint()).contains("Put the value in quotes: \"1.10\".");
        }

        @Test
        void aMappingWhereAListIsExpectedAndTheOtherWayAround() {
            assertProblem(problem(document("entityTypes:", "  Party: { traits: Named }")), INVALID_VALUE, "Party",
                    "/entityTypes/Party/traits", 4, 20);
            assertProblem(problem(document("entityTypes:", "  Party: { traits: [Named, 5] }")), INVALID_VALUE, "Party",
                    "/entityTypes/Party/traits/1", 4, 28);
            assertProblem(problem(document("entityTypes:", "  Party: [abstract]")), INVALID_VALUE, "Party",
                    "/entityTypes/Party", 4, 10);
            assertProblem(problem(document("entityTypes: [Party]")), INVALID_VALUE, "@document", "/entityTypes", 3, 14);
        }

        @Test
        void aLongValueIsShortenedInTheProblem() {
            Problem problem = problem(document("entityTypes:", "  Party: { abstract: " + "x".repeat(200) + " }"));

            assertThat(problem.found()).hasValueSatisfying(found -> assertThat(found.length()).isLessThan(100));
            assertThat(problem.found()).hasValueSatisfying(found -> assertThat(found).endsWith("...'"));
        }

        @Test
        void aTypeKeyMustBeAString() {
            Problem problem = problem(document("entityTypes:", "  ? [a, b]", "  : {}"));

            assertThat(problem.code()).isEqualTo(INVALID_VALUE);
            assertThat(problem.found()).contains("a list");
        }

        @Test
        void aValueTypeMustBeOneOfTheSeven() {
            Problem problem = problem(document("entityTypes:", "  Account:", "    attributes:",
                    "      balance: { type: numbr }"));

            assertProblem(problem, INVALID_VALUE, "Account.balance", "/entityTypes/Account/attributes/balance/type", 6, 24);
            assertThat(problem.expected()).contains("one of string, boolean, int, long, decimal, date, instant");
            assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Use one of"));
            assertThat(problem(document("entityTypes:", "  A:", "    attributes:", "      b: { type: strng }")).hint())
                    .contains("Did you mean 'string'?");
            assertThat(problem(document("entityTypes:", "  A:", "    attributes:", "      b: { type: String }")).code())
                    .isEqualTo(INVALID_VALUE);
            assertThat(problem(document("entityTypes:", "  A:", "    attributes:", "      b: { type: 5 }")).found())
                    .contains("the number 5");
        }

        @Test
        void aMultiplicityMustBeOneOfTheFourForms() {
            for (String multiplicity : List.of("2..3", "'*'", "1", "0..", "'1..n'", "many")) {
                Problem problem = problem(document("relationshipTypes:", "  R: { source: A, target: A, targetsPerSource: "
                        + multiplicity + ", sourcesPerTarget: '0..*', inverseName: back }"));

                assertThat(problem.code()).as(multiplicity).isEqualTo(INVALID_VALUE);
                assertThat(problem.path()).as(multiplicity).isEqualTo("R");
                assertThat(problem.location().orElseThrow().pointer()).as(multiplicity)
                        .isEqualTo("/relationshipTypes/R/targetsPerSource");
                assertThat(problem.expected()).as(multiplicity).contains("one of 0..1, 1..1, 0..*, 1..*");
            }
        }

        @Test
        void aKindMustBeAssociationOrComposition() {
            Problem problem = problem(document("relationshipTypes:", "  R: { source: A, target: A, kind: compostion, "
                    + "targetsPerSource: '0..*', sourcesPerTarget: '0..*', inverseName: back }"));

            assertProblem(problem, INVALID_VALUE, "R", "/relationshipTypes/R/kind", 4, 36);
            assertThat(problem.hint()).contains("Did you mean 'composition'?");
        }

        @Test
        void aRuleVersionMustBeAWholeNumber() {
            assertProblem(problem(document("entityTypes:", "  Organization:", "    rules:", "      - { name: lei-checksum, version: '1' }")),
                    INVALID_VALUE, "Organization.lei-checksum", "/entityTypes/Organization/rules/0/version", 6, 40);
            assertProblem(problem(document("entityTypes:", "  Organization:", "    rules:", "      - { name: lei-checksum, version: 1.5 }")),
                    INVALID_VALUE, "Organization.lei-checksum", "/entityTypes/Organization/rules/0/version", 6, 40);
            assertProblem(problem(document("entityTypes:", "  Organization:", "    rules: lei-checksum")), INVALID_VALUE,
                    "Organization", "/entityTypes/Organization/rules", 5, 12);
        }

        @Test
        void aLengthMustBeAWholeNumberThatFitsAnInt() {
            for (String length : List.of("1.5", "'3'", "3000000000", "true")) {
                Problem problem = problem(document("entityTypes:", "  A:", "    attributes:",
                        "      b: { type: string, minLength: " + length + " }"));

                assertThat(problem.code()).as(length).isEqualTo(INVALID_VALUE);
                assertThat(problem.expected()).as(length).contains("a whole number");
            }
        }

        @Test
        void aRangeMustBeAMappingWithAtMostOneBoundPerSide() {
            assertProblem(problem(document("entityTypes:", "  A:", "    attributes:", "      b: { type: int, range: 5 }")),
                    INVALID_VALUE, "A.b", "/entityTypes/A/attributes/b/range", 6, 30);
            Problem both = problem(document("entityTypes:", "  A:", "    attributes:",
                    "      b: { type: int, range: { atLeast: 1, greaterThan: 0 } }"));
            assertProblem(both, INVALID_VALUE, "A.b", "/entityTypes/A/attributes/b/range", 6, 30);
            assertThat(both.message()).contains("two lower bounds", "atLeast", "greaterThan");
            Problem bothUpper = problem(document("entityTypes:", "  A:", "    attributes:",
                    "      b: { type: int, range: { atMost: 1, lessThan: 2 } }"));
            assertProblem(bothUpper, INVALID_VALUE, "A.b", "/entityTypes/A/attributes/b/range", 6, 30);
            assertThat(bothUpper.message()).contains("two upper bounds");
        }

        @Test
        void aRangeWithOneBoundOnEachSideIsFine() {
            assertThat(SchemaDocuments.read(document("entityTypes:", "  A:", "    attributes:",
                    "      b: { type: int, range: { atLeast: 1, lessThan: 2 } }"))).isInstanceOf(DocumentReadResult.Read.class);
            assertThat(SchemaDocuments.read(document("entityTypes:", "  A:", "    attributes:",
                    "      b: { type: int, range: { greaterThan: 1, atMost: 2 } }"))).isInstanceOf(DocumentReadResult.Read.class);
        }

        @Test
        void aNullIsNeverAValue() {
            Problem problem = problem(document("entityTypes:", "  Party:", "    description:"));

            assertProblem(problem, INVALID_VALUE, "Party", "/entityTypes/Party/description", 5, 17);
            assertThat(problem.found()).contains("empty (null)");
            assertThat(problem(document("entityTypes:")).found()).contains("empty (null)");
        }

        @Test
        void aRootThatIsNoMappingIsReported() {
            assertProblem(problem("[1, 2]\n"), INVALID_VALUE, "@document", "", 1, 1);
            assertProblem(problem("just text\n"), INVALID_VALUE, "@document", "", 1, 1);
            assertProblem(problem("---\n"), INVALID_VALUE, "@document", "", 2, 1);
        }
    }

    @Nested
    class AllAtOnce {

        @Test
        void everyProblemIsReportedInTheOrderOfTheText() {
            List<Problem> problems = problems("""
                    cairn: 1
                    origin: { name: a, version: 1.0 }
                    entityTypes:
                      Party:
                        abstract: yes
                        colour: red
                        attributes:
                          name: { type: strng, requird: true }
                      Party: {}
                    relationshipTypes:
                      Owns: { source: Party }
                    """);

            assertThat(problems).extracting(problem -> problem.location().orElseThrow().line())
                    .isSorted()
                    .containsExactly(2, 5, 6, 8, 8, 9, 11, 11, 11, 11);
            assertThat(problems).extracting(Problem::code).containsExactly(INVALID_VALUE, INVALID_VALUE, UNKNOWN_KEY,
                    INVALID_VALUE, UNKNOWN_KEY, DUPLICATE_KEY, MISSING_KEY, MISSING_KEY, MISSING_KEY, MISSING_KEY);
        }
    }
}
