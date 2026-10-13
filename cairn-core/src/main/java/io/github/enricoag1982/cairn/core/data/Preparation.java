package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.commit.CommitSequence;
import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies the writes of one transaction to working copies of the timelines it touches, validates the state that
 * results (design D5 and D6 of {@code add-entity-versions}), checks the integrity of relationships over effective time
 * (design D3 and D4 of {@code add-relationships}), and runs the rules that apply to it (design D3 of
 * {@code add-entity-rules}). Nothing in the store changes: a commit appends {@link #changes()} afterwards, and a dry
 * run drops them.
 *
 * <p>A Preparation only orders the work; each part does one kind of it, on the {@link Workspace} that they share:
 * {@link Creations} and {@link Amendments} apply the writes, {@link Cascades} ends the compositions of what the writes
 * end, {@link Validation} and {@link Integrity} check the state that results, and {@link RuleStage} runs the rules. The
 * {@link ProblemLog} keeps what they find.
 *
 * <p>Writes are applied in order. A write with problems is applied as far as it can be, so that the problems of the
 * resulting state are found too: every problem of the transaction is reported, not only the first. When the writes are
 * applied, ending an entity ends its compositions too. Problems come in a fixed order: those of the transaction
 * itself, then those of each write in the order of the writes, then those of each entity and relationship (the
 * subjects) in the order in which the writes first touched them, and last those of the rules. The problems of a
 * subject are those of its values, which follow the order of the attributes in the schema, and then those of its
 * relationships.
 *
 * <p>A Preparation is used once and by one thread: the one holding the store's writer lock.
 */
final class Preparation {

    /** The path of problems about the transaction itself. */
    static final String TRANSACTION_PATH = "@transaction";

    /**
     * What a commit records for an entity or a relationship.
     *
     * @param id       the ID of the entity or relationship
     * @param type     its type
     * @param source   the source of a relationship; null for an entity
     * @param target   the target of a relationship; null for an entity
     * @param created  whether the transaction creates it
     * @param timeline its timeline after the transaction
     */
    record Change(Id id, TypeId type, EntityId source, EntityId target, boolean created, Timeline timeline) {
    }

    private final Transaction transaction;
    private final CommitSequence.Commit commit;
    private final ProblemLog problems = new ProblemLog();
    private final Workspace workspace;
    private final Creations creations;
    private final Amendments amendments;
    private final Cascades cascades;
    private final Validation validation;
    private final RuleStage ruleStage;
    private List<Id> ended = List.of();

    /**
     * Prepares a transaction.
     *
     * @param transaction         the transaction
     * @param index               the schema the transaction is validated against
     * @param storedEntities      the entities of the store, ordered by ID
     * @param storedRelationships the relationships of the store, ordered by ID
     * @param purgedIds           the IDs that purges removed, which no new entity or relationship may have
     * @param generator           a copy of the store's ID generator, which this preparation advances
     * @param commit              the basis and transaction time the transaction would get
     * @param checks              the value checks
     * @param rules               the rules with code that run once the declarative checks have passed
     * @param sequence            the store's sequence, which the caller has locked for writing and which refuses
     *                            writes while the rules run
     */
    Preparation(Transaction transaction, SchemaIndex index, Map<EntityId, StoredEntity> storedEntities,
            Map<RelationshipId, StoredRelationship> storedRelationships, Set<String> purgedIds, IdGenerator generator,
            CommitSequence.Commit commit, ValueChecks checks, Rules rules, CommitSequence sequence) {
        this.transaction = transaction;
        this.commit = commit;
        this.workspace = new Workspace(index, storedEntities, storedRelationships, purgedIds, generator, problems);
        WriteChecks writeChecks = new WriteChecks(problems);
        this.creations = new Creations(workspace, writeChecks, commit);
        this.amendments = new Amendments(workspace, writeChecks, commit);
        this.cascades = new Cascades(workspace);
        this.validation = new Validation(workspace, checks);
        this.ruleStage = new RuleStage(workspace, commit.basis(), rules, sequence);
    }

    /** Applies and validates the transaction; returns this. */
    Preparation run() {
        if (transaction.principal().isBlank()) {
            problems.add(Problems.of(ProblemCode.MISSING_PRINCIPAL, TRANSACTION_PATH,
                    "The transaction names no principal.", "the principal that submits the transaction, such as "
                    + "agent:onboarding", transaction.principal().isEmpty() ? "no principal" : "a blank principal",
                    null));
        }
        List<Write> writes = transaction.writes();
        for (int position = 0; position < writes.size(); position++) {
            switch (writes.get(position)) {
                case Write.Create create -> creations.create(create, position);
                case Write.Relate relate -> creations.relate(relate, position);
                case Write.Patch patch -> amendments.patch(patch);
                case Write.Replace replace -> amendments.replace(replace);
                case Write.EndDate endDate -> amendments.endDate(endDate);
            }
        }
        ended = cascades.run(amendments.ended());
        checkSubjects();
        ruleStage.run();
        return this;
    }

    /**
     * Checks the state that the writes leave every entity and relationship in, in the order in which the writes first
     * touched them: its values, if a write set them, then its integrity.
     */
    private void checkSubjects() {
        Integrity integrity = new Integrity(workspace.index(), workspace.working(), workspace.storedEntities(),
                workspace.storedRelationships());
        for (Working subject : List.copyOf(workspace.working().values())) {
            if (subject.failed()) {
                continue;
            }
            if (subject.written) {
                validation.validate(subject);
            }
            problems.addAll(subject.isRelationship() ? integrity.relationshipProblems(subject)
                    : integrity.entityProblems(subject));
        }
    }

    List<Problem> problems() {
        return problems.all();
    }

    /** The ID of every entity or relationship named with a temporary ID, in the order of the writes. */
    Map<TemporaryId, Id> temporaryIds() {
        return creations.temporaryIds();
    }

    /** The ID of every entity and relationship created, in the order of the writes. */
    List<Id> created() {
        return creations.created();
    }

    /** The ID of every relationship and entity that composition cascades ended, in the order of {@link Cascades}. */
    List<Id> ended() {
        return ended;
    }

    /** The generator after the IDs of this transaction were drawn. */
    IdGenerator generator() {
        return workspace.generator();
    }

    CommitSequence.Commit commit() {
        return commit;
    }

    /**
     * What a commit records: the entities and relationships that were created or whose state changed, in order of first
     * touch. One whose writes left it as it was, such as one that a transaction changes and changes back, records
     * nothing, even if the writes split its timeline into parts that different transactions recorded. An entity that is
     * only the end of a relationship is not changed.
     */
    List<Change> changes() {
        List<Change> changes = new ArrayList<>();
        for (Working subject : workspace.working().values()) {
            if (!subject.failed() && (subject.created || !subject.timeline.sameState(subject.before))) {
                TypeId type = subject.isRelationship() ? subject.relationshipType.id() : subject.entityType.id();
                changes.add(new Change(subject.id, type, subject.source, subject.target, subject.created,
                        subject.timeline));
            }
        }
        return changes;
    }
}
