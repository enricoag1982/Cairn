package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.Results.applied;
import static io.github.enricoag1982.cairn.core.Results.rejected;
import static io.github.enricoag1982.cairn.core.Results.unchanged;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeEntityType;
import static io.github.enricoag1982.cairn.fixtures.Definitions.plus;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withCheck;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaChange;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaPlan;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * How checks evolve, beyond the scenarios of the {@code schema-evolution} spec (design D5 of
 * {@code add-cel-expressions}).
 */
class CheckEvolutionTest {

    private static final String NON_NEGATIVE = "self.balance >= 0.0";

    private final TestClock clock = new TestClock();
    private final SchemaRegistry registry = SchemaRegistries.inMemory(clock, ReferenceModel.rules());

    private RegistrationResult register(String version, SchemaDefinition definition) {
        clock.advance(Duration.ofMinutes(1));
        return registry.register(ReferenceModel.origin(version), definition);
    }

    private static SchemaDefinition account(String message) {
        return withCheck(ReferenceModel.definition(), "Account", "balance-not-negative", NON_NEGATIVE, message);
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    @Test
    void aCheckOnANewTypeIsCompatible() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition withNewType = plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .entityType("Wallet", e -> e.attribute("balance", ValueType.DECIMAL).check("not-negative", NON_NEGATIVE))
                .build());

        applied(register("1.1.0", withNewType));

        assertThat(registry.current().entityType("Wallet").orElseThrow().checks()).hasSize(1);
    }

    @Test
    void aCheckOnANewSubtypeOfATypeWithChecksIsCompatible() {
        applied(register("1.0.0", withCheck(ReferenceModel.definition(), "Party", "name-set", "size(self.name) > 0",
                null)));
        SchemaDefinition withSubtype = plus(withCheck(ReferenceModel.definition(), "Party", "name-set",
                "size(self.name) > 0", null), SchemaDefinition.builder()
                .entityType("Trust", e -> e.supertype("Party").check("trust-check", "true")).build());

        applied(register("1.1.0", withSubtype));

        assertThat(registry.current().entityType("Trust").orElseThrow().effectiveChecks()).hasSize(2);
    }

    @Test
    void aCheckOnAnExistingAbstractTypeIsBreaking() {
        applied(register("1.0.0", ReferenceModel.definition()));

        Problem problem = only(rejected(register("1.1.0", withCheck(ReferenceModel.definition(), "Party", "name-set",
                "size(self.name) > 0", null))));

        assertThat(problem.code()).isEqualTo(ProblemCode.BREAKING_CHANGE);
        assertThat(problem.path()).isEqualTo("Party.name-set");
        assertThat(problem.hint()).isPresent();
    }

    @Test
    void movingACheckToASupertypeIsBreakingAndToASubtypeToo() {
        SchemaDefinition onPerson = withCheck(ReferenceModel.definition(), "Person", "name-set", "size(self.name) > 0",
                null);
        SchemaDefinition onParty = withCheck(ReferenceModel.definition(), "Party", "name-set", "size(self.name) > 0",
                null);
        applied(register("1.0.0", onPerson));

        Problem up = only(rejected(register("1.1.0", onParty)));

        assertThat(up.path()).isEqualTo("Party.name-set");
        assertThat(registry.current().entityType("Person").orElseThrow().checks()).hasSize(1);

        SchemaRegistry other = SchemaRegistries.inMemory(clock, ReferenceModel.rules());
        applied(other.register(ReferenceModel.origin("1.0.0"), onParty));
        Problem down = only(rejected(other.register(ReferenceModel.origin("1.1.0"), onPerson)));
        assertThat(down.path()).isEqualTo("Person.name-set");
    }

    @Test
    void changingTheExpressionIsBreakingAndChangingTheMessageIsCompatibleAndTakesEffect() {
        applied(register("1.0.0", account("A balance cannot be negative.")));
        SchemaDefinition otherExpression = withCheck(ReferenceModel.definition(), "Account", "balance-not-negative",
                "self.balance > 0.0", "A balance cannot be negative.");

        Problem problem = only(rejected(register("1.1.0", otherExpression)));
        assertThat(problem.code()).isEqualTo(ProblemCode.BREAKING_CHANGE);
        assertThat(problem.path()).isEqualTo("Account.balance-not-negative");

        applied(register("1.1.0", account("No overdrafts.")));
        assertThat(registry.current().entityType("Account").orElseThrow().checks())
                .containsExactly(new Check("balance-not-negative", NON_NEGATIVE, Optional.of("No overdrafts.")));
    }

    @Test
    void aCheckOnAnExistingRelationshipTypeIsBreaking() {
        applied(register("1.0.0", ReferenceModel.definition()));

        Problem problem = only(rejected(register("1.1.0", withCheck(ReferenceModel.definition(), "Employs",
                "title-not-blank", "!has(self.title) || size(self.title) > 0", null))));

        assertThat(problem.code()).isEqualTo(ProblemCode.BREAKING_CHANGE);
        assertThat(problem.path()).isEqualTo("Employs.title-not-blank");
    }

    @Test
    void theOrderOfTheChecksOfATypeIsNotAChange() {
        SchemaDefinition first = withCheck(account(null), "Account", "has-currency", "has(self.currency)", null);
        SchemaDefinition reversed = changeEntityType(ReferenceModel.definition(), "Account", type -> {
            List<Check> checks = new ArrayList<>(List.of(new Check("has-currency", "has(self.currency)"),
                    new Check("balance-not-negative", NON_NEGATIVE)));
            return new EntityTypeDefinition(type.name(), type.isAbstract(), type.supertype(), type.traits(),
                    type.attributes(), type.aliases(), type.deprecated(), type.description(), type.rules(), checks);
        });
        applied(register("1.0.0", first));

        unchanged(register("1.0.0", reversed));
    }

    @Test
    void aPlanListsTheRemovalOfACheckAsCompatibleAndTheAdditionAsBreaking() {
        applied(register("1.0.0", account(null)));

        SchemaPlan removal = registry.plan(ReferenceModel.origin("1.1.0"), ReferenceModel.definition());
        SchemaPlan addition = registry.plan(ReferenceModel.origin("1.1.0"),
                withCheck(account(null), "Account", "has-currency", "has(self.currency)", null));

        assertThat(removal.outcome()).isEqualTo(SchemaPlan.Outcome.APPLY);
        assertThat(removal.changes()).extracting(SchemaChange::path, SchemaChange::compatible)
                .containsExactly(tuple("Account.balance-not-negative", true));
        assertThat(addition.outcome()).isEqualTo(SchemaPlan.Outcome.REJECT);
        assertThat(addition.problems()).extracting(Problem::path).containsExactly("Account.has-currency");
    }

    @Test
    void aCheckThatIsRemovedAndAddedBackInALaterVersionIsBreakingTheSecondTime() {
        applied(register("1.0.0", account(null)));
        applied(register("1.1.0", ReferenceModel.definition()));

        Problem problem = only(rejected(register("1.2.0", account(null))));

        assertThat(problem.path()).isEqualTo("Account.balance-not-negative");
    }
}
