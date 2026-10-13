package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.documents.NodeReader.Entry;
import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Column;
import io.github.enricoag1982.cairn.query.Comparison;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.GroupKey;
import io.github.enricoag1982.cairn.query.Grouping;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Order;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryStep;
import io.github.enricoag1982.cairn.query.Start;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.SequenceNode;

/**
 * Reads the YAML node tree of a query into a {@link Query}, strictly, and reports every problem with its location
 * (capability {@code queries}, design D4 of {@code add-queries-and-aggregation}). The strict reading of nodes is
 * {@link NodeReader}'s, shared with schema documents and transaction payloads; what is specific to queries is here: the
 * keys of steps, conditions, operands, group keys, aggregates, columns and orders.
 *
 * <p>The path of a problem is {@code @query}, or {@code @query.from}, {@code @query.steps.<n>},
 * {@code @query.groupBy.<n>}, {@code @query.aggregates.<n>}, {@code @query.columns.<n>}, {@code @query.order.<n>} or
 * {@code @query.limit}, as the paths of the problems of running a query are, so that both are located by the same
 * {@link QueryLocations}. The reader checks what the records require, such as a limit of at least 0 and a maximum depth
 * of at least the minimum, and reports a value that breaks it as {@code INVALID_VALUE}: the records are only built if
 * there is no problem, so no exception escapes. It does not check names against a schema: that is for the view that runs
 * the query.
 *
 * <p>Literals are read as payloads read a value of an unknown type: a JSON number is an int, a long or a decimal, a
 * string is a string, and {@code true} and {@code false} are booleans; the engine reads a string that spells a date or an
 * instant as one where it is compared with a date or instant attribute. A reader is used once.
 */
final class QueryReader {

    /** How deep conditions may nest; a deeper query is a problem, because the engine would fail on it. */
    private static final int MAX_NESTING = 100;

    private final NodeReader nodes = new NodeReader();
    /** Where the parts of the query are, by the path that problems about them carry. */
    private final Map<String, DocumentLocation> parts = new HashMap<>();

    private QueryReader() {
    }

    /** Reads a query. */
    static QueryReadResult read(String text) {
        return switch (YamlTree.compose(text)) {
            case YamlTree.Failed failed ->
                    new QueryReadResult.Invalid(List.of(NodeReader.syntax(failed, QueryKeys.QUERY_PATH)));
            case YamlTree.Composed composed -> new QueryReader().readQuery(composed);
        };
    }

    // ------------------------------------------------------------------ the query

    private QueryReadResult readQuery(YamlTree.Composed composed) {
        Optional<Problem> unsupported = composed.root().flatMap(
                root -> NodeReader.unsupportedVersion(root, QueryKeys.QUERY_PATH, "queries", "query"));
        if (unsupported.isPresent()) {
            return new QueryReadResult.Invalid(List.of(unsupported.get()));
        }
        DocumentLocation rootLocation =
                composed.root().map(root -> NodeReader.location("", root)).orElse(new DocumentLocation("", 1, 1));
        nodes.addAll(YamlSubset.problems(composed, "a query", QueryReader::elementPath));
        if (composed.root().isEmpty()) {
            nodes.missing(QueryKeys.QUERY_REQUIRED, "the query", QueryKeys.QUERY_PATH, rootLocation);
            return invalid();
        }
        Optional<Map<String, Entry>> top = nodes.properties(composed.root().get(), "", QueryKeys.QUERY_PATH,
                "the query", QueryKeys.QUERY, QueryKeys.QUERY_REQUIRED);
        if (top.isEmpty()) {
            return invalid();
        }
        Map<String, Entry> p = top.get();
        p.forEach((key, entry) -> parts.put(QueryKeys.PART_PATH + key, NodeReader.location(entry.pointer(), entry.keyNode())));
        nodes.formatVersion(p.get("cairn"), QueryKeys.QUERY_PATH);
        nodes.string(p.get(Keys.SCHEMA), QueryKeys.QUERY_PATH, "value of '$schema'");
        Optional<Start> start = start(p.get("from"));
        List<QueryStep> steps = steps(p.get("steps"));
        List<GroupKey> keys = list(p.get("groupBy"), "groupBy", "a list of group keys", this::groupKey);
        List<Aggregate> aggregates = list(p.get("aggregates"), "aggregates", "a list of aggregates", this::aggregate);
        List<Column> columns = list(p.get("columns"), "columns", "a list of columns", this::column);
        List<Order> order = list(p.get("order"), "order", "a list of orders", this::order);
        OptionalInt limit = limit(p.get("limit"));
        if (nodes.hasProblems() || start.isEmpty()) {
            return invalid();
        }
        Optional<Grouping> grouping = p.containsKey("groupBy") || p.containsKey("aggregates")
                ? Optional.of(new Grouping(keys, aggregates)) : Optional.empty();
        Query query;
        try {
            query = new Query(start.get(), steps, grouping, columns, order, limit);
        } catch (RuntimeException e) {
            // The checks above leave no way to break the records; this is for a check that was missed.
            nodes.add(ProblemCode.INVALID_VALUE, QueryKeys.QUERY_PATH, "The query cannot be built: " + e.getMessage(),
                    "a query that follows the published JSON Schema", "a query that the records reject",
                    null, rootLocation);
            return invalid();
        }
        return new QueryReadResult.Read(new LocatedQuery(query, new QueryLocations(rootLocation, parts)));
    }

    private QueryReadResult invalid() {
        return new QueryReadResult.Invalid(nodes.ordered());
    }

    /** The path of the part of the query that the node at a position in the text belongs to. */
    private static String elementPath(List<String> tokens) {
        if (tokens.isEmpty() || !QueryKeys.QUERY.contains(tokens.getFirst()) || Keys.SCHEMA.equals(tokens.getFirst())
                || tokens.getFirst().equals("cairn")) {
            return QueryKeys.QUERY_PATH;
        }
        String part = QueryKeys.PART_PATH + tokens.getFirst();
        boolean item = tokens.size() >= 2 && tokens.get(1).chars().allMatch(Character::isDigit);
        return item ? part + "." + tokens.get(1) : part;
    }

    // ------------------------------------------------------------------ from, limit and lists

    private Optional<Start> start(Entry entry) {
        if (entry == null) {
            return Optional.empty();
        }
        String path = QueryKeys.PART_PATH + "from";
        Optional<Map<String, Entry>> properties = nodes.properties(entry.value(), entry.pointer(), path,
                "the 'from' of the query", QueryKeys.FROM, QueryKeys.FROM_REQUIRED);
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        String type = nodes.string(p.get("type"), path, "value of 'type'").orElse("");
        String binding = nodes.string(p.get("as"), path, "value of 'as'").orElse("");
        return Optional.of(new Start(type, binding, nodes.bool(p.get("exact"), path)));
    }

    private OptionalInt limit(Entry entry) {
        if (entry == null) {
            return OptionalInt.empty();
        }
        String path = QueryKeys.PART_PATH + "limit";
        OptionalInt limit = nodes.integer(entry, path);
        if (limit.isPresent() && limit.getAsInt() < 0) {
            wholeNumberOfAtLeast0(entry.value(), entry.pointer(), path, "The limit of a query");
            return OptionalInt.empty();
        }
        return limit;
    }

    private void wholeNumberOfAtLeast0(Node node, String pointer, String path, String what) {
        String found = NodeReader.describe(node);
        nodes.add(ProblemCode.INVALID_VALUE, path, what + " is a whole number of at least 0, but it is " + found + ".",
                "a whole number of at least 0", found, null, NodeReader.location(pointer, node));
    }

    /** Reads a list of parts of one kind: each item is read by a function of the node, its pointer and its path. */
    private <T> List<T> list(Entry entry, String key, String expected, PartReader<T> reader) {
        if (entry == null) {
            return List.of();
        }
        if (!(entry.value() instanceof SequenceNode sequence)) {
            nodes.invalidKind(entry.value(), entry.pointer(), QueryKeys.PART_PATH + key, "value of '" + key + "'",
                    expected);
            return List.of();
        }
        List<T> result = new ArrayList<>();
        for (int position = 0; position < sequence.getValue().size(); position++) {
            Node item = sequence.getValue().get(position);
            String pointer = Pointers.child(entry.pointer(), position);
            String path = QueryKeys.PART_PATH + key + "." + position;
            parts.put(path, NodeReader.location(pointer, item));
            reader.read(item, pointer, path, key + " at position " + position).ifPresent(result::add);
        }
        return result;
    }

    /** Reads one item of a list: the node, its JSON Pointer, the path of its problems, and what it is called. */
    @FunctionalInterface
    private interface PartReader<T> {
        Optional<T> read(Node node, String pointer, String path, String what);
    }

    // ------------------------------------------------------------------ steps

    private List<QueryStep> steps(Entry entry) {
        return list(entry, "steps", "a list of steps", this::step);
    }

    /** Reads one step: a mapping with exactly one key, which names its kind. */
    private Optional<QueryStep> step(Node node, String pointer, String path, String what) {
        Optional<Entry> kind = single(node, pointer, path, "the " + what, QueryKeys.STEP_KINDS, "a step");
        if (kind.isEmpty()) {
            return Optional.empty();
        }
        return switch (kind.get().key()) {
            case "where" -> condition(kind.get().value(), kind.get().pointer(), path, "the condition of the " + what, 0)
                    .map(QueryStep.Filter::new);
            case "follow" -> follow(kind.get(), path, what, false);
            case "optionalFollow" -> follow(kind.get(), path, what, true);
            default -> traverse(kind.get(), path, what);
        };
    }

    private Optional<QueryStep> follow(Entry kind, String path, String what, boolean optional) {
        Optional<Map<String, Entry>> properties = nodes.properties(kind.value(), kind.pointer(), path,
                "the " + kind.key() + " of the " + what, QueryKeys.FOLLOW, QueryKeys.FOLLOW_REQUIRED);
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        return Optional.of(new QueryStep.Follow(
                nodes.string(p.get("from"), path, "value of 'from'").orElse(""),
                nodes.string(p.get("name"), path, "value of 'name'").orElse(""),
                nodes.string(p.get("as"), path, "value of 'as'").orElse(""),
                nodes.string(p.get("relationship"), path, "value of 'relationship'"), optional));
    }

    private Optional<QueryStep> traverse(Entry kind, String path, String what) {
        Optional<Map<String, Entry>> properties = nodes.properties(kind.value(), kind.pointer(), path,
                "the traverse of the " + what, QueryKeys.TRAVERSE, QueryKeys.TRAVERSE_REQUIRED);
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        OptionalInt min = nodes.integer(p.get("minDepth"), path);
        OptionalInt max = nodes.integer(p.get("maxDepth"), path);
        if (min.isPresent() && min.getAsInt() < 0) {
            wholeNumberOfAtLeast0(p.get("minDepth").value(), p.get("minDepth").pointer(), path,
                    "The minimum depth of a traversal");
            min = OptionalInt.empty();
        }
        int minimum = min.orElse(0);
        if (max.isPresent() && max.getAsInt() < minimum) {
            Entry entry = p.get("maxDepth");
            String found = NodeReader.describe(entry.value());
            nodes.add(ProblemCode.INVALID_VALUE, path, "The maximum depth of a traversal is at least its minimum depth "
                    + minimum + ", but it is " + found + ".", "a whole number of at least " + minimum, found, null,
                    NodeReader.location(entry.pointer(), entry.value()));
            max = OptionalInt.empty();
        }
        return Optional.of(new QueryStep.Traverse(
                nodes.string(p.get("from"), path, "value of 'from'").orElse(""),
                nodes.string(p.get("name"), path, "value of 'name'").orElse(""),
                nodes.string(p.get("as"), path, "value of 'as'").orElse(""),
                nodes.string(p.get("relationship"), path, "value of 'relationship'"),
                nodes.string(p.get("depth"), path, "value of 'depth'"), minimum, max.orElse(minimum)));
    }

    // ------------------------------------------------------------------ conditions

    private Optional<Condition> condition(Node node, String pointer, String path, String what, int depth) {
        if (depth > MAX_NESTING) {
            String found = "conditions nested more than " + MAX_NESTING + " deep";
            nodes.add(ProblemCode.INVALID_VALUE, path, "Conditions nest at most " + MAX_NESTING + " deep.",
                    "fewer levels of and, or and not", found, "Flatten the conditions.",
                    NodeReader.location(pointer, node));
            return Optional.empty();
        }
        Optional<Entry> kind = single(node, pointer, path, what, QueryKeys.CONDITION_KINDS, "a condition");
        if (kind.isEmpty()) {
            return Optional.empty();
        }
        Entry entry = kind.get();
        return switch (entry.key()) {
            case "compare" -> compare(entry, path);
            case "in" -> in(entry, path);
            case "hasValue" -> pair(entry, path, QueryKeys.HAS_VALUE, "attribute", "of")
                    .map(names -> Condition.hasValue(names.get(1), names.get(0)));
            case "hasLink" -> pair(entry, path, QueryKeys.HAS_LINK, "of", "name")
                    .map(names -> Condition.hasLink(names.get(0), names.get(1)));
            case "isA" -> pair(entry, path, QueryKeys.IS_A, "of", "type")
                    .map(names -> Condition.isA(names.get(0), names.get(1)));
            case "and" -> conditions(entry, path, depth).map(Condition::and);
            case "or" -> conditions(entry, path, depth).map(Condition::or);
            default -> condition(entry.value(), entry.pointer(), path, "the condition of the not", depth + 1)
                    .map(Condition::not);
        };
    }

    private Optional<Condition> compare(Entry entry, String path) {
        Optional<Map<String, Entry>> properties = nodes.properties(entry.value(), entry.pointer(), path,
                "the compare", QueryKeys.COMPARE, QueryKeys.COMPARE);
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        Optional<Operand> left = operand(p.get("left"), path, "the left operand of the compare");
        Optional<Operand> right = operand(p.get("right"), path, "the right operand of the compare");
        Optional<String> op = p.containsKey("op") ? nodes.choice(p.get("op"), path, QueryKeys.OPERATORS)
                : Optional.empty();
        if (left.isEmpty() || right.isEmpty() || op.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Condition.compare(left.get(), Comparison.valueOf(op.get().toUpperCase(Locale.ROOT)),
                right.get()));
    }

    private Optional<Condition> in(Entry entry, String path) {
        Optional<Map<String, Entry>> properties = nodes.properties(entry.value(), entry.pointer(), path, "the in",
                QueryKeys.IN, QueryKeys.IN);
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        Optional<Operand> operand = operand(p.get("operand"), path, "the operand of the in");
        List<Value> values = new ArrayList<>();
        Entry list = p.get("values");
        if (list != null) {
            if (list.value() instanceof SequenceNode sequence) {
                for (int i = 0; i < sequence.getValue().size(); i++) {
                    nodes.literal(sequence.getValue().get(i), Pointers.child(list.pointer(), i), null, path,
                            "item " + (i + 1) + " of 'values'").ifPresent(values::add);
                }
            } else {
                nodes.invalidKind(list.value(), list.pointer(), path, "value of 'values'", "a list of values");
            }
        }
        return operand.map(found -> Condition.in(found, values));
    }

    /** Reads a mapping of two required strings, in the order of the keys given, and returns them in that order. */
    private Optional<List<String>> pair(Entry entry, String path, List<String> keys, String first, String second) {
        Optional<Map<String, Entry>> properties = nodes.properties(entry.value(), entry.pointer(), path,
                "the " + entry.key(), keys, keys);
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        Optional<String> one = nodes.string(p.get(first), path, "value of '" + first + "'");
        Optional<String> two = nodes.string(p.get(second), path, "value of '" + second + "'");
        return one.isPresent() && two.isPresent() ? Optional.of(List.of(one.get(), two.get())) : Optional.empty();
    }

    private Optional<List<Condition>> conditions(Entry entry, String path, int depth) {
        if (!(entry.value() instanceof SequenceNode sequence)) {
            nodes.invalidKind(entry.value(), entry.pointer(), path, "value of '" + entry.key() + "'",
                    "a list of conditions");
            return Optional.empty();
        }
        List<Condition> result = new ArrayList<>();
        boolean valid = true;
        for (int i = 0; i < sequence.getValue().size(); i++) {
            Optional<Condition> condition = condition(sequence.getValue().get(i), Pointers.child(entry.pointer(), i),
                    path, "condition " + (i + 1) + " of the " + entry.key(), depth + 1);
            condition.ifPresent(result::add);
            valid &= condition.isPresent();
        }
        return valid ? Optional.of(result) : Optional.empty();
    }

    // ------------------------------------------------------------------ operands

    /** Reads an operand: {@code {attribute, of}}, {@code {id}}, {@code {type}} or {@code {value}}. */
    private Optional<Operand> operand(Entry entry, String path, String what) {
        if (entry == null) {
            return Optional.empty();
        }
        int before = nodes.problemCount();
        Optional<Map<String, Entry>> properties = nodes.properties(entry.value(), entry.pointer(), path, what,
                QueryKeys.OPERAND, List.of());
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        Optional<Entry> kind = kindOf(p, QueryKeys.OPERAND_KINDS, entry.value(), entry.pointer(), path, what,
                "an operand", before);
        if (kind.isEmpty()) {
            return Optional.empty();
        }
        Entry chosen = kind.get();
        return switch (chosen.key()) {
            case "attribute" -> {
                Optional<String> attribute = nodes.string(chosen, path, "value of 'attribute'");
                Optional<String> binding = nodes.string(p.get("of"), path, "value of 'of'");
                yield attribute.isPresent() && binding.isPresent()
                        ? Optional.of(Operand.attribute(binding.get(), attribute.get())) : Optional.empty();
            }
            case "id" -> nodes.string(chosen, path, "value of 'id'").map(Operand::id);
            case "type" -> nodes.string(chosen, path, "value of 'type'").map(Operand::type);
            default -> nodes.literal(chosen.value(), chosen.pointer(), null, path, "value of 'value'")
                    .map(Operand::literal);
        };
    }

    // ------------------------------------------------------------------ grouping, columns and order

    private Optional<GroupKey> groupKey(Node node, String pointer, String path, String what) {
        Optional<Map<String, Entry>> properties = nodes.properties(node, pointer, path, "the " + what,
                QueryKeys.GROUP_KEY, QueryKeys.GROUP_KEY_REQUIRED);
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        Optional<String> binding = nodes.string(p.get("of"), path, "value of 'of'");
        Optional<String> attribute = nodes.string(p.get("attribute"), path, "value of 'attribute'");
        Optional<String> name = nodes.string(p.get("as"), path, "value of 'as'");
        if (binding.isEmpty()) {
            return Optional.empty();
        }
        if (attribute.isEmpty()) {
            return Optional.of(name.map(as -> GroupKey.binding(binding.get(), as))
                    .orElseGet(() -> GroupKey.binding(binding.get())));
        }
        return Optional.of(name.map(as -> GroupKey.attribute(binding.get(), attribute.get(), as))
                .orElseGet(() -> GroupKey.attribute(binding.get(), attribute.get())));
    }

    private Optional<Aggregate> aggregate(Node node, String pointer, String path, String what) {
        Optional<Map<String, Entry>> properties = nodes.properties(node, pointer, path, "the " + what,
                QueryKeys.AGGREGATE, QueryKeys.AGGREGATE_REQUIRED);
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        Optional<String> function = p.containsKey("function")
                ? nodes.choice(p.get("function"), path, QueryKeys.FUNCTIONS) : Optional.empty();
        Optional<String> name = nodes.string(p.get("as"), path, "value of 'as'");
        Optional<String> attribute = nodes.string(p.get("attribute"), path, "value of 'attribute'");
        Optional<String> binding = nodes.string(p.get("of"), path, "value of 'of'");
        if (attribute.isPresent() != binding.isPresent()) {
            Entry present = attribute.isPresent() ? p.get("attribute") : p.get("of");
            String missing = attribute.isPresent() ? "of" : "attribute";
            nodes.add(ProblemCode.MISSING_KEY, path, "The key '" + missing + "' is missing in the " + what
                    + ": the attribute to aggregate needs its binding and its name.", "the key '" + missing + "'",
                    null, "Add the key '" + missing + "'.", NodeReader.location(present.pointer(), present.keyNode()));
            return Optional.empty();
        }
        if (function.isEmpty() || name.isEmpty()) {
            return Optional.empty();
        }
        Optional<Operand.AttributeOf> of = attribute.isPresent()
                ? Optional.of(Operand.attribute(binding.get(), attribute.get())) : Optional.empty();
        return Optional.of(new Aggregate(Aggregate.Function.valueOf(function.get().toUpperCase(Locale.ROOT)), of,
                name.get()));
    }

    private Optional<Column> column(Node node, String pointer, String path, String what) {
        return columnOf(node, pointer, path, "the " + what, false).map(Parts::column);
    }

    private Optional<Order> order(Node node, String pointer, String path, String what) {
        return columnOf(node, pointer, path, "the " + what, true)
                .map(parts -> new Order(parts.column(), parts.descending()));
    }

    /** A column, and for an order whether it is descending. */
    private record Parts(Column column, boolean descending) {
    }

    /**
     * Reads a column or an order: a mapping with one of the keys {@code binding}, {@code attribute} (with {@code of}),
     * {@code depth}, {@code key} and {@code aggregate}, and {@code as} for a column or {@code descending} for an order.
     * A column without {@code as} is named like the factories of {@link Column} name it.
     */
    private Optional<Parts> columnOf(Node node, String pointer, String path, String what, boolean order) {
        int before = nodes.problemCount();
        Optional<Map<String, Entry>> properties = nodes.properties(node, pointer, path, what,
                order ? QueryKeys.ORDER : QueryKeys.COLUMN, List.of());
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        Optional<Entry> kind = kindOf(p, QueryKeys.COLUMN_KINDS, node, pointer, path, what,
                order ? "an order" : "a column", before);
        boolean descending = order && nodes.bool(p.get("descending"), path);
        Optional<String> name = order ? Optional.empty() : nodes.string(p.get("as"), path, "value of 'as'");
        if (kind.isEmpty()) {
            return Optional.empty();
        }
        Entry chosen = kind.get();
        Optional<String> value = nodes.string(chosen, path, "value of '" + chosen.key() + "'");
        if (value.isEmpty()) {
            return Optional.empty();
        }
        String text = value.get();
        Column column = switch (chosen.key()) {
            case "binding" -> name.map(as -> Column.binding(text, as)).orElseGet(() -> Column.binding(text));
            case "depth" -> name.map(as -> Column.depth(text, as)).orElseGet(() -> Column.depth(text));
            case "key" -> name.map(as -> Column.key(text, as)).orElseGet(() -> Column.key(text));
            case "aggregate" -> name.map(as -> Column.aggregate(text, as)).orElseGet(() -> Column.aggregate(text));
            default -> {
                Optional<String> binding = nodes.string(p.get("of"), path, "value of 'of'");
                yield binding.isEmpty() ? null : name.map(as -> Column.attribute(binding.get(), text, as))
                        .orElseGet(() -> Column.attribute(binding.get(), text));
            }
        };
        return column == null ? Optional.empty() : Optional.of(new Parts(column, descending));
    }

    // ------------------------------------------------------------------ one key names the kind

    /**
     * Reads a mapping that has exactly one key, which names its kind: a step or a condition. Unknown keys are reported
     * where they are, with the closest known key as a hint; a mapping without a key, or with several, is a problem.
     *
     * @return the member that names the kind; empty if there is none, or the mapping is no mapping
     */
    private Optional<Entry> single(Node node, String pointer, String path, String what, List<String> kinds,
            String thing) {
        int before = nodes.problemCount();
        Optional<Map<String, Entry>> properties = nodes.properties(node, pointer, path, what, kinds, List.of());
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        DocumentLocation at = NodeReader.location(pointer, node);
        if (p.isEmpty()) {
            if (nodes.problemCount() == before) {
                nodes.add(ProblemCode.MISSING_KEY, path, "The " + what + " has no key naming what it is.",
                        "one of " + String.join(", ", kinds), null, "Add one of the keys " + String.join(", ", kinds)
                                + ".", at);
            }
            return Optional.empty();
        }
        if (p.size() > 1) {
            String found = String.join(", ", p.keySet());
            nodes.add(ProblemCode.INVALID_VALUE, path, capitalized(thing) + " has one key naming what it is, but the "
                    + what + " has " + found + ".", "exactly one of " + String.join(", ", kinds), found,
                    "Write one " + thing.replaceFirst("^an? ", "") + " for each key.", at);
        }
        return Optional.of(p.values().iterator().next());
    }

    /**
     * Finds the member that names the kind of an operand, a column or an order, which has exactly one of the keys of
     * its kinds, and checks that {@code of} goes with {@code attribute} and with nothing else.
     */
    private Optional<Entry> kindOf(Map<String, Entry> p, List<String> kinds, Node node, String pointer, String path,
            String what, String thing, int problemsBefore) {
        List<String> present = kinds.stream().filter(p::containsKey).toList();
        DocumentLocation at = NodeReader.location(pointer, node);
        if (present.isEmpty()) {
            if (nodes.problemCount() == problemsBefore) {
                nodes.add(ProblemCode.MISSING_KEY, path, "The " + what + " has no key naming what it is.",
                        "one of " + String.join(", ", kinds), null, "Add one of the keys " + String.join(", ", kinds)
                                + ".", at);
            }
            return Optional.empty();
        }
        if (present.size() > 1) {
            String found = String.join(", ", present);
            nodes.add(ProblemCode.INVALID_VALUE, path, capitalized(thing) + " has one key naming what it is, but the "
                    + what + " has " + found + ".", "exactly one of " + String.join(", ", kinds), found,
                    "Keep the key you mean, and remove the others.", at);
            return Optional.empty();
        }
        String kind = present.getFirst();
        if (kind.equals("attribute") && !p.containsKey("of")) {
            nodes.add(ProblemCode.MISSING_KEY, path, "The key 'of' is missing in the " + what
                    + ": an attribute needs the binding it belongs to.", "the key 'of'", null,
                    "Add the key 'of', with the name of the binding.", NodeReader.location(p.get("attribute").pointer(),
                            p.get("attribute").keyNode()));
            return Optional.empty();
        }
        if (!kind.equals("attribute") && p.containsKey("of")) {
            Entry of = p.get("of");
            nodes.add(ProblemCode.INVALID_VALUE, path, "The key 'of' goes with 'attribute' only, but the " + what
                    + " has '" + kind + "'.", "the key 'of' only together with 'attribute'", "'of' with '" + kind + "'",
                    "Remove the key 'of'.", NodeReader.location(of.pointer(), of.keyNode()));
            return Optional.empty();
        }
        return Optional.of(p.get(kind));
    }

    private static String capitalized(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
