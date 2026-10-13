package io.github.enricoag1982.cairn.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ControllingOwnerHasLeiRule;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.EmployerHoldsAccountRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An in-memory store with the reference model registered and a clock that the test moves, with shortcuts to commit,
 * to reject and to replay the {@linkplain ReferenceStories stories of the reference domain}. It is created with the
 * rules of the reference domain, which all have code: {@code lei-checksum} and
 * {@code total-ownership-at-most-100} run. The deterministic ID generator makes IDs predictable: {@link #id(int)}. It
 * lives in the tests of {@code cairn-core}, because the test fixtures of {@code cairn-api} cannot create a store; the
 * stories, dates and values are in {@link ReferenceStories}.
 */
final class ReferenceStore {

    final TestClock clock = new TestClock();
    final Store store;
    /** The receipt of the latest {@link #createParties()}. */
    Receipt parties;

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
     * Store settings with the rules of the reference domain, {@code lei-checksum} and
     * {@code total-ownership-at-most-100}, both with their code. Chain the clock and the ID generator.
     */
    static StoreSettings.Builder referenceSettings() {
        return StoreSettings.builder().rules(ReferenceModel.executableRules())
                .declaredRules(ReferenceModel.declaredRules());
    }

    /**
     * A store of the reference domain in which {@code Employs} names the test-only rule {@code employer-holds-account},
     * which reads the accounts of the employer, and which the store has the code of.
     */
    static ReferenceStore withEmployerHoldsAccount() {
        return withRules(Definitions.withRule(ReferenceModel.definition(), "Employs",
                EmployerHoldsAccountRule.DESCRIPTOR), rulesWith(new EmployerHoldsAccountRule()),
                ReferenceModel.declaredRules());
    }

    /**
     * A store of the reference domain in which {@code Owns} names the test-only rule
     * {@code controlling-owner-has-lei}, which reads the owner, and which the store has the code of. {@code Owns} names
     * {@code total-ownership-at-most-100} too.
     */
    static ReferenceStore withControllingOwnerHasLei() {
        return withRules(Definitions.withRule(ReferenceModel.definition(), "Owns",
                ControllingOwnerHasLeiRule.DESCRIPTOR), rulesWith(new ControllingOwnerHasLeiRule()),
                ReferenceModel.declaredRules());
    }

    /** The rules of the reference domain, which have code, and the given rules besides. */
    static List<Rule> rulesWith(Rule... extra) {
        List<Rule> rules = new ArrayList<>(ReferenceModel.executableRules());
        rules.addAll(List.of(extra));
        return rules;
    }

    /** The deterministic generator's n-th ID, as the ID of an entity. */
    static EntityId id(int n) {
        return new EntityId(String.format("00000000-0000-7000-8000-%012x", n));
    }

    /** The deterministic generator's n-th ID, as the ID of a relationship. */
    static RelationshipId relationshipId(int n) {
        return new RelationshipId(String.format("00000000-0000-7000-8000-%012x", n));
    }

    /** The ID of the entity that a receipt gives a temporary ID. */
    static EntityId entity(Receipt receipt, TemporaryId temporaryId) {
        return (EntityId) receipt.temporaryIds().get(temporaryId);
    }

    /** The ID of the relationship that a receipt gives a temporary ID. */
    static RelationshipId relationship(Receipt receipt, TemporaryId temporaryId) {
        return (RelationshipId) receipt.temporaryIds().get(temporaryId);
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
        parties = replay(ReferenceStories.rd1Parties());
        return parties;
    }

    /**
     * RD-1 and RD-5: the parties, then accounts A-1 (held by Beta) and A-2 (held by ACME) from 1 January with balances
     * 1,000 and 2,000, both recorded on 5 January. An account needs its holder, so the parties are created first.
     *
     * @return the receipt of the accounts; the receipt of the parties is the field {@link #parties}
     */
    Receipt createAccounts() {
        createParties();
        return replay(ReferenceStories.rd5Accounts(parties.temporaryIds().get(ReferenceStories.BETA),
                parties.temporaryIds().get(ReferenceStories.ACME)));
    }

    static void assertProblem(Problem problem, ProblemCode code, String path) {
        assertThat(problem.code()).isEqualTo(code);
        assertThat(problem.path()).isEqualTo(path);
    }
}
