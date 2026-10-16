package io.github.enricoag1982.cairn.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.StubLanguage;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * How the validator checks the derived attributes of a definition (capability {@code derived-attributes}, design D3 of
 * {@code add-derived-attributes}), with a language that reads no expression ({@link StubLanguage}).
 */
class DerivationValidationTest {

    private final StubLanguage language = new StubLanguage();

    private List<Problem> validate(SchemaDefinition definition) {
        return SchemaValidator.validate(definition, List.of(), new ExpressionCompiler(Optional.of(language)));
    }

    private static List<Problem> validateWithoutLanguage(SchemaDefinition definition) {
        return SchemaValidator.validate(definition, List.of(), ExpressionCompiler.NONE);
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    private static SchemaDefinition account(String expression) {
        return SchemaDefinition.builder()
                .entityType("Account", e -> e.attribute("number", ValueType.STRING)
                        .attribute("balance", ValueType.DECIMAL)
                        .attribute("label", ValueType.STRING, a -> a.derived(expression)))
                .build();
    }

    // ------------------------------------------------------------------ no expression language

    @Test
    void withoutALanguageEveryDerivedAttributeIsUnavailableAtTheAttribute() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .trait("Labelled", t -> t.attribute("label", ValueType.STRING, a -> a.derived("self.name")))
                .entityType("Account", e -> e.includes("Labelled").attribute("name", ValueType.STRING)
                        .attribute("other", ValueType.STRING, a -> a.derived("self.name")))
                .relationshipType("Owns", r -> r.from("Account").to("Account").inverse("ownedBy")
                        .attribute("note", ValueType.STRING, a -> a.derived("'x'")))
                .build();

        List<Problem> problems = validateWithoutLanguage(definition);

        assertThat(problems).extracting(Problem::code).containsOnly(ProblemCode.EXPRESSIONS_UNAVAILABLE);
        assertThat(problems).extracting(Problem::path)
                .containsExactlyInAnyOrder("Labelled.label", "Account.other", "Owns.note");
        assertThat(problems).allSatisfy(problem -> assertThat(problem.hint()).hasValueSatisfying(hint ->
                assertThat(hint).contains("cairn-cel")));
    }

    @Test
    void aRegistryWithoutALanguageReportsOnlyTheTypesOfTheOriginThatRegisters() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Other", e -> e.attribute("label", ValueType.STRING, a -> a.derived("'x'")))
                .entityType("Account", e -> e.attribute("label", ValueType.STRING, a -> a.derived("'x'")))
                .build();

        List<Problem> problems = SchemaValidator.validate(definition, List.of(), ExpressionCompiler.NONE,
                Set.of("account"));

        assertThat(problems).extracting(Problem::path).containsExactly("Account.label");
    }

    @Test
    void aDefinitionWithoutDerivedAttributesNeedsNoLanguage() {
        assertThat(validateWithoutLanguage(SchemaDefinition.builder().entityType("Account", e -> { }).build()))
                .isEmpty();
    }

    // ------------------------------------------------------------------ the expression

    @Test
    void aBlankExpressionIsAProblem() {
        Problem problem = only(validate(account("  ")));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_DERIVATION);
        assertThat(problem.path()).isEqualTo("Account.label");
        assertThat(problem.message()).contains("blank");
        assertThat(language.derivations()).isEmpty();
    }

    @Test
    void anExpressionOfMoreThan4000CharactersIsAProblemAndOneOf4000IsNot() {
        assertThat(validate(account("x".repeat(4000)))).isEmpty();

        Problem problem = only(validate(account("x".repeat(4001))));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_DERIVATION);
        assertThat(problem.message()).contains("4001").contains("4000");
    }

    @Test
    void anExpressionThatTheLanguageRejectsIsAProblemWithItsPosition() {
        Problem problem = only(validate(account("rejected")));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_DERIVATION);
        assertThat(problem.path()).isEqualTo("Account.label");
        assertThat(problem.message()).contains("at line 2, column 5").contains("The stub rejects this.");
        assertThat(problem.found()).hasValue("rejected");
        assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected).contains("string"));
    }

    @Test
    void anExpressionWithoutAPositionHasNone() {
        Problem problem = only(validate(account("rejected without position")));

        assertThat(problem.message()).doesNotContain("at line").contains("The stub rejects this too.");
    }

    @Test
    void aLanguageThatCrashesWhenCompilingIsAProblemOfTheExpressionAndNoCrash() {
        Problem problem = only(validate(account("crash when compiled")));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_DERIVATION);
        assertThat(problem.message()).contains("The stub crashed.");
    }

    @Test
    void aLanguageThatCompilesToNothingIsAProblemOfTheExpression() {
        Problem problem = only(validate(account("nothing")));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_DERIVATION);
        assertThat(problem.message()).contains("compiled the expression to nothing");
    }

    // ------------------------------------------------------------------ what the language is asked

    @Test
    void theLanguageIsGivenTheAttributesThatAreNotDerivedAndTheTypeOfTheDerivedAttribute() {
        assertThat(validate(account("self.number"))).isEmpty();

        assertThat(language.derivations()).singleElement().satisfies(compiled -> {
            assertThat(compiled.expression()).isEqualTo("self.number");
            assertThat(compiled.attributes()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("number", ValueType.STRING, "balance", ValueType.DECIMAL));
            assertThat(compiled.result()).isEqualTo(ValueType.STRING);
        });
    }

    @Test
    void anInheritedDerivedAttributeIsCompiledForEachConcreteSubtypeAgainstItsAttributes() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Party", e -> e.abstractType().attribute("name", ValueType.STRING)
                        .attribute("label", ValueType.STRING, a -> a.derived("self.name")))
                .entityType("Person", e -> e.supertype("Party").attribute("age", ValueType.INT))
                .entityType("Organization", e -> e.supertype("Party").attribute("lei", ValueType.STRING))
                .build();

        assertThat(validate(definition)).isEmpty();

        assertThat(language.derivations()).extracting(StubLanguage.CompiledDerived::attributes)
                .containsExactlyInAnyOrder(Map.of("name", ValueType.STRING, "age", ValueType.INT),
                        Map.of("name", ValueType.STRING, "lei", ValueType.STRING));
    }

    @Test
    void aProblemOfAnInheritedDerivedAttributeIsLocatedAtTheTypeThatDeclaresItAndReportedOnce() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Party", e -> e.abstractType().attribute("name", ValueType.STRING)
                        .attribute("label", ValueType.STRING, a -> a.derived("rejected")))
                .entityType("Person", e -> e.supertype("Party"))
                .entityType("Organization", e -> e.supertype("Party"))
                .build();

        Problem problem = only(validate(definition));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_DERIVATION);
        assertThat(problem.path()).isEqualTo("Party.label");
        assertThat(problem.message()).contains("for Person").contains("The stub rejects this.");
    }

    @Test
    void aDerivedAttributeOfATraitIsCompiledForEachTypeThatIncludesIt() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .trait("Labelled", t -> t.attribute("label", ValueType.STRING, a -> a.derived("self.name")))
                .entityType("Person", e -> e.includes("Labelled").attribute("name", ValueType.STRING))
                .entityType("Account", e -> e.includes("Labelled").attribute("name", ValueType.STRING)
                        .attribute("number", ValueType.STRING))
                .build();

        assertThat(validate(definition)).isEmpty();

        assertThat(language.derivations()).extracting(StubLanguage.CompiledDerived::attributes)
                .containsExactlyInAnyOrder(Map.of("name", ValueType.STRING),
                        Map.of("name", ValueType.STRING, "number", ValueType.STRING));
    }

    @Test
    void anAbstractTypeAndATraitNobodyIncludesAreNotCompiledOnTheirOwn() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .trait("Labelled", t -> t.attribute("label", ValueType.STRING, a -> a.derived("self.name")))
                .entityType("Party", e -> e.abstractType().attribute("label", ValueType.STRING,
                        a -> a.derived("self.name")))
                .build();

        assertThat(validate(definition)).isEmpty();
        assertThat(language.derivations()).isEmpty();
    }

    @Test
    void aRelationshipTypeIsCompiledAgainstItsOwnAttributes() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Account", e -> { })
                .relationshipType("Owns", r -> r.from("Account").to("Account").inverse("ownedBy")
                        .attribute("share", ValueType.DECIMAL)
                        .attribute("heading", ValueType.STRING, a -> a.derived("'x'")))
                .build();

        assertThat(validate(definition)).isEmpty();

        assertThat(language.derivations()).singleElement().satisfies(compiled ->
                assertThat(compiled.attributes()).containsExactlyEntriesOf(Map.of("share", ValueType.DECIMAL)));
    }

    @Test
    void aTypeWithABrokenHierarchyIsNotCompiledAndCausesNoFollowUpProblem() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Organization", e -> e.supertype("Prty")
                        .attribute("label", ValueType.STRING, a -> a.derived("rejected")))
                .build();

        List<Problem> problems = validate(definition);

        assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.UNKNOWN_REFERENCE);
        assertThat(language.derivations()).isEmpty();
    }

    @Test
    void theCompilerKeepsWhatItCompiledPerExpressionAttributesAndResult() throws Exception {
        ExpressionCompiler compiler = new ExpressionCompiler(Optional.of(language));
        Map<String, ValueType> attributes = Map.of("balance", ValueType.DECIMAL);

        var first = compiler.compileDerivation("x", attributes, ValueType.STRING);
        var second = compiler.compileDerivation("x", Map.of("balance", ValueType.DECIMAL), ValueType.STRING);
        var otherResult = compiler.compileDerivation("x", attributes, ValueType.INT);
        var otherAttributes = compiler.compileDerivation("x", Map.of("balance", ValueType.INT), ValueType.STRING);

        assertThat(second).isSameAs(first);
        assertThat(otherResult).isNotSameAs(first);
        assertThat(otherAttributes).isNotSameAs(first);
        assertThat(language.derivations()).hasSize(3);
    }

    @Test
    void checksMayReadDerivedAttributesBecauseTheyAreComputedFirst() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Account", e -> e.attribute("number", ValueType.STRING)
                        .attribute("label", ValueType.STRING, a -> a.derived("self.number"))
                        .check("label-set", "true"))
                .build();

        assertThat(validate(definition)).isEmpty();

        assertThat(language.compiled()).singleElement().satisfies(compiled ->
                assertThat(compiled.attributes()).containsOnlyKeys("number", "label"));
    }
}
