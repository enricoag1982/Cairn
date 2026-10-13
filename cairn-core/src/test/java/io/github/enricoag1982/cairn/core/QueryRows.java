package io.github.enricoag1982.cairn.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.query.Row;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.util.List;

/** Shortcuts to read the results of queries in tests. */
final class QueryRows {

    private QueryRows() {
    }

    /** The rows of a result that must have rows. */
    static QueryResult.Rows rows(QueryResult result) {
        assertThat(result).as("query result").isInstanceOf(QueryResult.Rows.class);
        return (QueryResult.Rows) result;
    }

    /** The problems of a result that must be invalid. */
    static List<Problem> problems(QueryResult result) {
        assertThat(result).as("query result").isInstanceOf(QueryResult.Invalid.class);
        return ((QueryResult.Invalid) result).problems();
    }

    /** The only problem of a result that must be invalid with one problem. */
    static Problem onlyProblem(QueryResult result) {
        assertThat(problems(result)).hasSize(1);
        return problems(result).getFirst();
    }

    /** The text of the attribute {@code name} of the entity in a column; {@code null} if the binding is empty. */
    static String name(Row row, String column) {
        return row.entity(column).map(state -> text(state, "name")).orElse(null);
    }

    /** The names of the entities in a column, in the order of the rows; {@code null} for an empty binding. */
    static List<String> names(QueryResult.Rows rows, String column) {
        return rows.rows().stream().map(row -> name(row, column)).toList();
    }

    /** The text of a string attribute of an entity. */
    static String text(EntityState state, String attribute) {
        return ((Value.StringValue) state.values().get(attribute)).value();
    }

    /** The text of an attribute of the entity in a column of a row. */
    static String text(Row row, String column, String attribute) {
        return row.entity(column).map(state -> text(state, attribute)).orElse(null);
    }

    /** The texts of an attribute of the entities in a column, in the order of the rows; {@code null} for none. */
    static List<String> texts(QueryResult.Rows rows, String column, String attribute) {
        return rows.rows().stream().map(row -> text(row, column, attribute)).toList();
    }

    /** The decimal in a column. */
    static BigDecimal decimal(Row row, String column) {
        return ((Value.DecimalValue) row.value(column).orElseThrow()).value();
    }

    /** The value in a column of every row, or {@code null} where a row has none. */
    static List<Value> values(QueryResult.Rows rows, String column) {
        return rows.rows().stream().map(row -> row.value(column).orElse(null)).toList();
    }
}
