package io.github.enricoag1982.cairn.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import java.util.List;

/** Assertions on registration results and problems. */
final class Results {

    private Results() {
    }

    static RegistrationResult.Applied applied(RegistrationResult result) {
        assertThat(result).as("registration result").isInstanceOf(RegistrationResult.Applied.class);
        return (RegistrationResult.Applied) result;
    }

    static RegistrationResult.Unchanged unchanged(RegistrationResult result) {
        assertThat(result).as("registration result").isInstanceOf(RegistrationResult.Unchanged.class);
        return (RegistrationResult.Unchanged) result;
    }

    static List<Problem> rejected(RegistrationResult result) {
        assertThat(result).as("registration result").isInstanceOf(RegistrationResult.Rejected.class);
        return ((RegistrationResult.Rejected) result).problems();
    }

    /** Tells whether a problem concerns a type or one of its members. */
    static boolean locatedAt(Problem problem, String type) {
        return problem.path().equals(type) || problem.path().startsWith(type + ".");
    }

    static void assertSingleProblem(List<Problem> problems, ProblemCode code, String type) {
        assertThat(problems).as("problems").singleElement()
                .satisfies(problem -> assertThat(problem.code()).isEqualTo(code))
                .satisfies(problem -> assertThat(locatedAt(problem, type)).as("located at " + type + ": " + problem).isTrue());
    }
}
