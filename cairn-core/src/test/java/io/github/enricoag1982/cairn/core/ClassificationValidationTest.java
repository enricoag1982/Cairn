package io.github.enricoag1982.cairn.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * How a registry validates classifications (ADR-0036, design D1 of {@code add-attribute-access-control}): the spelling of a
 * classification, wherever an attribute is declared, and the classification that a derived attribute must have to not
 * reveal what its expression reads. The expressions are in CEL, so the tests need the module {@code cairn-cel} on their
 * class path, as they have it.
 */
class ClassificationValidationTest {

    private static final String CENTS = "int(self.balance * 100.0)";

    private final SchemaRegistry registry = SchemaRegistries.inMemory(new TestClock(), List.of(),
            Optional.of(ExpressionLanguages.discovered().orElseThrow()));

    private List<Problem> validate(SchemaDefinition definition) {
        return registry.validate(ReferenceModel.origin("1.0.0"), definition);
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    private static SchemaDefinition account(String balanceClassification, String centsClassification, String expression) {
        return SchemaDefinition.builder()
                .entityType("Account", e -> e
                        .attribute("number", ValueType.STRING)
                        .attribute("balance", ValueType.DECIMAL, a -> {
                            if (balanceClassification != null) {
                                a.classification(balanceClassification);
                            }
                        })
                        .attribute("balanceInCents", ValueType.LONG, a -> {
                            a.derived(expression);
                            if (centsClassification != null) {
                                a.classification(centsClassification);
                            }
                        }))
                .build();
    }

    // ------------------------------------------------------------------ spelling

    static Stream<String> goodNames() {
        return Stream.of("pay", "a", "a1", "pay-data", "x-1-y", "finance", "a".repeat(128));
    }

    static Stream<String> badNames() {
        return Stream.of("", "Pay", "pay data", "pay_data", "1pay", "-pay", "pay!", "pay\n", "pay ", "café",
                "a".repeat(129));
    }

    @ParameterizedTest
    @MethodSource("goodNames")
    void aClassificationWrittenLikeARuleNameIsValid(String name) {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Account", e -> e.attribute("balance", ValueType.DECIMAL, a -> a.classification(name)))
                .build();

        assertThat(validate(definition)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("badNames")
    void anyOtherClassificationIsAProblemAtTheAttribute(String name) {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Account", e -> e.attribute("number", ValueType.STRING)
                        .attribute("balance", ValueType.DECIMAL, a -> a.classification(name)))
                .build();

        Problem problem = only(validate(definition));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
        assertThat(problem.path()).isEqualTo("Account.balance");
        assertThat(problem.message()).contains("Account");
        assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected).contains("lowercase"));
        assertThat(problem.found()).hasValue(name);
    }

    @Test
    void attributesOfTraitsAndRelationshipTypesAreCheckedToo() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .trait("Named", t -> t.attribute("name", ValueType.STRING, a -> a.classification("Names")))
                .entityType("Party", e -> e.includes("Named"))
                .relationshipType("Owns", r -> r.from("Party").to("Party").inverse("ownedBy")
                        .attribute("share", ValueType.DECIMAL, a -> a.classification("own ership")))
                .build();

        List<Problem> problems = validate(definition);

        assertThat(problems).extracting(Problem::code).containsOnly(ProblemCode.INVALID_CLASSIFICATION);
        assertThat(problems).extracting(Problem::path).containsExactlyInAnyOrder("Named.name", "Owns.share");
    }

    @Test
    void anAttributeWithoutAClassificationAndOneWithAValidClassificationAreValid() {
        assertThat(validate(account(null, null, "'x'.size()"))).isEmpty();
        assertThat(validate(account("finance", "finance", CENTS))).isEmpty();
    }

    // ------------------------------------------------------------------ derived attributes

    @Test
    void aDerivedAttributeThatReadsAClassifiedAttributeAndHasItsClassificationIsValid() {
        assertThat(validate(account("finance", "finance", CENTS))).isEmpty();
    }

    @Test
    void aDerivedAttributeWithoutTheClassificationOfWhatItReadsIsAProblemAtTheDerivedAttribute() {
        Problem problem = only(validate(account("finance", null, CENTS)));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
        assertThat(problem.path()).isEqualTo("Account.balanceInCents");
        assertThat(problem.message()).contains("balance", "finance", "no classification");
        assertThat(problem.expected()).hasValue("the classification finance");
        assertThat(problem.found()).hasValue("no classification");
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("balanceInCents", "finance"));
    }

    @Test
    void aDerivedAttributeWithAnotherClassificationThanWhatItReadsIsAProblem() {
        Problem problem = only(validate(account("finance", "identity", CENTS)));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
        assertThat(problem.path()).isEqualTo("Account.balanceInCents");
        assertThat(problem.expected()).hasValue("the classification finance");
        assertThat(problem.found()).hasValue("the classification identity");
    }

    @Test
    void aDerivedAttributeThatReadsAttributesOfTwoClassificationsIsAProblemWhateverItsOwnClassification() {
        SchemaDefinition two = SchemaDefinition.builder()
                .entityType("Account", e -> e
                        .attribute("balance", ValueType.DECIMAL, a -> a.classification("finance"))
                        .attribute("limit", ValueType.DECIMAL, a -> a.classification("risk"))
                        .attribute("headroom", ValueType.DECIMAL, a -> a.derived("self.limit - self.balance")))
                .build();
        SchemaDefinition twoWithClassification = SchemaDefinition.builder()
                .entityType("Account", e -> e
                        .attribute("balance", ValueType.DECIMAL, a -> a.classification("finance"))
                        .attribute("limit", ValueType.DECIMAL, a -> a.classification("risk"))
                        .attribute("headroom", ValueType.DECIMAL,
                                a -> a.derived("self.limit - self.balance").classification("risk")))
                .build();

        for (SchemaDefinition definition : List.of(two, twoWithClassification)) {
            Problem problem = only(validate(definition));

            assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
            assertThat(problem.path()).isEqualTo("Account.headroom");
            assertThat(problem.message()).contains("finance and risk");
            assertThat(problem.found()).hasValue("attributes of the classifications finance, risk");
        }
    }

    @Test
    void aDerivedAttributeThatReadsClassifiedAndUnclassifiedAttributesNeedsTheClassification() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Account", e -> e
                        .attribute("number", ValueType.STRING)
                        .attribute("balance", ValueType.DECIMAL, a -> a.classification("finance"))
                        .attribute("label", ValueType.STRING, a -> a.derived("self.number + string(self.balance)")))
                .build();
        SchemaDefinition classified = SchemaDefinition.builder()
                .entityType("Account", e -> e
                        .attribute("number", ValueType.STRING)
                        .attribute("balance", ValueType.DECIMAL, a -> a.classification("finance"))
                        .attribute("label", ValueType.STRING,
                                a -> a.derived("self.number + string(self.balance)").classification("finance")))
                .build();

        assertThat(only(validate(definition)).path()).isEqualTo("Account.label");
        assertThat(validate(classified)).isEmpty();
    }

    @Test
    void aDerivedAttributeMayHaveAClassificationOfItsOwnWhenItReadsNothingClassified() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Account", e -> e
                        .attribute("number", ValueType.STRING)
                        .attribute("label", ValueType.STRING, a -> a.derived("self.number + '!'").classification("ops"))
                        .attribute("constant", ValueType.STRING, a -> a.derived("'x'").classification("ops"))
                        .attribute("plain", ValueType.STRING, a -> a.derived("self.number")))
                .build();

        assertThat(validate(definition)).isEmpty();
    }

    @Test
    void aTestForAValueIsAReadOfTheAttribute() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Account", e -> e
                        .attribute("balance", ValueType.DECIMAL, a -> a.classification("finance"))
                        .attribute("known", ValueType.BOOLEAN, a -> a.derived("has(self.balance)")))
                .build();

        Problem problem = only(validate(definition));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
        assertThat(problem.path()).isEqualTo("Account.known");
    }

    @Test
    void aReadInsideAConditionalOrAComprehensionCounts() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Account", e -> e
                        .attribute("number", ValueType.STRING)
                        .attribute("balance", ValueType.DECIMAL, a -> a.classification("finance"))
                        .attribute("cents", ValueType.LONG,
                                a -> a.derived("has(self.balance) ? dyn(int(self.balance * 100.0)) : null"))
                        .attribute("digits", ValueType.BOOLEAN,
                                a -> a.derived("[1, 2].exists(n, double(n) < self.balance)")))
                .build();

        List<Problem> problems = validate(definition);

        assertThat(problems).extracting(Problem::path).containsExactlyInAnyOrder("Account.cents", "Account.digits");
        assertThat(problems).extracting(Problem::code).containsOnly(ProblemCode.INVALID_CLASSIFICATION);
    }

    @Test
    void anExpressionThatUsesSelfInAnyOtherWayIsTakenToReadEveryAttribute() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Account", e -> e
                        .attribute("number", ValueType.STRING)
                        .attribute("balance", ValueType.DECIMAL, a -> a.classification("finance"))
                        .attribute("copy", ValueType.STRING, a -> a.derived("string(dyn(self)['number'])")))
                .build();
        SchemaDefinition classified = SchemaDefinition.builder()
                .entityType("Account", e -> e
                        .attribute("number", ValueType.STRING)
                        .attribute("balance", ValueType.DECIMAL, a -> a.classification("finance"))
                        .attribute("copy", ValueType.STRING,
                                a -> a.derived("string(dyn(self)['number'])").classification("finance")))
                .build();

        Problem problem = only(validate(definition));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
        assertThat(problem.path()).isEqualTo("Account.copy");
        assertThat(validate(classified)).isEmpty();
    }

    @Test
    void aMalformedClassificationOfWhatIsReadIsReportedOnceAtItsOwnAttribute() {
        Problem problem = only(validate(account("Finance Data", null, CENTS)));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
        assertThat(problem.path()).isEqualTo("Account.balance");
    }

    @Test
    void aDerivedAttributeOfATraitIsCheckedForEveryTypeThatIncludesItAndReportedAtTheTraitForEachTypeThatFails() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .trait("Priced", t -> t.attribute("cents", ValueType.LONG,
                        a -> a.derived("int(self.price * 100.0)").classification("retail")))
                .entityType("Book", e -> e.includes("Priced")
                        .attribute("price", ValueType.DECIMAL, a -> a.classification("retail")))
                .entityType("Pen", e -> e.includes("Priced")
                        .attribute("price", ValueType.DECIMAL, a -> a.classification("wholesale")))
                .entityType("Cup", e -> e.includes("Priced")
                        .attribute("price", ValueType.DECIMAL, a -> a.classification("retail")))
                .build();

        List<Problem> problems = validate(definition);

        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst().code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
        assertThat(problems.getFirst().path()).isEqualTo("Priced.cents");
        assertThat(problems.getFirst().message()).contains("wholesale", "retail").endsWith("This is so for Pen.");
    }

    @Test
    void aDerivedAttributeOfATraitIsReportedOnceWhenSeveralTypesThatIncludeItFailTheSameWay() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .trait("Priced", t -> t.attribute("price", ValueType.DECIMAL, a -> a.classification("retail"))
                        .attribute("cents", ValueType.LONG, a -> a.derived("int(self.price * 100.0)")))
                .entityType("Book", e -> e.includes("Priced"))
                .entityType("Pen", e -> e.includes("Priced"))
                .build();

        Problem problem = only(validate(definition));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
        assertThat(problem.path()).isEqualTo("Priced.cents");
        assertThat(problem.message()).as("the first type that fails").endsWith("This is so for Book.");
    }

    @Test
    void aDerivedAttributeOfARelationshipTypeIsCheckedToo() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Party", e -> e.attribute("name", ValueType.STRING))
                .relationshipType("Owns", r -> r.from("Party").to("Party").inverse("ownedBy")
                        .attribute("share", ValueType.DECIMAL, a -> a.classification("ownership"))
                        .attribute("percent", ValueType.DECIMAL, a -> a.derived("self.share * 100.0")))
                .build();

        Problem problem = only(validate(definition));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
        assertThat(problem.path()).isEqualTo("Owns.percent");
    }
}
