package io.github.enricoag1982.cairn.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The classification a derived attribute must have is checked over the types of every origin that a registration affects
 * (ADR-0036, design D1 of {@code add-attribute-access-control}), not only over those of the origin that is registered: a
 * registry validates the definition of an origin together with the definitions of the others, compiles each derived
 * attribute for every concrete type that has it, whoever declares the type, and so finds a derived attribute that would
 * reveal a classified one whichever origin classified the attribute, declared the derived attribute or inherited it.
 */
class CrossOriginClassificationTest {

    private static final Origin BASE_1 = new Origin("base", "1.0.0");
    private static final Origin BASE_2 = new Origin("base", "2.0.0");
    private static final Origin EXT_1 = new Origin("ext", "1.0.0");

    private final SchemaRegistry registry = SchemaRegistries.inMemory(new TestClock(), List.of(),
            Optional.of(ExpressionLanguages.discovered().orElseThrow()));

    /** The origin base: a Person with a salary, which is classified {@code pay} if asked. */
    private static SchemaDefinition base(boolean classified) {
        return SchemaDefinition.builder()
                .entityType("Person", person -> person
                        .attribute("name", ValueType.STRING)
                        .attribute("salary", ValueType.INT, salary -> {
                            if (classified) {
                                salary.classification("pay");
                            }
                        }))
                .build();
    }

    /** The origin ext: an Employee, a Person with a derived attribute that reads the salary of the Person. */
    private static SchemaDefinition ext(String classification) {
        return SchemaDefinition.builder()
                .entityType("Employee", employee -> employee.supertype("Person")
                        .attribute("doubled", ValueType.INT, doubled -> {
                            doubled.derived("self.salary * 2");
                            if (classification != null) {
                                doubled.classification(classification);
                            }
                        }))
                .build();
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    @Test
    void classifyingWhatAnotherOriginsDerivedAttributeReadsIsRefusedAtTheDerivedAttribute() {
        assertThat(registry.register(BASE_1, base(false))).isInstanceOf(RegistrationResult.Applied.class);
        assertThat(registry.register(EXT_1, ext(null))).isInstanceOf(RegistrationResult.Applied.class);

        List<Problem> problems = registry.validate(BASE_2, base(true));
        RegistrationResult result = registry.register(BASE_2, base(true));

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
        assertThat(problem.path()).as("the derived attribute of the other origin").isEqualTo("Employee.doubled");
        assertThat(problem.message()).contains("salary").contains("pay");
        assertThat(result).isInstanceOfSatisfying(RegistrationResult.Rejected.class,
                rejected -> assertThat(rejected.problems()).isEqualTo(problems));
        assertThat(registry.current().entityType("Person").orElseThrow().attribute("salary").orElseThrow()
                .classification()).isEmpty();
    }

    @Test
    void aDerivedAttributeOfAnotherOriginThatReadsAnAttributeClassifiedBeforeIsRefused() {
        assertThat(registry.register(BASE_1, base(true))).isInstanceOf(RegistrationResult.Applied.class);

        List<Problem> unclassified = registry.validate(EXT_1, ext(null));
        List<Problem> wrong = registry.validate(EXT_1, ext("other"));
        List<Problem> matching = registry.validate(EXT_1, ext("pay"));

        assertThat(only(unclassified).code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
        assertThat(only(unclassified).path()).isEqualTo("Employee.doubled");
        assertThat(only(wrong).code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
        assertThat(matching).isEmpty();
        assertThat(registry.register(EXT_1, ext("pay"))).isInstanceOf(RegistrationResult.Applied.class);
    }

    @Test
    void aTraitOfOneOriginWhoseDerivedAttributeReadsAnAttributeOfATypeOfAnotherIsCheckedWhereTheyMeet() {
        SchemaDefinition traits = SchemaDefinition.builder()
                .trait("Shouting", trait -> trait.attribute("shout", ValueType.STRING, shout ->
                        shout.derived("self.title + '!'")))
                .build();
        SchemaDefinition people = SchemaDefinition.builder()
                .entityType("Speaker", speaker -> speaker.includes("Shouting")
                        .attribute("title", ValueType.STRING, title -> title.classification("pay")))
                .build();
        assertThat(registry.register(new Origin("traits", "1.0.0"), traits)).isInstanceOf(RegistrationResult.Applied.class);

        List<Problem> problems = registry.validate(new Origin("people", "1.0.0"), people);

        assertThat(problems).extracting(Problem::code).contains(ProblemCode.INVALID_CLASSIFICATION);
        assertThat(problems).extracting(Problem::path).contains("Shouting.shout");
    }
}
