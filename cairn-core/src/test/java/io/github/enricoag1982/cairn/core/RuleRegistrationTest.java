package io.github.enricoag1982.cairn.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.fixtures.LeiChecksumRule;
import io.github.enricoag1982.cairn.fixtures.NameNotBlankRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.fixtures.TotalOwnershipRule;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How a store gets its rules (design D2 of {@code add-entity-rules}): given to the settings, found by the service loader,
 * or declared without code, and which of them make creating the store fail.
 */
class RuleRegistrationTest {

    private static final RuleDescriptor LEI_TWO = new RuleDescriptor("lei-checksum", 2, "Version 2.", "Organization",
            List.of());

    private static Store store(StoreSettings.Builder settings) {
        return Stores.inMemory(settings.clock(new TestClock()).build());
    }

    @Test
    void theDescriptionsOfRulesWithCodeAndOfDeclaredRulesAreTheAvailableRules() {
        Store store = store(StoreSettings.builder().rules(new LeiChecksumRule(), new NameNotBlankRule())
                .declaredRules(List.of(TotalOwnershipRule.DESCRIPTOR)));

        assertThat(store.schema().availableRules()).containsExactlyInAnyOrder(LeiChecksumRule.DESCRIPTOR,
                NameNotBlankRule.DESCRIPTOR, TotalOwnershipRule.DESCRIPTOR);
    }

    @Test
    void rulesCanBeGivenAsAnArrayOrACollectionAndTheLastCallWins() {
        StoreSettings settings = StoreSettings.builder().rules(new LeiChecksumRule())
                .rules(List.of(new NameNotBlankRule())).build();
        StoreSettings varargs = StoreSettings.builder().rules(new LeiChecksumRule()).rules(new NameNotBlankRule())
                .rules(new NameNotBlankRule(), new LeiChecksumRule()).build();

        assertThat(settings.rules()).extracting(rule -> rule.descriptor().name()).containsExactly("name-not-blank");
        assertThat(varargs.rules()).extracting(rule -> rule.descriptor().name())
                .containsExactly("name-not-blank", "lei-checksum");
        assertThat(StoreSettings.defaults().rules()).isEmpty();
        assertThat(new StoreSettings(new TestClock(), IdGenerators.deterministic(), List.of()).rules()).isEmpty();
    }

    @Test
    void theRulesAreCopiedWhenTheSettingsAreBuilt() {
        List<Rule> given = new ArrayList<>(List.of(new LeiChecksumRule()));
        StoreSettings settings = StoreSettings.builder().rules(given).build();
        given.add(new NameNotBlankRule());

        assertThat(settings.rules()).hasSize(1);
        assertThatThrownBy(() -> settings.rules().add(new NameNotBlankRule()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void settingsRejectNullRules() {
        assertThatThrownBy(() -> StoreSettings.builder().rules((Rule[]) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StoreSettings.builder().rules((List<Rule>) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StoreSettings.builder().rules(new LeiChecksumRule(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StoreSettings(new TestClock(), IdGenerators.deterministic(), null, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void twoRulesWithTheSameNameAndVersionMakeCreatingTheStoreFail() {
        assertThatThrownBy(() -> store(StoreSettings.builder().rules(new LeiChecksumRule(), new LeiChecksumRule())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lei-checksum");
        assertThatThrownBy(() -> store(StoreSettings.builder().rules(new LeiChecksumRule())
                .declaredRules(ReferenceModel.rules())))
                .as("a rule with code and the same rule declared")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lei-checksum");
    }

    @Test
    void twoVersionsOfARuleAreFine() {
        Rule second = new EntityRule() {
            @Override
            public RuleDescriptor descriptor() {
                return LEI_TWO;
            }

            @Override
            public void check(EntityState version, RuleContext context) {
                // Nothing to check.
            }
        };

        Store store = store(StoreSettings.builder().rules(new LeiChecksumRule(), second));

        assertThat(store.schema().availableRules()).containsExactly(LeiChecksumRule.DESCRIPTOR, LEI_TWO);
    }

    @Test
    void aRuleWhoseNameBreaksTheNamingRuleMakesCreatingTheStoreFail() {
        Rule bad = () -> new RuleDescriptor("Bad_Name", 1, "Bad.", "Organization", List.of());

        assertThatThrownBy(() -> store(StoreSettings.builder().rules(bad)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Bad_Name");
    }

    @Test
    void aRuleWithoutADescriptorMakesCreatingTheStoreFail() {
        Rule none = () -> null;

        assertThatThrownBy(() -> store(StoreSettings.builder().rules(none))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theServiceLoaderFindsRulesWhenAskedTo() {
        StoreSettings withoutDiscovery = StoreSettings.builder().rules(new LeiChecksumRule()).build();
        StoreSettings discovered = StoreSettings.builder().rules(new LeiChecksumRule()).discoverRules().build();
        StoreSettings onlyDiscovered = StoreSettings.builder().discoverRules().build();

        assertThat(withoutDiscovery.rules()).extracting(Rule::descriptor).containsExactly(LeiChecksumRule.DESCRIPTOR);
        assertThat(discovered.rules()).extracting(Rule::descriptor)
                .containsExactly(LeiChecksumRule.DESCRIPTOR, DiscoveredTestRule.DESCRIPTOR);
        assertThat(onlyDiscovered.rules()).singleElement().isInstanceOf(DiscoveredTestRule.class);
    }

    @Test
    void discoveringDoesNotDependOnTheOrderOfTheCalls() {
        StoreSettings discoveringFirst = StoreSettings.builder().discoverRules().rules(new LeiChecksumRule()).build();

        assertThat(discoveringFirst.rules()).extracting(Rule::descriptor)
                .containsExactly(LeiChecksumRule.DESCRIPTOR, DiscoveredTestRule.DESCRIPTOR);
    }

    @Test
    void discoveredRulesAreAvailableToTheSchemaRegistry() {
        Store store = store(StoreSettings.builder().discoverRules());

        assertThat(store.schema().availableRules()).containsExactly(DiscoveredTestRule.DESCRIPTOR);
    }

    @Test
    void aRuleFoundTwiceMakesCreatingTheStoreFail() {
        assertThatThrownBy(() -> store(StoreSettings.builder().rules(new DiscoveredTestRule()).discoverRules()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("discovered-test-rule");
    }
}
