package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.Results.applied;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.commit.CommitSequence;
import io.github.enricoag1982.cairn.core.schema.InMemorySchemaRegistry;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.SchemaTransaction;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Design D3: a schema registry built on a sequence and a lock that it shares with other writers. */
class SharedSequenceTest {

    private final TestClock clock = new TestClock();
    private final CommitSequence sequence = new CommitSequence(clock);
    private final SchemaRegistry registry = new InMemorySchemaRegistry(sequence, ReferenceModel.rules());

    private static SchemaDefinition small(String name) {
        return SchemaDefinition.builder().entityType(name).build();
    }

    /** Stands for a data transaction: takes a basis and a transaction time from the shared sequence. */
    private CommitSequence.Commit dataCommit() {
        return sequence.underWriterLock(() -> {
            CommitSequence.Commit commit = sequence.next();
            sequence.publish(commit);
            return commit;
        });
    }

    @Test
    void schemaAndDataTransactionsShareOneSequenceOfBasesAndTimes() {
        var first = applied(registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()));
        CommitSequence.Commit data = dataCommit();
        var second = applied(registry.register(new Origin("extra", "1"), small("Extra")));

        assertThat(first.basis()).isEqualTo(new Basis(1));
        assertThat(data.basis()).isEqualTo(new Basis(2));
        assertThat(second.basis()).isEqualTo(new Basis(3));
        assertThat(data.transactionTime()).isAfter(first.transactionTime());
        assertThat(second.transactionTime()).isAfter(data.transactionTime());
        assertThat(sequence.latestBasis()).isEqualTo(new Basis(3));
    }

    @Test
    void theSchemaHistoryHasTheBasesOfItsOwnTransactionsOnly() {
        applied(registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()));
        dataCommit();
        dataCommit();
        applied(registry.register(new Origin("extra", "1"), small("Extra")));

        assertThat(registry.history()).extracting(SchemaTransaction::basis)
                .containsExactly(new Basis(1), new Basis(4));
    }

    @Test
    void theSchemaAtABasisInBetweenIsTheOneOfTheLatestSchemaTransactionBeforeIt() {
        applied(registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()));
        dataCommit();
        applied(registry.register(new Origin("extra", "1"), small("Extra")));

        assertThat(registry.asOf(new Basis(1)).entityType("Extra")).isEmpty();
        assertThat(registry.asOf(new Basis(2)).entityType("Extra")).isEmpty();
        assertThat(registry.asOf(new Basis(2)).entityType("Person")).isPresent();
        assertThat(registry.asOf(new Basis(3)).entityType("Extra")).isPresent();
    }

    @Test
    void theSchemaAtATransactionTimeInBetweenIsTheOneOfTheLatestSchemaTransactionBeforeIt() {
        var first = applied(registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()));
        CommitSequence.Commit data = dataCommit();
        var second = applied(registry.register(new Origin("extra", "1"), small("Extra")));

        assertThat(registry.asOf(data.transactionTime()).entityType("Extra")).isEmpty();
        assertThat(registry.asOf(data.transactionTime()).entityType("Person")).isPresent();
        assertThat(registry.asOf(second.transactionTime()).entityType("Extra")).isPresent();
        assertThat(registry.asOf(first.transactionTime().minus(1, ChronoUnit.MICROS)).isEmpty()).isTrue();
    }

    @Test
    void registrationsThatChangeNothingDoNotUseABasis() {
        applied(registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()));

        RegistrationResult again = registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition());
        RegistrationResult invalid = registry.register(new Origin("bad origin!", "x"), small("Bad"));

        assertThat(again).isInstanceOf(RegistrationResult.Unchanged.class);
        assertThat(invalid).isInstanceOf(RegistrationResult.Rejected.class);
        assertThat(sequence.latestBasis()).isEqualTo(new Basis(1));
        assertThat(dataCommit().basis()).isEqualTo(new Basis(2));
    }

    @Test
    void registeringWaitsForTheWriterLock() throws InterruptedException {
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread writer = new Thread(() -> sequence.underWriterLock(() -> {
            holding.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        writer.start();
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

        Thread registrar = new Thread(() -> registry.register(new Origin("extra", "1"), small("Extra")));
        registrar.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (registrar.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(registrar.getState()).as("waits for the lock").isEqualTo(Thread.State.WAITING);
        assertThat(registry.current().isEmpty()).isTrue();

        release.countDown();
        writer.join(10_000);
        registrar.join(10_000);
        assertThat(registry.current().entityType("Extra")).isPresent();
    }

    @Test
    void registriesFromTheFactoriesHaveASequenceOfTheirOwn() {
        SchemaRegistry first = SchemaRegistries.inMemory(clock);
        SchemaRegistry second = SchemaRegistries.inMemory(clock);

        var one = applied(first.register(new Origin("a", "1"), small("A")));
        var two = applied(second.register(new Origin("b", "1"), small("B")));

        assertThat(one.basis()).isEqualTo(new Basis(1));
        assertThat(two.basis()).isEqualTo(new Basis(1));
    }
}
