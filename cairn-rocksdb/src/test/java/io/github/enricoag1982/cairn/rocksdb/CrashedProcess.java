package io.github.enricoag1982.cairn.rocksdb;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import java.nio.file.Path;
import java.util.List;

/**
 * The program that a test forks to show what a crash leaves: it commits the transactions of RD-1 to RD-3 to a RocksDB store
 * in the directory it is given, with the clock and the ID generator of {@link #tell}, and then halts the virtual machine
 * without closing the store, which releases nothing and flushes nothing, as a power cut or a kill does for the process.
 */
public final class CrashedProcess {

    /** The exit status of the halt, which tells the test that every commit returned before it. */
    public static final int HALTED = 42;

    private CrashedProcess() {
    }

    /**
     * Tells RD-1 to RD-3 to a store, in the order of their recording dates. The reference model must be registered.
     *
     * @param store the store
     * @param clock the clock that the stories move
     * @return the receipts of the three transactions
     */
    static List<Receipt> tell(Store store, TestClock clock) {
        Receipt parties = ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        var acme = ReferenceTales.entity(parties, ACME);
        var beta = ReferenceTales.entity(parties, BETA);
        Receipt ownership = ReferenceStories.replay(store, clock, ReferenceStories.rd2Ownership(acme, beta));
        Receipt employment = ReferenceStories.replay(store, clock,
                ReferenceStories.rd3Employment(beta, ReferenceTales.entity(parties, ALICE)));
        return List.of(parties, ownership, employment);
    }

    /**
     * Commits RD-1 to RD-3 and halts; with the argument {@code purge} after the directory, commits RD-1 and RD-3 and purges
     * Alice, as the purge scenario does, and halts after the purge returned.
     *
     * @param arguments the directory of the store, and {@code purge} or nothing
     */
    public static void main(String[] arguments) {
        TestClock clock = new TestClock();
        DurableStore store = RocksDbStores.open(Path.of(arguments[0]), StoreSettings.builder().clock(clock)
                .idGenerator(IdGenerators.deterministic()).rules(ReferenceModel.executableRules())
                .declaredRules(ReferenceModel.declaredRules()).build());
        RocksDbFixture.registerReferenceModel(store);
        if (arguments.length > 1 && arguments[1].equals("purge")) {
            Receipt parties = ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
            var alice = ReferenceTales.entity(parties, ALICE);
            ReferenceStories.replay(store, clock,
                    ReferenceStories.rd3Employment(ReferenceTales.entity(parties, BETA), alice));
            store.purge(ReferenceStories.rd11PurgeAlice(alice));
        } else {
            tell(store, clock);
        }
        Runtime.getRuntime().halt(HALTED);
    }
}
