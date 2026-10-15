package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.WriteChecks.attributePath;

import io.github.enricoag1982.cairn.core.commit.CommitSequence;
import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.Ref;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Applies the writes that change something that exists: patch, replace and end-date, on entities and relationships
 * alike (design D5 and D6 of {@code add-entity-versions} and D1 of {@code add-relationships}). Checks the conflict
 * checks of the writes against what is committed now. Problems about the target are located at the path of the
 * reference that names it; a target named by a unique key is resolved first, at the start of the write's period, and
 * problems about the entity then name its ID, while problems about the key are located at {@code @write.<position>}
 * (ADR-0026).
 *
 * <p>Used by one {@link Preparation}, in the thread that holds the store's writer lock.
 */
final class Amendments {

    private final Workspace workspace;
    private final ProblemLog problems;
    private final WriteChecks checks;
    private final CommitSequence.Commit commit;
    private final List<Cascades.Ended> ended = new ArrayList<>();

    Amendments(Workspace workspace, WriteChecks checks, CommitSequence.Commit commit) {
        this.workspace = workspace;
        this.problems = workspace.problems();
        this.checks = checks;
        this.commit = commit;
    }

    /** The entities that the end-dates ended, in the order of the writes, with the instants they end at. */
    List<Cascades.Ended> ended() {
        return ended;
    }

    void patch(Write.Patch write, int position) {
        Ref named = workspace.resolveKey(write.target(), write.period().from(), position);
        String path = pathOf(named, position);
        Working target = named == null ? null : workspace.resolve(named);
        boolean validPeriod = checks.checkPeriod(path, write.period());
        if (target == null || target.failed()) {
            return;
        }
        SubjectType type = target.type();
        WriteChecks.Resolved set = checks.resolveValues(path, type, write.set());
        Set<AttributeId> cleared = new LinkedHashSet<>();
        List<Attribute> affected = new ArrayList<>(set.attributes());
        boolean unresolved = set.unresolvedNames();
        for (String name : write.clear()) {
            Optional<Attribute> attribute = type.attribute(name);
            if (attribute.isEmpty()) {
                checks.unknownAttribute(path, type, name);
                unresolved = true;
            } else if (cleared.add(attribute.get().id()) && !affected.contains(attribute.get())) {
                affected.add(attribute.get());
            }
        }
        target.namesUnresolved |= unresolved;
        checkConflict(write.check(), target, path, affected);
        if (validPeriod && isEffective(target, path, write.period())) {
            target.timeline = target.timeline.update(write.period().from(), Segment.endOf(write.period()), old -> {
                Map<AttributeId, Value> changed = new HashMap<>(old);
                cleared.forEach(changed::remove);
                changed.putAll(set.values());
                return changed;
            }, commit.transactionTime(), commit.basis());
        }
    }

    void replace(Write.Replace write, int position) {
        Ref named = workspace.resolveKey(write.target(), write.period().from(), position);
        String path = pathOf(named, position);
        Working target = named == null ? null : workspace.resolve(named);
        boolean validPeriod = checks.checkPeriod(path, write.period());
        if (target == null || target.failed()) {
            return;
        }
        WriteChecks.Resolved values = checks.resolveValues(path, target.type(), write.values());
        target.namesUnresolved |= values.unresolvedNames();
        checkConflict(write.check(), target, path, null);
        if (validPeriod && isEffective(target, path, write.period())) {
            target.timeline = target.timeline.update(write.period().from(), Segment.endOf(write.period()),
                    old -> values.values(), commit.transactionTime(), commit.basis());
        }
    }

    void endDate(Write.EndDate write, int position) {
        Ref named = workspace.resolveKey(write.target(), write.from(), position);
        String path = pathOf(named, position);
        Working target = named == null ? null : workspace.resolve(named);
        if (target == null || target.failed()) {
            return;
        }
        checkConflict(write.check(), target, path, null);
        Optional<Timeline.Lifetime> lifetime = target.timeline.lifetime();
        if (lifetime.isEmpty() || !write.from().isAfter(lifetime.get().from())) {
            problems.add(Problems.of(ProblemCode.ENTITY_NOT_EFFECTIVE, path, "The " + target.kind() + " cannot be ended "
                    + "from " + write.from() + (lifetime.isPresent() ? ", because it exists only from "
                    + lifetime.get().from() + " on." : "."), "an instant after the start of the " + target.kind(),
                    write.from().toString(), null));
            return;
        }
        target.timeline = target.timeline.endAt(write.from());
        if (!target.isRelationship()) {
            ended.add(new Cascades.Ended(target, write.from()));
        }
    }

    /** What problems about a write's target call it: the reference it was resolved to, else the write itself. */
    private static String pathOf(Ref resolved, int position) {
        return resolved == null ? Workspace.writePath(position) : Workspace.pathOf(resolved);
    }

    /** Reports a period that the target does not exist throughout; tells whether it exists throughout it. */
    private boolean isEffective(Working target, String path, EffectivePeriod period) {
        if (target.timeline.covers(period.from(), Segment.endOf(period))) {
            return true;
        }
        String lifetime = target.timeline.lifetime()
                .map(found -> "exists " + Segment.describe(found.from(), found.end()))
                .orElse("does not exist");
        problems.add(Problems.of(ProblemCode.ENTITY_NOT_EFFECTIVE, path, "The " + target.kind() + " " + lifetime
                + ", so it cannot be changed " + Segment.describe(period.from(), Segment.endOf(period)) + ".",
                "a period within the lifetime of the " + target.kind(),
                Segment.describe(period.from(), Segment.endOf(period)), null));
        return false;
    }

    // ------------------------------------------------------------------ conflict checks

    /**
     * Compares the entity or relationship as the caller read it with it as committed now. A per-attribute check
     * compares the given attributes; for a write that sets the complete state (a replace or an end-date),
     * {@code attributes} is {@code null} and the whole entity or relationship is compared. One created in this
     * transaction cannot have changed.
     */
    private void checkConflict(ConflictCheck check, Working target, String path, List<Attribute> attributes) {
        Basis readAt = switch (check) {
            case ConflictCheck.None none -> null;
            case ConflictCheck.PerEntity perEntity -> perEntity.readAt();
            case ConflictCheck.PerAttribute perAttribute -> perAttribute.readAt();
        };
        if (readAt == null || target.created) {
            return;
        }
        Stored<?> stored = target.stored;
        Timeline then = stored.at(readAt);
        Timeline now = stored.latest();
        boolean perAttribute = check instanceof ConflictCheck.PerAttribute && attributes != null;
        if (!perAttribute) {
            if (!then.sameState(now)) {
                problems.add(conflict(path, "The " + target.kind() + " was changed by a transaction after basis "
                        + readAt.value() + ", at which the write's caller read it.", readAt, stored, target.kind()));
            }
            return;
        }
        for (Attribute attribute : attributes) {
            if (!then.sameValues(now, attribute.id())) {
                problems.add(conflict(attributePath(path, attribute), "The attribute " + attribute.name()
                        + " was changed by a transaction after basis " + readAt.value() + ", at which the write's "
                        + "caller read it.", readAt, stored, target.kind()));
            }
        }
    }

    private static Problem conflict(String path, String message, Basis readAt, Stored<?> stored, String kind) {
        return Problems.of(ProblemCode.CONFLICT, path, message, "no change since basis " + readAt.value(),
                "a change recorded at basis " + stored.latestBasis().value(),
                "Read the " + kind + " again, and decide whether the write still applies.");
    }
}
