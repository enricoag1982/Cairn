package io.github.enricoag1982.cairn.documents;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.SchemaRegistries;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.util.List;

/** Helpers for the tests that read transaction payloads. */
final class PayloadFixtures {

    private PayloadFixtures() {
    }

    // ------------------------------------------------------------------ stores and schemas

    /** An in-memory store with the reference model registered, deterministic IDs and a clock the test moves. */
    static final class ReferenceDomainStore {

        final TestClock clock = new TestClock();
        final Store store;

        ReferenceDomainStore() {
            store = Stores.inMemory(StoreSettings.builder()
                    .clock(clock)
                    .idGenerator(IdGenerators.deterministic())
                    .rules(ReferenceModel.executableRules())
                    .declaredRules(ReferenceModel.declaredRules())
                    .build());
            assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                    .isInstanceOf(RegistrationResult.Applied.class);
        }

        Schema schema() {
            return store.schema().current();
        }

        /** The latest view, at a time late enough that the stories of the reference domain are effective. */
        View latest() {
            return store.view(Coordinates.effectiveAt(ReferenceStories.on(12, 31)));
        }

        /** A lookup of the IDs the store holds, through the timelines of its latest view. */
        IdLookup lookup() {
            return IdLookup.from(latest());
        }

        /** A lookup of the IDs the store holds and of the entities that unique keys name, through its latest views. */
        IdLookup storeLookup() {
            return IdLookup.fromStore(store);
        }

        /** Reads a payload that must be valid, with the lookup of the store that also resolves unique keys. */
        TransactionPayload readWithKeys(String text) {
            return PayloadFixtures.read(text, schema(), storeLookup());
        }

        /** Replays a story of the reference domain: moves the clock to its recording date and commits it. */
        Receipt replay(ReferenceStories.Recording recording) {
            return ReferenceStories.replay(store, clock, recording);
        }

        /** Commits a transaction that must succeed. */
        Receipt commit(Transaction transaction) {
            CommitResult result = store.commit(transaction);
            assertThat(result).as("commit result").isInstanceOf(CommitResult.Committed.class);
            return ((CommitResult.Committed) result).receipt();
        }

        /** Runs a transaction dry, and returns its problems; it must be rejected. */
        List<Problem> dryRunProblems(Transaction transaction) {
            DryRunResult result = store.dryRun(transaction);
            assertThat(result).as("dry run result").isInstanceOf(DryRunResult.Rejected.class);
            return ((DryRunResult.Rejected) result).problems();
        }

        /** Commits a transaction that must be rejected, and returns its problems. */
        List<Problem> rejectedProblems(Transaction transaction) {
            CommitResult result = store.commit(transaction);
            assertThat(result).as("commit result").isInstanceOf(CommitResult.Rejected.class);
            return ((CommitResult.Rejected) result).problems();
        }

        /** Reads a payload that must be valid, with the lookup of the store. */
        TransactionPayload read(String text) {
            return PayloadFixtures.read(text, schema(), lookup());
        }
    }

    /** Registers a schema in a registry of its own, and returns the schema. */
    static Schema schemaOf(SchemaDefinition definition) {
        RegistrationResult result = SchemaRegistries.inMemory(new TestClock())
                .register(new Origin("test-model", "1.0.0"), definition);
        assertThat(result).isInstanceOf(RegistrationResult.Applied.class);
        return ((RegistrationResult.Applied) result).schema();
    }

    // ------------------------------------------------------------------ reading

    /** Reads a payload that must have no problems. */
    static TransactionPayload read(String text, Schema schema) {
        return read(text, schema, IdLookup.none());
    }

    /** Reads a payload that must have no problems. */
    static TransactionPayload read(String text, Schema schema, IdLookup lookup) {
        PayloadReadResult result = Payloads.readTransaction(text, schema, lookup);
        assertThat(result).as("result of reading:%n%s", text).isInstanceOf(PayloadReadResult.Read.class);
        return ((PayloadReadResult.Read) result).payload();
    }

    /** Reads a payload that must have problems, and returns them; every one is located. */
    static List<Problem> problems(String text, Schema schema) {
        PayloadReadResult result = Payloads.readTransaction(text, schema);
        assertThat(result).as("result of reading:%n%s", text).isInstanceOf(PayloadReadResult.Invalid.class);
        List<Problem> problems = ((PayloadReadResult.Invalid) result).problems();
        assertThat(problems).as("every problem of a payload is located").allSatisfy(problem ->
                assertThat(problem.location()).as(problem.toString()).isPresent());
        return problems;
    }

    /** Reads a payload that must have exactly one problem, and returns it. */
    static Problem problem(String text, Schema schema) {
        List<Problem> problems = problems(text, schema);
        assertThat(problems).as("problems of:%n%s", text).hasSize(1);
        return problems.getFirst();
    }

    // ------------------------------------------------------------------ payload text

    /**
     * A JSON payload by the principal {@code agent:onboarding} with the given writes, each on a line of its own: the
     * first write is on line 2, the second on line 3, and so on.
     */
    static String payload(String... writes) {
        return "{ \"cairn\": 1, \"principal\": \"agent:onboarding\", \"writes\": [\n"
                + String.join(",\n", writes) + "\n] }\n";
    }

    /** A write that creates an organization from 1 January 2026 with a temporary ID. */
    static String createOrganization(String temporaryId, String name) {
        return "{ \"create\": { \"temporaryId\": \"" + temporaryId + "\", \"entityType\": \"Organization\", "
                + "\"from\": \"2026-01-01T00:00:00Z\", \"values\": { \"name\": \"" + name + "\" } } }";
    }

    /** The location of the first occurrence of a token in a text: the line and column count from 1. */
    static DocumentLocation locationOf(String text, String token, String pointer) {
        int index = text.indexOf(token);
        assertThat(index).as("%s in:%n%s", token, text).isNotNegative();
        int line = 1 + (int) text.substring(0, index).chars().filter(c -> c == '\n').count();
        int column = index - text.lastIndexOf('\n', index - 1);
        return new DocumentLocation(pointer, line, column);
    }

    /** The JSON Pointers that problems are located at, in order. */
    static List<String> pointers(List<Problem> problems) {
        return problems.stream().map(problem -> problem.location().orElseThrow().pointer()).toList();
    }
}
