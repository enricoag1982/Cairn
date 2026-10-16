package io.github.enricoag1982.cairn.documents;

import java.util.List;

/**
 * The keys of queries written as JSON or YAML, of format version 1, per position (design D4 of
 * {@code add-queries-and-aggregation}). The keys mirror the records of {@code io.github.enricoag1982.cairn.query}. The
 * reader accepts exactly these keys, and the published JSON Schema {@code query-1.schema.json} declares the same ones.
 *
 * <p>A step, a condition, an operand, a column and an order are mappings in which some keys name what kind of thing they
 * are: a step has exactly one of {@link #STEP_KINDS}, a condition one of {@link #CONDITION_KINDS}, an operand one of
 * {@link #OPERAND_KINDS}, and a column or an order one of {@link #COLUMN_KINDS}.
 */
final class QueryKeys {

    /** The path of problems about the query as a whole, and of those that no part of it is concerned in. */
    static final String QUERY_PATH = "@query";

    /** The start of the path of problems about a part of the query: {@code @query.} and the name of the part. */
    static final String PART_PATH = QUERY_PATH + ".";

    /** The keys of the query; {@value Keys#SCHEMA} names the published JSON Schema for editors and is ignored. */
    static final List<String> QUERY = List.of(
            "cairn", "from", "steps", "groupBy", "aggregates", "columns", "order", "limit", Keys.SCHEMA);
    /** The keys the query must have. */
    static final List<String> QUERY_REQUIRED = List.of("from");

    /**
     * The keys of {@code from}: the entity type, the name each entity is bound to, whether it is the exact type, and the
     * IDs of the entities to start from, if it starts from some.
     */
    static final List<String> FROM = List.of("type", "as", "exact", "ids");
    /** The keys {@code from} must have. */
    static final List<String> FROM_REQUIRED = List.of("type", "as");

    /** The keys that name the kind of a step: a step has exactly one of them. */
    static final List<String> STEP_KINDS = List.of("where", "follow", "optionalFollow", "traverse");

    /** The keys of a {@code follow} and an {@code optionalFollow}. */
    static final List<String> FOLLOW = List.of("from", "name", "as", "relationship");
    /** The keys a {@code follow} and an {@code optionalFollow} must have. */
    static final List<String> FOLLOW_REQUIRED = List.of("from", "name", "as");

    /** The keys of a {@code traverse}. */
    static final List<String> TRAVERSE = List.of("from", "name", "as", "minDepth", "maxDepth", "relationship", "depth",
            "along");
    /** The keys a {@code traverse} must have: without {@code maxDepth}, it traverses to the end. */
    static final List<String> TRAVERSE_REQUIRED = List.of("from", "name", "as");

    /** The keys that name the kind of a condition: a condition has exactly one of them. */
    static final List<String> CONDITION_KINDS = List.of("compare", "in", "hasValue", "hasLink", "isA", "and", "or", "not");

    /** The keys of a {@code compare}; all are required. */
    static final List<String> COMPARE = List.of("left", "op", "right");

    /** The comparisons that {@code op} names, in the order of the enum. */
    static final List<String> OPERATORS = List.of("eq", "ne", "lt", "le", "gt", "ge");

    /** The keys of an {@code in}; both are required. */
    static final List<String> IN = List.of("operand", "values");

    /** The keys of a {@code hasValue}; both are required. */
    static final List<String> HAS_VALUE = List.of("attribute", "of");

    /**
     * The keys of a {@code hasLink}. {@code along}, a condition on the relationship of a link, needs
     * {@code relationship}, the name it refers to the relationship by.
     */
    static final List<String> HAS_LINK = List.of("of", "name", "relationship", "along");
    /** The keys a {@code hasLink} must have. */
    static final List<String> HAS_LINK_REQUIRED = List.of("of", "name");

    /** The keys of an {@code isA}; both are required. */
    static final List<String> IS_A = List.of("of", "type");

    /**
     * The keys of an operand. An operand has exactly one of {@link #OPERAND_KINDS}, and {@code of}, the binding, goes
     * with {@code attribute} and with nothing else.
     */
    static final List<String> OPERAND = List.of("attribute", "of", "id", "type", "value");
    /** The keys that name the kind of an operand. */
    static final List<String> OPERAND_KINDS = List.of("attribute", "id", "type", "value");

    /** The keys of a group key. */
    static final List<String> GROUP_KEY = List.of("of", "attribute", "as");
    /** The keys a group key must have. */
    static final List<String> GROUP_KEY_REQUIRED = List.of("of");

    /** The keys of an aggregate. */
    static final List<String> AGGREGATE = List.of("function", "attribute", "of", "as");
    /** The keys an aggregate must have. */
    static final List<String> AGGREGATE_REQUIRED = List.of("function", "as");

    /** The functions that {@code function} names, in the order of the enum. */
    static final List<String> FUNCTIONS = List.of("count", "sum", "min", "max", "avg");

    /**
     * The keys of a column. A column has exactly one of {@link #COLUMN_KINDS}; {@code of}, the binding, goes with
     * {@code attribute} and with nothing else; {@code as} names the column.
     */
    static final List<String> COLUMN = List.of("binding", "attribute", "of", "depth", "key", "aggregate", "as");
    /** The keys that name the kind of a column. */
    static final List<String> COLUMN_KINDS = List.of("binding", "attribute", "depth", "key", "aggregate");

    /** The keys of an order: those of a column, but its name, and whether it is descending. */
    static final List<String> ORDER = List.of("binding", "attribute", "of", "depth", "key", "aggregate", "descending");

    private QueryKeys() {
    }
}
