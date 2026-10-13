package io.github.enricoag1982.cairn.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The rules of the reference domain that have code, and how the model splits its rules into those and the rest. */
class ReferenceRulesTest {

    /** What a rule reported: the code, the attribute if it named one, and the message. */
    private record Report(String code, String attribute, String message) {
    }

    /** A context that only collects what the rule reports. */
    private static final class Reports implements RuleContext {
        final List<Report> reports = new ArrayList<>();

        @Override
        public View view() {
            throw new UnsupportedOperationException();
        }

        @Override
        public View viewAt(Instant effectiveAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void problem(String code, String attribute, String message) {
            reports.add(new Report(code, attribute, message));
        }

        @Override
        public void problem(String code, String message) {
            reports.add(new Report(code, null, message));
        }
    }

    private static List<Report> check(EntityRule rule, String type, Map<String, Value> values) {
        EntityState version = new EntityState(new EntityId("e1"), type, values,
                EffectivePeriod.from(Instant.parse("2026-01-01T00:00:00Z")), Instant.parse("2026-01-05T09:00:00Z"),
                new Basis(1));
        Reports context = new Reports();
        rule.check(version, context);
        return context.reports;
    }

    private static Map<String, Value> values(Object... namesAndValues) {
        Map<String, Value> values = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            values.put((String) namesAndValues[i], Value.of((String) namesAndValues[i + 1]));
        }
        return values;
    }

    @Test
    void theCheckDigitsOfRealLeisAreValid() {
        assertThat(LeiChecksumRule.hasValidCheckDigits(ReferenceStories.LEI)).isTrue();
        assertThat(LeiChecksumRule.hasValidCheckDigits("HWUPKR0MPOU8FGXBT394")).isTrue();
        assertThat(LeiChecksumRule.hasValidCheckDigits("INR2EJN1ERAN0W5ZP974")).isTrue();
    }

    @Test
    void wrongCheckDigitsAndTextThatIsNoLeiAreInvalid() {
        assertThat(LeiChecksumRule.hasValidCheckDigits(ReferenceStories.LEI_WRONG_CHECK_DIGITS)).isFalse();
        assertThat(LeiChecksumRule.hasValidCheckDigits("5493001KJTIIGC8Y1R11")).isFalse();
        assertThat(LeiChecksumRule.hasValidCheckDigits("")).isFalse();
        assertThat(LeiChecksumRule.hasValidCheckDigits("5493001KJTIIGC8Y1R1")).as("too short").isFalse();
        assertThat(LeiChecksumRule.hasValidCheckDigits("5493001KJTIIGC8Y1R122")).as("too long").isFalse();
        assertThat(LeiChecksumRule.hasValidCheckDigits("5493001kjtiigc8y1r12")).as("lowercase").isFalse();
        assertThat(LeiChecksumRule.hasValidCheckDigits("5493001KJTIIGC8Y1R-2")).isFalse();
    }

    @Test
    void theLeiRuleReportsWrongCheckDigitsAboutTheLeiAttribute() {
        List<Report> reports = check(new LeiChecksumRule(), "Organization",
                values("name", "Gamma", "lei", ReferenceStories.LEI_WRONG_CHECK_DIGITS));

        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.code()).isEqualTo("invalid-check-digits");
            assertThat(report.attribute()).isEqualTo("lei");
            assertThat(report.message()).contains(ReferenceStories.LEI_WRONG_CHECK_DIGITS);
        });
    }

    @Test
    void theLeiRuleAcceptsAValidLeiAndAnOrganizationWithoutOne() {
        assertThat(check(new LeiChecksumRule(), "Organization", values("name", "ACME", "lei", ReferenceStories.LEI)))
                .isEmpty();
        assertThat(check(new LeiChecksumRule(), "Organization", values("name", "ACME"))).isEmpty();
    }

    @Test
    void theNameRuleReportsABlankNameAboutTheNameAttribute() {
        for (String blank : List.of("", " ", "  ", "\t\n")) {
            assertThat(check(new NameNotBlankRule(), "Person", values("name", blank))).as("'" + blank + "'")
                    .singleElement().satisfies(report -> {
                        assertThat(report.code()).isEqualTo("blank-name");
                        assertThat(report.attribute()).isEqualTo("name");
                    });
        }
        assertThat(check(new NameNotBlankRule(), "Person", values("name", "Alice"))).isEmpty();
        assertThat(check(new NameNotBlankRule(), "Person", values())).isEmpty();
    }

    @Test
    void theRulesReportOnlyCodesTheyDeclare() {
        List<Report> lei = check(new LeiChecksumRule(), "Organization", values("lei", "bad"));
        List<Report> name = check(new NameNotBlankRule(), "Person", values("name", " "));

        assertThat(lei).extracting(Report::code).isSubsetOf(codes(LeiChecksumRule.DESCRIPTOR));
        assertThat(name).extracting(Report::code).isSubsetOf(codes(NameNotBlankRule.DESCRIPTOR));
    }

    private static List<String> codes(RuleDescriptor rule) {
        return rule.codes().stream().map(code -> code.code()).toList();
    }

    @Test
    void theRulesDescribeThemselvesAsTheReferenceModelListsThem() {
        assertThat(new LeiChecksumRule().descriptor()).isEqualTo(ReferenceModel.rules().getFirst());
        assertThat(new LeiChecksumRule().descriptor().name()).isEqualTo("lei-checksum");
        assertThat(new NameNotBlankRule().descriptor().name()).isEqualTo("name-not-blank");
        assertThat(new NameNotBlankRule().descriptor().appliesTo()).isEqualTo("Party");
        assertThat(ReferenceModel.rules()).map(RuleDescriptor::name)
                .doesNotContain("name-not-blank");
    }

    @Test
    void theModelSplitsItsRulesIntoThoseWithCodeAndThoseDeclaredWithout() {
        assertThat(ReferenceModel.executableRules()).map(Rule::descriptor)
                .containsExactly(LeiChecksumRule.DESCRIPTOR);
        assertThat(ReferenceModel.declaredRules()).map(RuleDescriptor::name)
                .containsExactly("total-ownership-at-most-100");
        List<RuleDescriptor> all = new ArrayList<>(ReferenceModel.executableRules().stream()
                .map(Rule::descriptor).toList());
        all.addAll(ReferenceModel.declaredRules());
        assertThat(all).containsExactlyInAnyOrderElementsOf(ReferenceModel.rules());
    }
}
