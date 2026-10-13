package io.github.enricoag1982.cairn.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The problem codes of requests of AI agents (design D4 of {@code add-agent-tools}). */
class AgentProblemCodesTest {

    private static final List<String> CODES = List.of("APPROVAL_DENIED", "PRINCIPAL_MISMATCH");

    @Test
    void everyCodeOfTheDesignExistsAndIsExplained() {
        assertThat(CODES).allSatisfy(name -> assertThat(ProblemCode.valueOf(name).explanation())
                .as(name).isNotBlank().endsWith("."));
    }
}
