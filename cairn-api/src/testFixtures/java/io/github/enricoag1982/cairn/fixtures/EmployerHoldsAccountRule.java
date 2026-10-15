package io.github.enricoag1982.cairn.fixtures;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.RelationshipRule;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import java.time.Instant;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * The test-only rule {@code employer-holds-account} (ADR-0018, ADR-0025, capability {@code entity-rules}): an
 * organization holds at least one account, through {@code HeldBy}, whenever it employs someone. The reference model does
 * not name it; the scenarios that need it make {@code Employs} name it.
 *
 * <p>The rule is for {@code Employs}, but what it depends on is the accounts of the employer, which are other
 * relationships: ending a {@code HeldBy} can break it, though nothing about the {@code Employs} changes, and
 * {@code HeldBy} does not name it. It checks every instant of the employment's period at which the employer's accounts
 * change, which it finds by navigating the employer's {@code HeldBy} relationships across effective time, so a write that
 * changes them runs the rule again (a re-check).
 */
public final class EmployerHoldsAccountRule implements RelationshipRule {

    /** The code the rule reports when the employer holds no account at some instant of the employment. */
    public static final String NO_ACCOUNT = "no-account";

    /** The description of the rule, as the reference domain page gives it. */
    public static final RuleDescriptor DESCRIPTOR = new RuleDescriptor("employer-holds-account", 1,
            "An organization holds at least one account, through HeldBy, whenever it employs someone.",
            "Employs",
            List.of(new RuleCode(NO_ACCOUNT,
                    "The employer holds no account through HeldBy at some point in effective time at which it "
                            + "employs someone. Relate an account to the employer, or end the employment.")));

    /** The name by which the accounts of a party are navigated: the inverse name of {@code HeldBy}. */
    private static final String HOLDS = "holds";

    @Override
    public RuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void check(RelationshipState version, RuleContext context) {
        for (Instant at : instants(version, context)) {
            if (context.viewAt(at).navigate(version.source(), HOLDS).isEmpty()) {
                context.problem(NO_ACCOUNT, "The employer " + version.source() + " would hold no account at " + at
                        + ", while it employs " + version.target() + ".");
                return;
            }
        }
    }

    /** The start of the version's period, and each instant in it at which an account of the employer starts or ends. */
    private static NavigableSet<Instant> instants(RelationshipState version, RuleContext context) {
        EffectivePeriod period = version.period();
        NavigableSet<Instant> instants = new TreeSet<>();
        instants.add(period.from());
        for (RelationshipState heldBy : context.view().navigateTimeline(version.source(), HOLDS)) {
            if (period.contains(heldBy.period().from())) {
                instants.add(heldBy.period().from());
            }
            heldBy.period().until().filter(period::contains).ifPresent(instants::add);
        }
        return instants;
    }
}
