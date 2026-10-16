package io.github.enricoag1982.cairn.core.query;

import io.github.enricoag1982.cairn.core.schema.ClosestName;
import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.core.schema.Values;
import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Cell;
import io.github.enricoag1982.cairn.query.Column;
import io.github.enricoag1982.cairn.query.Comparison;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.GroupKey;
import io.github.enricoag1982.cairn.query.Grouping;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Order;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryStep;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks a query against the schema of a view and turns it into a {@link Plan} (design D2). Every name is resolved: types,
 * attributes (including the inherited ones and those of traits), relationship names and inverse names, by name or alias
 * without regard to case, and bindings, which are compared exactly, against the bindings that the steps before bind. Every
 * problem is reported, with the path of the part of the query it concerns and the closest name where one is close, and the
 * view is not read: checking uses the schema only.
 *
 * <p>A name that cannot be resolved binds nothing and ends no check: the parts that depend on it are skipped, so that one
 * mistake is one problem, and the rest of the query is still checked.
 *
 * <p>A view restricted to a clearance tells the checker what it withholds (ADR-0036, design D3 of
 * {@code add-attribute-access-control}): every place where the query names an attribute that the view withholds, in a filter, an order, a group key, an aggregate, a column or the condition of a traversal or a link test, is a
 * problem {@code ACCESS_DENIED}, and the attribute is treated as one that cannot be resolved. Nothing is read, as for every
 * problem.
 */
final class Checker {

    /**
     * What checking gives.
     *
     * @param plan     the query ready to run; null if there are problems
     * @param problems the problems; empty if there is a plan
     */
    record Checked(Plan plan, List<Problem> problems) {
    }

    /** What a name of a relationship leads to: the relationship type, and the entity type at the other end. */
    private record Navigation(RelationshipType type, EntityType end) {
    }

    /** An operand that was resolved: how to read it, its value type, and how to name it in a message. */
    private record Resolved(Plan.Fetch fetch, ValueType type, String text, Value literal) {

        boolean isLiteral() {
            return literal != null;
        }

        /** The literal with the value type of what it is compared with, where it can take it. */
        Resolved takingTypeOf(ValueType target) {
            Value coerced = Comparing.coerce(literal, target);
            return coerced == literal ? this : new Resolved(row -> coerced, coerced.valueType(), text, coerced);
        }
    }

    /** Where a key or an aggregate is in a grouped row. */
    private record Output(int index, boolean key) {
    }

    private final Schema schema;
    private final Query query;
    /** What the view that runs the query withholds; empty for a view that is not restricted, which withholds nothing. */
    private final Optional<Withholding> withholding;
    private final List<Problem> problems = new ArrayList<>();
    /** The bindings by name, as bound so far. */
    private final Map<String, Binding> names = new LinkedHashMap<>();
    /**
     * The only bindings that names can refer to while the {@code along} condition of a traversal or of a link test is
     * checked: its own relationship, if it names one. Null otherwise.
     */
    private Map<String, Binding> alongScope;
    /** What the condition that is checked belongs to, for messages: {@code traversal} or {@code hasLink test}. */
    private String alongOwner;
    /** Every binding, named or not, by the position of its cell in a row. */
    private final List<Binding> slots = new ArrayList<>();
    /** The keys and aggregates of a grouped query by name; null for a query without grouping. */
    private Map<String, Output> outputs;

    private Checker(Schema schema, Query query, Optional<Withholding> withholding) {
        this.schema = schema;
        this.query = query;
        this.withholding = withholding;
    }

    /**
     * Checks a query.
     *
     * @param schema    the schema of the view that runs the query
     * @param query     the query
     * @param withholding what the view that runs the query withholds, if it is restricted
     * @return the plan, or the problems; reads nothing
     */
    static Checked check(Schema schema, Query query, Optional<Withholding> withholding) {
        return new Checker(schema, query, withholding).run();
    }

    private Checked run() {
        EntityType startType = entityType("@query.from", query.start().entityType());
        bind("@query.from", query.start().binding(), Binding.Kind.ENTITY, startType);
        List<Stage> stages = new ArrayList<>();
        for (int n = 0; n < query.steps().size(); n++) {
            Stage stage = step("@query.steps." + n, query.steps().get(n));
            if (stage != null) {
                stages.add(stage);
            }
        }
        Groups groups = query.grouping().map(this::grouping).orElse(null);
        List<String> columnNames = new ArrayList<>();
        List<Plan.Pick> columns = new ArrayList<>();
        columns(columnNames, columns);
        List<Comparator<Cell[]>> orders = orders();
        if (!problems.isEmpty()) {
            return new Checked(null, List.copyOf(problems));
        }
        Comparator<Cell[]> rowOrder = rowOrder();
        List<Comparator<Cell[]>> finalOrder = new ArrayList<>(orders);
        finalOrder.add(groups == null ? rowOrder : keyOrder(groups.keys().size()));
        OptionalInt limit = query.limit();
        return new Checked(new Plan(startType.name(), query.start().exactType(), query.start().ids(), slots.size(),
                List.copyOf(stages), groups, List.copyOf(columnNames), List.copyOf(columns), rowOrder,
                chain(finalOrder), limit), List.of());
    }

    // ------------------------------------------------------------------ bindings

    /** Binds a name, reporting it if the query has bound it already. */
    private Binding bind(String path, String name, Binding.Kind kind, EntityType type) {
        return bind(path, name, kind, type == null ? null : type.name(), type == null ? null : type.attributes());
    }

    private Binding bind(String path, String name, Binding.Kind kind, String typeName, List<Attribute> attributes) {
        return bind(path, name, kind, typeName, attributes, List.of());
    }

    private Binding bind(String path, String name, Binding.Kind kind, String typeName, List<Attribute> attributes,
            List<String> unrelated) {
        boolean taken = name != null && names.containsKey(name);
        if (taken) {
            problem(ProblemCode.DUPLICATE_BINDING, path, "The query binds the name '" + name + "' more than once.",
                    "a name that no other binding of the query has", name, null);
        }
        Binding binding = new Binding(name == null || taken ? null : name, kind, slots.size(), typeName, attributes,
                unrelated);
        slots.add(binding);
        if (binding.name() != null) {
            names.put(name, binding);
        }
        return binding;
    }

    /** Finds a binding that the query has bound by now, of one of the kinds; reports it if there is none. */
    private Binding lookup(String path, String name, Binding.Kind... kinds) {
        EnumSet<Binding.Kind> allowed = EnumSet.copyOf(List.of(kinds));
        Map<String, Binding> visible = alongScope != null ? alongScope : names;
        Binding found = visible.get(name);
        if (found != null && allowed.contains(found.kind())) {
            return found;
        }
        String wanted = allowed.stream().map(Binding.Kind::phrase).collect(Collectors.joining(" or "));
        if (found != null) {
            problem(ProblemCode.UNKNOWN_BINDING, path, "The binding '" + name + "' holds " + found.kind().phrase()
                    + ", but " + wanted + " is needed here.", "a binding that holds " + wanted, found.kind().phrase(),
                    null);
            return null;
        }
        if (alongScope != null) {
            alongProblem(path, name);
            return null;
        }
        List<String> candidates = names.values().stream().filter(binding -> allowed.contains(binding.kind()))
                .map(Binding::name).toList();
        problem(ProblemCode.UNKNOWN_BINDING, path, "The query has no binding '" + name + "' at this point.",
                candidates.isEmpty() ? "a binding that holds " + wanted + ", bound by an earlier step"
                        : "one of " + String.join(", ", candidates), name, closest(name, candidates));
        return null;
    }

    /** Reports a name in an {@code along} condition that is not the relationship of the traversal or link test. */
    private void alongProblem(String path, String name) {
        String owner = alongOwner;
        if (alongScope.isEmpty()) {
            problem(ProblemCode.UNKNOWN_BINDING, path, "The 'along' condition of a " + owner + " can refer only to the "
                    + "relationship of the " + owner + ", but the " + owner + " does not name it, and the condition "
                    + "refers to '" + name + "'.", "the name of the relationship of the " + owner + ", given with "
                    + "'relationship'", name, "Name the relationship of the " + owner + ", and refer to that name.");
            return;
        }
        String own = alongScope.keySet().iterator().next();
        problem(ProblemCode.UNKNOWN_BINDING, path, "The 'along' condition of a " + owner + " can refer only to the "
                + "relationship of the " + owner + ", '" + own + "', and to values, but it refers to '" + name + "'.",
                "the relationship '" + own + "' of the " + owner, name, Optional.ofNullable(closest(name, List.of(own)))
                        .orElse("Refer to the relationship '" + own + "' of the " + owner + ", or to a value."));
    }

    // ------------------------------------------------------------------ names in the schema

    private EntityType entityType(String path, String name) {
        Optional<EntityType> found = schema.entityType(name);
        if (found.isPresent()) {
            return found.get();
        }
        List<ClosestName.Candidate> candidates = schema.entityTypes().stream()
                .flatMap(type -> ClosestName.candidates(type.name(), type.aliases()).stream()).toList();
        problem(ProblemCode.UNKNOWN_ENTITY_TYPE, path, "The schema has no entity type '" + name + "'.",
                "the name or an alias of an entity type", name, closestOf(name, candidates));
        return null;
    }

    /** Finds what a relationship name or inverse name leads to; reports a name that is neither. */
    private Navigation navigation(String path, String name) {
        Optional<RelationshipType> byName = schema.relationshipType(name);
        Optional<RelationshipType> byInverse = schema.relationshipTypeByInverseName(name);
        if (byName.isEmpty() && byInverse.isEmpty()) {
            List<ClosestName.Candidate> candidates = new ArrayList<>();
            for (RelationshipType type : schema.relationshipTypes()) {
                candidates.addAll(ClosestName.candidates(type.name(), type.aliases()));
                if (!type.inverseName().isEmpty()) {
                    candidates.addAll(ClosestName.candidates(type.inverseName(), type.inverseAliases()));
                }
            }
            problem(ProblemCode.UNKNOWN_RELATIONSHIP_TYPE, path, "The schema has no relationship type or inverse name '"
                    + name + "'.", "the name or an alias of a relationship type, or its inverse name", name,
                    closestOf(name, candidates));
            return null;
        }
        RelationshipType type = byName.orElseGet(byInverse::orElseThrow);
        String end = byName.isEmpty() && !type.symmetric() ? type.source() : type.target();
        return new Navigation(type, schema.entityType(end).orElse(null));
    }

    /** Finds an attribute of a binding's type; reports a name that is none. Empty silently if the type is unknown. */
    private Attribute attribute(String path, Binding binding, String name) {
        if (!binding.unrelated().isEmpty()) {
            problem(ProblemCode.UNKNOWN_ATTRIBUTE, path, "The binding '" + binding.name() + "' can hold entities of the "
                    + "types " + String.join(" and ", binding.unrelated()) + ", which have no common supertype, so it "
                    + "has no attribute '" + name + "' to read.", "an attribute of a binding whose entities have a "
                    + "common supertype", name, "Start the traversal at depth 1 to bind only the entities it reaches, "
                    + "or test the binding with isA.");
            return null;
        }
        if (binding.attributes() == null) {
            return null;
        }
        Optional<Attribute> found = binding.attributes().stream().filter(attribute -> attribute.isNamed(name))
                .findFirst();
        if (found.isPresent()) {
            Optional<String> blocked = withholding.flatMap(view -> view.blockedBy(found.get()));
            if (blocked.isPresent()) {
                denied(path, binding, found.get(), blocked.get());
                return null;
            }
            return found.get();
        }
        List<ClosestName.Candidate> candidates = binding.attributes().stream()
                .flatMap(attribute -> ClosestName.candidates(attribute.name(), attribute.aliases()).stream()).toList();
        String known = binding.attributes().stream().map(Attribute::name).collect(Collectors.joining(", "));
        problem(ProblemCode.UNKNOWN_ATTRIBUTE, path, "The " + binding.kind().typeWord() + " " + binding.typeName()
                + " has no attribute '" + name + "'.", known.isEmpty() ? "an attribute of " + binding.typeName()
                : "one of " + known, name, closestOf(name, candidates));
        return null;
    }

    /** Reports an attribute that the clearance of the view does not cover, or that the store classifies since. */
    private void denied(String path, Binding binding, Attribute attribute, String classification) {
        Set<String> covered = withholding.orElseThrow().covered();
        problem(ProblemCode.ACCESS_DENIED, path, "The query names the attribute " + attribute.name() + " of "
                + binding.typeName() + ", which is classified " + classification + ", but the clearance of the view "
                + "that runs it does not cover " + classification + ", so the query would reveal what the view "
                + "withholds.", "a clearance that covers " + classification,
                covered.isEmpty() ? "a clearance that covers no classification"
                        : "a clearance that covers " + String.join(", ", covered),
                "Leave " + attribute.name() + " out of the query, or run the query through a view whose clearance "
                + "covers " + classification + ".");
    }

    // ------------------------------------------------------------------ steps

    private Stage step(String path, QueryStep step) {
        return switch (step) {
            case QueryStep.Filter filter -> {
                Plan.Test test = condition(path, filter.condition());
                yield test == null ? null : new Stage.Filter(test);
            }
            case QueryStep.Follow follow -> {
                Binding from = lookup(path, follow.from(), Binding.Kind.ENTITY);
                Navigation navigation = navigation(path, follow.name());
                Binding entity = bindEnd(path, follow.binding(), navigation);
                Binding relationship = bindRelationship(path, follow.relationship(), navigation);
                yield from == null || navigation == null ? null : new Stage.Follow(from.slot(), follow.name(),
                        entity.slot(), relationship.slot(), follow.optional());
            }
            case QueryStep.Traverse traverse -> {
                Binding from = lookup(path, traverse.from(), Binding.Kind.ENTITY);
                Navigation navigation = navigation(path, traverse.name());
                Binding entity = bindReached(path, traverse, from, navigation);
                Binding relationship = bindRelationship(path, traverse.relationship(), navigation);
                Binding depth = bind(path, traverse.depth().orElse(null), Binding.Kind.DEPTH, null, null);
                Plan.Test test = traverse.along().map(condition -> along(path, condition, "traversal", traverse.relationship(),
                        relationship))
                        .orElse(null);
                yield from == null || navigation == null || traverse.along().isPresent() && test == null ? null
                        : new Stage.Traverse(from.slot(), traverse.name(), entity.slot(), relationship.slot(),
                                depth.slot(), test, traverse.minDepth(), traverse.maxDepth().orElse(Integer.MAX_VALUE));
            }
        };
    }

    private Binding bindEnd(String path, String name, Navigation navigation) {
        return bind(path, name, Binding.Kind.ENTITY, navigation == null ? null : navigation.end());
    }

    /**
     * Binds the entity that a traversal reaches. With a minimum depth of 0 it can also be the entity the traversal starts
     * from, so its type is the closest common supertype of the type at the other end and the type of the binding that
     * the traversal starts from; if there is none, the binding has no type to read attributes of.
     */
    private Binding bindReached(String path, QueryStep.Traverse traverse, Binding from, Navigation navigation) {
        EntityType end = navigation == null ? null : navigation.end();
        if (end == null) {
            return bind(path, traverse.binding(), Binding.Kind.ENTITY, (EntityType) null);
        }
        if (traverse.minDepth() > 0 || from == null) {
            return bind(path, traverse.binding(), Binding.Kind.ENTITY, end);
        }
        List<String> types = new ArrayList<>();
        if (!from.unrelated().isEmpty()) {
            types.addAll(from.unrelated());
        } else if (from.typeName() == null) {
            return bind(path, traverse.binding(), Binding.Kind.ENTITY, (EntityType) null);
        } else {
            EntityType start = schema.entityType(from.typeName()).orElse(null);
            if (start == null) {
                return bind(path, traverse.binding(), Binding.Kind.ENTITY, (EntityType) null);
            }
            EntityType common = closestCommonSupertype(start, end);
            if (common != null) {
                return bind(path, traverse.binding(), Binding.Kind.ENTITY, common);
            }
            types.add(start.name());
        }
        if (!types.contains(end.name())) {
            types.add(end.name());
        }
        return bind(path, traverse.binding(), Binding.Kind.ENTITY, null, null, types);
    }

    /** The nearest entity type that two types are or extend; null if they have none. */
    private EntityType closestCommonSupertype(EntityType first, EntityType second) {
        Set<String> ofSecond = new HashSet<>();
        ofSecond.add(second.name());
        ofSecond.addAll(second.supertypes());
        List<String> ofFirst = new ArrayList<>();
        ofFirst.add(first.name());
        ofFirst.addAll(first.supertypes());
        for (String name : ofFirst) {
            if (ofSecond.contains(name)) {
                return schema.entityType(name).orElse(null);
            }
        }
        return null;
    }

    /**
     * Checks the {@code along} condition of a traversal or of a link test. It can refer to the relationship that its
     * owner names, if it names one, and to literals; any other name is an unknown binding.
     *
     * @param path         the path of the problems
     * @param condition    the condition
     * @param owner        what the condition belongs to, for messages
     * @param name         the name the owner gives the relationship, if it does
     * @param relationship the binding that the name refers to
     */
    private Plan.Test along(String path, Condition condition, String owner, Optional<String> name,
            Binding relationship) {
        Map<String, Binding> scope = new LinkedHashMap<>();
        name.ifPresent(own -> scope.put(own, relationship));
        Map<String, Binding> outerScope = alongScope;
        String outerOwner = alongOwner;
        alongScope = scope;
        alongOwner = owner;
        try {
            return condition(path, condition);
        } finally {
            alongScope = outerScope;
            alongOwner = outerOwner;
        }
    }

    private Binding bindRelationship(String path, Optional<String> name, Navigation navigation) {
        RelationshipType type = navigation == null ? null : navigation.type();
        return bind(path, name.orElse(null), Binding.Kind.RELATIONSHIP, type == null ? null : type.name(),
                type == null ? null : type.attributes());
    }

    /**
     * The relationship of a link test's links, for its {@code along} condition: it belongs to the condition alone, so it
     * has no place in a row of the query; the condition is run on a row of one cell.
     */
    private static Binding localRelationship(Navigation navigation) {
        RelationshipType type = navigation == null ? null : navigation.type();
        return new Binding(null, Binding.Kind.RELATIONSHIP, 0, type == null ? null : type.name(),
                type == null ? null : type.attributes(), List.of());
    }

    // ------------------------------------------------------------------ conditions

    private Plan.Test condition(String path, Condition condition) {
        return switch (condition) {
            case Condition.Compare compare -> compare(path, compare);
            case Condition.In in -> in(path, in);
            case Condition.HasValue hasValue -> {
                Binding binding = lookup(path, hasValue.binding(), Binding.Kind.ENTITY, Binding.Kind.RELATIONSHIP);
                Attribute attribute = binding == null ? null : attribute(path, binding, hasValue.attribute());
                if (attribute == null) {
                    yield null;
                }
                int slot = binding.slot();
                String name = attribute.name();
                yield (row, context) -> Cells.attribute(row[slot], name) != null;
            }
            case Condition.HasLink hasLink -> {
                Binding binding = lookup(path, hasLink.binding(), Binding.Kind.ENTITY);
                Navigation navigation = navigation(path, hasLink.name());
                Plan.Test along = hasLink.along().map(inner -> along(path, inner, "hasLink test",
                        hasLink.relationship(), localRelationship(navigation))).orElse(null);
                if (binding == null || navigation == null || hasLink.along().isPresent() && along == null) {
                    yield null;
                }
                int slot = binding.slot();
                String name = hasLink.name();
                if (along == null) {
                    yield (row, context) -> row[slot] instanceof Cell.EntityCell bound
                            && !context.navigate(bound.state().id(), name).isEmpty();
                }
                yield (row, context) -> row[slot] instanceof Cell.EntityCell bound
                        && context.navigate(bound.state().id(), name).stream().anyMatch(link -> {
                            Cell[] probe = {new Cell.RelationshipCell(link.relationship())};
                            return along.holds(probe, context);
                        });
            }
            case Condition.IsA isA -> {
                Binding binding = lookup(path, isA.binding(), Binding.Kind.ENTITY);
                EntityType type = entityType(path, isA.entityType());
                if (binding == null || type == null) {
                    yield null;
                }
                int slot = binding.slot();
                Set<String> types = new HashSet<>();
                types.add(type.name().toLowerCase(Locale.ROOT));
                type.subtypes().forEach(subtype -> types.add(subtype.toLowerCase(Locale.ROOT)));
                yield (row, context) -> row[slot] instanceof Cell.EntityCell bound
                        && types.contains(bound.state().entityType().toLowerCase(Locale.ROOT));
            }
            case Condition.And and -> {
                List<Plan.Test> tests = conditions(path, and.conditions());
                yield tests == null ? null : (row, context) -> tests.stream().allMatch(test -> test.holds(row, context));
            }
            case Condition.Or or -> {
                List<Plan.Test> tests = conditions(path, or.conditions());
                yield tests == null ? null : (row, context) -> tests.stream().anyMatch(test -> test.holds(row, context));
            }
            case Condition.Not not -> {
                Plan.Test test = condition(path, not.condition());
                yield test == null ? null : (row, context) -> !test.holds(row, context);
            }
        };
    }

    /** Checks all of some conditions, so that all their problems are reported; null if there was one. */
    private List<Plan.Test> conditions(String path, List<Condition> conditions) {
        List<Plan.Test> tests = new ArrayList<>();
        boolean valid = true;
        for (Condition condition : conditions) {
            Plan.Test test = condition(path, condition);
            valid &= test != null;
            tests.add(test);
        }
        return valid ? tests : null;
    }

    private Plan.Test compare(String path, Condition.Compare compare) {
        Resolved left = operand(path, compare.left());
        Resolved right = operand(path, compare.right());
        if (left == null || right == null) {
            return null;
        }
        Resolved first = left.isLiteral() && !right.isLiteral() ? left.takingTypeOf(right.type()) : left;
        Resolved second = right.isLiteral() && !left.isLiteral() ? right.takingTypeOf(left.type()) : right;
        if (!Comparing.comparable(first.type(), second.type(), compare.comparison())) {
            incomparable(path, first, compare.comparison(), second);
            return null;
        }
        Comparison comparison = compare.comparison();
        Plan.Fetch fetchFirst = first.fetch();
        Plan.Fetch fetchSecond = second.fetch();
        return (row, context) -> {
            Value a = fetchFirst.of(row);
            Value b = fetchSecond.of(row);
            return a != null && b != null && Comparing.holds(a, comparison, b);
        };
    }

    private Plan.Test in(String path, Condition.In in) {
        Resolved operand = operand(path, in.operand());
        if (operand == null) {
            return null;
        }
        List<Value> values = new ArrayList<>();
        boolean valid = true;
        for (Value value : in.values()) {
            Value taken = operand.isLiteral() ? value : Comparing.coerce(value, operand.type());
            Resolved literal = new Resolved(row -> taken, taken.valueType(), describe(taken), taken);
            if (!Comparing.comparable(operand.type(), taken.valueType(), Comparison.EQ)) {
                incomparable(path, operand, Comparison.EQ, literal);
                valid = false;
            }
            values.add(taken);
        }
        if (!valid) {
            return null;
        }
        Plan.Fetch fetch = operand.fetch();
        return (row, context) -> {
            Value value = fetch.of(row);
            return value != null && values.stream().anyMatch(candidate -> Comparing.equal(value, candidate));
        };
    }

    private void incomparable(String path, Resolved first, Comparison comparison, Resolved second) {
        Comparing.Category category = Comparing.category(first.type());
        if (category == Comparing.category(second.type())) {
            problem(ProblemCode.INCOMPARABLE_VALUES, path, "Booleans compare for equality only, but " + first.text()
                    + " and " + second.text() + " are compared with " + comparison + ", which needs an order.",
                    "EQ or NE", comparison.name(), null);
            return;
        }
        problem(ProblemCode.INCOMPARABLE_VALUES, path, "Cannot compare " + first.text() + " (" + typeName(first.type())
                + ") with " + second.text() + " (" + typeName(second.type()) + ").", category.phrase(),
                typeName(second.type()), null);
    }

    // ------------------------------------------------------------------ operands

    private Resolved operand(String path, Operand operand) {
        return switch (operand) {
            case Operand.AttributeOf of -> {
                Binding binding = lookup(path, of.binding(), Binding.Kind.ENTITY, Binding.Kind.RELATIONSHIP);
                Attribute attribute = binding == null ? null : attribute(path, binding, of.attribute());
                if (attribute == null) {
                    yield null;
                }
                int slot = binding.slot();
                String name = attribute.name();
                yield new Resolved(row -> Cells.attribute(row[slot], name), attribute.valueType(),
                        of.binding() + "." + of.attribute(), null);
            }
            case Operand.IdOf of -> {
                Binding binding = lookup(path, of.binding(), Binding.Kind.ENTITY, Binding.Kind.RELATIONSHIP);
                yield binding == null ? null : new Resolved(row -> Cells.id(row[binding.slot()]), ValueType.STRING,
                        "the ID of " + of.binding(), null);
            }
            case Operand.TypeOf of -> {
                Binding binding = lookup(path, of.binding(), Binding.Kind.ENTITY, Binding.Kind.RELATIONSHIP);
                yield binding == null ? null : new Resolved(row -> Cells.type(row[binding.slot()]), ValueType.STRING,
                        "the type of " + of.binding(), null);
            }
            case Operand.Literal literal -> new Resolved(row -> literal.value(), literal.value().valueType(),
                    describe(literal.value()), literal.value());
        };
    }

    // ------------------------------------------------------------------ grouping

    private Groups grouping(Grouping grouping) {
        Map<String, Output> found = new LinkedHashMap<>();
        List<Plan.Pick> keys = new ArrayList<>();
        for (int n = 0; n < grouping.keys().size(); n++) {
            String path = "@query.groupBy." + n;
            GroupKey key = grouping.keys().get(n);
            Plan.Pick pick = key(path, key);
            claim(found, path, key.name(), keys.size(), true);
            keys.add(pick == null ? row -> Cells.EMPTY : pick);
        }
        List<Groups.Computed> computed = new ArrayList<>();
        for (int n = 0; n < grouping.aggregates().size(); n++) {
            String path = "@query.aggregates." + n;
            Aggregate aggregate = grouping.aggregates().get(n);
            Groups.Computed one = aggregate(path, aggregate);
            claim(found, path, aggregate.name(), keys.size() + computed.size(), false);
            computed.add(one == null ? new Groups.Computed(aggregate.function(), null, null) : one);
        }
        outputs = found;
        return new Groups(List.copyOf(keys), List.copyOf(computed));
    }

    private void claim(Map<String, Output> found, String path, String name, int index, boolean key) {
        if (found.containsKey(name)) {
            problem(ProblemCode.DUPLICATE_BINDING, path, "The name '" + name
                    + "' is given to more than one group key or aggregate.",
                    "a name that no other group key or aggregate has", name, null);
        } else {
            found.put(name, new Output(index, key));
        }
    }

    private Plan.Pick key(String path, GroupKey key) {
        if (key.attribute().isEmpty()) {
            Binding binding = lookup(path, key.binding(), Binding.Kind.ENTITY, Binding.Kind.RELATIONSHIP,
                    Binding.Kind.DEPTH);
            return binding == null ? null : row -> Cells.orEmpty(row[binding.slot()]);
        }
        Binding binding = lookup(path, key.binding(), Binding.Kind.ENTITY, Binding.Kind.RELATIONSHIP);
        Attribute attribute = binding == null ? null : attribute(path, binding, key.attribute().get());
        if (attribute == null) {
            return null;
        }
        int slot = binding.slot();
        String name = attribute.name();
        return row -> Cells.of(Cells.attribute(row[slot], name));
    }

    private Groups.Computed aggregate(String path, Aggregate aggregate) {
        Aggregate.Function function = aggregate.function();
        if (aggregate.of().isEmpty()) {
            if (function == Aggregate.Function.COUNT) {
                return new Groups.Computed(function, null, null);
            }
            problem(ProblemCode.INVALID_AGGREGATE, path, "A " + word(function) + " needs an attribute to work on, but "
                    + "the aggregate '" + aggregate.name() + "' has none.", "an attribute of a binding", "no attribute",
                    null);
            return null;
        }
        Operand.AttributeOf of = aggregate.of().get();
        Binding binding = lookup(path, of.binding(), Binding.Kind.ENTITY, Binding.Kind.RELATIONSHIP);
        Attribute attribute = binding == null ? null : attribute(path, binding, of.attribute());
        if (attribute == null) {
            return null;
        }
        Comparing.Category category = Comparing.category(attribute.valueType());
        boolean numbers = function == Aggregate.Function.SUM || function == Aggregate.Function.AVG;
        String found = typeName(attribute.valueType()) + " attribute '" + attribute.name() + "' of " + binding.typeName();
        if (numbers && category != Comparing.Category.NUMBER) {
            problem(ProblemCode.INVALID_AGGREGATE, path, "A " + word(function) + " needs numbers, but the attribute '"
                    + attribute.name() + "' of " + binding.typeName() + " is " + article(attribute.valueType()) + ".",
                    "a numeric attribute (int, long or decimal)", found, null);
            return null;
        }
        if (!numbers && function != Aggregate.Function.COUNT && category == Comparing.Category.BOOLEAN) {
            problem(ProblemCode.INVALID_AGGREGATE, path, "A " + word(function) + " needs values that can be ordered, "
                    + "but the attribute '" + attribute.name() + "' of " + binding.typeName() + " is a boolean.",
                    "an attribute that holds numbers, dates, instants or strings", found, null);
            return null;
        }
        int slot = binding.slot();
        String name = attribute.name();
        return new Groups.Computed(function, row -> Cells.attribute(row[slot], name), category);
    }

    // ------------------------------------------------------------------ columns and order

    /** Fills the names and picks of the columns: the query's own, or by default the bindings or the keys and aggregates. */
    private void columns(List<String> columnNames, List<Plan.Pick> columnPicks) {
        if (query.columns().isEmpty()) {
            if (outputs == null) {
                for (Binding binding : slots) {
                    if (binding.name() != null) {
                        int slot = binding.slot();
                        columnNames.add(binding.name());
                        columnPicks.add(row -> Cells.orEmpty(row[slot]));
                    }
                }
            } else {
                outputs.forEach((name, output) -> {
                    int index = output.index();
                    columnNames.add(name);
                    columnPicks.add(row -> Cells.orEmpty(row[index]));
                });
            }
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int n = 0; n < query.columns().size(); n++) {
            String path = "@query.columns." + n;
            Column column = query.columns().get(n);
            Plan.Pick pick = column(path, column);
            if (!seen.add(column.name())) {
                problem(ProblemCode.DUPLICATE_BINDING, path, "The name '" + column.name()
                        + "' is given to more than one column.", "a name that no other column has", column.name(),
                        null);
            }
            columnNames.add(column.name());
            columnPicks.add(pick == null ? row -> Cells.EMPTY : pick);
        }
    }

    private List<Comparator<Cell[]>> orders() {
        List<Comparator<Cell[]>> comparators = new ArrayList<>();
        for (int n = 0; n < query.order().size(); n++) {
            Order order = query.order().get(n);
            Plan.Pick pick = column("@query.order." + n, order.column());
            if (pick != null) {
                int direction = order.descending() ? -1 : 1;
                comparators.add((first, second) -> direction * Integer.signum(Comparing.orderMissingFirst(
                        Cells.sortValue(pick.of(first)), Cells.sortValue(pick.of(second)))));
            }
        }
        return comparators;
    }

    /** What a column reads out of a row: of the bindings before grouping, of the keys and aggregates after it. */
    private Plan.Pick column(String path, Column column) {
        if (outputs != null) {
            return switch (column) {
                case Column.KeyColumn key -> output(path, key.key(), true);
                case Column.AggregateColumn aggregate -> output(path, aggregate.aggregate(), false);
                default -> {
                    problem(ProblemCode.UNKNOWN_BINDING, path, "The rows of a grouped query have only their group keys "
                            + "and aggregates, so the column '" + column.name() + "' of a binding is not available.",
                            "a group key or an aggregate", column.name(), hintForOutputs(column.name(), true));
                    yield null;
                }
            };
        }
        return switch (column) {
            case Column.BindingColumn binding -> {
                Binding found = lookup(path, binding.binding(), Binding.Kind.ENTITY, Binding.Kind.RELATIONSHIP);
                yield found == null ? null : row -> Cells.orEmpty(row[found.slot()]);
            }
            case Column.AttributeColumn attribute -> {
                Binding found = lookup(path, attribute.binding(), Binding.Kind.ENTITY, Binding.Kind.RELATIONSHIP);
                Attribute resolved = found == null ? null : attribute(path, found, attribute.attribute());
                if (resolved == null) {
                    yield null;
                }
                int slot = found.slot();
                String name = resolved.name();
                yield row -> Cells.of(Cells.attribute(row[slot], name));
            }
            case Column.DepthColumn depth -> {
                Binding found = lookup(path, depth.binding(), Binding.Kind.DEPTH);
                yield found == null ? null : row -> Cells.orEmpty(row[found.slot()]);
            }
            case Column.KeyColumn key -> noGrouping(path, key.key());
            case Column.AggregateColumn aggregate -> noGrouping(path, aggregate.aggregate());
        };
    }

    private Plan.Pick output(String path, String name, boolean key) {
        Output found = outputs.get(name);
        if (found != null && found.key() == key) {
            int index = found.index();
            return row -> Cells.orEmpty(row[index]);
        }
        String kind = key ? "group key" : "aggregate";
        if (found != null) {
            problem(ProblemCode.UNKNOWN_BINDING, path, "The name '" + name + "' is " + (key ? "an aggregate" : "a group key")
                    + ", not " + (key ? "a group key" : "an aggregate") + ".", "the name of a " + kind, name, null);
        } else {
            problem(ProblemCode.UNKNOWN_BINDING, path, "The query has no " + kind + " '" + name + "'.",
                    "the name of a " + kind, name, hintForOutputs(name, key));
        }
        return null;
    }

    private String hintForOutputs(String name, boolean key) {
        List<String> candidates = outputs.entrySet().stream().filter(entry -> entry.getValue().key() == key)
                .map(Map.Entry::getKey).toList();
        return closest(name, candidates);
    }

    private Plan.Pick noGrouping(String path, String name) {
        problem(ProblemCode.UNKNOWN_BINDING, path, "The query has no grouping, so it has no group key or aggregate '"
                + name + "'.", "a binding, an attribute of a binding or a depth", name, null);
        return null;
    }

    /** Orders rows by the IDs of their bindings in the order they were bound; empty bindings come first. */
    private Comparator<Cell[]> rowOrder() {
        List<Comparator<Cell[]>> comparators = new ArrayList<>();
        for (Binding binding : slots) {
            if (binding.kind() != Binding.Kind.DEPTH) {
                comparators.add(byCell(binding.slot()));
            }
        }
        return chain(comparators);
    }

    /** Orders grouped rows by their keys, which come first in them. */
    private static Comparator<Cell[]> keyOrder(int keys) {
        List<Comparator<Cell[]>> comparators = new ArrayList<>();
        for (int i = 0; i < keys; i++) {
            comparators.add(byCell(i));
        }
        return chain(comparators);
    }

    private static Comparator<Cell[]> byCell(int index) {
        return (first, second) -> Comparing.orderMissingFirst(Cells.sortValue(first[index]),
                Cells.sortValue(second[index]));
    }

    private static Comparator<Cell[]> chain(List<Comparator<Cell[]>> comparators) {
        List<Comparator<Cell[]>> copy = List.copyOf(comparators);
        return (first, second) -> {
            for (Comparator<Cell[]> comparator : copy) {
                int order = comparator.compare(first, second);
                if (order != 0) {
                    return order;
                }
            }
            return 0;
        };
    }

    // ------------------------------------------------------------------ messages

    private void problem(ProblemCode code, String path, String message, String expected, String found, String hint) {
        problems.add(Problems.of(code, path, message, expected, found, hint));
    }

    private static String closest(String name, Collection<String> candidates) {
        return ClosestName.of(name, candidates).map(closest -> "Did you mean '" + closest + "'?").orElse(null);
    }

    private static String closestOf(String name, Collection<ClosestName.Candidate> candidates) {
        return ClosestName.ofCandidates(name, candidates).map(closest -> "Did you mean '" + closest + "'?")
                .orElse(null);
    }

    private static String typeName(ValueType type) {
        return Values.typeName(type);
    }

    private static String article(ValueType type) {
        String name = typeName(type);
        return (name.startsWith("i") ? "an " : "a ") + name;
    }

    private static String word(Aggregate.Function function) {
        return switch (function) {
            case COUNT -> "count";
            case SUM -> "sum";
            case MIN -> "minimum";
            case MAX -> "maximum";
            case AVG -> "average";
        };
    }

    /** A value as a message names it: strings in quotes. */
    private static String describe(Value value) {
        return value instanceof Value.StringValue ? "'" + Values.describe(value) + "'" : Values.describe(value);
    }
}
