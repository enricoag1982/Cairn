package io.github.enricoag1982.cairn.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class DocumentLocationTest {

    @Test
    void linesAndColumnsCountFromOne() {
        DocumentLocation location = new DocumentLocation("/entityTypes/Organization", 21, 3);

        assertThat(location.pointer()).isEqualTo("/entityTypes/Organization");
        assertThat(location.line()).isEqualTo(21);
        assertThat(location.column()).isEqualTo(3);
        assertThatThrownBy(() -> new DocumentLocation("", 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentLocation("", 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentLocation(null, 1, 1)).isInstanceOf(NullPointerException.class);
        assertThat(new DocumentLocation("", 1, 1).pointer()).isEmpty();
    }

    @Test
    void readsAsLineColumnAndPointer() {
        assertThat(new DocumentLocation("/cairn", 3, 1)).hasToString("line 3, column 1 (/cairn)");
        assertThat(new DocumentLocation("", 1, 1)).hasToString("line 1, column 1 (document)");
    }

    @Test
    void aProblemHasNoLocationUntilItIsLocated() {
        Problem problem = new Problem(ProblemCode.UNKNOWN_REFERENCE, "Organization", "The supertype 'Prty' does not exist.");
        DocumentLocation location = new DocumentLocation("/entityTypes/Organization", 21, 3);

        Problem located = problem.at(location);

        assertThat(problem.location()).isEmpty();
        assertThat(located.location()).contains(location);
        assertThat(located).usingRecursiveComparison().ignoringFields("location").isEqualTo(problem);
        assertThat(located).isNotEqualTo(problem);
        assertThat(located).hasToString(problem.toString());
    }

    @Test
    void theShorterConstructorsLeaveTheLocationOut() {
        assertThat(new Problem(ProblemCode.INVALID_NAME, "x", "y").location()).isEmpty();
        assertThat(new Problem(ProblemCode.INVALID_NAME, "x", "y", Optional.of("a"), Optional.of("b"), Optional.of("c"))
                .location()).isEmpty();
        assertThatThrownBy(() -> new Problem(ProblemCode.INVALID_NAME, "x", "y", Optional.empty(), Optional.empty(),
                Optional.empty(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Problem(ProblemCode.INVALID_NAME, "x", "y").at(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theDocumentProblemCodesAreExplained() {
        assertThat(ProblemCode.valueOf("DOCUMENT_SYNTAX").explanation()).contains("YAML");
        assertThat(ProblemCode.valueOf("UNSUPPORTED_FORMAT_VERSION").explanation()).contains("cairn: 1");
        assertThat(ProblemCode.valueOf("UNSUPPORTED_YAML_FEATURE").explanation()).contains("anchors");
        assertThat(ProblemCode.valueOf("DUPLICATE_KEY").explanation()).contains("twice");
        assertThat(ProblemCode.valueOf("UNKNOWN_KEY").explanation()).contains("hint");
        assertThat(ProblemCode.valueOf("MISSING_KEY").explanation()).contains("required");
        assertThat(ProblemCode.valueOf("INVALID_VALUE").explanation()).contains("multiplicity");
    }

    @Test
    void aStoredDefinitionHoldsAnOriginAndItsDefinition() {
        StoredDefinition stored = new StoredDefinition(new Origin("reference-model", "1.0.0"), SchemaDefinition.EMPTY);

        assertThat(stored.origin().version()).isEqualTo("1.0.0");
        assertThat(stored.definition()).isEqualTo(SchemaDefinition.EMPTY);
        assertThatThrownBy(() -> new StoredDefinition(null, SchemaDefinition.EMPTY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StoredDefinition(new Origin("x", "1"), null))
                .isInstanceOf(NullPointerException.class);
    }
}
