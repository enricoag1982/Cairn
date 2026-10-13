package io.github.enricoag1982.cairn.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.commit.CommitSequence;
import io.github.enricoag1982.cairn.core.schema.InMemorySchemaRegistry;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prepares and records transactions the way {@link InMemoryStore} does, but keeps the stored entities and relationships
 * in plain maps, so that a test of this package can read the timelines that a commit appended, which no view reads for
 * relationships yet. The reference model is registered, and the rules of the reference domain have their code.
 */
final class Recorder {

    final TestClock clock = new TestClock();
    final CommitSequence sequence = new CommitSequence(clock);
    final InMemorySchemaRegistry registry = new InMemorySchemaRegistry(sequence, ReferenceModel.rules());
    final Map<EntityId, StoredEntity> entities = new HashMap<>();
    final Map<RelationshipId, StoredRelationship> relationships = new HashMap<>();
    private final Rules rules = new Rules(ReferenceModel.executableRules());
    private IdGenerator generator = IdGenerators.deterministic();

    Recorder() {
        assertThat(registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .isInstanceOf(RegistrationResult.Applied.class);
    }

    /** Prepares a transaction, without recording it. */
    Preparation prepare(Transaction transaction) {
        return sequence.underWriterLock(() -> new Preparation(transaction, new SchemaIndex(registry.current()),
                entities, relationships, generator.copy(), sequence.next(), new ValueChecks(), rules, sequence)
                .run());
    }

    /** Prepares a transaction that must be valid, and records it. */
    Preparation commit(Transaction transaction) {
        return sequence.underWriterLock(() -> {
            Preparation preparation = prepare(transaction);
            assertThat(preparation.problems()).isEmpty();
            for (Preparation.Change change : new ArrayList<>(preparation.changes())) {
                record(preparation, change);
            }
            generator = preparation.generator();
            sequence.publish(preparation.commit());
            return preparation;
        });
    }

    private void record(Preparation preparation, Preparation.Change change) {
        switch (change.id()) {
            case EntityId id -> {
                StoredEntity entity = change.created() ? new StoredEntity(id, change.type()) : entities.get(id);
                entity.append(preparation.commit().basis(), change.timeline());
                entities.put(id, entity);
            }
            case RelationshipId id -> {
                StoredRelationship relationship = change.created()
                        ? new StoredRelationship(id, change.type(), change.source(), change.target())
                        : relationships.get(id);
                relationship.append(preparation.commit().basis(), change.timeline());
                relationships.put(id, relationship);
            }
        }
    }

    /** The IDs of the changes of a preparation, in the order of first touch. */
    static List<String> changed(Preparation preparation) {
        return preparation.changes().stream().map(change -> change.id().value()).toList();
    }
}
