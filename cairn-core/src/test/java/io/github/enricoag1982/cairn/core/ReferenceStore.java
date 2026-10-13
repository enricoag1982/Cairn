package io.github.enricoag1982.cairn.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory store with the reference model registered and a clock that the test moves, with shortcuts for the
 * stories of the reference domain. The deterministic ID generator makes IDs predictable: {@link #id(int)}.
 */
final class ReferenceStore {

    static final TemporaryId ACME = new TemporaryId("acme");
    static final TemporaryId BETA = new TemporaryId("beta");
    static final TemporaryId ALICE = new TemporaryId("alice");
    static final String LEI = "5493001KJTIIGC8Y1R12";

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
        store = Stores.inMemory(StoreSettings.builder()
                .clock(clock)
                .idGenerator(generator)
                .availableRules(ReferenceModel.rules())
                .build());
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), definition))
                .isInstanceOf(RegistrationResult.Applied.class);
    }

    /** A store with deterministic IDs in which another schema than the reference model was registered. */
    static ReferenceStore with(SchemaDefinition definition) {
        return new ReferenceStore(IdGenerators.deterministic(), definition);
    }

    /** Midnight UTC on a day of 2026. */
    static Instant on(int month, int day) {
        return on(2026, month, day);
    }

    static Instant on(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** The deterministic generator's n-th ID. */
    static EntityId id(int n) {
        return new EntityId(String.format("00000000-0000-7000-8000-%012x", n));
    }

    static EffectivePeriod from(int month, int day) {
        return EffectivePeriod.from(on(month, day));
    }

    /** Values from alternating names and values. */
    static Map<String, Value> values(Object... namesAndValues) {
        Map<String, Value> values = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            Object value = namesAndValues[i + 1];
            values.put((String) namesAndValues[i], switch (value) {
                case Value constant -> constant;
                case String text -> Value.of(text);
                case Integer number -> Value.of(number);
                case Long number -> Value.of(number);
                case java.math.BigDecimal number -> Value.of(number);
                case LocalDate date -> Value.of(date);
                default -> throw new IllegalArgumentException("Not a value: " + value);
            });
        }
        return values;
    }

    static java.math.BigDecimal money(String amount) {
        return new java.math.BigDecimal(amount);
    }

    /** Moves the clock to a time; the transaction recorded next gets that transaction time. */
    ReferenceStore recordedOn(Instant time) {
        clock.advance(Duration.between(clock.instant(), time));
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
        CommitResult result = store.commit(transaction);
        assertThat(result).as("commit result").isInstanceOf(CommitResult.Rejected.class);
        return ((CommitResult.Rejected) result).problems();
    }

    /** A view at an effective time, as known now. */
    View view(Instant effectiveAt) {
        return store.view(Coordinates.effectiveAt(effectiveAt));
    }

    /** RD-1: ACME and Beta (organizations) and Alice (a person), from 1 January, recorded now. */
    Receipt createParties() {
        return commit(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .create(BETA, "Organization", from(1, 1), values("name", "Beta"))
                .create(ALICE, "Person", from(1, 1), values("name", "Alice"))
                .build());
    }

    /** RD-5: accounts A-1 and A-2 from 1 January with balances 1,000 and 2,000. */
    Receipt createAccounts() {
        return commit(Transaction.by("importer")
                .create(new TemporaryId("a1"), "Account", from(1, 1),
                        values("number", "A-1", "currency", "EUR", "balance", money("1000")))
                .create(new TemporaryId("a2"), "Account", from(1, 1),
                        values("number", "A-2", "currency", "EUR", "balance", money("2000")))
                .build());
    }

    static void assertProblem(Problem problem, ProblemCode code, String path) {
        assertThat(problem.code()).isEqualTo(code);
        assertThat(problem.path()).isEqualTo(path);
    }
}
