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
import java.lang.reflect.Proxy;
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

    /** A context that only collects what the rule reports, and shows the store before the transaction if it has one. */
    private static final class Reports implements RuleContext {
        final List<Report> reports = new ArrayList<>();
        private final View before;

        Reports() {
            this(null);
        }

        Reports(View before) {
            this.before = before;
        }

        @Override
        public View view() {
            throw new UnsupportedOperationException();
        }

        @Override
        public View viewAt(Instant effectiveAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public View before() {
            if (before == null) {
                throw new UnsupportedOperationException();
            }
            return before;
        }

        @Override
        public View beforeAt(Instant effectiveAt) {
            return before();
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

    private static EntityState organization(Map<String, Value> values) {
        return organization(EffectivePeriod.from(Instant.parse("2026-01-01T00:00:00Z")), values);
    }

    private static EntityState organization(EffectivePeriod period, Map<String, Value> values) {
        return new EntityState(new EntityId("e1"), "Organization", values, period,
                Instant.parse("2026-01-05T09:00:00Z"), new Basis(1));
    }

    private static List<Report> check(EntityRule rule, String type, Map<String, Value> values) {
        EntityState version = new EntityState(new EntityId("e1"), type, values,
                EffectivePeriod.from(Instant.parse("2026-01-01T00:00:00Z")), Instant.parse("2026-01-05T09:00:00Z"),
                new Basis(1));
        Reports context = new Reports();
        rule.check(version, context);
        return context.reports;
    }

    /** A view of the store as committed before the transaction, which holds the given versions of an entity only. */
    private static View storeHolding(List<EntityState> held) {
        return (View) Proxy.newProxyInstance(View.class.getClassLoader(), new Class<?>[] {View.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("timeline") && arguments[0] instanceof EntityId id) {
                        return held.stream().filter(state -> state.id().equals(id)).toList();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** The reports of {@code lei-kept} for a version, with the store holding what it held before the transaction. */
    private static List<Report> checkLeiKept(List<EntityState> held, EntityState version) {
        Reports context = new Reports(storeHolding(held));
        new LeiKeptRule().check(version, context);
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
    void theLeiKeptRuleReportsAnLeiThatWasHeldBeforeTheTransactionAndIsGoneAboutTheWholeOrganization() {
        EntityState held = organization(values("name", "ACME", "lei", ReferenceStories.LEI));

        List<Report> reports = checkLeiKept(List.of(held), organization(values("name", "ACME")));

        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.code()).isEqualTo("lei-removed");
            assertThat(report.attribute()).as("about the whole organization").isNull();
            assertThat(report.message()).contains("e1");
        });
        assertThat(checkLeiKept(List.of(held), organization(values("name", "ACME", "lei", " "))))
                .as("a blank LEI is no LEI").hasSize(1);
    }

    @Test
    void theLeiKeptRuleAcceptsAnLeiThatIsKeptOrNewAndAnOrganizationTheStoreDidNotHold() {
        EntityState held = organization(values("name", "ACME", "lei", ReferenceStories.LEI));
        EntityState withoutLei = organization(values("name", "ACME"));

        assertThat(checkLeiKept(List.of(held), organization(values("name", "ACME Holdings", "lei",
                ReferenceStories.LEI)))).as("kept").isEmpty();
        assertThat(checkLeiKept(List.of(withoutLei), organization(values("name", "ACME", "lei",
                ReferenceStories.LEI)))).as("given").isEmpty();
        assertThat(checkLeiKept(List.of(withoutLei), withoutLei)).as("never had one").isEmpty();
        assertThat(checkLeiKept(List.of(), withoutLei)).as("not held before: nothing to keep").isEmpty();
    }

    @Test
    void theLeiKeptRuleComparesWithEveryVersionHeldInThePeriodAndOnlyWithThose() {
        Instant march = Instant.parse("2026-03-01T00:00:00Z");
        Instant june = Instant.parse("2026-06-01T00:00:00Z");
        Instant september = Instant.parse("2026-09-01T00:00:00Z");
        EntityState january = organization(EffectivePeriod.between(Instant.parse("2026-01-01T00:00:00Z"), march),
                values("name", "ACME"));
        EntityState spring = organization(EffectivePeriod.between(march, june),
                values("name", "ACME", "lei", ReferenceStories.LEI));
        EntityState summer = organization(EffectivePeriod.from(june), values("name", "ACME"));
        List<EntityState> held = List.of(january, spring, summer);

        assertThat(checkLeiKept(held, organization(EffectivePeriod.from(Instant.parse("2026-01-01T00:00:00Z")),
                values("name", "ACME")))).as("the LEI of March to June is in the period, not at its start").hasSize(1);
        assertThat(checkLeiKept(held, organization(EffectivePeriod.between(june, september), values("name", "ACME"))))
                .as("the period starts after the LEI").isEmpty();
        assertThat(checkLeiKept(held, organization(EffectivePeriod.between(Instant.parse("2026-01-01T00:00:00Z"),
                march), values("name", "ACME")))).as("the period ends where the LEI starts").isEmpty();
    }

    @Test
    void theRulesReportOnlyCodesTheyDeclare() {
        List<Report> lei = check(new LeiChecksumRule(), "Organization", values("lei", "bad"));
        List<Report> name = check(new NameNotBlankRule(), "Person", values("name", " "));
        List<Report> kept = checkLeiKept(List.of(organization(values("name", "A", "lei", ReferenceStories.LEI))),
                organization(values("name", "A")));

        assertThat(lei).extracting(Report::code).isSubsetOf(codes(LeiChecksumRule.DESCRIPTOR));
        assertThat(name).extracting(Report::code).isSubsetOf(codes(NameNotBlankRule.DESCRIPTOR));
        assertThat(kept).extracting(Report::code).isSubsetOf(codes(LeiKeptRule.DESCRIPTOR));
    }

    private static List<String> codes(RuleDescriptor rule) {
        return rule.codes().stream().map(code -> code.code()).toList();
    }

    @Test
    void theRulesDescribeThemselvesAsTheReferenceModelListsThem() {
        assertThat(new LeiChecksumRule().descriptor()).isEqualTo(ReferenceModel.rules().getFirst());
        assertThat(new TotalOwnershipRule().descriptor()).isEqualTo(ReferenceModel.rules().get(1));
        assertThat(new TotalOwnershipRule().descriptor().name()).isEqualTo("total-ownership-at-most-100");
        assertThat(new TotalOwnershipRule().descriptor().appliesTo()).isEqualTo("Owns");
        assertThat(codes(TotalOwnershipRule.DESCRIPTOR)).containsExactly("over-100-percent");
        assertThat(new LeiChecksumRule().descriptor().name()).isEqualTo("lei-checksum");
        assertThat(new NameNotBlankRule().descriptor().name()).isEqualTo("name-not-blank");
        assertThat(new NameNotBlankRule().descriptor().appliesTo()).isEqualTo("Party");
        assertThat(new LeiKeptRule().descriptor().name()).isEqualTo("lei-kept");
        assertThat(new LeiKeptRule().descriptor().appliesTo()).isEqualTo("Organization");
        assertThat(codes(LeiKeptRule.DESCRIPTOR)).containsExactly("lei-removed");
        assertThat(ReferenceModel.rules()).map(RuleDescriptor::name)
                .doesNotContain("name-not-blank", "lei-kept");
    }

    @Test
    void everyRuleOfTheModelHasCodeSoNoneIsDeclaredWithout() {
        assertThat(ReferenceModel.executableRules()).map(Rule::descriptor)
                .containsExactly(LeiChecksumRule.DESCRIPTOR, TotalOwnershipRule.DESCRIPTOR);
        assertThat(ReferenceModel.declaredRules()).isEmpty();
        List<RuleDescriptor> all = new ArrayList<>(ReferenceModel.executableRules().stream()
                .map(Rule::descriptor).toList());
        all.addAll(ReferenceModel.declaredRules());
        assertThat(all).containsExactlyInAnyOrderElementsOf(ReferenceModel.rules());
    }
}
