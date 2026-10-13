package io.github.enricoag1982.cairn.fixtures;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.RelationshipRule;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * The rule {@code total-ownership-at-most-100} of the reference domain (ADR-0018, capability {@code entity-rules}): the
 * owners of a party hold at most 100% of it together, at every point in effective time. It is the reference example of
 * a rule on a relationship whose verdict depends on other relationships (design D5 of {@code add-relationships}).
 *
 * <p>The verdict on one {@code Owns} relationship depends on the other {@code Owns} relationships to the same target,
 * which can start, end or change at any instant of the checked version's period. So the rule collects the start of the
 * period and every instant in it at which an {@code Owns} relationship to the target starts or ends, and sums the
 * shares of the owners at each instant, through a view at that instant. The sum can only grow where a relationship
 * starts or changes, and it is cheap to check at the ends too. The rule reports the first instant at which the shares
 * add up to more than 100.
 *
 * <p>The rule reads the version it checks and the state the transaction would produce, and nothing else, so it is
 * deterministic. Its descriptor is the one that {@link ReferenceModel#rules()} lists.
 */
public final class TotalOwnershipRule implements RelationshipRule {

    /** The code the rule reports when the shares add up to more than 100%. */
    public static final String OVER_100_PERCENT = "over-100-percent";

    /** The description of the rule: the page's row for {@code total-ownership-at-most-100}, with its code. */
    public static final RuleDescriptor DESCRIPTOR = new RuleDescriptor("total-ownership-at-most-100", 1,
            "The owners of a party hold at most 100% of it together, at every point in effective time.",
            "Owns",
            List.of(new RuleCode(OVER_100_PERCENT,
                    "The shares of a party's owners add up to more than 100% at some point in effective time. Lower "
                            + "a share, or end an ownership.")));

    private static final BigDecimal LIMIT = new BigDecimal("100");

    /** The name by which the owners of a party are navigated: the inverse name of {@code Owns}. */
    private static final String OWNED_BY = "ownedBy";

    @Override
    public RuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void check(RelationshipState version, RuleContext context) {
        for (Instant at : instants(version, context)) {
            BigDecimal total = total(version, context, at);
            if (total.compareTo(LIMIT) > 0) {
                context.problem(OVER_100_PERCENT, "The owners of " + version.target() + " would hold "
                        + total.stripTrailingZeros().toPlainString() + "% of it at " + at + ", which is more than "
                        + "100%.");
                return;
            }
        }
    }

    /** The start of the version's period, and each instant in it at which an ownership of the target starts or ends. */
    private static NavigableSet<Instant> instants(RelationshipState version, RuleContext context) {
        EffectivePeriod period = version.period();
        NavigableSet<Instant> instants = new TreeSet<>();
        instants.add(period.from());
        for (RelationshipState owns : context.view().navigateTimeline(version.target(), OWNED_BY)) {
            if (period.contains(owns.period().from())) {
                instants.add(owns.period().from());
            }
            owns.period().until().filter(period::contains).ifPresent(instants::add);
        }
        return instants;
    }

    /** The sum of the shares of the owners of the version's target at one instant. */
    private static BigDecimal total(RelationshipState version, RuleContext context, Instant at) {
        BigDecimal total = BigDecimal.ZERO;
        for (Link owner : context.viewAt(at).navigate(version.target(), OWNED_BY)) {
            if (owner.relationship().values().get("share") instanceof Value.DecimalValue share) {
                total = total.add(share.value());
            }
        }
        return total;
    }
}
