package io.github.enricoag1982.cairn.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.commit.CommitSequence;
import io.github.enricoag1982.cairn.core.kv.RecordingKeyValueStore;
import io.github.enricoag1982.cairn.core.schema.InMemorySchemaRegistry;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.List;
import java.util.Set;

/**
 * Prepares and records transactions the way {@link InMemoryStore} does, on a {@link Ledger} that a test of this package
 * can read as it is stored, without a view: the timeline that a commit wrote, and the ones before it. The reference
 * model is registered, and the rules of the reference domain have their code.
 */
final class Recorder {

    final TestClock clock = new TestClock();
    final CommitSequence sequence = new CommitSequence(clock);
    final InMemorySchemaRegistry registry = new InMemorySchemaRegistry(sequence, ReferenceModel.rules());
    final RecordingKeyValueStore keys = new RecordingKeyValueStore();
    final Ledger ledger = new Ledger(keys);
    private final Rules rules = new Rules(ReferenceModel.executableRules());
    private IdGenerator generator = IdGenerators.deterministic();

    Recorder() {
        assertThat(registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .isInstanceOf(RegistrationResult.Applied.class);
    }

    /** Prepares a transaction, without recording it. */
    Preparation prepare(Transaction transaction) {
        return sequence.underWriterLock(() -> new Preparation(transaction, new SchemaIndex(registry.current()),
                ledger, Set.of(), generator.copy(), sequence.next(), new ValueChecks(), rules, sequence).run());
    }

    /** Prepares a transaction that must be valid, and records it. */
    Preparation commit(Transaction transaction) {
        return sequence.underWriterLock(() -> {
            Preparation preparation = prepare(transaction);
            assertThat(preparation.problems()).isEmpty();
            ledger.write(preparation.commit().basis(), preparation.changes(), preparation.index());
            generator = preparation.generator();
            sequence.publish(preparation.commit());
            return preparation;
        });
    }

    /** An entity or relationship as the ledger holds it: what is stored of it, and its timelines by basis. */
    final class Stored {

        private final Held latest;

        private Stored(Held latest) {
            this.latest = latest;
        }

        TypeId type() {
            return latest.type();
        }

        EntityId source() {
            return latest.source();
        }

        EntityId target() {
            return latest.target();
        }

        /** The timeline known after the latest transaction that changed it. */
        Timeline latest() {
            return latest.timeline();
        }

        /** The basis of the latest transaction that changed it. */
        Basis latestBasis() {
            return latest.basis();
        }

        /** The timeline known after the latest transaction at or below a basis; empty if it was not recorded. */
        Timeline at(Basis basis) {
            return ledger.timelineAt(latest.id(), basis);
        }
    }

    /** The entity as the ledger holds it. */
    Stored entity(EntityId id) {
        return new Stored(ledger.entity(id).orElseThrow());
    }

    /** The relationship as the ledger holds it. */
    Stored relationship(RelationshipId id) {
        return new Stored(ledger.relationship(id).orElseThrow());
    }

    /** The IDs of the changes of a preparation, in the order of first touch. */
    static List<String> changed(Preparation preparation) {
        return preparation.changes().stream().map(change -> change.id().value()).toList();
    }
}
