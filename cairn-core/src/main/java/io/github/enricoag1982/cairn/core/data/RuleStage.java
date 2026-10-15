package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.commit.CommitSequence;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.schema.Basis;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the rules of the entities and relationships the transaction writes (design D2 and D3 of
 * {@code add-entity-rules} and D5 of {@code add-relationships}), after the declarative checks and the integrity checks
 * of every subject. A subject takes part if it is written, has rules and has no problem so far; an entity that is only
 * the end of a relationship does not. A rule with code runs for the segments of the subject's new timeline that differ
 * from its previous timeline, of which there may be none; for a relationship that is related, that is its whole
 * timeline. A rule without code is reported whatever the write changed. The rules read a provisional view of the state
 * the transaction would produce, which is built once and only if some subject takes part or a re-check needs it.
 *
 * <p>The stage also keeps what {@link Rechecks} needs to know about it: the runner, which knows the versions that it ran
 * rules for and the rules that reported a problem, and the view's source. What each run read is staged for the
 * {@link ReadIndex} (design D1 and D3 of {@code add-rule-rechecks}).
 *
 * <p>Used by one {@link Preparation}, in the thread that holds the store's writer lock.
 */
final class RuleStage {

    private final Workspace workspace;
    private final ProblemLog problems;
    private final Basis basis;
    private final Rules rules;
    private final CommitSequence sequence;
    private final ReadIndex.Stage stage;
    private RuleRunner runner;

    RuleStage(Workspace workspace, Basis basis, Rules rules, CommitSequence sequence, ReadIndex.Stage stage) {
        this.workspace = workspace;
        this.problems = workspace.problems();
        this.basis = basis;
        this.rules = rules;
        this.sequence = sequence;
        this.stage = stage;
    }

    /**
     * The runner of the transaction's rules, which holds the state of the view they read. It is made on first use, after
     * the writes have been applied.
     */
    RuleRunner runner() {
        if (runner == null) {
            runner = new RuleRunner(rules, source(), sequence, stage);
        }
        return runner;
    }

    /** The state the rules read: the store as committed, with the transaction's timelines laid over it. */
    private ProvisionalView.Source source() {
        Map<EntityId, ProvisionalView.Overlaid> entities = new HashMap<>();
        Map<RelationshipId, ProvisionalView.OverlaidRelationship> relationships = new HashMap<>();
        for (Working subject : workspace.working().values()) {
            if (subject.failed()) {
                continue;
            }
            if (subject.isRelationship()) {
                relationships.put((RelationshipId) subject.id, new ProvisionalView.OverlaidRelationship(
                        subject.relationshipType.id(), subject.source, subject.target, subject.timeline));
            } else {
                entities.put((EntityId) subject.id, new ProvisionalView.Overlaid(subject.entityType.id(),
                        subject.timeline));
            }
        }
        return new ProvisionalView.Source(basis, workspace.index(), workspace.ledger(), entities, relationships);
    }

    void run() {
        List<RuleRunner.Subject> subjects = new ArrayList<>();
        for (Working subject : workspace.working().values()) {
            if (subject.failed() || !subject.written || subject.type().effectiveRules().isEmpty()
                    || problems.concerns(subject)) {
                continue;
            }
            subjects.add(new RuleRunner.Subject(subject.id, subject.path, subject.type(), subject.source,
                    subject.target, subject.timeline.changedSince(subject.before)));
        }
        if (subjects.isEmpty()) {
            return;
        }
        problems.addAll(runner().run(subjects));
    }
}
