package io.github.enricoag1982.cairn.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Who and why of a schema registration (design D6 of {@code add-agent-tools}). */
class SubmissionTest {

    @Test
    void aSubmissionKeepsItsComponents() {
        Submission submission = new Submission("agent:modeler", Optional.of("alice"), Optional.of("Contact data"));

        assertThat(submission.principal()).isEqualTo("agent:modeler");
        assertThat(submission.onBehalfOf()).contains("alice");
        assertThat(submission.reason()).contains("Contact data");
    }

    @Test
    void byNamesOnlyThePrincipal() {
        assertThat(Submission.by("agent:modeler"))
                .isEqualTo(new Submission("agent:modeler", Optional.empty(), Optional.empty()));
    }

    @Test
    void unknownIsThePrincipalAtUnknown() {
        assertThat(Submission.unknown().principal()).isEqualTo("@unknown").isEqualTo(Submission.UNKNOWN_PRINCIPAL);
        assertThat(Submission.unknown().onBehalfOf()).isEmpty();
        assertThat(Submission.unknown().reason()).isEmpty();
    }

    @Test
    void nullComponentsAreRejected() {
        assertThatThrownBy(() -> new Submission(null, Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Submission("p", null, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Submission("p", Optional.empty(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aBlankPrincipalIsRejected() {
        assertThatThrownBy(() -> Submission.by("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Submission.by("  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
