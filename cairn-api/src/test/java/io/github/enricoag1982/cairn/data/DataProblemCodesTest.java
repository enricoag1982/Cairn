package io.github.enricoag1982.cairn.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.ProblemCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The problem codes of data transactions (design D6 of {@code add-entity-versions}, D4 of {@code add-entity-rules} and
 * D3 of {@code add-relationships}).
 */
class DataProblemCodesTest {

    private static final List<String> CODES = List.of(
            "UNKNOWN_ENTITY_TYPE", "ABSTRACT_ENTITY_TYPE", "UNKNOWN_ATTRIBUTE", "VALUE_TYPE_MISMATCH",
            "VALUE_OUT_OF_RANGE", "VALUE_NOT_ALLOWED", "VALUE_PATTERN_MISMATCH", "VALUE_LENGTH",
            "MISSING_REQUIRED_VALUE", "DEPRECATED_ATTRIBUTE", "UNIQUE_VALUE_TAKEN", "UNKNOWN_ENTITY",
            "ENTITY_NOT_EFFECTIVE", "ENTITY_EXISTS", "INVALID_ENTITY_ID", "UNKNOWN_TEMPORARY_ID",
            "DUPLICATE_TEMPORARY_ID", "INVALID_PERIOD", "CONFLICT", "MISSING_PRINCIPAL", "IDEMPOTENCY_KEY_REUSED",
            "RULE_VIOLATION", "RULE_FAILED", "RULE_UNAVAILABLE", "UNKNOWN_RELATIONSHIP_TYPE", "WRONG_END_TYPE",
            "END_NOT_EFFECTIVE", "RELATIONSHIP_OUTLIVES_END", "TOO_FEW_RELATIONSHIPS", "TOO_MANY_RELATIONSHIPS",
            "DUPLICATE_RELATIONSHIP", "UNKNOWN_RELATIONSHIP");

    @Test
    void everyCodeOfTheDesignExistsAndIsExplained() {
        assertThat(CODES).allSatisfy(name -> assertThat(ProblemCode.valueOf(name).explanation())
                .as(name).isNotBlank().endsWith("."));
    }
}
