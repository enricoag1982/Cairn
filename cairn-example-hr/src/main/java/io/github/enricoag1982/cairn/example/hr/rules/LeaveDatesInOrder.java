package io.github.enricoag1982.cairn.example.hr.rules;

import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.example.hr.model.LeaveDatesInOrderRuleV1;
import io.github.enricoag1982.cairn.example.hr.model.LeaveRequest;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;

/**
 * The rule {@code leave-dates-in-order} of the HR domain: the last day of a leave request is not before its first day.
 * A constraint across two attributes, which a schema document cannot declare.
 */
public final class LeaveDatesInOrder extends LeaveDatesInOrderRuleV1 {

    /** The code the rule reports when the last day is before the first day. */
    public static final String LAST_BEFORE_FIRST = "last-before-first";

    private static final RuleDescriptor DESCRIPTOR = describe(
            "The last day is not before the first day.",
            new RuleCode(LAST_BEFORE_FIRST, "The last day of a leave request is before its first day. Swap them, or "
                    + "correct the one that is wrong."));

    /** Creates the rule; the service loader calls it. */
    public LeaveDatesInOrder() {
    }

    @Override
    public RuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected void check(LeaveRequest request, RuleContext context) {
        if (request.lastDay().isBefore(request.firstDay())) {
            context.problem(LAST_BEFORE_FIRST, "lastDay", "The last day " + request.lastDay() + " is before the first "
                    + "day " + request.firstDay() + ".");
        }
    }
}
