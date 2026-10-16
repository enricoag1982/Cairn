package io.github.enricoag1982.cairn.example.hr;

import io.github.enricoag1982.cairn.example.hr.model.BelongsTo;
import io.github.enricoag1982.cairn.example.hr.model.Employment;
import io.github.enricoag1982.cairn.example.hr.model.EmploymentOf;
import io.github.enricoag1982.cairn.example.hr.model.Fills;
import io.github.enricoag1982.cairn.example.hr.model.ManagedBy;
import io.github.enricoag1982.cairn.example.hr.model.OrgUnit;
import io.github.enricoag1982.cairn.example.hr.model.PartOf;
import io.github.enricoag1982.cairn.example.hr.model.Position;
import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Column;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.Order;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.Traversal;

/**
 * The questions HR asks of the company, as queries ({@code docs/reference/hr-domain.md}): headcount by unit, the
 * managers above an employee, and vacant positions. They are built with the typed references of the generated types, so
 * that the compiler checks names and values; the headcount is also given as JSON, the form agents send to
 * {@code cairn_query}. The queries that follow the hierarchy of units traverse it to the end, whatever its depth.
 */
public final class Reports {

    /**
     * The same headcount query as {@link #headcount()}, in the JSON form that {@code Queries.read} reads.
     */
    public static final String HEADCOUNT_JSON = """
            {
              "from": { "type": "OrgUnit", "as": "unit" },
              "steps": [
                { "traverse": { "from": "unit", "name": "subunits", "minDepth": 0, "as": "below" } },
                { "follow": { "from": "below", "name": "positions", "as": "position" } },
                { "follow": { "from": "position", "name": "filledBy", "as": "employment" } }
              ],
              "groupBy": [ { "of": "unit", "attribute": "code", "as": "unit" } ],
              "aggregates": [ { "function": "count", "as": "headcount" } ],
              "order": [ { "key": "unit" } ]
            }
            """;

    private Reports() {
    }

    /**
     * The headcount of each unit with the units below it: from each unit, the unit itself and the units below it by
     * {@code PartOf} (depth 0 and more), their positions, and the employments that fill them, counted per unit. A
     * vacant position has no employment and is not counted. The result has the columns {@code unit} (the unit's code)
     * and {@code headcount}, by code.
     *
     * @return the query
     */
    public static Query headcount() {
        return Query.from(OrgUnit.TYPE, "unit")
                .traverse(Traversal.of("unit", PartOf.INVERSE, "below").minDepth(0))
                .follow("below", BelongsTo.INVERSE, "position")
                .follow("position", Fills.INVERSE, "employment")
                .groupBy(OrgUnit.CODE.of("unit").groupKey("unit"))
                .aggregate(Aggregate.count("headcount"))
                .orderBy(Order.ascending(Column.key("unit")))
                .build();
    }

    /**
     * The managers of an employee, from the nearest up: from the employment, its position, the unit of the position and
     * the units above it by {@code PartOf} (depth 0, the unit itself, and more), the employments that manage them, and
     * their persons. The result has the columns {@code distance}, the number of {@code PartOf} between the employee's
     * unit and the unit that the manager manages, and {@code manager}, the manager's person.
     *
     * @param employeeNumber the employee number of the employee, such as {@code E-004}
     * @return the query
     */
    public static Query managersOf(String employeeNumber) {
        return Query.from(Employment.TYPE, "employee")
                .where(Employment.EMPLOYEE_NUMBER.of("employee").eq(employeeNumber))
                .follow("employee", Fills.TYPE, "position")
                .follow("position", BelongsTo.TYPE, "unit")
                .traverse(Traversal.of("unit", PartOf.TYPE, "managed").minDepth(0).depth("level"))
                .follow("managed", ManagedBy.TYPE, "managerEmployment")
                .follow("managerEmployment", EmploymentOf.TYPE, "manager")
                .select(Column.depth("level", "distance"), Column.binding("manager"))
                .orderBy(Order.ascending(Column.depth("level")))
                .build();
    }

    /**
     * The vacant positions: those that no employment fills at the view's effective time. The result has the position
     * as its only column, by ID.
     *
     * @return the query
     */
    public static Query vacantPositions() {
        return Query.from(Position.TYPE, "position")
                .where(Condition.not(Condition.hasLink("position", Fills.INVERSE)))
                .orderBy(Position.CODE.of("position").ascending())
                .build();
    }
}
