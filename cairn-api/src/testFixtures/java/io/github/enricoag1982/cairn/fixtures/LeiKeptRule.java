package io.github.enricoag1982.cairn.fixtures;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.List;

/**
 * The test-only rule {@code lei-kept} (ADR-0018, capability {@code entity-rules}): an organization that had an LEI
 * before the transaction, at any time in the checked version's period, still has one. The reference model does not name
 * it; the scenarios that need it make {@code Organization} name it.
 *
 * <p>The rule is the example of a rule that reads the store as it was committed before the transaction, through
 * {@link RuleContext#before()}: it reads the timeline of the organization there with the ID of the version it checks,
 * and compares the version with every previous version whose period overlaps its own, not only with the one at the
 * start of the period, so that its verdict holds at each instant of the period. An organization that the store did not
 * hold, such as one that the transaction creates, had no LEI, so there is nothing to keep.
 */
public final class LeiKeptRule implements EntityRule {

    /** The code the rule reports when an organization that had an LEI in the checked version's period has none. */
    public static final String LEI_REMOVED = "lei-removed";

    /** The description of the rule, as the reference domain page gives it. */
    public static final RuleDescriptor DESCRIPTOR = new RuleDescriptor("lei-kept", 1,
            "An organization that had an LEI before the transaction, at any time in the checked version's period, "
                    + "still has one.",
            "Organization",
            List.of(new RuleCode(LEI_REMOVED,
                    "The organization had an LEI before the transaction at some time in the period of the version "
                            + "that the transaction writes, and the version has none. Keep the LEI.")));

    @Override
    public RuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void check(EntityState version, RuleContext context) {
        if (hasLei(version)) {
            return;
        }
        for (EntityState held : context.before().timeline(version.id())) {
            if (overlap(held.period(), version.period()) && hasLei(held)) {
                context.problem(LEI_REMOVED, "The organization " + version.id() + " had an LEI before the transaction, "
                        + "from " + held.period().from() + ", and would have none from " + version.period().from()
                        + ".");
                return;
            }
        }
    }

    /** Tells whether two periods have an instant in common. */
    private static boolean overlap(EffectivePeriod a, EffectivePeriod b) {
        return a.until().map(end -> b.from().isBefore(end)).orElse(true)
                && b.until().map(end -> a.from().isBefore(end)).orElse(true);
    }

    /** Tells whether a version of an organization has a non-blank LEI. */
    private static boolean hasLei(EntityState organization) {
        return organization.values().get("lei") instanceof Value.StringValue lei && !lei.value().isBlank();
    }
}
