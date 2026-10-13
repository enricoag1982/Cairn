package io.github.enricoag1982.cairn.fixtures;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityState;
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
import java.util.Optional;
import java.util.TreeSet;

/**
 * The test-only rule {@code controlling-owner-has-lei} (ADR-0018, ADR-0025, capability {@code entity-rules}): an
 * organization that owns more than 50% of a party has an LEI. The reference model does not name it; the scenarios that
 * need it make {@code Owns} name it.
 *
 * <p>The rule is for {@code Owns}, but what it depends on is a value of the owner, another entity: clearing the owner's
 * LEI can break it, though nothing about the {@code Owns} changes. It checks the owner at the start of the ownership's
 * period and at every later instant in it at which the owner has a new version, which it finds by reading the owner's
 * timeline, so a write that changes the owner runs the rule again (a re-check). An ownership of 50% or less reads nothing.
 */
public final class ControllingOwnerHasLeiRule implements RelationshipRule {

    /** The code the rule reports when the controlling owner has no LEI at some instant of the ownership. */
    public static final String NO_LEI = "no-lei";

    /** The description of the rule, as the reference domain page gives it. */
    public static final RuleDescriptor DESCRIPTOR = new RuleDescriptor("controlling-owner-has-lei", 1,
            "An organization that owns more than 50% of a party has an LEI.",
            "Owns",
            List.of(new RuleCode(NO_LEI,
                    "The organization owns more than 50% of a party and has no LEI at some point in effective time "
                            + "in the ownership. Give the organization an LEI, or lower the share.")));

    private static final BigDecimal CONTROL = new BigDecimal("50");

    @Override
    public RuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void check(RelationshipState version, RuleContext context) {
        if (!(version.values().get("share") instanceof Value.DecimalValue share)
                || share.value().compareTo(CONTROL) <= 0) {
            return;
        }
        for (Instant at : instants(version, context)) {
            Optional<EntityState> owner = context.viewAt(at).entity(version.source());
            if (owner.isPresent() && !hasLei(owner.get())) {
                context.problem(NO_LEI, "The owner " + version.source() + " of more than 50% of " + version.target()
                        + " would have no LEI at " + at + ".");
                return;
            }
        }
    }

    private static boolean hasLei(EntityState owner) {
        return owner.values().get("lei") instanceof Value.StringValue lei && !lei.value().isBlank();
    }

    /** The start of the version's period, and each instant in it at which the owner has a new version. */
    private static NavigableSet<Instant> instants(RelationshipState version, RuleContext context) {
        EffectivePeriod period = version.period();
        NavigableSet<Instant> instants = new TreeSet<>();
        instants.add(period.from());
        for (EntityState owner : context.view().timeline(version.source())) {
            if (period.contains(owner.period().from())) {
                instants.add(owner.period().from());
            }
        }
        return instants;
    }
}
