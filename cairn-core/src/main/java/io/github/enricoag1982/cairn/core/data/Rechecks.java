package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.data.Timeline.Stretch;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.schema.EffectiveRule;
import io.github.enricoag1982.cairn.schema.RuleReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds the rules to run again because a transaction changes what they read, and runs them (design D4 of
 * {@code add-rule-rechecks}, ADR-0025): after the rules of what the transaction writes have run, and only if the
 * transaction has no problems but those of rules. It asks the {@link ReadIndex} for the checks that the transaction's
 * {@link Changes} reach, and for each entity or relationship and rule that has one, runs the rule for the versions that
 * overlap the periods of those checks, in the state the transaction would produce, whether or not the transaction writes
 * that entity or relationship.
 *
 * <ul>
 *   <li>A rule that reported a problem about something the transaction writes is left out: that problem rejects the
 *       transaction already, and a rule that sums over many things would report it once for each of them.
 *   <li>A version that the rules of the transaction's own writes ran for is not run twice.
 *   <li>The order is that of the IDs of the entities and relationships, then the order in which their type names the
 *       rules, then the order of their versions. Their problems come after all others.
 *   <li>A check whose rule the entity's or relationship's type no longer names, after a schema registration, is dropped.
 *   <li>The runs record what they read like any other, and never cause more re-checks: rules do not write.
 *   <li>A rule that reads the store as it was before the transaction reads, in a re-check, the state before the
 *       transaction that runs it again (design D2 of {@code add-previous-state-to-rules}).
 * </ul>
 *
 * <p>Used by one {@link Preparation}, in the thread that holds the store's writer lock.
 */
final class Rechecks {

    /** An entity or relationship as the transaction would leave it: what a re-check needs to run its rules. */
    private record Resolved(Id id, String path, SubjectType type, EntityId source, EntityId target,
            Timeline timeline) {
    }

    private final Workspace workspace;
    private final RuleStage ruleStage;
    private final ReadIndex index;
    private final ReadIndex.Stage stage;

    /**
     * Creates the re-checks of a transaction.
     *
     * @param workspace the entities and relationships the transaction touches
     * @param ruleStage the rule stage of the transaction, whose runner and view the re-checks share
     * @param index     the index of what rules read, as the store holds it before the transaction
     * @param stage     where the transaction stages its changes to the index
     */
    Rechecks(Workspace workspace, RuleStage ruleStage, ReadIndex index, ReadIndex.Stage stage) {
        this.workspace = workspace;
        this.ruleStage = ruleStage;
        this.index = index;
        this.stage = stage;
    }

    /**
     * Runs the rules that the changes reach again, and adds their problems to the transaction's.
     *
     * @param changes what the transaction changes
     */
    void run(Changes changes) {
        Set<ReadIndex.Check> hit = index.hits(changes.byKey());
        if (hit.isEmpty()) {
            return;
        }
        Map<String, Map<RuleReference, List<ReadIndex.Check>>> hitBySubject = new TreeMap<>();
        for (ReadIndex.Check check : hit) {
            hitBySubject.computeIfAbsent(check.subject().value(), id -> new LinkedHashMap<>())
                    .computeIfAbsent(check.rule(), rule -> new ArrayList<>()).add(check);
        }
        RuleRunner runner = ruleStage.runner();
        Set<RuleReference> left = runner.reporting();
        List<RuleRunner.Recheck> rechecks = new ArrayList<>();
        for (Map<RuleReference, List<ReadIndex.Check>> byRule : hitBySubject.values()) {
            Id id = byRule.values().iterator().next().getFirst().subject();
            Resolved subject = resolve(id);
            if (subject != null) {
                rechecksOf(subject, byRule, left, runner, rechecks);
            }
        }
        if (!rechecks.isEmpty()) {
            workspace.problems().addAll(runner.recheck(rechecks));
        }
    }

    /** The rules to run again for one entity or relationship, in the order in which its type names them. */
    private void rechecksOf(Resolved subject, Map<RuleReference, List<ReadIndex.Check>> byRule, Set<RuleReference> left,
            RuleRunner runner, List<RuleRunner.Recheck> rechecks) {
        Set<RuleReference> named = new HashSet<>();
        subject.type().effectiveRules().forEach(effective -> named.add(effective.rule()));
        byRule.forEach((rule, checks) -> {
            if (!named.contains(rule)) {
                checks.forEach(stage::drop);
            }
        });
        RuleRunner.Subject checked = new RuleRunner.Subject(subject.id(), subject.path(), subject.type(),
                subject.source(), subject.target(), List.of());
        Set<RuleReference> done = new HashSet<>();
        for (EffectiveRule effective : subject.type().effectiveRules()) {
            List<ReadIndex.Check> checks = byRule.get(effective.rule());
            if (checks == null || left.contains(effective.rule()) || !done.add(effective.rule())) {
                continue;
            }
            List<Segment> versions = subject.timeline().segments().stream()
                    .filter(version -> overlaps(version, checks))
                    .filter(version -> !runner.ran(subject.id(), effective.rule(), version.from()))
                    .toList();
            if (!versions.isEmpty()) {
                rechecks.add(new RuleRunner.Recheck(checked, effective, versions));
            }
        }
    }

    /** Tells whether a version's period overlaps a period that one of the checks still covers. */
    private static boolean overlaps(Segment version, List<ReadIndex.Check> checks) {
        for (ReadIndex.Check check : checks) {
            for (Stretch period : check.periods()) {
                if (version.from().isBefore(period.end()) && period.from().isBefore(version.end())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The entity or relationship as the transaction would leave it, with the name its problems have: the one the
     * transaction gives it if it writes it, else its ID. Null if it is not known any more or its type is not in the
     * schema the transaction would produce.
     */
    private Resolved resolve(Id id) {
        Working working = workspace.working().get(id);
        if (working != null) {
            return working.failed() ? null : new Resolved(id, working.path, working.type(), working.source,
                    working.target, working.timeline);
        }
        Held stored = workspace.ledger().held(id).orElse(null);
        if (stored == null) {
            return null;
        }
        return switch (id) {
            case EntityId ignored -> workspace.index().entityType(stored.type())
                    .map(type -> new Resolved(id, id.value(), SubjectType.of(type), null, null, stored.timeline()))
                    .orElse(null);
            case RelationshipId ignored -> workspace.index().relationshipType(stored.type())
                    .map(type -> new Resolved(id, id.value(), SubjectType.of(type), stored.source(), stored.target(),
                            stored.timeline()))
                    .orElse(null);
        };
    }
}
