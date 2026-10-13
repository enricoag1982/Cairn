package io.github.enricoag1982.cairn.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import org.junit.jupiter.api.Test;

class OriginRulesTest {

    @Test
    void versionsCompareNumberByNumber() {
        assertThat(OriginRules.compareVersions("1.10.0", "1.9.0")).isPositive();
        assertThat(OriginRules.compareVersions("1.9.0", "1.10.0")).isNegative();
        assertThat(OriginRules.compareVersions("2", "1.99.99")).isPositive();
        assertThat(OriginRules.compareVersions("1.0", "1.0.0")).isZero();
        assertThat(OriginRules.compareVersions("10.0.0", "9.0.0")).isPositive();
    }

    @Test
    void wellFormedOriginsPass() {
        assertThat(OriginRules.validate(new Origin("reference-model", "1.0.0"))).isEmpty();
        assertThat(OriginRules.validate(new Origin("crm_model.v2", "12"))).isEmpty();
    }

    @Test
    void malformedNamesAndVersionsAreRejected() {
        assertThat(OriginRules.validate(new Origin("", "1.0.0"))).singleElement()
                .satisfies(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_ORIGIN));
        assertThat(OriginRules.validate(new Origin("crm model", "1.0.0"))).hasSize(1);
        assertThat(OriginRules.validate(new Origin("crm", "1.x"))).hasSize(1);
        assertThat(OriginRules.validate(new Origin("crm", "1..0"))).hasSize(1);
        assertThat(OriginRules.validate(new Origin("crm", ""))).hasSize(1);
    }
}
