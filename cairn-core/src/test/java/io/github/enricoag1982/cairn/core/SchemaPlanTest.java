package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.Results.applied;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeEntityType;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeTrait;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withAttributes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaChange;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaPlan;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Planning a registration, and that it agrees with registering. */
class SchemaPlanTest {

    private static final SchemaDefinition BASE = ReferenceModel.definition();

    private TestClock clock;
    private SchemaRegistry registry;

    @BeforeEach
    void createRegistry() {
        clock = new TestClock();
        registry = SchemaRegistries.inMemory(clock, ReferenceModel.rules());
    }

    private static SchemaDefinition withEmail() {
        return withPersonAttribute("email");
    }

    private static SchemaDefinition withPersonAttribute(String name) {
        return changeEntityType(BASE, "Person", type -> {
            List<AttributeDefinition> attributes = new ArrayList<>(type.attributes());
            attributes.add(AttributeDefinition.builder(name, ValueType.STRING).build());
            return withAttributes(type, attributes);
        });
    }

    private static SchemaDefinition withoutBirthDate() {
        return changeEntityType(BASE, "Person", type -> withAttributes(type, List.of()));
    }

    private static Origin v(String version) {
        return ReferenceModel.origin(version);
    }

    @Test
    void planningAFirstRegistrationListsEveryTypeAsACompatibleChange() {
        SchemaPlan plan = registry.plan(v("1.0.0"), BASE);

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.APPLY);
        assertThat(plan.problems()).isEmpty();
        assertThat(plan.changes()).allMatch(SchemaChange::compatible);
        assertThat(plan.changes()).map(SchemaChange::path).containsExactlyInAnyOrder("Named", "Party", "Person",
                "Organization", "Address", "Account", "CustomerRole", "Owns", "Employs", "HeldBy", "HasAddress",
                "PlayedBy");
    }

    @Test
    void aCompatibleChangeIsPlannedWithoutRecordingAnything() {
        applied(registry.register(v("1.0.0"), BASE));
        clock.advance(Duration.ofDays(1));
        int transactions = registry.history().size();
        var current = registry.current();

        SchemaPlan plan = registry.plan(v("1.1.0"), withEmail());

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.APPLY);
        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("Person.email");
            assertThat(change.compatible()).isTrue();
            assertThat(change.hint()).isEmpty();
        });
        assertThat(plan.problems()).isEmpty();
        assertThat(registry.history()).hasSize(transactions);
        assertThat(registry.current()).isSameAs(current);
    }

    @Test
    void aBreakingChangeIsPlannedWithTheProblemRegisteringWouldReport() {
        applied(registry.register(v("1.0.0"), BASE));

        SchemaPlan plan = registry.plan(v("1.1.0"), withoutBirthDate());

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.REJECT);
        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("Person.birthDate");
            assertThat(change.compatible()).isFalse();
            assertThat(change.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("deprecated", "alias"));
        });
        assertThat(plan.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.BREAKING_CHANGE);
            assertThat(problem.path()).isEqualTo("Person.birthDate");
            assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("deprecated", "alias"));
        });
    }

    @Test
    void aPlanListsCompatibleAndBreakingChangesTogether() {
        applied(registry.register(v("1.0.0"), BASE));
        SchemaDefinition both = changeEntityType(withEmail(), "Person", type -> {
            List<AttributeDefinition> attributes = new ArrayList<>(type.attributes());
            attributes.removeIf(attribute -> attribute.name().equals("birthDate"));
            return withAttributes(type, attributes);
        });

        SchemaPlan plan = registry.plan(v("1.1.0"), both);

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.REJECT);
        assertThat(plan.changes()).hasSize(2);
        assertThat(plan.changes()).filteredOn(SchemaChange::compatible).singleElement()
                .satisfies(change -> assertThat(change.path()).isEqualTo("Person.email"));
        assertThat(plan.problems()).singleElement()
                .satisfies(problem -> assertThat(problem.path()).isEqualTo("Person.birthDate"));
    }

    @Test
    void registeringTheSameDefinitionAgainIsPlannedAsUnchanged() {
        applied(registry.register(v("1.0.0"), BASE));

        SchemaPlan plan = registry.plan(v("1.0.0"), BASE);

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.UNCHANGED);
        assertThat(plan.changes()).isEmpty();
        assertThat(plan.problems()).isEmpty();
    }

    @Test
    void anInvalidDefinitionIsPlannedAsRejectedWithTheValidationProblems() {
        SchemaDefinition invalid = SchemaDefinition.builder().entityType("Organization", e -> e.supertype("Prty"))
                .entityType("Party").build();

        SchemaPlan plan = registry.plan(v("1.0.0"), invalid);

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.REJECT);
        assertThat(plan.changes()).isEmpty();
        assertThat(plan.problems()).isEqualTo(registry.validate(v("1.0.0"), invalid)).hasSize(1);
        assertThat(plan.problems().getFirst().hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Party"));
    }

    @Test
    void aChangedDefinitionWithoutAHigherVersionIsPlannedAsRejectedAndStillListsItsChanges() {
        applied(registry.register(v("1.0.0"), BASE));

        SchemaPlan plan = registry.plan(v("1.0.0"), withEmail());

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.REJECT);
        assertThat(plan.problems()).singleElement()
                .satisfies(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.VERSION_NOT_INCREASED));
        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("Person.email");
            assertThat(change.compatible()).isTrue();
        });
    }

    @Test
    void anEarlierVersionIsPlannedAsUnchangedWithTheChangesOfTheReversedComparison() {
        applied(registry.register(v("1.0.0"), BASE));
        applied(registry.register(v("1.1.0"), withEmail()));

        SchemaPlan plan = registry.plan(v("1.0.0"), BASE);

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.UNCHANGED);
        assertThat(plan.problems()).isEmpty();
        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("Person.email");
            assertThat(change.compatible()).isTrue();
        });
    }

    @Test
    void anEarlierVersionThatTheStoredOneDidNotEvolveFromCompatiblyIsPlannedAsRejected() {
        applied(registry.register(v("1.0.0"), BASE));
        applied(registry.register(v("1.1.0"), withEmail()));

        SchemaPlan plan = registry.plan(v("1.0.5"), withPersonAttribute("nickname"));

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.REJECT);
        assertThat(plan.problems()).hasSize(2).allMatch(problem -> problem.code() == ProblemCode.BREAKING_CHANGE);
        assertThat(plan.problems()).extracting(Problem::path).containsExactly("@origin", "Person.nickname");
        assertThat(plan.changes()).extracting(SchemaChange::path, SchemaChange::compatible)
                .containsExactlyInAnyOrder(tuple("Person.nickname", false), tuple("Person.email", true));
    }

    @Test
    void theProblemsOfARejectedRollbackCarryNoHints() {
        applied(registry.register(v("1.0.0"), BASE));
        applied(registry.register(v("1.1.0"), withEmail()));
        SchemaDefinition older = withPersonAttribute("nickname");

        SchemaPlan plan = registry.plan(v("1.0.5"), older);

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.REJECT);
        assertThat(plan.problems()).isNotEmpty().allSatisfy(problem -> assertThat(problem.hint()).isEmpty());
        List<Problem> registered = ((RegistrationResult.Rejected) registry.register(v("1.0.5"), older)).problems();
        assertThat(registered).isEqualTo(plan.problems());
    }

    @Test
    void theProblemsOfARejectedForwardRegistrationKeepTheirHints() {
        applied(registry.register(v("1.0.0"), BASE));

        List<Problem> problems = ((RegistrationResult.Rejected) registry.register(v("1.1.0"), withoutBirthDate()))
                .problems();

        assertThat(problems).singleElement().satisfies(problem -> assertThat(problem.hint()).isPresent());
    }

    private static SchemaDefinition withNicknameOnNamed(boolean required) {
        return changeTrait(BASE, "Named", trait -> {
            List<AttributeDefinition> attributes = new ArrayList<>(trait.attributes());
            attributes.add(required
                    ? AttributeDefinition.builder("nickname", ValueType.STRING).required().build()
                    : AttributeDefinition.builder("nickname", ValueType.STRING).build());
            return new TraitDefinition(trait.name(), attributes, trait.aliases(), trait.deprecated(), trait.description());
        });
    }

    @Test
    void anAttributeAddedToATraitThatTypesIncludeIsListedOnceAtTheTrait() {
        applied(registry.register(v("1.0.0"), BASE));

        SchemaPlan plan = registry.plan(v("1.1.0"), withNicknameOnNamed(false));

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.APPLY);
        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("Named.nickname");
            assertThat(change.compatible()).isTrue();
        });
    }

    @Test
    void aRequiredAttributeAddedToATraitThatExistingTypesIncludeIsStillBreakingAndListedOnce() {
        applied(registry.register(v("1.0.0"), BASE));

        SchemaPlan plan = registry.plan(v("1.1.0"), withNicknameOnNamed(true));

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.REJECT);
        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("Named.nickname");
            assertThat(change.compatible()).isFalse();
        });
        assertThat(plan.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.BREAKING_CHANGE);
            assertThat(problem.path()).isEqualTo("Named.nickname");
        });
    }

    @Test
    void anAttributeAddedToAnAbstractTypeWithConcreteSubtypesIsListedOnceAtTheType() {
        applied(registry.register(v("1.0.0"), BASE));
        SchemaDefinition withNickname = changeEntityType(BASE, "Party", type -> {
            List<AttributeDefinition> attributes = new ArrayList<>(type.attributes());
            attributes.add(AttributeDefinition.builder("nickname", ValueType.STRING).build());
            return withAttributes(type, attributes);
        });

        SchemaPlan plan = registry.plan(v("1.1.0"), withNickname);

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.APPLY);
        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("Party.nickname");
            assertThat(change.compatible()).isTrue();
        });
    }

    @Test
    void anAttributeAddedToAnAbstractTypeWithoutConcreteSubtypesIsListedAsCompatible() {
        SchemaRegistry fresh = SchemaRegistries.inMemory(new TestClock());
        applied(fresh.register(v("1.0.0"), SchemaDefinition.builder().entityType("Lonely", e -> e.abstractType()).build()));
        SchemaDefinition withRequired = SchemaDefinition.builder()
                .entityType("Lonely", e -> e.abstractType().attribute("x", ValueType.STRING, a -> a.required()))
                .build();

        SchemaPlan plan = fresh.plan(v("1.1.0"), withRequired);

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.APPLY);
        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("Lonely.x");
            assertThat(change.compatible()).isTrue();
        });
        applied(fresh.register(v("1.1.0"), withRequired));
    }

    @Test
    void anAttributeAddedToATraitThatNoTypeIncludesIsListedAsCompatible() {
        SchemaRegistry fresh = SchemaRegistries.inMemory(new TestClock());
        applied(fresh.register(v("1.0.0"), SchemaDefinition.builder().trait("Audited", t -> { }).entityType("Person")
                .build()));

        SchemaPlan plan = fresh.plan(v("1.1.0"), SchemaDefinition.builder()
                .trait("Audited", t -> t.attribute("createdBy", ValueType.STRING, a -> a.required()))
                .entityType("Person").build());

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.APPLY);
        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("Audited.createdBy");
            assertThat(change.compatible()).isTrue();
        });
    }

    @Test
    void anAttributeOfAnAddedTypeIsNotListedSeparately() {
        applied(registry.register(v("1.0.0"), BASE));
        SchemaDefinition withContract = SchemaDefinition.builder()
                .entityType("Contract", e -> e.attribute("signed", ValueType.DATE, a -> a.required()))
                .trait("Audited", t -> t.attribute("createdBy", ValueType.STRING))
                .build();
        SchemaDefinition combined = new SchemaDefinition(
                java.util.stream.Stream.concat(BASE.entityTypes().stream(), withContract.entityTypes().stream()).toList(),
                java.util.stream.Stream.concat(BASE.traits().stream(), withContract.traits().stream()).toList(),
                BASE.relationshipTypes());

        SchemaPlan plan = registry.plan(v("1.1.0"), combined);

        assertThat(plan.changes()).extracting(SchemaChange::path).containsExactlyInAnyOrder("Contract", "Audited");
    }

    @Test
    void aPlanAndARegistrationOfTheSameInputAgree() {
        applied(registry.register(v("1.0.0"), BASE));
        applied(registry.register(v("1.1.0"), withEmail()));
        SchemaDefinition invalid = SchemaDefinition.builder().entityType("1Bad").build();
        List<Object[]> inputs = List.of(
                new Object[] {v("1.2.0"), withEmail()},                        // unchanged definition, higher version
                new Object[] {v("1.2.0"), BASE},                               // drops email: breaking
                new Object[] {v("1.1.0"), withoutBirthDate()},                 // same version, different definition
                new Object[] {v("1.0.0"), BASE},                               // earlier version, evolved compatibly
                new Object[] {v("1.0.0"), withoutBirthDate()},                 // earlier version, not evolved from
                new Object[] {v("1.2.0"), invalid},                            // invalid
                new Object[] {new Origin("other", "1.0.0"), SchemaDefinition.builder().entityType("Contract").build()},
                new Object[] {new Origin("bad name", "1"), SchemaDefinition.EMPTY});

        for (Object[] input : inputs) {
            Origin origin = (Origin) input[0];
            SchemaDefinition definition = (SchemaDefinition) input[1];
            SchemaPlan plan = registry.plan(origin, definition);
            int transactions = registry.history().size();

            RegistrationResult result = registry.register(origin, definition);

            switch (plan.outcome()) {
                case APPLY -> {
                    assertThat(result).as("%s %s", origin, plan).isInstanceOf(RegistrationResult.Applied.class);
                    assertThat(registry.history()).hasSize(transactions + 1);
                }
                case UNCHANGED -> {
                    assertThat(result).as("%s %s", origin, plan).isInstanceOf(RegistrationResult.Unchanged.class);
                    assertThat(registry.history()).hasSize(transactions);
                }
                case REJECT -> {
                    assertThat(result).as("%s %s", origin, plan).isInstanceOf(RegistrationResult.Rejected.class);
                    assertThat(((RegistrationResult.Rejected) result).problems()).isEqualTo(plan.problems());
                    assertThat(registry.history()).hasSize(transactions);
                }
            }
        }
    }

    @Test
    void planningDoesNotUseUpIdentifiers() {
        SchemaRegistry planned = SchemaRegistries.inMemory(new TestClock(), ReferenceModel.rules());
        planned.plan(v("1.0.0"), BASE);
        planned.plan(v("1.0.0"), BASE);
        var withPlans = applied(planned.register(v("1.0.0"), BASE)).schema();
        var without = applied(SchemaRegistries.inMemory(new TestClock(), ReferenceModel.rules())
                .register(v("1.0.0"), BASE)).schema();

        assertThat(withPlans.entityType("Person").orElseThrow().id()).isEqualTo(without.entityType("Person").orElseThrow().id());
        assertThat(withPlans.entityType("Person").orElseThrow().attribute("birthDate").orElseThrow().id())
                .isEqualTo(without.entityType("Person").orElseThrow().attribute("birthDate").orElseThrow().id());
    }

    @Test
    void breakingChangesToTightenedConstraintsCarryAHint() {
        applied(registry.register(v("1.0.0"), BASE));
        SchemaDefinition tightened = changeEntityType(BASE, "Address", type -> withAttributes(type, type.attributes().stream()
                .map(attribute -> attribute.name().equals("country")
                        ? new AttributeDefinition(attribute.name(), attribute.valueType(), attribute.required(),
                                attribute.unique(), attribute.range(), attribute.aliases(), attribute.deprecated(),
                                attribute.description(), attribute.examples(), attribute.allowedValues(),
                                java.util.Optional.of("[A-Z]{2}"), attribute.minLength(), attribute.maxLength())
                        : attribute)
                .toList()));

        SchemaPlan plan = registry.plan(v("1.1.0"), tightened);

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.REJECT);
        assertThat(plan.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.path()).isEqualTo("Address.country");
            assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("optional", "unconstrained"));
        });
        List<Problem> registered = ((RegistrationResult.Rejected) registry.register(v("1.1.0"), tightened)).problems();
        assertThat(registered).isEqualTo(plan.problems());
    }
}
