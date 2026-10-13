package io.github.enricoag1982.cairn.example.hr.rules;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.example.hr.model.Employment;
import io.github.enricoag1982.cairn.example.hr.model.HasLeaveRequest;
import io.github.enricoag1982.cairn.example.hr.model.LeaveRequest;
import io.github.enricoag1982.cairn.example.hr.model.VacationWithinEntitlementRuleV1;
import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.GroupKey;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * The rule {@code vacation-within-entitlement} of the HR domain: the approved vacation days of an employment, with
 * first days in the same calendar year, add up to at most 25.
 *
 * <p>The rule is named by {@code LeaveRequest}, whose writes can break it, and checks an approved vacation only: any
 * other request adds no days. The sum is a {@link Query} that a view at one instant runs: the employment's leave
 * requests that are vacations, approved, with a first day in the year of the checked one, summing their days. The other
 * requests of the employment can start or change at any instant of the checked version's period, so the rule runs the
 * query at the start of the period and at every later instant in it at which a request of the employment, or one of
 * its versions, starts or ends, each through a view at that instant ({@link RuleContext#viewAt(Instant)}).
 */
public final class VacationWithinEntitlement extends VacationWithinEntitlementRuleV1 {

    /** The code the rule reports when the approved vacation days of a year add up to more than 25. */
    public static final String OVER_ENTITLEMENT = "over-entitlement";

    /** The yearly entitlement, in days. */
    public static final BigDecimal ENTITLEMENT_DAYS = new BigDecimal("25");

    /** The inverse name of {@code HasLeaveRequest}: from a leave request to the employment that requests it. */
    private static final String REQUESTER = "requester";

    private static final RuleDescriptor DESCRIPTOR = describe(
            "An employment's approved vacation days, of requests whose first days are in one calendar year, add up to "
                    + "at most 25.",
            new RuleCode(OVER_ENTITLEMENT, "The approved vacation days of the employment, with first days in the "
                    + "same calendar year, add up to more than 25. Approve fewer days, or cancel or reject another "
                    + "request first."));

    /** Creates the rule; the service loader calls it. */
    public VacationWithinEntitlement() {
    }

    @Override
    public RuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected void check(LeaveRequest request, RuleContext context) {
        if (!request.kind().equals("vacation") || !request.status().equals("approved")) {
            return;
        }
        List<Link> requesters = context.view().navigate(request.id(), REQUESTER);
        if (requesters.isEmpty()) {
            return;
        }
        EntityId employment = requesters.getFirst().entity().id();
        Query query = approvedVacationDays(employment, request.firstDay().getYear());
        for (Instant at : instants(request.entityState().period(), employment, context.view())) {
            BigDecimal total = total(context.viewAt(at).query(query));
            if (total.compareTo(ENTITLEMENT_DAYS) > 0) {
                context.problem(OVER_ENTITLEMENT, "days", "The approved vacation days of "
                        + employmentNumber(context.view(), employment) + " in " + request.firstDay().getYear()
                        + " would add up to " + total.stripTrailingZeros().toPlainString() + " at " + at
                        + ", which is more than " + ENTITLEMENT_DAYS + ".");
                return;
            }
        }
    }

    /**
     * The query for the sum: from the employment, to its leave requests that are approved vacations with a first day
     * in the year, grouped by the employment, summing the days.
     */
    private static Query approvedVacationDays(EntityId employment, int year) {
        return Query.from(Employment.TYPE, "e")
                .where(Condition.eq(Operand.id("e"), Operand.literal(employment.value())))
                .follow("e", HasLeaveRequest.TYPE, "l")
                .where(Condition.and(
                        LeaveRequest.KIND.of("l").eq("vacation"),
                        LeaveRequest.STATUS.of("l").eq("approved"),
                        LeaveRequest.FIRST_DAY.of("l").ge(LocalDate.of(year, 1, 1)),
                        LeaveRequest.FIRST_DAY.of("l").le(LocalDate.of(year, 12, 31))))
                .groupBy(GroupKey.binding("e"))
                .aggregate(LeaveRequest.DAYS.of("l").sum("total"))
                .build();
    }

    /** The sum that the query found; no row means no such request, so no days. */
    private static BigDecimal total(QueryResult result) {
        return switch (result) {
            case QueryResult.Rows rows -> rows.rows().stream()
                    .map(row -> row.value("total").orElseThrow())
                    .map(value -> ((Value.DecimalValue) value).value())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            case QueryResult.Invalid invalid -> throw new IllegalStateException(
                    "The query for the approved vacation days is wrong: " + invalid.problems());
        };
    }

    /**
     * The start of the checked version's period, and each instant in it at which a leave request of the employment, or
     * a version of one, starts or ends.
     */
    private static NavigableSet<Instant> instants(EffectivePeriod period, EntityId employment, View view) {
        NavigableSet<Instant> instants = new TreeSet<>();
        instants.add(period.from());
        for (RelationshipState has : view.navigateTimeline(employment, HasLeaveRequest.TYPE)) {
            within(period, has.period(), instants);
            for (EntityState version : view.timeline(has.target())) {
                within(period, version.period(), instants);
            }
        }
        return instants;
    }

    /** Adds the start and the end of another period that lie in the checked one. */
    private static void within(EffectivePeriod checked, EffectivePeriod other, NavigableSet<Instant> instants) {
        if (checked.contains(other.from())) {
            instants.add(other.from());
        }
        other.until().filter(checked::contains).ifPresent(instants::add);
    }

    /** The employee number of an employment for a message, or its ID if the view does not have it. */
    private static String employmentNumber(View view, EntityId employment) {
        return view.entity(employment).map(state -> Employment.from(state).employeeNumber())
                .orElse(employment.toString());
    }
}
