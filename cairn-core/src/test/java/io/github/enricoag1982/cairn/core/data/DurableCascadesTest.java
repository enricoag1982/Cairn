package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.ReferenceTales.EFFECTIVE_TIMES;
import static io.github.enricoag1982.cairn.core.data.ReferenceTales.entity;
import static io.github.enricoag1982.cairn.core.data.ReferenceTales.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Design D1 of {@code change-composition-cascades} with a durable store: a commit in which a cascade ends the other
 * relationships of a target is read the same from a store that is reopened, and from one recovered from its log, as from
 * an in-memory store that committed the same transactions.
 */
class DurableCascadesTest {

    private static final TemporaryId HOME = new TemporaryId("home");
    private static final TemporaryId HAS_ADDRESS = new TemporaryId("hasAddress");
    private static final TemporaryId MAILED_TO = new TemporaryId("mailedTo");

    /** The reference domain with the test-only association MailedTo from an account to an address. */
    private static final SchemaDefinition MODEL = Definitions.plus(ReferenceModel.definition(),
            SchemaDefinition.builder().relationshipType("MailedTo", r -> r.from("Account").to("Address")
                    .targetsPerSource(Multiplicity.ZERO_OR_ONE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                    .inverse("mailedFrom")).build());

    @TempDir
    Path directory;

    private DurableFixture durable;
    private final TestClock memoryClock = new TestClock();
    private Store memory;

    @BeforeEach
    void setUp() {
        durable = new DurableFixture(directory);
        memory = Stores.inMemory(StoreSettings.builder().clock(memoryClock).idGenerator(IdGenerators.deterministic())
                .rules(ReferenceModel.executableRules()).declaredRules(ReferenceModel.declaredRules()).build());
    }

    @AfterEach
    void tearDown() {
        durable.closeAll();
    }

    private static void register(Store store) {
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), MODEL))
                .isInstanceOf(RegistrationResult.Applied.class);
    }

    /**
     * What a store was told: the receipts, the receipt of the address that A-1 is mailed to, and the transaction with an
     * idempotency key that Beta ends in.
     */
    private record Tale(ReferenceTales.Told told, Receipt address, Transaction ending) {
    }

    /**
     * RD-1 and RD-5, then Beta's address, to which A-1's statements are mailed, and last Beta ending from 1 June, with
     * A-1 moving to ACME: the cascade ends the address and, with it, A-1's MailedTo.
     */
    private static Tale tell(Store store, TestClock clock) {
        ReferenceTales.Told told = new ReferenceTales.Told();
        told.parties = told.accept(store, clock, ReferenceStories.rd1Parties());
        EntityId acme = entity(told.parties, ACME);
        EntityId beta = entity(told.parties, BETA);
        told.accounts = told.accept(store, clock, ReferenceStories.rd5Accounts(beta, acme));
        EntityId a1 = entity(told.accounts, A1);
        clock.moveTo(on(5, 1));
        Receipt address = DurableFixture.commit(store, Transaction.by(ReferenceStories.PRINCIPAL)
                .create(HOME, "Address", from(1, 1),
                        values("street", "1 Main St", "city", "Springfield", "country", "US"))
                .relate(HAS_ADDRESS, "HasAddress", beta, HOME, from(1, 1), values())
                .relate(MAILED_TO, "MailedTo", a1, HOME, from(1, 1), values()).build());
        told.receipts.add(address);
        clock.moveTo(on(5, 20));
        Transaction ending = Transaction.by(ReferenceStories.PRINCIPAL).idempotencyKey("beta-leaves")
                .endDate(beta, on(6, 1))
                .endDate(relationship(told.accounts, HELD_BY_A1), on(6, 1))
                .relate("HeldBy", a1, acme, from(6, 1), values()).build();
        told.receipts.add(DurableFixture.commit(store, ending));
        return new Tale(told, address, ending);
    }

    @Test
    void aCommitThatACascadeMadeLongerIsReadTheSameFromAReopenedStoreAndFromTheLog() {
        DirectoryStore store = durable.open();
        register(store);
        register(memory);

        Tale onDisk = tell(store, durable.clock);
        Tale inMemory = tell(memory, memoryClock);

        RelationshipId mailedTo = relationship(onDisk.address(), MAILED_TO);
        List<Id> ended = List.of(relationship(onDisk.address(), HAS_ADDRESS), entity(onDisk.address(), HOME), mailedTo);
        assertThat(onDisk.told().receipts.getLast().ended()).containsExactlyElementsOf(ended);
        assertThat(onDisk.told().receipts).isEqualTo(inMemory.told().receipts);
        List<Transaction> keyed = List.of(onDisk.ending());
        List<Id> ids = onDisk.told().ids();
        StoreEquivalence.Observation expected = StoreEquivalence.observe(memory, ids, EFFECTIVE_TIMES, keyed);
        StoreEquivalence.assertEquivalent(expected, StoreEquivalence.observe(store, ids, EFFECTIVE_TIMES, keyed));
        assertThat(expected.keyed()).as("the receipt that the key gets again lists what the cascade ended")
                .singleElement().satisfies(result -> assertThat(((CommitResult.Committed) result).receipt().ended())
                        .containsExactlyElementsOf(ended));

        store.close();
        DirectoryStore reopened = durable.open();
        StoreEquivalence.assertEquivalent(expected, StoreEquivalence.observe(reopened, ids, EFFECTIVE_TIMES, keyed));
        assertThat(reopened.view(Coordinates.effectiveAt(on(5, 31))).relationship(mailedTo)).isPresent();
        assertThat(reopened.view(Coordinates.effectiveAt(on(6, 1))).relationship(mailedTo)).isEmpty();
        reopened.writeSnapshot();
        DurableFixture.crash(reopened);

        DirectoryStore recovered = durable.open();
        StoreEquivalence.assertEquivalent(expected, StoreEquivalence.observe(recovered, ids, EFFECTIVE_TIMES, keyed));
    }
}
