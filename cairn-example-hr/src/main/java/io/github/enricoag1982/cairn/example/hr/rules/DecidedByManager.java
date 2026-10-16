package io.github.enricoag1982.cairn.example.hr.rules;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.example.hr.model.BelongsTo;
import io.github.enricoag1982.cairn.example.hr.model.DecidedBy;
import io.github.enricoag1982.cairn.example.hr.model.DecidedByManagerRuleV1;
import io.github.enricoag1982.cairn.example.hr.model.Employment;
import io.github.enricoag1982.cairn.example.hr.model.Fills;
import io.github.enricoag1982.cairn.example.hr.model.HasLeaveRequest;
import io.github.enricoag1982.cairn.example.hr.model.LeaveRequest;
import io.github.enricoag1982.cairn.example.hr.model.ManagedBy;
import io.github.enricoag1982.cairn.example.hr.model.OrgUnit;
import io.github.enricoag1982.cairn.example.hr.model.PartOf;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The rule {@code decided-by-manager} of the HR domain: the employment that decides a leave request is not the
 * requester, and manages, through {@code ManagedBy}, the unit of the requester's position, or a unit above it by
 * {@code PartOf}, on the request's first day.
 *
 * <p>The rule reads through two views. It finds the request and its requester through {@link RuleContext#view()}, the
 * state at the start of the decision's period, which is when the request exists. Everything else it reads through
 * {@link RuleContext#viewAt(Instant)} at the request's first day, because the verdict is about who manages whom on that
 * day, whatever the decision's own period: the requester's position, its unit, the units above it and their managers.
 * The first day is the one the request has when the decision starts; a correction of the request that takes effect
 * later does not check the decision again. A change to what the rule read on the first day, such as ending the
 * {@code ManagedBy} of a unit, does (ADR-0025).
 */
public final class DecidedByManager extends DecidedByManagerRuleV1 {

    /** The code the rule reports when the deciding employment does not manage the requester's unit or one above it. */
    public static final String NOT_A_MANAGER = "not-a-manager";

    /** The code the rule reports when the deciding employment is the requester: nobody decides their own request. */
    public static final String OWN_REQUEST = "own-request";

    private static final RuleDescriptor DESCRIPTOR = describe(
            "The deciding employment is not the requester, and manages the requester's unit, or a unit above it, on "
                    + "the request's first day.",
            new RuleCode(NOT_A_MANAGER, "The employment that decides a leave request must manage, through ManagedBy, "
                    + "the unit of the requester's position or a unit above it, on the request's first day. Let "
                    + "the manager of the unit, or of a unit above it, decide."),
            new RuleCode(OWN_REQUEST, "The employment that decides a leave request is the one that requests it, "
                    + "even if it manages its own unit. Let the manager of a unit above it decide."));

    /** Creates the rule; the service loader calls it. */
    public DecidedByManager() {
    }

    @Override
    public RuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected void check(DecidedBy decision, RuleContext context) {
        View atDecision = context.view();
        Optional<EntityState> request = atDecision.entity(decision.source());
        List<Link> requesters = atDecision.navigate(decision.source(), HasLeaveRequest.INVERSE);
        if (request.isEmpty() || requesters.isEmpty()) {
            context.problem(NOT_A_MANAGER, "The leave request " + decision.source() + " has no requester.");
            return;
        }
        EntityId requester = requesters.getFirst().entity().id();
        if (requester.equals(decision.target())) {
            context.problem(OWN_REQUEST, number(atDecision, requester) + " cannot decide their own leave request.");
            return;
        }
        Instant firstDay = LeaveRequest.from(request.get()).firstDay().atStartOfDay(ZoneOffset.UTC).toInstant();
        View onFirstDay = context.viewAt(firstDay);
        List<EntityId> units = unitsAbove(onFirstDay, requester);
        if (units.isEmpty()) {
            context.problem(NOT_A_MANAGER, "The requester " + number(atDecision, requester) + " has no position on "
                    + "the first day, " + firstDay + ", so nobody manages the unit.");
            return;
        }
        boolean manages = units.stream().anyMatch(unit -> onFirstDay.navigate(unit, ManagedBy.TYPE).stream()
                .anyMatch(link -> link.entity().id().equals(decision.target())));
        if (!manages) {
            context.problem(NOT_A_MANAGER, number(atDecision, decision.target()) + " manages none of the units "
                    + units.stream().map(unit -> code(onFirstDay, unit)).toList() + " of the requester "
                    + number(atDecision, requester) + " on " + firstDay + ".");
        }
    }

    /**
     * The unit of the requester's position, then the units above it, nearest first, as the view reads them; empty if
     * the requester fills no position.
     */
    private static List<EntityId> unitsAbove(View view, EntityId requester) {
        List<EntityId> units = new ArrayList<>();
        for (Link fills : view.navigate(requester, Fills.TYPE)) {
            for (Link belongsTo : view.navigate(fills.entity().id(), BelongsTo.TYPE)) {
                EntityId unit = belongsTo.entity().id();
                units.add(unit);
                view.traverse(unit, PartOf.TYPE).stream().map(Step::entity).map(EntityState::id).forEach(units::add);
            }
        }
        return units;
    }

    /** The employee number of an employment for a message, or its ID if the view does not have it. */
    private static String number(View view, EntityId employment) {
        return view.entity(employment).map(state -> Employment.from(state).employeeNumber())
                .orElse(employment.toString());
    }

    /** The code of a unit for a message, or its ID if the view does not have it. */
    private static String code(View view, EntityId unit) {
        return view.entity(unit).map(state -> OrgUnit.from(state).code()).orElse(unit.toString());
    }
}
