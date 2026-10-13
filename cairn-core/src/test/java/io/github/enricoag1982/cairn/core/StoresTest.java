package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.ACME;
import static io.github.enricoag1982.cairn.core.ReferenceStore.from;
import static io.github.enricoag1982.cairn.core.ReferenceStore.id;
import static io.github.enricoag1982.cairn.core.ReferenceStore.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Design D2 and D3: creating stores, and their settings. */
class StoresTest {

    private static final RuleDescriptor RULE = ReferenceModel.rules().getFirst();

    @Test
    void defaultSettingsHaveTheSystemClockAUuidV7GeneratorAndNoRules() {
        StoreSettings settings = StoreSettings.defaults();

        assertThat(settings.availableRules()).isEmpty();
        assertThat(settings.idGenerator().accepts(IdGenerators.uuidV7().next().value())).isTrue();
        assertThat(settings.idGenerator().accepts("not an id")).isFalse();
        assertThat(settings.clock().instant()).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
    }

    @Test
    void theBuilderSetsEachPartOfTheSettings() {
        InstantSource clock = InstantSource.fixed(Instant.parse("2026-01-05T09:00:00Z"));
        IdGenerator generator = IdGenerators.deterministic();

        StoreSettings settings = StoreSettings.builder().clock(clock).idGenerator(generator)
                .availableRules(List.of(RULE)).build();

        assertThat(settings.clock()).isSameAs(clock);
        assertThat(settings.idGenerator()).isSameAs(generator);
        assertThat(settings.availableRules()).containsExactly(RULE);
    }

    @Test
    void settingTheAvailableRulesAgainReplacesThem() {
        StoreSettings settings = StoreSettings.builder().availableRules(ReferenceModel.rules())
                .availableRules(List.of(RULE)).build();

        assertThat(settings.availableRules()).containsExactly(RULE);
    }

    @Test
    void theSettingsKeepTheirOwnCopyOfTheRules() {
        List<RuleDescriptor> rules = new ArrayList<>(List.of(RULE));
        StoreSettings settings = StoreSettings.builder().availableRules(rules).build();

        rules.clear();

        assertThat(settings.availableRules()).containsExactly(RULE);
        assertThatThrownBy(() -> settings.availableRules().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theDefaultGeneratorReadsTheClockOfTheSettings() {
        TestClock clock = new TestClock();
        Store store = Stores.inMemory(StoreSettings.builder().clock(clock).build());
        store.schema().register(new Origin("things", "1"), SchemaDefinition.builder().entityType("Thing").build());

        var receipt = ((CommitResult.Committed) store.commit(Transaction.by("tester")
                .create("Thing", from(1, 1), values()).build())).receipt();

        String id = receipt.created().getFirst().value();
        assertThat(Long.parseLong(id.substring(0, 8) + id.substring(9, 13), 16))
                .isEqualTo(TestClock.START.toEpochMilli());
    }

    @Test
    void settingsRejectNullsAndRulesThatBreakTheNamingRules() {
        assertThatThrownBy(() -> new StoreSettings(null, IdGenerators.deterministic(), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StoreSettings(InstantSource.system(), null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StoreSettings.builder().clock(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StoreSettings.builder().idGenerator(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Stores.inMemory(null)).isInstanceOf(NullPointerException.class);
        RuleDescriptor bad = new RuleDescriptor("Bad Name", 1, "d", "Organization", List.of());
        assertThatThrownBy(() -> Stores.inMemory(StoreSettings.builder().availableRules(List.of(bad)).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theAvailableRulesAreThoseOfTheStoresSchemaRegistry() {
        Store store = Stores.inMemory(StoreSettings.builder().availableRules(ReferenceModel.rules()).build());

        assertThat(store.schema().availableRules()).hasSize(2);
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .isInstanceOf(RegistrationResult.Applied.class);
        assertThat(Stores.inMemory().schema().availableRules()).isEmpty();
    }

    @Test
    void aNewStoreIsEmpty() {
        Store store = Stores.inMemory();

        assertThat(store.latestBasis()).isEqualTo(Basis.NONE);
        assertThat(store.transactions()).isEmpty();
        assertThat(store.schema().current().isEmpty()).isTrue();
    }

    @Test
    void theStoresHaveSequencesOfTheirOwn() {
        TestClock clock = new TestClock();
        StoreSettings settings = StoreSettings.builder().clock(clock).availableRules(ReferenceModel.rules()).build();
        Store first = Stores.inMemory(settings);
        Store second = Stores.inMemory(settings);

        first.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition());
        first.commit(Transaction.by("tester").create("Organization", from(1, 1), values("name", "A")).build());

        assertThat(first.latestBasis()).isEqualTo(new Basis(2));
        assertThat(second.latestBasis()).isEqualTo(Basis.NONE);
    }

    @Test
    void storesMadeFromTheSameSettingsDoNotShareTheirGenerator() {
        IdGenerator generator = IdGenerators.deterministic();
        TestClock clock = new TestClock();
        StoreSettings settings = StoreSettings.builder().clock(clock).idGenerator(generator)
                .availableRules(ReferenceModel.rules()).build();
        Transaction transaction = Transaction.by("tester").create(ACME, "Organization", from(1, 1),
                values("name", "ACME")).build();

        List<Id> ids = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Store store = Stores.inMemory(settings);
            store.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition());
            ids.add(((CommitResult.Committed) store.commit(transaction)).receipt().created().getFirst());
        }

        assertThat(ids).containsExactly(id(1), id(1));
        assertThat(generator.next()).as("the generator of the settings was not used up").isEqualTo(id(1));
    }
}
