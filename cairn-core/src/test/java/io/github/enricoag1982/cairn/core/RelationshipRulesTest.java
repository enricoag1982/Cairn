package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipRule;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TotalOwnershipRule;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Design D5 of {@code add-relationships}: rules on relationship types. Which versions of which relationships they check,
 * what they see through their context, how what they report is located, and how a rule that does not fit the kind of
 * the type that names it, or that has no code, is reported. The last part tests the reference rule
 * {@code total-ownership-at-most-100}.
 */
class RelationshipRulesTest {

    private static final TemporaryId EMPLOYS = new TemporaryId("employs");
    private static final TemporaryId OWNS = new TemporaryId("owns");
    private static final TemporaryId OTHER_OWNS = new TemporaryId("otherOwns");
    private static final TemporaryId OTHER_EMPLOYS = new TemporaryId("otherEmploys");
    private static final TemporaryId DELTA = new TemporaryId("delta");

    /** A store of the reference domain whose schema also names the given rules, each on the type it applies to. */
    private static ReferenceStore storeWith(Rule... extra) {
        SchemaDefinition model = ReferenceModel.definition();
        for (Rule rule : extra) {
            model = TestRelationshipRule.namedIn(model, rule.descriptor().appliesTo(), rule.descriptor());
        }
        return ReferenceStore.withRules(model, ReferenceStore.rulesWith(extra), ReferenceModel.declaredRules());
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    /** A rule on Employs that records every version it checks. */
    private static TestRelationshipRule recorder(List<RelationshipState> checked) {
        return TestRelationshipRule.of("recorder", "Employs", (version, context) -> checked.add(version));
    }

    private static Transaction.Builder transaction() {
        return Transaction.by("importer");
    }

    private static String title(RelationshipState state) {
        return state.values().get("title") instanceof Value.StringValue text ? text.value() : "";
    }

    // ------------------------------------------------------------------ which versions are checked

    @Test
    void aRuleChecksTheVersionOfARelationshipThatTheTransactionRelates() {
        List<RelationshipState> checked = new ArrayList<>();
        ReferenceStore reference = storeWith(recorder(checked));
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);

        Receipt receipt = reference.commit(transaction()
                .relate(EMPLOYS, "Employs", beta, alice, from(3, 1), values("title", "Analyst")).build());

        assertThat(checked).singleElement().satisfies(version -> {
            assertThat(version.id()).isEqualTo(relationship(receipt, EMPLOYS));
            assertThat(version.relationshipType()).isEqualTo("Employs");
            assertThat(version.source()).isEqualTo(beta);
            assertThat(version.target()).isEqualTo(alice);
            assertThat(version.values()).containsEntry("title", Value.of("Analyst"));
            assertThat(version.period()).isEqualTo(from(3, 1));
            assertThat(version.recordedBasis()).isEqualTo(receipt.basis());
            assertThat(version.recordedAt()).isEqualTo(receipt.transactionTime());
        });
    }

    @Test
    void everyChangedVersionOfANewRelationshipIsChecked() {
        List<RelationshipState> checked = new ArrayList<>();
        ReferenceStore reference = storeWith(recorder(checked));
        Receipt parties = reference.createParties();

        reference.commit(transaction()
                .relate(EMPLOYS, "Employs", entity(parties, BETA), entity(parties, ALICE), from(3, 1),
                        values("title", "Analyst"))
                .patch(EMPLOYS, from(6, 1), values("title", "Senior analyst")).build());

        assertThat(checked).extracting(version -> version.period().from(), RelationshipRulesTest::title)
                .containsExactly(tuple(on(3, 1), "Analyst"), tuple(on(6, 1), "Senior analyst"));
    }

    @Test
    void onlyTheVersionsThatTheTransactionChangesAreChecked() {
        List<RelationshipState> checked = new ArrayList<>();
        ReferenceStore reference = storeWith(recorder(checked));
        Receipt parties = reference.createParties();
        RelationshipId employs = relationship(reference.commit(transaction()
                .relate(EMPLOYS, "Employs", entity(parties, BETA), entity(parties, ALICE), from(3, 1),
                        values("title", "Analyst")).build()), EMPLOYS);
        checked.clear();

        reference.commit(transaction().patch(employs, from(6, 1), values("title", "Senior analyst")).build());
        assertThat(checked).as("the changed part of the timeline").singleElement().satisfies(version -> {
            assertThat(version.period()).isEqualTo(from(6, 1));
            assertThat(title(version)).isEqualTo("Senior analyst");
        });
        checked.clear();

        reference.commit(transaction().patch(employs, from(6, 1), values("title", "Senior analyst")).build());
        reference.commit(transaction().replace(employs, from(6, 1), values("title", "Senior analyst")).build());
        reference.commit(transaction().patch(employs, from(3, 1), values()).build());
        assertThat(checked).as("writes that change nothing").isEmpty();

        reference.commit(transaction().endDate(employs, on(9, 1)).build());
        assertThat(checked).as("cutting off a relationship changes no values").isEmpty();
    }

    @Test
    void aRuleOnARelationshipTypeDoesNotRunWhenOnlyAnEndChangesAndAnEntityRuleDoesNotRunWhenOnlyARelationshipDoes() {
        List<RelationshipState> checked = new ArrayList<>();
        List<String> entitiesChecked = new ArrayList<>();
        TestEntityRule forOrganizations = TestEntityRule.of("entity-probe", "Organization",
                (version, context) -> entitiesChecked.add(version.id().value()));
        ReferenceStore reference = ReferenceStore.withRules(
                TestRelationshipRule.namedIn(TestEntityRule.namedIn(ReferenceModel.definition(), "Organization",
                        forOrganizations.descriptor()), "Employs", recorder(checked).descriptor()),
                ReferenceStore.rulesWith(forOrganizations, recorder(checked)), ReferenceModel.declaredRules());
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        RelationshipId employs = relationship(reference.commit(transaction()
                .relate(EMPLOYS, "Employs", beta, entity(parties, ALICE), from(3, 1), values()).build()), EMPLOYS);
        checked.clear();
        entitiesChecked.clear();

        reference.commit(transaction().patch(beta, from(1, 1), values("name", "Beta Two")).build());
        assertThat(checked).as("only an end changed").isEmpty();
        assertThat(entitiesChecked).containsExactly(beta.value());
        entitiesChecked.clear();

        reference.commit(transaction().patch(employs, from(3, 1), values("title", "Analyst")).build());
        assertThat(checked).hasSize(1);
        assertThat(entitiesChecked).as("an entity that is only an end of the relationship").isEmpty();
    }

    @Test
    void theRulesOfARelationshipTypesSupertypesRunToo() {
        SchemaDefinition model = Definitions.plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .relationshipType("WorksFor", r -> r.from("Person").to("Organization")
                        .targetsPerSource(Multiplicity.ZERO_OR_ONE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("workers"))
                .relationshipType("Contracts", r -> r.supertype("WorksFor").from("Person").to("Organization")
                        .targetsPerSource(Multiplicity.ZERO_OR_ONE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("contractors"))
                .build());
        List<String> checked = new ArrayList<>();
        TestRelationshipRule forContracts = TestRelationshipRule.of("for-contracts", "Contracts",
                (version, context) -> checked.add("Contracts rule: " + version.relationshipType()));
        TestRelationshipRule forWorksFor = TestRelationshipRule.of("for-works-for", "WorksFor",
                (version, context) -> checked.add("WorksFor rule: " + version.relationshipType()));
        model = TestRelationshipRule.namedIn(TestRelationshipRule.namedIn(model, "Contracts",
                forContracts.descriptor()), "WorksFor", forWorksFor.descriptor());
        ReferenceStore reference = ReferenceStore.withRules(model, ReferenceStore.rulesWith(forContracts, forWorksFor),
                ReferenceModel.declaredRules());
        Receipt parties = reference.createParties();

        reference.commit(transaction()
                .relate("Contracts", entity(parties, ALICE), entity(parties, BETA), from(3, 1), values()).build());

        assertThat(checked).as("the type's own rules before those of its supertype")
                .containsExactly("Contracts rule: Contracts", "WorksFor rule: Contracts");
    }

    // ------------------------------------------------------------------ declarative checks come first

    @Test
    void aRelationshipWithADeclarativeProblemIsNotChecked() {
        List<RelationshipState> checked = new ArrayList<>();
        TestRelationshipRule recorder = TestRelationshipRule.of("recorder", "Owns",
                (version, context) -> checked.add(version));
        ReferenceStore reference = storeWith(recorder);
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);

        List<Problem> problems = reference.reject(transaction()
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 0))
                .relate(OTHER_OWNS, "Owns", beta, acme, from(2, 1), values()).build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.VALUE_OUT_OF_RANGE, "$owns.share"),
                tuple(ProblemCode.MISSING_REQUIRED_VALUE, "$otherOwns.share"));
        assertThat(checked).isEmpty();
    }

    @Test
    void aRelationshipWithAProblemAboutItsEndsOrItsLifetimeIsNotChecked() {
        List<RelationshipState> checked = new ArrayList<>();
        ReferenceStore reference = storeWith(recorder(checked));
        Receipt parties = reference.createParties();

        List<Problem> beforeEndsExist = reference.reject(transaction()
                .relate(EMPLOYS, "Employs", entity(parties, BETA), entity(parties, ALICE),
                        EffectivePeriod.from(on(2025, 12, 1)), values()).build());
        Receipt accounts = reference.createAccounts();
        List<Problem> wrongEnd = reference.reject(transaction()
                .relate(OTHER_OWNS, "Employs", entity(accounts, A1), entity(parties, ALICE), from(3, 1), values())
                .build());

        assertThat(only(beforeEndsExist).code()).isEqualTo(ProblemCode.END_NOT_EFFECTIVE);
        assertThat(only(wrongEnd).code()).isEqualTo(ProblemCode.WRONG_END_TYPE);
        assertThat(checked).isEmpty();
    }

    // ------------------------------------------------------------------ paths

    @Test
    void aRuleProblemAboutARelationshipIsLocatedLikeOtherDataProblemsOfRelationships() {
        TestRelationshipRule picky = TestRelationshipRule.of("picky", "Employs", (version, context) -> {
            context.problem("odd", "title", "The title is odd.");
            context.problem("odd", "TITLE", "Reported with other capitals.");
            context.problem("whole", "The relationship is odd.");
        }, "odd", "whole");
        ReferenceStore reference = storeWith(picky);
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);

        List<Problem> withTemporaryId = reference.reject(transaction()
                .relate(EMPLOYS, "Employs", beta, alice, from(3, 1), values()).build());
        List<Problem> withoutTemporaryId = reference.reject(transaction()
                .relate("Employs", beta, alice, from(3, 1), values()).build());
        List<Problem> suppliedId = reference.reject(transaction()
                .relate(ReferenceStore.relationshipId(900), "Employs", beta, alice, from(3, 1), values()).build());

        assertThat(withTemporaryId).extracting(Problem::code, Problem::path, problem -> problem.ruleCode().orElse(""))
                .containsExactly(
                        tuple(ProblemCode.RULE_VIOLATION, "$employs.title", "picky/odd"),
                        tuple(ProblemCode.RULE_VIOLATION, "$employs.title", "picky/odd"),
                        tuple(ProblemCode.RULE_VIOLATION, "$employs", "picky/whole"));
        assertThat(withoutTemporaryId).extracting(Problem::path)
                .containsExactly("@write.0.title", "@write.0.title", "@write.0");
        assertThat(suppliedId).extracting(Problem::path).containsExactly(
                ReferenceStore.relationshipId(900).value() + ".title",
                ReferenceStore.relationshipId(900).value() + ".title", ReferenceStore.relationshipId(900).value());
        assertThat(withTemporaryId.getFirst().message()).isEqualTo("The title is odd.");
    }

    @Test
    void aRuleProblemAboutAnExistingRelationshipIsLocatedAtItsId() {
        TestRelationshipRule picky = TestRelationshipRule.of("picky", "Employs", (version, context) -> {
            if (title(version).equals("Odd")) {
                context.problem("odd", "title", "The title is odd.");
            }
        }, "odd");
        ReferenceStore reference = storeWith(picky);
        Receipt parties = reference.createParties();
        RelationshipId employs = relationship(reference.commit(transaction()
                .relate(EMPLOYS, "Employs", entity(parties, BETA), entity(parties, ALICE), from(3, 1), values())
                .build()), EMPLOYS);

        List<Problem> problems = reference.reject(transaction()
                .patch(employs, from(6, 1), values("title", "Odd")).build());

        assertThat(only(problems).path()).isEqualTo(employs.value() + ".title");
    }

    @Test
    void aProblemThatSeveralVersionsOfARelationshipShareIsReportedOnce() {
        TestRelationshipRule always = TestRelationshipRule.of("always", "Employs", (version, context) ->
                context.problem("odd", "title", "Always odd."), "odd");
        ReferenceStore reference = storeWith(always);
        Receipt parties = reference.createParties();

        List<Problem> problems = reference.reject(transaction()
                .relate(EMPLOYS, "Employs", entity(parties, BETA), entity(parties, ALICE), from(3, 1), values())
                .patch(EMPLOYS, from(6, 1), values("title", "Analyst")).build());

        assertThat(problems).extracting(Problem::code, Problem::path)
                .containsExactly(tuple(ProblemCode.RULE_VIOLATION, "$employs.title"));
    }

    // ------------------------------------------------------------------ attributes of the relationship's type

    @Test
    void anAttributeThatTheRelationshipsTypeDoesNotHaveIsAFailureOfTheRule() {
        TestRelationshipRule typo = TestRelationshipRule.of("typo", "Employs", (version, context) -> {
            context.problem("odd", "title", "A real problem.");
            context.problem("odd", "name", "An attribute of entities, not of Employs.");
            context.problem("odd", "titel", "A problem about a typo.");
        }, "odd");
        ReferenceStore reference = storeWith(typo);
        Receipt parties = reference.createParties();

        List<Problem> problems = reference.reject(transaction()
                .relate(EMPLOYS, "Employs", entity(parties, BETA), entity(parties, ALICE), from(3, 1), values())
                .build());

        assertThat(problems).extracting(Problem::code, Problem::path, problem -> problem.ruleCode().orElse(""))
                .containsExactly(
                        tuple(ProblemCode.RULE_VIOLATION, "$employs.title", "typo/odd"),
                        tuple(ProblemCode.RULE_FAILED, "$employs", ""),
                        tuple(ProblemCode.RULE_FAILED, "$employs", ""));
        Problem failed = problems.get(2);
        assertThat(failed.message()).contains("typo").contains("titel").contains("Employs").contains("relationship");
        assertThat(failed.found()).contains("titel");
        assertThat(failed.expected()).hasValueSatisfying(expected -> assertThat(expected).contains("title"));
        assertThat(failed.hint().orElse("")).contains("relationship");
    }

    @Test
    void aRuleMayNameAnAttributeOfARelationshipByItsAliasAndTheProblemIsLocatedAtTheAttributesName() {
        SchemaDefinition renamed = Definitions.changeRelationshipType(ReferenceModel.definition(), "Employs", type ->
                new RelationshipTypeDefinition(type.name(), type.source(), type.target(), type.targetsPerSource(),
                        type.sourcesPerTarget(), type.inverseName(), type.inverseAliases(), type.kind(),
                        type.symmetric(), type.supertype(), List.of(Definitions.renamed(type.attributes().getFirst(),
                                "position", "title")), type.aliases(), type.deprecated(), type.description(),
                        type.rules()));
        TestRelationshipRule byAlias = TestRelationshipRule.of("by-alias", "Employs", (version, context) ->
                context.problem("odd", "title", "Reported by an old name."), "odd");
        ReferenceStore reference = ReferenceStore.withRules(byAlias.namedIn(renamed, "Employs"),
                ReferenceStore.rulesWith(byAlias), ReferenceModel.declaredRules());
        Receipt parties = reference.createParties();

        List<Problem> problems = reference.reject(transaction()
                .relate(EMPLOYS, "Employs", entity(parties, BETA), entity(parties, ALICE), from(3, 1), values())
                .build());

        assertThat(only(problems).path()).isEqualTo("$employs.position");
    }

    // ------------------------------------------------------------------ the rule must fit the type

    @Test
    void aRuleThatIsOnlyAnEntityRuleCannotCheckARelationship() {
        EntityRule forEntities = TestEntityRule.of("entity-only", "Employs", (version, context) -> { });
        ReferenceStore reference = ReferenceStore.withRules(
                TestRelationshipRule.namedIn(ReferenceModel.definition(), "Employs", forEntities.descriptor()),
                ReferenceStore.rulesWith(forEntities), ReferenceModel.declaredRules());
        Receipt parties = reference.createParties();

        Problem problem = only(reference.reject(transaction()
                .relate(EMPLOYS, "Employs", entity(parties, BETA), entity(parties, ALICE), from(3, 1), values())
                .build()));

        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_FAILED);
        assertThat(problem.path()).isEqualTo("$employs");
        assertThat(problem.message()).contains("entity-only").contains("Employs").contains("relationship type")
                .contains("RelationshipRule");
        assertThat(problem.expected()).hasValue("a RelationshipRule");
        assertThat(problem.hint().orElse("")).contains("RelationshipRule").contains("entity types");
        assertThat(reference.commit(transaction()
                .relate("Owns", entity(parties, ACME), entity(parties, BETA), from(3, 1), values("share", 10))
                .build())).as("relationships of other types are not affected").isNotNull();
    }

    @Test
    void aRuleThatIsOnlyARelationshipRuleCannotCheckAnEntity() {
        TestRelationshipRule forRelationships = TestRelationshipRule.of("relationship-only", "Organization",
                (version, context) -> { });
        ReferenceStore reference = ReferenceStore.withRules(
                TestEntityRule.namedIn(ReferenceModel.definition(), "Organization", forRelationships.descriptor()),
                ReferenceStore.rulesWith(forRelationships), ReferenceModel.declaredRules());

        Problem problem = only(reference.reject(transaction()
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build()));

        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_FAILED);
        assertThat(problem.path()).isEqualTo("$acme");
        assertThat(problem.message()).contains("relationship-only").contains("Organization").contains("entity type")
                .contains("EntityRule");
        assertThat(problem.expected()).hasValue("an EntityRule");
        assertThat(problem.hint().orElse("")).contains("EntityRule").contains("relationship types");
        assertThat(reference.commit(transaction().create(ALICE, "Person", from(1, 1), values("name", "Alice"))
                .build())).as("entities of other types are not affected").isNotNull();
    }

    /** A rule that checks entities and relationships alike, for one type, and records what it checked. */
    private record Both(RuleDescriptor descriptor, List<String> checked) implements EntityRule, RelationshipRule {

        Both(String name, String appliesTo, List<String> checked) {
            this(new RuleDescriptor(name, 1, "Checks both kinds.", appliesTo, List.of()), checked);
        }

        @Override
        public void check(EntityState version, RuleContext context) {
            checked.add("entity " + version.entityType());
        }

        @Override
        public void check(RelationshipState version, RuleContext context) {
            checked.add("relationship " + version.relationshipType());
        }
    }

    @Test
    void aRuleThatImplementsBothInterfacesChecksEachKindOfTypeThatNamesIt() {
        List<String> checked = new ArrayList<>();
        Both onPerson = new Both("both-on-person", "Person", checked);
        Both onEmploys = new Both("both-on-employs", "Employs", checked);
        SchemaDefinition model = TestRelationshipRule.namedIn(TestEntityRule.namedIn(ReferenceModel.definition(),
                "Person", onPerson.descriptor()), "Employs", onEmploys.descriptor());
        ReferenceStore reference = ReferenceStore.withRules(model, ReferenceStore.rulesWith(onPerson, onEmploys),
                ReferenceModel.declaredRules());
        Receipt parties = reference.createParties();
        checked.clear();

        reference.commit(transaction()
                .relate("Employs", entity(parties, BETA), entity(parties, ALICE), from(3, 1), values()).build());

        assertThat(checked).containsExactly("relationship Employs");
        checked.clear();
        reference.commit(transaction().patch(entity(parties, ALICE), from(1, 1), values("name", "Alice Two"))
                .build());
        assertThat(checked).containsExactly("entity Person");
    }

    // ------------------------------------------------------------------ rules without code

    @Test
    void aDeclaredRuleWithoutCodeRejectsTheWritesOfRelationshipsOfThatType() {
        TestRelationshipRule declared = TestRelationshipRule.of("employment-check", "Employs", (v, c) -> { });
        ReferenceStore reference = ReferenceStore.withRules(
                TestRelationshipRule.namedIn(ReferenceModel.definition(), "Employs", declared.descriptor()),
                ReferenceModel.executableRules(), List.of(declared.descriptor()));
        Receipt parties = reference.createParties();

        List<Problem> problems = reference.reject(transaction()
                .relate(EMPLOYS, "Employs", entity(parties, BETA), entity(parties, ALICE), from(3, 1), values())
                .build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_UNAVAILABLE);
        assertThat(problem.path()).isEqualTo("$employs");
        assertThat(problem.message()).contains("employment-check");
        assertThat(reference.commit(transaction()
                .relate("Owns", entity(parties, ACME), entity(parties, BETA), from(3, 1), values("share", 10))
                .build())).as("relationships of other types are not affected").isNotNull();
    }

    // ------------------------------------------------------------------ what a rule sees

    /** What a rule saw of the relationships through its context while it checked one version. */
    private record Seen(
            List<Link> employersAtMay,
            List<Link> employersAtJuly,
            List<RelationshipState> employersAcrossTime,
            Optional<RelationshipState> checkedById,
            List<RelationshipState> timelineOfChecked,
            List<Step> traversal,
            Optional<RelationshipState> viewAtJuly) {
    }

    @Test
    void aRulesViewsShowTheRelationshipsTheTransactionWouldProduceLikeAViewAfterTheCommit() {
        AtomicReference<Seen> seen = new AtomicReference<>();
        TestRelationshipRule probe = TestRelationshipRule.of("probe", "Employs", (version, context) -> {
            if (title(version).equals("Advisor")) {
                EntityId alice = version.target();
                View view = context.view();
                seen.set(new Seen(context.viewAt(on(5, 1)).navigate(alice, "employedBy"),
                        context.viewAt(on(7, 1)).navigate(alice, "employedBy"),
                        view.navigateTimeline(alice, "employedBy"), view.relationship(version.id()),
                        view.relationshipTimeline(version.id()), view.traverse(alice, "employedBy", r -> true, 3),
                        context.viewAt(on(7, 1)).relationship(version.id())));
            }
        });
        ReferenceStore reference = storeWith(probe);
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        RelationshipId betaEmploys = relationship(reference.commit(transaction()
                .relate(EMPLOYS, "Employs", beta, alice, from(3, 1), values("title", "Analyst")).build()), EMPLOYS);

        Receipt receipt = reference.commit(transaction()
                .create(DELTA, "Organization", from(1, 1), values("name", "Delta"))
                .relate(OTHER_EMPLOYS, "Employs", DELTA, alice, EffectivePeriod.between(on(4, 1), on(8, 1)),
                        values("title", "Advisor"))
                .endDate(betaEmploys, on(6, 1)).build());

        RelationshipId deltaEmploys = relationship(receipt, OTHER_EMPLOYS);
        Seen s = seen.get();
        assertThat(s).isNotNull();
        assertThat(s.employersAtMay()).extracting(link -> link.relationship().id())
                .as("the committed relationship and the one of the transaction, in the order of their IDs")
                .containsExactly(betaEmploys, deltaEmploys);
        assertThat(s.employersAtJuly()).extracting(link -> link.relationship().id())
                .as("the committed relationship that the transaction ends is gone, the new one goes on")
                .containsExactly(deltaEmploys);
        assertThat(s.employersAtJuly().getFirst().entity().values()).containsEntry("name", Value.of("Delta"));
        assertThat(s.employersAcrossTime()).extracting(RelationshipState::id, version -> version.period().from(),
                version -> version.period().until().orElseThrow())
                .containsExactly(tuple(betaEmploys, on(3, 1), on(6, 1)), tuple(deltaEmploys, on(4, 1), on(8, 1)));
        assertThat(s.checkedById()).as("the version at the start of the checked period")
                .hasValueSatisfying(version -> assertThat(version.id()).isEqualTo(deltaEmploys));
        assertThat(s.timelineOfChecked()).singleElement().satisfies(version ->
                assertThat(version.recordedBasis()).isEqualTo(receipt.basis()));
        assertThat(s.traversal()).extracting(step -> step.entity().id(), Step::depth, step -> step.via().id())
                .containsExactly(tuple(beta, 1, betaEmploys), tuple(entity(receipt, DELTA), 1, deltaEmploys));
        assertThat(s.viewAtJuly()).hasValueSatisfying(version -> assertThat(version.id()).isEqualTo(deltaEmploys));

        assertThat(s.employersAtMay()).isEqualTo(reference.view(on(5, 1)).navigate(alice, "employedBy"));
        assertThat(s.employersAtJuly()).isEqualTo(reference.view(on(7, 1)).navigate(alice, "employedBy"));
        assertThat(s.employersAcrossTime()).isEqualTo(reference.view(on(1, 1)).navigateTimeline(alice, "employedBy"));
        assertThat(s.timelineOfChecked()).isEqualTo(reference.view(on(1, 1)).relationshipTimeline(deltaEmploys));
        assertThat(s.checkedById()).isEqualTo(reference.view(on(4, 1)).relationship(deltaEmploys));
        assertThat(s.traversal()).isEqualTo(reference.view(on(4, 1)).traverse(alice, "employedBy", r -> true, 3));
    }

    @Test
    void aRuleSeesRelationshipsOfTheStoreThatTheTransactionDoesNotTouch() {
        List<List<EntityId>> owners = new ArrayList<>();
        TestRelationshipRule probe = TestRelationshipRule.of("probe", "Employs", (version, context) ->
                owners.add(context.view().navigate(version.source(), "ownedBy").stream()
                        .map(link -> link.entity().id()).toList()));
        ReferenceStore reference = storeWith(probe);
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        reference.commit(transaction().relate("Owns", acme, beta, from(2, 1), values("share", 60)).build());

        reference.commit(transaction()
                .relate("Employs", beta, entity(parties, ALICE), from(3, 1), values()).build());

        assertThat(owners).containsExactly(List.of(acme));
    }

    @Test
    void aDryRunRunsTheRulesOfRelationshipsAndRecordsNothing() {
        ReferenceStore reference = new ReferenceStore();
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        reference.commit(transaction().relate("Owns", entity(parties, ACME), beta, from(2, 1), values("share", 60))
                .build());
        Basis before = reference.store.latestBasis();
        Transaction tooMuch = transaction().relate("Owns", entity(parties, ALICE), beta, from(3, 1),
                values("share", 50)).build();

        DryRunResult result = reference.store.dryRun(tooMuch);

        assertThat(result).isInstanceOf(DryRunResult.Rejected.class);
        assertThat(((DryRunResult.Rejected) result).problems()).isEqualTo(reference.reject(tooMuch));
        assertThat(reference.store.latestBasis()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ failing rules

    @Test
    void aRuleOfARelationshipThatThrowsIsAFailureOfTheRuleAndTheOtherRelationshipsAreStillChecked() {
        TestRelationshipRule picky = TestRelationshipRule.of("picky", "Employs", (version, context) -> {
            if (title(version).equals("Bad")) {
                throw new IllegalArgumentException("no good");
            }
            context.problem("odd", "Reported for " + title(version) + ".");
        }, "odd");
        ReferenceStore reference = storeWith(picky);
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        Basis before = reference.store.latestBasis();

        List<Problem> problems = reference.reject(transaction()
                .relate(EMPLOYS, "Employs", beta, alice, from(3, 1), values("title", "Bad"))
                .relate(OTHER_EMPLOYS, "Employs", entity(parties, ACME), alice, from(6, 1),
                        values("title", "Fine")).build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.RULE_FAILED, "$employs"), tuple(ProblemCode.RULE_VIOLATION, "$otherEmploys"));
        Problem failed = problems.getFirst();
        assertThat(failed.found()).contains("no good");
        assertThat(failed.message()).contains("picky").contains("IllegalArgumentException");
        assertThat(reference.store.latestBasis()).isEqualTo(before);
    }

    @Test
    void aRuleOfARelationshipThatTriesToWriteIsAFailureOfTheRuleAndTheStoreIsUnchanged() {
        AtomicReference<Store> self = new AtomicReference<>();
        TestRelationshipRule writer = TestRelationshipRule.of("writer", "Employs", (version, context) ->
                self.get().commit(transaction().create("Organization", from(1, 1), values("name", "Inner")).build()));
        ReferenceStore reference = storeWith(writer);
        self.set(reference.store);
        Receipt parties = reference.createParties();
        Basis before = reference.store.latestBasis();

        Problem problem = only(reference.reject(transaction()
                .relate(EMPLOYS, "Employs", entity(parties, BETA), entity(parties, ALICE), from(3, 1), values())
                .build()));

        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_FAILED);
        assertThat(problem.path()).isEqualTo("$employs");
        assertThat(problem.message()).contains("writer");
        assertThat(reference.store.latestBasis()).isEqualTo(before);
        assertThat(reference.view(on(2, 1)).entities("Organization")).hasSize(2);
    }

    // ------------------------------------------------------------------ total-ownership-at-most-100

    private final ReferenceStore owners = new ReferenceStore();
    private EntityId acme;
    private EntityId beta;
    private EntityId gamma;

    /** ACME, Beta and Alice (RD-1) and Gamma from 1 January. */
    private void theOwners() {
        Receipt parties = owners.createParties();
        acme = entity(parties, ACME);
        beta = entity(parties, BETA);
        gamma = entity(owners.commit(transaction().create(GAMMA, "Organization", from(1, 1),
                values("name", "Gamma")).build()), GAMMA);
    }

    private Transaction ownership(EntityId owner, EffectivePeriod period, Object share) {
        return transaction().relate("Owns", owner, beta, period, values("share", share)).build();
    }

    private static Problem violation(List<Problem> problems) {
        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).contains("total-ownership-at-most-100/over-100-percent");
        return problem;
    }

    @Test
    void ownersMayHoldExactly100PercentButNotMore() {
        theOwners();
        owners.commit(ownership(acme, from(2, 1), 55));
        owners.commit(ownership(gamma, from(2, 1), 45));
        EntityId delta = entity(owners.commit(transaction().create(DELTA, "Organization", from(1, 1),
                values("name", "Delta")).build()), DELTA);

        Problem tooMuch = violation(owners.reject(transaction().relate(OWNS, "Owns", delta, beta, from(2, 1),
                values("share", new BigDecimal("0.01"))).build()));

        assertThat(tooMuch.message()).contains("100.01");
        assertThat(tooMuch.path()).isEqualTo("$owns");
        assertThat(owners.view(on(3, 1)).navigate(beta, "ownedBy")).hasSize(2);
    }

    @Test
    void ownersThatNeverOverlapDoNotAddUp() {
        theOwners();
        owners.commit(ownership(acme, EffectivePeriod.between(on(2, 1), on(6, 1)), 60));

        owners.commit(ownership(gamma, from(6, 1), 60));

        assertThat(owners.view(on(5, 31)).navigate(beta, "ownedBy")).hasSize(1);
        assertThat(owners.view(on(6, 1)).navigate(beta, "ownedBy")).hasSize(1);
    }

    @Test
    void anOwnershipThatHasEndedDoesNotCount() {
        theOwners();
        RelationshipId acmeOwns = relationship(owners.commit(transaction()
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 60)).build()), OWNS);
        owners.commit(transaction().endDate(acmeOwns, on(4, 1)).build());

        owners.commit(ownership(gamma, from(4, 1), 60));

        assertThat(owners.view(on(5, 1)).navigate(beta, "ownedBy")).extracting(link -> link.entity().id())
                .containsExactly(gamma);
    }

    @Test
    void anOwnershipThatStartsInsideTheOtherOnesPeriodIsCheckedWhereItOverlaps() {
        theOwners();
        owners.commit(ownership(acme, from(2, 1), 55));

        Problem problem = violation(owners.reject(ownershipBetween(gamma, on(3, 1), on(4, 1), 50)));

        assertThat(problem.message()).contains("105").contains(on(3, 1).toString());
        owners.commit(ownershipBetween(gamma, on(3, 1), on(4, 1), 45));
    }

    private Transaction ownershipBetween(EntityId owner, Instant from, Instant until, int share) {
        return ownership(owner, EffectivePeriod.between(from, until), share);
    }

    @Test
    void aChangeOfAShareAtALaterInstantIsCheckedAtThatInstant() {
        theOwners();
        RelationshipId acmeOwns = relationship(owners.commit(transaction()
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 40)).build()), OWNS);
        owners.commit(ownership(gamma, from(2, 1), 50));

        Problem problem = violation(owners.reject(transaction()
                .patch(acmeOwns, from(7, 1), values("share", 60)).build()));

        assertThat(problem.message()).contains("110").contains(on(7, 1).toString());
        assertThat(problem.path()).isEqualTo(acmeOwns.value());
        owners.commit(transaction().patch(acmeOwns, from(7, 1), values("share", 50)).build());
    }

    @Test
    void ownersThatAreRelatedInTheSameTransactionAreCheckedTogether() {
        theOwners();

        List<Problem> problems = owners.reject(transaction()
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 60))
                .relate(OTHER_OWNS, "Owns", gamma, beta, from(2, 1), values("share", 60)).build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.RULE_VIOLATION, "$owns"), tuple(ProblemCode.RULE_VIOLATION, "$otherOwns"));
        owners.commit(transaction()
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 60))
                .relate(OTHER_OWNS, "Owns", gamma, beta, from(2, 1), values("share", 40)).build());
    }

    @Test
    void lowerSharesAndEndingAnOwnershipNeverBreakTheRule() {
        theOwners();
        RelationshipId acmeOwns = relationship(owners.commit(transaction()
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 60)).build()), OWNS);
        owners.commit(ownership(gamma, from(2, 1), 40));

        owners.commit(transaction().patch(acmeOwns, from(2, 1), values("share", 30)).build());
        owners.commit(transaction().endDate(acmeOwns, on(8, 1)).build());

        assertThat(owners.view(on(9, 1)).navigate(beta, "ownedBy")).hasSize(1);
    }

    @Test
    void theOwnersOfOnePartyDoNotCountForAnother() {
        theOwners();
        owners.commit(ownership(acme, from(2, 1), 90));

        owners.commit(transaction().relate("Owns", gamma, entity(owners.parties, ALICE), from(2, 1),
                values("share", 90)).build());
        owners.commit(transaction().relate("Owns", acme, gamma, from(2, 1), values("share", 100)).build());

        assertThat(owners.view(on(3, 1)).navigate(gamma, "ownedBy")).hasSize(1);
    }

    @Test
    void theRuleIsTheOneOfTheReferenceDomain() {
        Store store = owners.store;

        assertThat(store.schema().availableRules()).contains(TotalOwnershipRule.DESCRIPTOR);
        assertThat(store.schema().current().relationshipType("Owns").orElseThrow().effectiveRules())
                .extracting(rule -> rule.rule().name()).containsExactly("total-ownership-at-most-100");
        assertThat(ReferenceModel.executableRules()).extracting(rule -> rule.descriptor().name())
                .contains("total-ownership-at-most-100");
    }
}
