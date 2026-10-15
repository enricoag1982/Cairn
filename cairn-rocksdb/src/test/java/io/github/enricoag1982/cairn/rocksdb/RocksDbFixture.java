package io.github.enricoag1982.cairn.rocksdb;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.kv.KeyValueStores;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.EmployerHoldsAccountRule;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens RocksDB stores on one directory for a test, one after the other, with the rules of the reference domain, a clock
 * that the test moves and a new deterministic ID generator each time, as an application that restarts has. Stores that the
 * test leaves open are closed by {@link #closeAll()}.
 */
final class RocksDbFixture {

    final Path directory;
    final TestClock clock = new TestClock();
    private final List<DurableStore> opened = new ArrayList<>();

    RocksDbFixture(Path directory) {
        this.directory = directory;
    }

    /** Opens a store with the rules of the reference domain and the fixture's clock. */
    DurableStore open() {
        return open(clock, IdGenerators.deterministic(), ReferenceModel.executableRules(),
                ReferenceModel.declaredRules());
    }

    DurableStore open(InstantSource clock, IdGenerator generator, List<Rule> rules, List<RuleDescriptor> declared) {
        DurableStore store = RocksDbStores.open(directory, settings(clock, generator, rules, declared));
        opened.add(store);
        return store;
    }

    private static StoreSettings settings(InstantSource clock, IdGenerator generator, List<Rule> rules,
            List<RuleDescriptor> declared) {
        return StoreSettings.builder().clock(clock).idGenerator(generator).rules(rules).declaredRules(declared).build();
    }

    /**
     * Opens a store that stops while a purge compacts its files, as a process that stops does: a store over the database
     * in the directory, with the rules of the reference domain, whose key-value store fails when it is asked to flush or
     * to compact, so that a purge throws after its batch was written. Close it, and open the directory again, to see what
     * a stop leaves.
     */
    DurableStore openStopping(StoppingKeyValueStore.Stop stop) {
        Runnable release = KeyValueStores.lockDirectory(directory);
        RocksDbKeyValueStore database = RocksDbKeyValueStore.open(directory.resolve(RocksDbStores.DATABASE), release);
        DurableStore store = KeyValueStores.openDurable(new StoppingKeyValueStore(database, stop),
                settings(clock, IdGenerators.deterministic(), ReferenceModel.executableRules(),
                        ReferenceModel.declaredRules()));
        opened.add(store);
        return store;
    }

    /** Opens a store and registers the reference model in it, which is the first transaction. */
    DurableStore openWithReferenceModel() {
        DurableStore store = open();
        registerReferenceModel(store);
        return store;
    }

    /** The rules of the reference domain and the test-only rule {@code employer-holds-account}, all with their code. */
    static List<Rule> rulesWithEmployerHoldsAccount() {
        List<Rule> rules = new ArrayList<>(ReferenceModel.executableRules());
        rules.add(new EmployerHoldsAccountRule());
        return rules;
    }

    /**
     * Opens a store with the rules of the reference domain and {@code employer-holds-account}, and registers the reference
     * model in which {@code Employs} names that rule, which is the first transaction.
     */
    DurableStore openWithEmployerHoldsAccount() {
        DurableStore store = reopenWithEmployerHoldsAccount();
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), Definitions.withRule(
                ReferenceModel.definition(), "Employs", EmployerHoldsAccountRule.DESCRIPTOR)))
                .isInstanceOf(RegistrationResult.Applied.class);
        return store;
    }

    /** Opens the store of {@link #openWithEmployerHoldsAccount()} again, with its rules and a new ID generator. */
    DurableStore reopenWithEmployerHoldsAccount() {
        return open(clock, IdGenerators.deterministic(), rulesWithEmployerHoldsAccount(),
                ReferenceModel.declaredRules());
    }

    static void registerReferenceModel(Store store) {
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .isInstanceOf(RegistrationResult.Applied.class);
    }

    /** Replays a story on a store with the fixture's clock; it must be accepted. */
    Receipt replay(Store store, ReferenceStories.Recording recording) {
        return ReferenceStories.replay(store, clock, recording);
    }

    static Receipt commit(Store store, Transaction transaction) {
        CommitResult result = store.commit(transaction);
        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        return ((CommitResult.Committed) result).receipt();
    }

    /** Closes the stores that the test left open; a store that was closed is not closed again. */
    void closeAll() {
        for (DurableStore store : opened) {
            store.close();
        }
        opened.clear();
    }
}
