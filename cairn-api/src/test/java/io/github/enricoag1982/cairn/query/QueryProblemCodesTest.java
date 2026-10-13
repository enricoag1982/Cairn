package io.github.enricoag1982.cairn.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.ProblemCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The problem codes of queries (design D2 of {@code add-queries-and-aggregation}). */
class QueryProblemCodesTest {

    private static final List<String> NEW_CODES = List.of("UNKNOWN_BINDING", "DUPLICATE_BINDING",
            "INCOMPARABLE_VALUES", "INVALID_AGGREGATE");

    @Test
    void everyNewCodeOfTheDesignExistsAndIsExplained() {
        assertThat(NEW_CODES).allSatisfy(name -> assertThat(ProblemCode.valueOf(name).explanation())
                .as(name).isNotBlank().endsWith("."));
    }

    @Test
    void theExplanationsSayWhatTheyAreAbout() {
        assertThat(ProblemCode.UNKNOWN_BINDING.explanation()).contains("binding");
        assertThat(ProblemCode.DUPLICATE_BINDING.explanation()).contains("the same name");
        assertThat(ProblemCode.INCOMPARABLE_VALUES.explanation()).contains("booleans compare for equality only");
        assertThat(ProblemCode.INVALID_AGGREGATE.explanation()).contains("sum or an average needs numbers");
    }

    @Test
    void theCodesThatQueriesReuseSayHowQueriesUseThem() {
        List<String> reused = List.of("UNKNOWN_ENTITY_TYPE", "UNKNOWN_ATTRIBUTE", "UNKNOWN_RELATIONSHIP_TYPE",
                "DOCUMENT_SYNTAX", "UNKNOWN_KEY", "MISSING_KEY", "INVALID_VALUE");

        assertThat(reused).allSatisfy(name -> assertThat(ProblemCode.valueOf(name).explanation())
                .as(name).contains("A query reports it too").endsWith("."));
    }
}
