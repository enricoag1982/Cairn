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

    @Test
    void theCodesThatAgentToolsReuseSayHowTheToolsUseThem() {
        List<String> reused = List.of("UNKNOWN_ENTITY", "UNKNOWN_RELATIONSHIP", "UNKNOWN_ENTITY_TYPE",
                "UNKNOWN_RELATIONSHIP_TYPE", "MISSING_KEY", "UNKNOWN_KEY", "INVALID_VALUE", "DOCUMENT_SYNTAX");

        assertThat(reused).allSatisfy(name -> assertThat(ProblemCode.valueOf(name).explanation())
                .as(name).contains("An agent tool reports it too").endsWith("."));
    }
}
