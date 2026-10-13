package io.github.enricoag1982.cairn.example.hr.rules;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.example.hr.model.NoUnitCyclesRuleV1;
import io.github.enricoag1982.cairn.example.hr.model.OrgUnit;
import io.github.enricoag1982.cairn.example.hr.model.PartOf;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * The rule {@code no-unit-cycles} of the HR domain: following {@code PartOf} from a unit never leads back to it.
 *
 * <p>A {@code PartOf} from a unit to its parent closes a cycle if a traversal by {@code PartOf} from the parent reaches
 * the unit. The hierarchy can change at any instant of the checked version's period, so the rule checks the start of
 * the period and every later instant in it at which a {@code PartOf} above the parent starts or ends, each through a
 * view at that instant ({@link RuleContext#viewAt(Instant)}).
 */
public final class NoUnitCycles extends NoUnitCyclesRuleV1 {

    /** The code the rule reports when a unit would become part of itself. */
    public static final String CYCLE = "cycle";

    private static final RuleDescriptor DESCRIPTOR = describe(
            "Following PartOf from a unit never leads back to it.",
            new RuleCode(CYCLE, "A unit cannot be part of itself, directly or through the units above it. Choose "
                    + "another parent, or end the PartOf that leads from the parent back to the unit."));

    /** Creates the rule; the service loader calls it. */
    public NoUnitCycles() {
    }

    @Override
    public RuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected void check(PartOf partOf, RuleContext context) {
        EntityId unit = partOf.source();
        EntityId parent = partOf.target();
        for (Instant at : instants(partOf.relationshipState(), context)) {
            View view = context.viewAt(at);
            boolean cycle = unit.equals(parent)
                    || view.traverse(parent, PartOf.TYPE, relationship -> true, Integer.MAX_VALUE).stream()
                            .anyMatch(step -> step.entity().id().equals(unit));
            if (cycle) {
                context.problem(CYCLE, code(view, unit) + " would be part of " + code(view, parent) + ", which is "
                        + "part of " + code(view, unit) + " at " + at + ".");
                return;
            }
        }
    }

    /**
     * The start of the version's period, and each instant in it at which a {@code PartOf} starts or ends in the
     * hierarchy above the parent: those are the instants at which the answer can change.
     */
    private static NavigableSet<Instant> instants(RelationshipState version, RuleContext context) {
        EffectivePeriod period = version.period();
        NavigableSet<Instant> instants = new TreeSet<>();
        instants.add(period.from());
        Set<EntityId> seen = new HashSet<>();
        Deque<EntityId> todo = new ArrayDeque<>();
        todo.push(version.target());
        while (!todo.isEmpty()) {
            EntityId unit = todo.pop();
            if (!seen.add(unit)) {
                continue;
            }
            for (RelationshipState partOf : context.view().navigateTimeline(unit, PartOf.TYPE)) {
                if (period.contains(partOf.period().from())) {
                    instants.add(partOf.period().from());
                }
                partOf.period().until().filter(period::contains).ifPresent(instants::add);
                todo.push(partOf.target());
            }
        }
        return instants;
    }

    /** The code of a unit for a message, or its ID if the view does not have it. */
    private static String code(View view, EntityId unit) {
        return view.entity(unit).map(state -> OrgUnit.from(state).code()).orElse(unit.toString());
    }
}
