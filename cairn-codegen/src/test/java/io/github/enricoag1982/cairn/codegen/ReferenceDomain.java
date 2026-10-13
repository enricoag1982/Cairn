package io.github.enricoag1982.cairn.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.documents.DocumentReadResult;
import io.github.enricoag1982.cairn.documents.SchemaDocument;
import io.github.enricoag1982.cairn.documents.SchemaDocuments;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * An in-memory store with the reference model registered from the schema document the types were generated from, a
 * clock that tests move, and shortcuts to commit stories and read states.
 */
final class ReferenceDomain {

    /** The schema document of the reference domain. */
    static final Path DOCUMENT = Path.of("").toAbsolutePath().getParent()
            .resolve("docs/reference/reference-model.cairn.yaml");

    final TestClock clock = new TestClock();
    final Store store;

    /** A store that runs the rules of the reference domain, which have code in the test fixtures. */
    ReferenceDomain() {
        this(ReferenceModel.executableRules());
    }

    /** A store that runs the given rules. */
    ReferenceDomain(List<Rule> rules) {
        store = Stores.inMemory(StoreSettings.builder().clock(clock).rules(rules).build());
        SchemaDocument document = read(DOCUMENT);
        assertThat(store.schema().register(document.origin(), document.definition()))
                .isInstanceOf(RegistrationResult.Applied.class);
    }

    static SchemaDocument read(Path file) {
        try {
            DocumentReadResult result = SchemaDocuments.read(Files.readString(file));
            assertThat(result).isInstanceOf(DocumentReadResult.Read.class);
            return ((DocumentReadResult.Read) result).document();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Replays a story: the clock moves to its recording date, and its transaction must be committed. */
    Receipt replay(ReferenceStories.Recording recording) {
        return ReferenceStories.replay(store, clock, recording);
    }

    /** Commits a transaction at a recording time; it must be committed. */
    Receipt commit(Instant recordedAt, Transaction transaction) {
        clock.moveTo(recordedAt);
        CommitResult result = store.commit(transaction);
        assertThat(result).as("commit result").isInstanceOf(CommitResult.Committed.class);
        return ((CommitResult.Committed) result).receipt();
    }

    /** Commits a transaction at a recording time and returns the result, committed or rejected. */
    CommitResult attempt(Instant recordedAt, Transaction transaction) {
        clock.moveTo(recordedAt);
        return store.commit(transaction);
    }

    /** A view at an effective time, as known now. */
    View view(Instant effectiveAt) {
        return store.view(Coordinates.effectiveAt(effectiveAt));
    }

    /** The state of an entity at an effective time. */
    EntityState entity(Instant effectiveAt, EntityId id) {
        return view(effectiveAt).entity(id).orElseThrow(() -> new AssertionError("No entity " + id));
    }

    /** The state of a relationship at an effective time. */
    RelationshipState relationship(Instant effectiveAt, RelationshipId id) {
        return view(effectiveAt).relationship(id).orElseThrow(() -> new AssertionError("No relationship " + id));
    }

    static EntityId entityId(Receipt receipt, TemporaryId temporaryId) {
        return (EntityId) receipt.temporaryIds().get(temporaryId);
    }

    static RelationshipId relationshipId(Receipt receipt, TemporaryId temporaryId) {
        return (RelationshipId) receipt.temporaryIds().get(temporaryId);
    }
}
