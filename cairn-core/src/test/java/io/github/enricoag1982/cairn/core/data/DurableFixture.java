package io.github.enricoag1982.cairn.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.core.TestBackend;
import io.github.enricoag1982.cairn.core.storage.CommitLog;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.EmployerHoldsAccountRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens durable stores on one directory for a test, one after the other, with the rules of the reference domain, a
 * clock that the test moves and a new deterministic ID generator each time, as an application that restarts has. Stores
 * that the test leaves open are closed by {@link #closeAll()}.
 *
 * <p>The {@code open} methods open directory stores, for the tests about their files. The {@code openStore} methods open
 * the kind of durable store that the run of the tests is for: a directory store, or a RocksDB store when the tests run
 * against RocksDB (see {@link TestBackend}), for the tests that every durable store must pass.
 */
final class DurableFixture {

    final Path directory;
    final TestClock clock = new TestClock();
    /** The size after which log segments are complete; small in the tests of segments. */
    long segmentLimit = CommitLog.DEFAULT_SEGMENT_LIMIT;
    private final List<DurableStore> opened = new ArrayList<>();

    DurableFixture(Path directory) {
        this.directory = directory;
    }

    /** Opens a store with the rules of the reference domain and the fixture's clock. */
    DirectoryStore open() {
        return open(clock, IdGenerators.deterministic(), ReferenceModel.executableRules(),
                ReferenceModel.declaredRules());
    }

    DirectoryStore open(InstantSource clock, IdGenerator generator, List<Rule> rules, List<RuleDescriptor> declared) {
        DirectoryStore store = DirectoryStore.open(directory, clock, generator, rules, declared, segmentLimit);
        opened.add(store);
        return store;
    }

    /**
     * Opens a durable store of the backend under test, with the settings given: a directory store, or a RocksDB store.
     * The directory is held until the store is closed.
     */
    DurableStore openStore(StoreSettings settings) {
        DurableStore store = TestBackend.isRocksDb() ? TestBackend.openRocksDb(directory, settings)
                : Stores.durable(directory, settings);
        opened.add(store);
        return store;
    }

    /** Opens a durable store of the backend under test with the rules of the reference domain and the fixture's clock. */
    DurableStore openStore() {
        return openStore(clock, IdGenerators.deterministic(), ReferenceModel.executableRules(),
                ReferenceModel.declaredRules());
    }

    DurableStore openStore(InstantSource clock, IdGenerator generator, List<Rule> rules,
            List<RuleDescriptor> declared) {
        return openStore(StoreSettings.builder().clock(clock).idGenerator(generator).rules(rules)
                .declaredRules(declared).build());
    }

    /** Opens a durable store of the backend under test and registers the reference model in it. */
    DurableStore openStoreWithReferenceModel() {
        DurableStore store = openStore();
        registerReferenceModel(store);
        return store;
    }

    /** Opens a store of the backend under test like {@link #openWithEmployerHoldsAccount()} does, and registers its model. */
    DurableStore openStoreWithEmployerHoldsAccount() {
        DurableStore store = reopenStoreWithEmployerHoldsAccount();
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), Definitions.withRule(
                ReferenceModel.definition(), "Employs", EmployerHoldsAccountRule.DESCRIPTOR)))
                .isInstanceOf(RegistrationResult.Applied.class);
        return store;
    }

    /** Opens the store of {@link #openStoreWithEmployerHoldsAccount()} again, with its rules and a new ID generator. */
    DurableStore reopenStoreWithEmployerHoldsAccount() {
        return openStore(clock, IdGenerators.deterministic(), rulesWithEmployerHoldsAccount(),
                ReferenceModel.declaredRules());
    }

    /** Opens a store and registers the reference model in it, which is the first transaction. */
    DirectoryStore openWithReferenceModel() {
        DirectoryStore store = open();
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
     * Opens a store with the rules of the reference domain and {@code employer-holds-account}, and registers the
     * reference model in which {@code Employs} names that rule, which is the first transaction. To open it again, call
     * {@link #reopenWithEmployerHoldsAccount()}.
     */
    DirectoryStore openWithEmployerHoldsAccount() {
        DirectoryStore store = reopenWithEmployerHoldsAccount();
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), Definitions.withRule(
                ReferenceModel.definition(), "Employs", EmployerHoldsAccountRule.DESCRIPTOR)))
                .isInstanceOf(RegistrationResult.Applied.class);
        return store;
    }

    /** Opens the store of {@link #openWithEmployerHoldsAccount()} again, with its rules and a new ID generator. */
    DirectoryStore reopenWithEmployerHoldsAccount() {
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

    /** Replays a story that the store must reject; returns the problems. */
    List<Problem> rejectReplay(Store store, ReferenceStories.Recording recording) {
        CommitResult result = ReferenceStories.attempt(store, clock, recording);
        assertThat(result).isInstanceOf(CommitResult.Rejected.class);
        return ((CommitResult.Rejected) result).problems();
    }

    static Receipt commit(Store store, Transaction transaction) {
        CommitResult result = store.commit(transaction);
        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        return ((CommitResult.Committed) result).receipt();
    }

    /**
     * Stands for a crash: the store stops without a snapshot file, and without closing the log, but its lock is
     * released, so that a store can be opened on the directory in this JVM.
     */
    static void crash(DirectoryStore store) {
        store.abandon();
    }

    /** Closes the stores that the test left open. */
    void closeAll() {
        for (DurableStore store : opened) {
            store.close();
        }
        opened.clear();
    }
}
