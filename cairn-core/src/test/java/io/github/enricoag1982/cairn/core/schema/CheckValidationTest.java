package io.github.enricoag1982.cairn.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.StubLanguage;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RelationshipKind;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** How the validator checks the checks of a definition (capability {@code checks}, design D1 and D2). */
class CheckValidationTest {

    private final StubLanguage language = new StubLanguage();

    private List<Problem> validate(SchemaDefinition definition) {
        return SchemaValidator.validate(definition, List.of(), new CheckCompiler(Optional.of(language)));
    }

    private static List<Problem> validateWithoutLanguage(SchemaDefinition definition) {
        return SchemaValidator.validate(definition, List.of(), CheckCompiler.NONE);
    }

    private static SchemaDefinition account(String name, String expression) {
        return SchemaDefinition.builder()
                .entityType("Account", e -> e.attribute("balance", ValueType.DECIMAL).check(name, expression))
                .build();
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    // ------------------------------------------------------------------ no expression language

    @Test
    void withoutALanguageEveryCheckIsUnavailableAtTheCheck() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Account", e -> e.check("first-check", "true").check("second-check", "true"))
                .relationshipType("Owns", r -> r.from("Account").to("Account").inverse("ownedBy")
                        .check("third-check", "true"))
                .build();

        List<Problem> problems = validateWithoutLanguage(definition);

        assertThat(problems).extracting(Problem::code).containsOnly(ProblemCode.CHECKS_UNAVAILABLE);
        assertThat(problems).extracting(Problem::path)
                .containsExactly("Account.first-check", "Account.second-check", "Owns.third-check");
        assertThat(problems).allSatisfy(problem -> assertThat(problem.hint()).hasValueSatisfying(hint ->
                assertThat(hint).contains("cairn-cel")));
    }

    @Test
    void aDefinitionWithoutChecksNeedsNoLanguage() {
        assertThat(validateWithoutLanguage(SchemaDefinition.builder().entityType("Account", e -> { }).build())).isEmpty();
    }

    @Test
    void aMalformedNameIsReportedWhateverTheLanguageIs() {
        assertThat(only(validateWithoutLanguage(account("Bad_Name", "true"))).code())
                .isEqualTo(ProblemCode.INVALID_CHECK_NAME);
        assertThat(only(validate(account("Bad_Name", "true"))).code()).isEqualTo(ProblemCode.INVALID_CHECK_NAME);
        assertThat(language.compiled()).as("a check with a malformed name is not compiled").isEmpty();
    }

    // ------------------------------------------------------------------ names

    @Test
    void aCheckNameIsLowercaseLettersDigitsAndHyphensStartingWithALetterOfAtMost128Characters() {
        for (String valid : List.of("a", "balance-not-negative", "x1", "a-1-b", "a".repeat(128))) {
            assertThat(validate(account(valid, "true"))).as(valid).isEmpty();
        }
        for (String invalid : List.of("", "1a", "-a", "A", "aB", "a_b", "a b", "a.b", "é", "a".repeat(129))) {
            Problem problem = only(validate(account(invalid, "true")));
            assertThat(problem.code()).as(invalid).isEqualTo(ProblemCode.INVALID_CHECK_NAME);
            assertThat(problem.path()).isEqualTo("Account." + invalid);
            assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected).contains("lowercase"));
        }
    }

    @Test
    void aTypeCannotDeclareTheSameNameTwice() {
        SchemaDefinition definition = new SchemaDefinition(List.of(new EntityTypeDefinition("Account", false,
                Optional.empty(), List.of(), List.of(), List.of(), false, Optional.empty(), List.of(),
                List.of(new Check("same", "true"), new Check("same", "false")))), List.of(), List.of());

        Problem problem = only(validate(definition));

        assertThat(problem.code()).isEqualTo(ProblemCode.DUPLICATE_CHECK);
        assertThat(problem.path()).isEqualTo("Account.same");
    }

    @Test
    void aNameDeclaredByAnySupertypeIsTakenAndTheNearestOneIsNamed() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Party", e -> e.abstractType().check("name-set", "true"))
                .entityType("Organization", e -> e.supertype("Party").abstractType())
                .entityType("Bank", e -> e.supertype("Organization").check("name-set", "true"))
                .build();

        Problem problem = only(validate(definition));

        assertThat(problem.code()).isEqualTo(ProblemCode.DUPLICATE_CHECK);
        assertThat(problem.path()).isEqualTo("Bank.name-set");
        assertThat(problem.message()).contains("Party");
    }

    @Test
    void sisterTypesMayDeclareTheSameName() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Party", e -> e.abstractType())
                .entityType("Person", e -> e.supertype("Party").check("name-set", "true"))
                .entityType("Organization", e -> e.supertype("Party").check("name-set", "true"))
                .build();

        assertThat(validate(definition)).isEmpty();
    }

    @Test
    void relationshipTypesInheritChecksToo() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Party", e -> { })
                .relationshipType("Link", r -> r.from("Party").to("Party").inverse("linkedBy")
                        .check("dates-in-order", "true"))
                .relationshipType("Owns", r -> r.from("Party").to("Party").inverse("ownedBy").supertype("Link")
                        .check("dates-in-order", "true"))
                .build();

        Problem problem = only(validate(definition));

        assertThat(problem.code()).isEqualTo(ProblemCode.DUPLICATE_CHECK);
        assertThat(problem.path()).isEqualTo("Owns.dates-in-order");
    }

    // ------------------------------------------------------------------ expressions

    @Test
    void aBlankExpressionIsInvalidAndIsNotCompiled() {
        for (String blank : List.of("", " ", " \t\n ")) {
            Problem problem = only(validate(account("a-check", blank)));
            assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CHECK);
            assertThat(problem.path()).isEqualTo("Account.a-check");
            assertThat(problem.message()).contains("blank");
        }
        assertThat(language.compiled()).isEmpty();
    }

    @Test
    void anExpressionHasAtMost4000CodePoints() {
        String longest = "x".repeat(4_000);
        String emoji = "😀".repeat(4_000);

        assertThat(validate(account("a-check", longest))).isEmpty();
        assertThat(validate(account("b-check", emoji))).as("4,000 code points of two chars each").isEmpty();
        Problem tooLong = only(validate(account("c-check", longest + "x")));
        assertThat(tooLong.code()).isEqualTo(ProblemCode.INVALID_CHECK);
        assertThat(tooLong.message()).contains("4001").contains("4000");
        assertThat(only(validate(account("d-check", emoji + "x"))).code()).isEqualTo(ProblemCode.INVALID_CHECK);
        assertThat(language.compiled()).hasSize(2);
    }

    @Test
    void aCompilationErrorGivesThePositionAndTheAttributesTheTypeHas() {
        Problem problem = only(validate(account("a-check", "rejected")));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CHECK);
        assertThat(problem.path()).isEqualTo("Account.a-check");
        assertThat(problem.message()).contains("The stub rejects this.").contains("at line 2, column 5");
        assertThat(problem.found()).contains("rejected");
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("balance").contains("self."));
    }

    @Test
    void anErrorWithoutAPositionHasNone() {
        Problem problem = only(validate(account("a-check", "rejected without position")));

        assertThat(problem.message()).contains("The stub rejects this too.").doesNotContain("at line");
    }

    @Test
    void aLanguageThatFailsOtherwiseIsReportedAsFailingToCompile() {
        assertThat(only(validate(account("a-check", "crash when compiled"))).message())
                .contains("stub").contains("failed").contains("The stub crashed.");
        assertThat(only(validate(account("b-check", "nothing"))).code()).isEqualTo(ProblemCode.INVALID_CHECK);
    }

    @Test
    void everyProblemOfEveryCheckIsReportedAtOnce() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Account", e -> e.check("bad-one", "rejected").check("good", "true")
                        .check("Bad_Two", "true").check("bad-three", " "))
                .build();

        List<Problem> problems = validate(definition);

        assertThat(problems).extracting(Problem::path)
                .containsExactly("Account.bad-one", "Account.Bad_Two", "Account.bad-three");
        assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.INVALID_CHECK,
                ProblemCode.INVALID_CHECK_NAME, ProblemCode.INVALID_CHECK);
    }

    // ------------------------------------------------------------------ what a check is compiled against

    @Test
    void aCheckIsCompiledAgainstTheAttributesOfItsTypeIncludingInheritedOnes() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .trait("Named", t -> t.attribute("name", ValueType.STRING))
                .entityType("Party", e -> e.abstractType().includes("Named")
                        .attribute("since", ValueType.DATE).check("name-set", "true"))
                .entityType("Person", e -> e.supertype("Party").attribute("age", ValueType.INT))
                .build();

        assertThat(validate(definition)).isEmpty();

        assertThat(language.compiled()).singleElement().satisfies(compiled -> {
            assertThat(compiled.expression()).isEqualTo("true");
            assertThat(compiled.attributes()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("name", ValueType.STRING, "since", ValueType.DATE));
        });
    }

    @Test
    void aRelationshipTypeCheckIsCompiledAgainstTheAttributesOfTheRelationship() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Party", e -> e.attribute("name", ValueType.STRING))
                .relationshipType("Employs", r -> r.from("Party").to("Party").inverse("employedBy")
                        .attribute("title", ValueType.STRING).check("title-set", "true"))
                .build();

        assertThat(validate(definition)).isEmpty();

        assertThat(language.compiled()).singleElement().satisfies(compiled ->
                assertThat(compiled.attributes()).containsExactlyInAnyOrderEntriesOf(Map.of("title", ValueType.STRING)));
    }

    @Test
    void aTypeWithABrokenHierarchyIsNotCompiledAndCausesNoFollowUpProblem() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Organization", e -> e.supertype("Prty").check("name-set", "rejected"))
                .build();

        List<Problem> problems = validate(definition);

        assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.UNKNOWN_REFERENCE);
        assertThat(language.compiled()).isEmpty();
    }

    @Test
    void theCompilerKeepsWhatItCompiled() throws Exception {
        CheckCompiler compiler = new CheckCompiler(Optional.of(language));
        Map<String, ValueType> attributes = Map.of("balance", ValueType.DECIMAL);

        var first = compiler.compile("true", attributes);
        var second = compiler.compile("true", Map.of("balance", ValueType.DECIMAL));
        var other = compiler.compile("true", Map.of("balance", ValueType.INT));

        assertThat(second).isSameAs(first);
        assertThat(other).isNotSameAs(first);
        assertThat(language.compiled()).hasSize(2);
    }

    @Test
    void aRelationshipTypeNeedsTheSameValidationAsAnEntityType() {
        RelationshipTypeDefinition owns = new RelationshipTypeDefinition("Owns", "Account", "Account",
                Multiplicity.ZERO_OR_MORE, Multiplicity.ZERO_OR_MORE, "ownedBy", List.of(), RelationshipKind.ASSOCIATION,
                false, Optional.empty(), List.of(), List.of(), false, Optional.empty(), List.of(),
                List.of(new Check("Bad", "true")));
        SchemaDefinition definition = new SchemaDefinition(
                List.of(new EntityTypeDefinition("Account", false, Optional.empty(), List.of(), List.of(), List.of(),
                        false, Optional.empty(), List.of())), List.of(), List.of(owns));

        Problem problem = only(validate(definition));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CHECK_NAME);
        assertThat(problem.path()).isEqualTo("Owns.Bad");
    }
}
