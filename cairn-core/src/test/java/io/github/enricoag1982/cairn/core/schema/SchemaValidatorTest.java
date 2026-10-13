package io.github.enricoag1982.cairn.core.schema;

import static io.github.enricoag1982.cairn.schema.ValueType.BOOLEAN;
import static io.github.enricoag1982.cairn.schema.ValueType.DATE;
import static io.github.enricoag1982.cairn.schema.ValueType.DECIMAL;
import static io.github.enricoag1982.cairn.schema.ValueType.STRING;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.AttributeBuilder;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Literal;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for the checks that the validator added for self-describing schemas. */
class SchemaValidatorTest {

    private static final RuleDescriptor LEI = rule("lei-checksum", 1, "Organization");
    private static final RuleDescriptor NOT_BLANK = rule("name-not-blank", 1, "Party");
    private static final RuleDescriptor OWNERSHIP = rule("total-ownership-at-most-100", 1, "Owns");

    private static RuleDescriptor rule(String name, int version, String appliesTo) {
        return new RuleDescriptor(name, version, "Checks something.", appliesTo, List.of());
    }

    private static List<Problem> validate(SchemaDefinition definition, RuleDescriptor... rules) {
        return SchemaValidator.validate(definition, List.of(rules));
    }

    private static SchemaDefinition withAttribute(String type, String name, ValueType valueType,
            UnaryOperator<AttributeBuilder> spec) {
        return SchemaDefinition.builder()
                .entityType(type, e -> e.attribute(name, valueType, a -> spec.apply(a)))
                .build();
    }

    private static void assertOnly(List<Problem> problems, ProblemCode code, String path) {
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(code);
            assertThat(problem.path()).isEqualTo(path);
            assertThat(problem.message()).isNotBlank();
        });
    }

    @Nested
    class StringConstraints {

        @Test
        void validConstraintsPass() {
            assertThat(validate(withAttribute("Account", "currency", STRING,
                    a -> a.pattern("[A-Z]{3}").minLength(3).maxLength(3).allowedValues("EUR", "USD")))).isEmpty();
        }

        @Test
        void constraintsOnOtherValueTypesAreRejectedOncePerAttribute() {
            List<Problem> problems = validate(withAttribute("Account", "balance", DECIMAL,
                    a -> a.pattern("[0-9]+").minLength(1).allowedValues("1")));

            assertOnly(problems, ProblemCode.TEXT_CONSTRAINT_NOT_ALLOWED, "Account.balance");
            assertThat(problems.getFirst().message()).contains("allowed values", "a pattern", "a length");
            assertThat(problems.getFirst().expected()).contains("a string attribute");
            assertThat(problems.getFirst().found()).contains("a decimal attribute");
        }

        @Test
        void aBrokenConstraintOnANonStringAttributeCausesNoFollowUpProblem() {
            assertOnly(validate(withAttribute("Account", "balance", DECIMAL, a -> a.pattern("[A-Z").minLength(5).maxLength(3))),
                    ProblemCode.TEXT_CONSTRAINT_NOT_ALLOWED, "Account.balance");
        }

        @Test
        void anInvalidPatternIsReportedWithTheOffendingText() {
            List<Problem> problems = validate(withAttribute("Account", "currency", STRING, a -> a.pattern("[A-Z")));

            assertOnly(problems, ProblemCode.INVALID_PATTERN, "Account.currency");
            assertThat(problems.getFirst().found()).contains("[A-Z");
            assertThat(problems.getFirst().expected()).isPresent();
        }

        @Test
        void aMinimumAboveTheMaximumIsReportedOnce() {
            List<Problem> problems = validate(withAttribute("CustomerRole", "segment", STRING,
                    a -> a.minLength(5).maxLength(3)));

            assertOnly(problems, ProblemCode.INVALID_LENGTH, "CustomerRole.segment");
            assertThat(problems.getFirst().found()).contains("5");
        }

        @Test
        void equalMinimumAndMaximumAreFine() {
            assertThat(validate(withAttribute("Organization", "lei", STRING, a -> a.minLength(20).maxLength(20))))
                    .isEmpty();
        }

        @Test
        void negativeLengthsAreReportedEach() {
            assertThat(validate(withAttribute("Organization", "lei", STRING, a -> a.minLength(-1))))
                    .singleElement().satisfies(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_LENGTH));
            assertThat(validate(withAttribute("Organization", "lei", STRING, a -> a.maxLength(-1))))
                    .singleElement().satisfies(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_LENGTH));
            assertThat(validate(withAttribute("Organization", "lei", STRING, a -> a.minLength(-2).maxLength(-1))))
                    .hasSize(2).allSatisfy(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_LENGTH));
        }

        @Test
        void aRepeatedAllowedValueIsReportedOnce() {
            List<Problem> problems = validate(withAttribute("CustomerRole", "segment", STRING,
                    a -> a.allowedValues("Corporate", "Retail", "Corporate", "Corporate")));

            assertOnly(problems, ProblemCode.DUPLICATE_ALLOWED_VALUE, "CustomerRole.segment");
            assertThat(problems.getFirst().found()).contains("Corporate");
        }

        @Test
        void allowedValuesDifferingInCaseAreDistinct() {
            assertThat(validate(withAttribute("CustomerRole", "segment", STRING, a -> a.allowedValues("Retail", "retail"))))
                    .isEmpty();
        }

        @Test
        void aRangeOnAStringStillGetsItsOwnProblem() {
            assertOnly(validate(withAttribute("Account", "currency", STRING, a -> a.range(
                    Range.atLeast(Bound.inclusive(Literal.of("A")))))), ProblemCode.RANGE_NOT_ALLOWED, "Account.currency");
        }
    }

    @Nested
    class Examples {

        @Test
        void examplesThatSatisfyEveryConstraintPass() {
            assertThat(validate(withAttribute("Organization", "lei", STRING, a -> a
                    .pattern("[A-Z0-9]{18}[0-9]{2}").minLength(20).maxLength(20)
                    .examples(Literal.of("5493001KJTIIGC8Y1R12"))))).isEmpty();
        }

        @Test
        void anExampleThatBreaksThePatternIsReportedWithExpectedAndFound() {
            List<Problem> problems = validate(withAttribute("Organization", "lei", STRING, a -> a
                    .pattern("[A-Z0-9]{18}[0-9]{2}").examples(Literal.of("ABC"))));

            assertOnly(problems, ProblemCode.INVALID_EXAMPLE, "Organization.lei");
            assertThat(problems.getFirst().found()).contains("ABC");
            assertThat(problems.getFirst().expected()).hasValueSatisfying(text -> assertThat(text).contains("[A-Z0-9]{18}[0-9]{2}"));
        }

        @Test
        void aPatternMustMatchTheWholeExample() {
            assertOnly(validate(withAttribute("Account", "currency", STRING, a -> a
                    .pattern("[A-Z]{3}").examples(Literal.of("EURO")))), ProblemCode.INVALID_EXAMPLE, "Account.currency");
        }

        @Test
        void anExampleOfTheWrongValueTypeIsReported() {
            List<Problem> problems = validate(withAttribute("Person", "birthDate", DATE, a -> a
                    .examples(Literal.of("tomorrow"))));

            assertOnly(problems, ProblemCode.INVALID_EXAMPLE, "Person.birthDate");
            assertThat(problems.getFirst().expected()).contains("a date value");
            assertThat(problems.getFirst().found()).contains("tomorrow");
        }

        @Test
        void aWholeNumberFitsADecimalAttribute() {
            assertThat(validate(withAttribute("Account", "balance", DECIMAL, a -> a
                    .examples(Literal.of(100), Literal.of(100L), Literal.of(new BigDecimal("12.50")))))).isEmpty();
        }

        @Test
        void aBooleanExampleFitsABooleanAttribute() {
            assertThat(validate(withAttribute("Account", "active", BOOLEAN, a -> a.examples(Literal.of(true))))).isEmpty();
            assertOnly(validate(withAttribute("Account", "active", BOOLEAN, a -> a.examples(Literal.of("yes")))),
                    ProblemCode.INVALID_EXAMPLE, "Account.active");
        }

        @Test
        void anExampleOutsideTheRangeIsReported() {
            Range range = Range.between(Bound.exclusive(Literal.of(0)), Bound.inclusive(Literal.of(100)));
            List<Problem> problems = validate(withAttribute("Owns", "share", DECIMAL, a -> a.range(range)
                    .examples(Literal.of(50), Literal.of(0), Literal.of(101))));

            assertThat(problems).hasSize(2).allSatisfy(problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_EXAMPLE);
                assertThat(problem.path()).isEqualTo("Owns.share");
                assertThat(problem.expected()).hasValueSatisfying(text -> assertThat(text).contains("> 0 and <= 100"));
            });
            assertThat(problems).map(problem -> problem.found().orElseThrow()).containsExactly("0", "101");
        }

        @Test
        void anExampleOutsideTheAllowedValuesIsReported() {
            assertOnly(validate(withAttribute("CustomerRole", "segment", STRING, a -> a
                    .allowedValues("Corporate", "Retail").examples(Literal.of("Corporate"), Literal.of("Private")))),
                    ProblemCode.INVALID_EXAMPLE, "CustomerRole.segment");
        }

        @Test
        void lengthsCountUnicodeCodePoints() {
            String clef = "𝄞";
            assertThat(validate(withAttribute("Account", "code", STRING, a -> a.minLength(2).maxLength(2)
                    .examples(Literal.of(clef + clef))))).isEmpty();
            assertThat(validate(withAttribute("Account", "code", STRING, a -> a.minLength(3)
                    .examples(Literal.of(clef + clef))))).hasSize(1);
            assertThat(validate(withAttribute("Account", "code", STRING, a -> a.maxLength(1)
                    .examples(Literal.of(clef + clef))))).hasSize(1);
        }

        @Test
        void anExampleShorterThanTheMinimumOrLongerThanTheMaximumIsReported() {
            assertOnly(validate(withAttribute("Account", "code", STRING, a -> a.minLength(3).examples(Literal.of("ab")))),
                    ProblemCode.INVALID_EXAMPLE, "Account.code");
            assertOnly(validate(withAttribute("Account", "code", STRING, a -> a.maxLength(3).examples(Literal.of("abcd")))),
                    ProblemCode.INVALID_EXAMPLE, "Account.code");
        }

        @Test
        void everyConstraintAnExampleBreaksIsReported() {
            assertThat(validate(withAttribute("Account", "code", STRING, a -> a.pattern("[0-9]+").minLength(5)
                    .examples(Literal.of("ab"))))).hasSize(2)
                    .allSatisfy(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_EXAMPLE));
        }

        @Test
        void aConstraintThatIsInvalidItselfCausesNoFollowUpProblemForExamples() {
            assertOnly(validate(withAttribute("Account", "currency", STRING, a -> a.pattern("[A-Z")
                    .examples(Literal.of("EUR")))), ProblemCode.INVALID_PATTERN, "Account.currency");
            assertOnly(validate(withAttribute("Account", "code", STRING, a -> a.minLength(5).maxLength(3)
                    .examples(Literal.of("abcd")))), ProblemCode.INVALID_LENGTH, "Account.code");
            assertOnly(validate(withAttribute("Account", "balance", DECIMAL, a -> a.range(
                    Range.between(Bound.inclusive(Literal.of(10)), Bound.exclusive(Literal.of(10))))
                    .examples(Literal.of(5)))), ProblemCode.RANGE_EMPTY, "Account.balance");
        }

        @Test
        void aRangeBoundOfTheWrongTypeIsReportedWithExpectedAndFound() {
            List<Problem> problems = validate(withAttribute("Person", "birthDate", DATE, a -> a.range(
                    Range.atLeast(Bound.inclusive(Literal.of(1))))));

            assertOnly(problems, ProblemCode.RANGE_LITERAL_MISMATCH, "Person.birthDate");
            assertThat(problems.getFirst().expected()).contains("a date value");
            assertThat(problems.getFirst().found()).contains("1");
        }

        @Test
        void examplesOfTraitAttributesAreCheckedToo() {
            List<Problem> problems = validate(SchemaDefinition.builder()
                    .trait("Coded", t -> t.attribute("code", STRING, a -> a.maxLength(2).examples(Literal.of("abc"))))
                    .build());

            assertOnly(problems, ProblemCode.INVALID_EXAMPLE, "Coded.code");
        }
    }

    @Nested
    class RuleReferences {

        private final SchemaDefinition parties = SchemaDefinition.builder()
                .entityType("Party", e -> e.abstractType())
                .entityType("Person", e -> e.supertype("Party"))
                .entityType("Organization", e -> e.supertype("Party"))
                .relationshipType("Owns", r -> r.from("Organization").to("Party").inverse("ownedBy"))
                .build();

        private SchemaDefinition naming(String type, String rule, int version) {
            return withRule(parties, type, rule, version);
        }

        private SchemaDefinition withRule(SchemaDefinition base, String type, String rule, int version) {
            return new SchemaDefinition(
                    base.entityTypes().stream().map(t -> t.name().equals(type)
                            ? new EntityTypeDefinition(t.name(), t.isAbstract(),
                                    t.supertype(), t.traits(), t.attributes(), t.aliases(), t.deprecated(),
                                    t.description(), append(t.rules(), rule, version))
                            : t).toList(),
                    base.traits(),
                    base.relationshipTypes().stream().map(t -> t.name().equals(type)
                            ? new RelationshipTypeDefinition(t.name(), t.source(),
                                    t.target(), t.targetsPerSource(), t.sourcesPerTarget(), t.inverseName(),
                                    t.inverseAliases(), t.kind(), t.symmetric(), t.supertype(), t.attributes(),
                                    t.aliases(), t.deprecated(), t.description(), append(t.rules(), rule, version))
                            : t).toList());
        }

        private List<RuleReference> append(
                List<RuleReference> rules, String name, int version) {
            List<RuleReference> result = new ArrayList<>(rules);
            result.add(new RuleReference(name, version));
            return result;
        }

        @Test
        void anAvailableRuleForTheTypeIsAccepted() {
            assertThat(validate(naming("Organization", "lei-checksum", 1), LEI)).isEmpty();
            assertThat(validate(naming("Owns", "total-ownership-at-most-100", 1), OWNERSHIP)).isEmpty();
        }

        @Test
        void aRuleForASupertypeCanBeNamedBySubtypes() {
            assertThat(validate(naming("Party", "name-not-blank", 1), NOT_BLANK)).isEmpty();
            assertThat(validate(naming("Organization", "name-not-blank", 1), NOT_BLANK)).isEmpty();
        }

        @Test
        void aRuleIsMatchedToItsTypeByAliasToo() {
            SchemaDefinition aliased = SchemaDefinition.builder()
                    .entityType("Company", e -> e.aliases("Organization").rule("lei-checksum", 1))
                    .build();

            assertThat(validate(aliased, LEI)).isEmpty();
        }

        @Test
        void aMalformedNameIsReportedWithTheNamingRule() {
            List<Problem> problems = validate(naming("Organization", "LEI_checksum", 1), LEI);

            assertOnly(problems, ProblemCode.INVALID_RULE_NAME, "Organization.LEI_checksum");
            assertThat(problems.getFirst().expected()).contains(Names.RULE_NAME_FORM);
            assertThat(problems.getFirst().found()).contains("LEI_checksum");
        }

        @Test
        void aVersionBelowOneIsReportedAndCausesNoFollowUpProblem() {
            List<Problem> problems = validate(naming("Organization", "lei-checksum", 0), LEI);

            assertOnly(problems, ProblemCode.INVALID_RULE_NAME, "Organization.lei-checksum");
            assertThat(problems.getFirst().found()).contains("0");
        }

        @Test
        void aMalformedNameAndVersionAreTwoProblems() {
            assertThat(validate(naming("Organization", "Lei", -1), LEI)).hasSize(2)
                    .allSatisfy(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_RULE_NAME));
        }

        @Test
        void anUnknownVersionHintsAtTheAvailableVersions() {
            List<Problem> problems = validate(naming("Organization", "lei-checksum", 2), LEI);

            assertOnly(problems, ProblemCode.UNKNOWN_RULE, "Organization.lei-checksum");
            assertThat(problems.getFirst().found()).contains("lei-checksum 2");
            assertThat(problems.getFirst().hint()).hasValueSatisfying(hint -> assertThat(hint).contains("version", "1"));
        }

        @Test
        void severalAvailableVersionsAreAllListed() {
            List<Problem> problems = validate(naming("Organization", "lei-checksum", 3), LEI,
                    rule("lei-checksum", 2, "Organization"));

            assertThat(problems.getFirst().hint()).hasValueSatisfying(hint -> assertThat(hint).contains("versions", "1, 2"));
        }

        @Test
        void anUnknownNameHintsAtTheClosestRuleName() {
            List<Problem> problems = validate(naming("Organization", "lei-checksun", 1), LEI);

            assertOnly(problems, ProblemCode.UNKNOWN_RULE, "Organization.lei-checksun");
            assertThat(problems.getFirst().hint()).hasValueSatisfying(hint -> assertThat(hint).contains("lei-checksum"));
        }

        @Test
        void anUnknownNameWithNothingCloseHasNoHint() {
            assertThat(validate(naming("Organization", "something-else", 1), LEI)).singleElement()
                    .satisfies(problem -> assertThat(problem.hint()).isEmpty());
            assertThat(validate(naming("Organization", "lei-checksum", 1)))
                    .singleElement().satisfies(problem -> assertThat(problem.hint()).isEmpty());
        }

        @Test
        void aRuleForAnotherTypeIsRejected() {
            List<Problem> problems = validate(naming("Person", "lei-checksum", 1), LEI);

            assertOnly(problems, ProblemCode.RULE_TYPE_MISMATCH, "Person.lei-checksum");
            assertThat(problems.getFirst().expected()).hasValueSatisfying(text -> assertThat(text).contains("Organization"));
            assertThat(problems.getFirst().found()).contains("Person");
        }

        @Test
        void aRuleForASubtypeCannotBeNamedBySupertypes() {
            assertOnly(validate(naming("Party", "lei-checksum", 1), LEI), ProblemCode.RULE_TYPE_MISMATCH,
                    "Party.lei-checksum");
        }

        @Test
        void aRuleForAnEntityTypeCannotBeNamedByARelationshipType() {
            assertOnly(validate(naming("Owns", "lei-checksum", 1), LEI), ProblemCode.RULE_TYPE_MISMATCH,
                    "Owns.lei-checksum");
        }

        @Test
        void aRuleForATypeThatDoesNotExistMatchesNoType() {
            assertOnly(validate(naming("Organization", "orphan", 1), rule("orphan", 1, "Nothing")),
                    ProblemCode.RULE_TYPE_MISMATCH, "Organization.orphan");
        }

        @Test
        void aRuleNamedByASupertypeAndASubtypeIsReportedAtTheSubtype() {
            SchemaDefinition both = withRule(naming("Party", "name-not-blank", 1), "Organization", "name-not-blank", 1);

            List<Problem> problems = validate(both, NOT_BLANK);

            assertOnly(problems, ProblemCode.DUPLICATE_RULE, "Organization.name-not-blank");
            assertThat(problems.getFirst().message()).contains("Party");
        }

        @Test
        void aRuleNamedByAGrandparentIsAlsoADuplicate() {
            SchemaDefinition definition = SchemaDefinition.builder()
                    .entityType("Party", e -> e.abstractType().rule("name-not-blank", 1))
                    .entityType("Legal", e -> e.supertype("Party").abstractType())
                    .entityType("Organization", e -> e.supertype("Legal").rule("name-not-blank", 1))
                    .build();

            assertOnly(validate(definition, NOT_BLANK), ProblemCode.DUPLICATE_RULE, "Organization.name-not-blank");
        }

        @Test
        void anotherVersionOfTheSameRuleIsADuplicateToo() {
            SchemaDefinition both = withRule(naming("Party", "name-not-blank", 1), "Organization", "name-not-blank", 2);

            assertThat(validate(both, NOT_BLANK, rule("name-not-blank", 2, "Party")))
                    .singleElement().satisfies(problem -> {
                        assertThat(problem.code()).isEqualTo(ProblemCode.DUPLICATE_RULE);
                        assertThat(problem.path()).isEqualTo("Organization.name-not-blank");
                    });
        }

        @Test
        void aRuleNamedTwiceByOneTypeIsReportedAtTheRepeat() {
            SchemaDefinition twice = withRule(naming("Organization", "lei-checksum", 1), "Organization", "lei-checksum", 1);

            assertOnly(validate(twice, LEI), ProblemCode.DUPLICATE_RULE, "Organization.lei-checksum");
        }

        @Test
        void siblingsMayNameTheSameRule() {
            SchemaDefinition siblings = withRule(naming("Person", "name-not-blank", 1), "Organization", "name-not-blank", 1);

            assertThat(validate(siblings, NOT_BLANK)).isEmpty();
        }

        @Test
        void aBrokenHierarchySkipsTheChecksThatNeedIt() {
            SchemaDefinition broken = SchemaDefinition.builder()
                    .entityType("Organization", e -> e.supertype("Prty").rule("lei-checksum", 1))
                    .entityType("Party")
                    .build();

            assertOnly(validate(broken, LEI), ProblemCode.UNKNOWN_REFERENCE, "Organization");
        }

        @Test
        void aBrokenHierarchyStillReportsUnknownRules() {
            SchemaDefinition broken = SchemaDefinition.builder()
                    .entityType("Organization", e -> e.supertype("Prty").rule("nothing", 1))
                    .entityType("Party")
                    .build();

            assertThat(validate(broken, LEI)).extracting(Problem::code)
                    .containsExactlyInAnyOrder(ProblemCode.UNKNOWN_REFERENCE, ProblemCode.UNKNOWN_RULE);
        }
    }

    @Nested
    class References {

        private SchemaDefinition withSupertype(String name) {
            return SchemaDefinition.builder()
                    .entityType("Party", e -> e.abstractType())
                    .entityType("Person")
                    .entityType("Organization", e -> e.supertype(name))
                    .build();
        }

        @Test
        void aMisspelledSupertypeHintsAtTheClosestEntityType() {
            List<Problem> problems = validate(withSupertype("Prty"));

            assertOnly(problems, ProblemCode.UNKNOWN_REFERENCE, "Organization");
            assertThat(problems.getFirst().found()).contains("Prty");
            assertThat(problems.getFirst().hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Party"));
        }

        @Test
        void aSupertypeNothingIsCloseToHasNoHint() {
            assertThat(validate(withSupertype("Counterparty"))).singleElement()
                    .satisfies(problem -> assertThat(problem.hint()).isEmpty());
        }

        @Test
        void aSupertypeWithoutANameHasNoHintAndNothingFound() {
            assertThat(validate(withSupertype(""))).singleElement().satisfies(problem -> {
                assertThat(problem.hint()).isEmpty();
                assertThat(problem.found()).isEmpty();
            });
        }

        @Test
        void aTypeIsNotSuggestedAsItsOwnSupertype() {
            SchemaDefinition definition = SchemaDefinition.builder()
                    .entityType("Organization", e -> e.supertype("Organizaton"))
                    .build();

            assertThat(validate(definition)).singleElement()
                    .satisfies(problem -> assertThat(problem.hint()).isEmpty());
        }

        @Test
        void anAliasIsMatchedAndTheTypeIsNamed() {
            SchemaDefinition definition = SchemaDefinition.builder()
                    .entityType("Party", e -> e.abstractType().aliases("Actor"))
                    .entityType("Organization", e -> e.supertype("Acter"))
                    .build();

            assertThat(validate(definition)).singleElement().satisfies(problem ->
                    assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Party")));
        }

        @Test
        void onlyTheKindThatCouldBeMeantIsSuggested() {
            SchemaDefinition definition = SchemaDefinition.builder()
                    .trait("Named", t -> { })
                    .entityType("Nomad")
                    .entityType("Person", e -> e.includes("Namd"))
                    .build();

            assertThat(validate(definition)).singleElement().satisfies(problem ->
                    assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Named")));
        }

        @Test
        void aMisspelledRelationshipEndHintsAtTheClosestEntityType() {
            SchemaDefinition definition = SchemaDefinition.builder()
                    .entityType("Account")
                    .entityType("Party")
                    .relationshipType("HeldBy", r -> r.from("Acount").to("Party").inverse("holds"))
                    .build();

            assertThat(validate(definition)).singleElement().satisfies(problem -> {
                assertThat(problem.path()).isEqualTo("HeldBy");
                assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Account"));
            });
        }

        @Test
        void anInvalidNameStatesTheFormAndWhatWasFound() {
            SchemaDefinition definition = SchemaDefinition.builder().entityType("1Party").build();

            assertThat(validate(definition)).singleElement().satisfies(problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_NAME);
                assertThat(problem.expected()).contains(Names.NAME_FORM);
                assertThat(problem.found()).contains("1Party");
            });
        }
    }
}
