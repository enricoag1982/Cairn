package io.github.enricoag1982.cairn.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.time.Instant;
import java.util.List;

/**
 * An in-memory store with the reference model registered and a clock that the test moves, with shortcuts to commit,
 * to reject and to replay the {@linkplain ReferenceStories stories of the reference domain}. It is created with the
 * rules of the reference domain: {@code lei-checksum} runs, and {@code total-ownership-at-most-100} is declared without
 * code until relationships exist. The deterministic ID
 * generator makes IDs predictable: {@link #id(int)}. It lives in the tests of {@code cairn-core}, because the test
 * fixtures of {@code cairn-api} cannot create a store; the stories, dates and values are in {@link ReferenceStories}.
 */
final class ReferenceStore {

    final TestClock clock = new TestClock();
    final Store store;

    /** A store with deterministic IDs, in which the reference model 1.0.0 was registered on 5 January. */
    ReferenceStore() {
        this(IdGenerators.deterministic());
    }

    ReferenceStore(IdGenerator generator) {
        this(generator, ReferenceModel.definition());
    }

    private ReferenceStore(IdGenerator generator, SchemaDefinition definition) {
        this(generator, definition, ReferenceModel.executableRules(), ReferenceModel.declaredRules());
    }

    private ReferenceStore(IdGenerator generator, SchemaDefinition definition, List<Rule> rules,
            List<RuleDescriptor> declaredRules) {
        store = Stores.inMemory(StoreSettings.builder()
                .clock(clock)
                .idGenerator(generator)
                .rules(rules)
                .declaredRules(declaredRules)
                .build());
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), definition))
                .isInstanceOf(RegistrationResult.Applied.class);
    }

    /** A store with deterministic IDs in which another schema than the reference model was registered. */
    static ReferenceStore with(SchemaDefinition definition) {
        return new ReferenceStore(IdGenerators.deterministic(), definition);
    }

    /**
     * A store with deterministic IDs and exactly the given rules, in which a schema was registered. The reference model
     * names {@code total-ownership-at-most-100}, so a schema derived from it needs that rule declared.
     *
     * @param definition    the schema to register
     * @param rules         the rules with code
     * @param declaredRules the rules declared without code
     */
    static ReferenceStore withRules(SchemaDefinition definition, List<Rule> rules, List<RuleDescriptor> declaredRules) {
        return new ReferenceStore(IdGenerators.deterministic(), definition, rules, declaredRules);
    }

    /**
     * Store settings with the rules of the reference domain: {@code lei-checksum} with its code, and
     * {@code total-ownership-at-most-100} declared without it. Chain the clock and the ID generator.
     */
    static StoreSettings.Builder referenceSettings() {
        return StoreSettings.builder().rules(ReferenceModel.executableRules())
                .declaredRules(ReferenceModel.declaredRules());
    }

    /** The deterministic generator's n-th ID. */
    static EntityId id(int n) {
        return new EntityId(String.format("00000000-0000-7000-8000-%012x", n));
    }

    /** Moves the clock to a time; the transaction recorded next gets that transaction time. */
    ReferenceStore recordedOn(Instant time) {
        clock.moveTo(time);
        return this;
    }

    /** Commits a transaction that must succeed. */
    Receipt commit(Transaction transaction) {
        CommitResult result = store.commit(transaction);
        assertThat(result).as("commit result").isInstanceOf(CommitResult.Committed.class);
        return ((CommitResult.Committed) result).receipt();
    }

    /** Commits a transaction that must be rejected, and returns its problems. */
    List<Problem> reject(Transaction transaction) {
        return rejected(store.commit(transaction));
    }

    /** A view at an effective time, as known now. */
    View view(Instant effectiveAt) {
        return store.view(Coordinates.effectiveAt(effectiveAt));
    }

    /** Replays a story: moves the clock to its recording date and commits its transaction, which must succeed. */
    Receipt replay(ReferenceStories.Recording recording) {
        return ReferenceStories.replay(store, clock, recording);
    }

    /** Replays a story that the store must reject, and returns the problems. */
    List<Problem> rejectReplay(ReferenceStories.Recording recording) {
        return rejected(ReferenceStories.attempt(store, clock, recording));
    }

    private static List<Problem> rejected(CommitResult result) {
        assertThat(result).as("commit result").isInstanceOf(CommitResult.Rejected.class);
        return ((CommitResult.Rejected) result).problems();
    }

    /** RD-1: ACME and Beta (organizations) and Alice (a person), from 1 January, recorded on 5 January. */
    Receipt createParties() {
        return replay(ReferenceStories.rd1Parties());
    }

    /** RD-5: accounts A-1 and A-2 from 1 January with balances 1,000 and 2,000, recorded on 5 January. */
    Receipt createAccounts() {
        return replay(ReferenceStories.rd5Accounts());
    }

    static void assertProblem(Problem problem, ProblemCode code, String path) {
        assertThat(problem.code()).isEqualTo(code);
        assertThat(problem.path()).isEqualTo(path);
    }
}
