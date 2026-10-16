package io.github.enricoag1982.cairn.example.hr.rules;

import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.example.hr.model.DecidedRequestsStayRuleV1;
import io.github.enricoag1982.cairn.example.hr.model.LeaveRequest;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The rule {@code decided-requests-stay} of the HR domain: a leave request that was approved or rejected before the
 * transaction keeps its kind, its first and last days and its days, and its status may change only to
 * {@code cancelled}.
 *
 * <p>The rule is named by {@code LeaveRequest}, the type it checks. It compares the version it checks with every
 * version of the same request that the store held before the transaction during the version's period, which the base
 * class reads with {@link #previous}, not only with the one at the start of the period: a verdict must hold at each
 * instant of the period. A change effective from 1 March that approves a request with other dates, when the store
 * approved it on 2 March with the dates it had, would otherwise pass, because the request was only requested on
 * 1 March. Each previous version that was approved or rejected must be kept by the version that is checked: the same
 * kind, first day, last day and days, and the same status or {@code cancelled}. A request that the store did not hold,
 * such as one the transaction creates, or one that was only requested during the period, may be written as it likes. A
 * write that only ends a request, as ending an employment does through its composition, changes no values and runs no
 * rules.
 *
 * <p>The rule reports one problem per request, about the first previous version that the version does not keep, and
 * names what differs from that version. It reads the request's timeline through the state before the transaction, so
 * the store records that read like any other (ADR-0025).
 */
public final class DecidedRequestsStay extends DecidedRequestsStayRuleV1 {

    /** The code the rule reports when a decided request changes beyond a change of its status to cancelled. */
    public static final String DECIDED = "decided";

    /** The statuses of a request that was decided. */
    private static final Set<String> DECIDED_STATUSES = Set.of("approved", "rejected");

    /** The only status to which a decided request may change. */
    private static final String CANCELLED = "cancelled";

    private static final RuleDescriptor DESCRIPTOR = describe(
            "A leave request that was approved or rejected before the transaction keeps its kind, its first and last "
                    + "days and its days; its status may change only to cancelled.",
            new RuleCode(DECIDED, "The leave request was approved or rejected before the transaction, and the "
                    + "transaction changes its kind, its first or last day, its days, or its status to one but "
                    + "cancelled. Leave the decided request as it is, cancel it, or file a new request."));

    /** Creates the rule; the service loader calls it. */
    public DecidedRequestsStay() {
    }

    @Override
    public RuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected void check(LeaveRequest request, RuleContext context) {
        for (LeaveRequest held : previous(request, context)) {
            if (!DECIDED_STATUSES.contains(held.status())) {
                continue;
            }
            List<String> changed = changes(held, request);
            if (!changed.isEmpty()) {
                context.problem(DECIDED, "The leave request " + request.id() + " was " + held.status()
                        + " before the transaction, from " + held.entityState().period().from() + ", so it keeps its "
                        + "kind, its first and last days and its days, and its status may change only to " + CANCELLED
                        + "; the transaction changes " + String.join(", ", changed) + ".");
                return;
            }
        }
    }

    /** The names of what a request has changed from a decided version of it that the rule requires to be kept. */
    private static List<String> changes(LeaveRequest decided, LeaveRequest request) {
        List<String> changed = new ArrayList<>();
        if (!decided.kind().equals(request.kind())) {
            changed.add("kind");
        }
        if (!decided.firstDay().equals(request.firstDay())) {
            changed.add("firstDay");
        }
        if (!decided.lastDay().equals(request.lastDay())) {
            changed.add("lastDay");
        }
        if (decided.days().compareTo(request.days()) != 0) {
            changed.add("days");
        }
        if (!decided.status().equals(request.status()) && !request.status().equals(CANCELLED)) {
            changed.add("status");
        }
        return changed;
    }
}
