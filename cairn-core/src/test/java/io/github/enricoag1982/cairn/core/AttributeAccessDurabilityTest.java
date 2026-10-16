package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.Value;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Classifications and clearances in durable stores, whichever kind the run of the tests is for: a directory store, or a
 * RocksDB store (see {@link TestBackend}). The classifications of the schema survive a reopening, and so does the
 * clearance of a transaction that has an idempotency key, which a retry must repeat (ADR-0036, design D1 and D4 of
 * {@code add-attribute-access-control}). The recovery of directory stores from their log is shown in
 * {@code DurableClearanceTest}.
 */
class AttributeAccessDurabilityTest {

    @TempDir
    Path directory;

    private final List<DurableStore> opened = new ArrayList<>();

    @AfterEach
    void closeStores() {
        opened.forEach(DurableStore::close);
    }

    private StoreSettings settings(TestClock clock) {
        return StoreSettings.builder().clock(clock).idGenerator(IdGenerators.deterministic())
                .rules(ReferenceModel.executableRules()).declaredRules(ReferenceModel.declaredRules()).build();
    }

    private DurableStore open(TestClock clock) {
        StoreSettings settings = settings(clock);
        DurableStore store = TestBackend.isRocksDb() ? TestBackend.openRocksDb(directory, settings)
                : Stores.durable(directory, settings);
        opened.add(store);
        return store;
    }

    private record Told(EntityId a1, EntityId alice, EntityId acme) {
    }

    private static Told tell(DurableStore store, TestClock clock) {
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), ClassifiedModel.definition()))
                .isInstanceOf(RegistrationResult.Applied.class);
        Receipt parties = ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        Receipt accounts = ReferenceStories.replay(store, clock, ReferenceStories.rd5Accounts(
                parties.temporaryIds().get(BETA), parties.temporaryIds().get(ACME)));
        return new Told((EntityId) accounts.temporaryIds().get(A1), (EntityId) parties.temporaryIds().get(ALICE),
                (EntityId) parties.temporaryIds().get(ACME));
    }

    private static Transaction renameAlice(EntityId alice, Clearance clearance) {
        Transaction.Builder builder = Transaction.by(PRINCIPAL).idempotencyKey("rename-alice");
        if (clearance != null) {
            builder.clearance(clearance);
        }
        return builder.patch(alice, from(4, 1), values("name", "Alice Smith")).build();
    }

    @Test
    void theClassificationsOfTheSchemaSurviveAReopening() {
        TestClock clock = new TestClock();
        DurableStore first = open(clock);
        Told told = tell(first, clock);
        first.close();

        DurableStore reopened = open(clock);

        assertThat(reopened.schema().current().entityType("Account").orElseThrow().attributes())
                .extracting(attribute -> attribute.name(), attribute -> attribute.classification())
                .containsExactly(tuple("number", Optional.empty()),
                        tuple("currency", Optional.of("operations")),
                        tuple("balance", Optional.of("finance")));
        EntityState state = reopened.view(Coordinates.effectiveAt(on(3, 31))).restrictedTo(Clearance.of("finance"))
                .entity(told.a1()).orElseThrow();
        assertThat(state.values()).containsEntry("balance", Value.of(money("1000"))).doesNotContainKey("currency");
        assertThat(state.withheld()).containsExactly("currency");
        clock.moveTo(on(4, 2));
        assertThat(reopened.commit(Transaction.by(PRINCIPAL).clearance(Clearance.none())
                .patch(told.a1(), from(4, 1), values("balance", money("1"))).build()))
                .isInstanceOf(CommitResult.Rejected.class);
    }

    @Test
    void aClassificationAddedByALaterVersionSurvivesAReopening() {
        TestClock clock = new TestClock();
        DurableStore first = open(clock);
        assertThat(first.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .isInstanceOf(RegistrationResult.Applied.class);
        clock.moveTo(on(2, 1));
        assertThat(first.schema().register(ReferenceModel.origin("1.1.0"), Definitions.withClassification(
                ReferenceModel.definition(), "Account", "balance", "finance")))
                .isInstanceOf(RegistrationResult.Applied.class);
        first.close();

        DurableStore reopened = open(clock);

        assertThat(reopened.schema().current().entityType("Account").orElseThrow().attribute("balance").orElseThrow()
                .classification()).hasValue("finance");
    }

    @Test
    void theClearanceOfATransactionWithAnIdempotencyKeyIsKeptForTheRetry() {
        TestClock clock = new TestClock();
        DurableStore first = open(clock);
        Told told = tell(first, clock);
        clock.moveTo(on(4, 2));
        Receipt receipt = ((CommitResult.Committed) first.commit(renameAlice(told.alice(),
                Clearance.of("identity", "finance")))).receipt();
        first.close();

        DurableStore reopened = open(clock);

        assertThat(reopened.commit(renameAlice(told.alice(), Clearance.of("finance", "identity"))))
                .isEqualTo(new CommitResult.Committed(receipt));
        assertThat(reopened.dryRun(renameAlice(told.alice(), Clearance.of("finance", "identity"))))
                .isInstanceOf(DryRunResult.WouldCommit.class);
        for (Clearance other : new Clearance[] {Clearance.of("identity"), Clearance.none(), null}) {
            CommitResult retry = reopened.commit(renameAlice(told.alice(), other));
            assertThat(retry).as("a retry with %s", other).isInstanceOf(CommitResult.Rejected.class);
            List<Problem> problems = ((CommitResult.Rejected) retry).problems();
            assertThat(problems).singleElement()
                    .satisfies(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_REUSED));
        }
    }

    @Test
    void aClearanceThatCoversNothingIsKeptAsSuchAndNotAsNoClearance() {
        TestClock clock = new TestClock();
        DurableStore first = open(clock);
        Told told = tell(first, clock);
        clock.moveTo(on(4, 2));
        Receipt withNone = ((CommitResult.Committed) first.commit(renameAlice(told.alice(), Clearance.none()))).receipt();
        first.close();

        DurableStore reopened = open(clock);

        assertThat(reopened.commit(renameAlice(told.alice(), Clearance.none())))
                .isEqualTo(new CommitResult.Committed(withNone));
        assertThat(reopened.commit(renameAlice(told.alice(), null))).isInstanceOf(CommitResult.Rejected.class);
    }

    @Test
    void aTransactionWithoutAClearanceIsKeptWithoutOne() {
        TestClock clock = new TestClock();
        DurableStore first = open(clock);
        Told told = tell(first, clock);
        clock.moveTo(on(4, 2));
        Receipt without = ((CommitResult.Committed) first.commit(renameAlice(told.alice(), null))).receipt();
        first.close();

        DurableStore reopened = open(clock);

        assertThat(reopened.commit(renameAlice(told.alice(), null))).isEqualTo(new CommitResult.Committed(without));
        assertThat(reopened.commit(renameAlice(told.alice(), Clearance.none()))).isInstanceOf(CommitResult.Rejected.class);
    }
}
