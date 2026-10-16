package io.github.enricoag1982.cairn.query;

import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * One row of a query's result (ADR-0023, capability {@code queries}): a {@link Cell} for each column, in the order of the
 * columns.
 *
 * <p>The accessors by column name fail with an {@link IllegalArgumentException} if the row has no column of that name,
 * or if the column holds another kind of cell than the accessor reads, naming the column and both kinds; an empty cell
 * is an empty {@link Optional}.
 *
 * <p>A value is read as its Java type with {@link #string}, {@link #bool}, {@link #intValue}, {@link #longValue},
 * {@link #decimal}, {@link #date} and {@link #instant}, which fail for a value of another type, or as a
 * {@link BigDecimal} with {@link #number}, whatever its numeric type. A bound entity is read as a generated type with
 * {@link #entity(String, Function)}.
 *
 * @param cells the cells, keyed by the names of the columns and in their order
 */
public record Row(Map<String, Cell> cells) {

    /**
     * Rejects null components and copies the cells, keeping their order.
     */
    public Row {
        Objects.requireNonNull(cells, "cells");
        Map<String, Cell> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Cell> entry : cells.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "cells key"),
                    Objects.requireNonNull(entry.getValue(), "cells value"));
        }
        cells = Collections.unmodifiableMap(copy);
    }

    /**
     * Reads a cell.
     *
     * @param column the name of the column
     * @return the cell
     * @throws IllegalArgumentException if the row has no such column
     */
    public Cell cell(String column) {
        Objects.requireNonNull(column, "column");
        Cell cell = cells.get(column);
        if (cell == null) {
            throw new IllegalArgumentException("The row has no column '" + column + "'; its columns are "
                    + cells.keySet() + ".");
        }
        return cell;
    }

    /**
     * Reads the state of a bound entity.
     *
     * @param column the name of the column
     * @return the state; empty if the binding is empty
     * @throws IllegalArgumentException if the row has no such column, or it holds no entity
     */
    public Optional<EntityState> entity(String column) {
        return switch (cell(column)) {
            case Cell.EntityCell cell -> Optional.of(cell.state());
            case Cell.Empty ignored -> Optional.empty();
            default -> throw wrongKind(column, "an entity");
        };
    }

    /**
     * Reads the state of a bound relationship.
     *
     * @param column the name of the column
     * @return the state; empty if the binding is empty
     * @throws IllegalArgumentException if the row has no such column, or it holds no relationship
     */
    public Optional<RelationshipState> relationship(String column) {
        return switch (cell(column)) {
            case Cell.RelationshipCell cell -> Optional.of(cell.state());
            case Cell.Empty ignored -> Optional.empty();
            default -> throw wrongKind(column, "a relationship");
        };
    }

    /**
     * Reads a value.
     *
     * @param column the name of the column
     * @return the value; empty if there is none
     * @throws IllegalArgumentException if the row has no such column, or it holds an entity or a relationship
     */
    public Optional<Value> value(String column) {
        return switch (cell(column)) {
            case Cell.ValueCell cell -> Optional.of(cell.value());
            case Cell.Empty ignored -> Optional.empty();
            default -> throw wrongKind(column, "a value");
        };
    }

    /**
     * Reads a string value.
     *
     * @param column the name of the column
     * @return the string; empty if there is no value
     * @throws IllegalArgumentException if the row has no such column, or it holds anything but a string
     */
    public Optional<String> string(String column) {
        return switch (cell(column)) {
            case Cell.ValueCell(Value.StringValue value) -> Optional.of(value.value());
            case Cell.Empty ignored -> Optional.empty();
            default -> throw wrongKind(column, "a string");
        };
    }

    /**
     * Reads a boolean value.
     *
     * @param column the name of the column
     * @return the boolean; empty if there is no value
     * @throws IllegalArgumentException if the row has no such column, or it holds anything but a boolean
     */
    public Optional<Boolean> bool(String column) {
        return switch (cell(column)) {
            case Cell.ValueCell(Value.BooleanValue value) -> Optional.of(value.value());
            case Cell.Empty ignored -> Optional.empty();
            default -> throw wrongKind(column, "a boolean");
        };
    }

    /**
     * Reads an int value, such as the depth of a traversal.
     *
     * @param column the name of the column
     * @return the int; empty if there is no value
     * @throws IllegalArgumentException if the row has no such column, or it holds anything but an int
     */
    public Optional<Integer> intValue(String column) {
        return switch (cell(column)) {
            case Cell.ValueCell(Value.IntValue value) -> Optional.of(value.value());
            case Cell.Empty ignored -> Optional.empty();
            default -> throw wrongKind(column, "an int");
        };
    }

    /**
     * Reads a long value, such as a count.
     *
     * @param column the name of the column
     * @return the long; empty if there is no value
     * @throws IllegalArgumentException if the row has no such column, or it holds anything but a long
     */
    public Optional<Long> longValue(String column) {
        return switch (cell(column)) {
            case Cell.ValueCell(Value.LongValue value) -> Optional.of(value.value());
            case Cell.Empty ignored -> Optional.empty();
            default -> throw wrongKind(column, "a long");
        };
    }

    /**
     * Reads a decimal value, such as a sum or an average.
     *
     * @param column the name of the column
     * @return the decimal; empty if there is no value
     * @throws IllegalArgumentException if the row has no such column, or it holds anything but a decimal
     */
    public Optional<BigDecimal> decimal(String column) {
        return switch (cell(column)) {
            case Cell.ValueCell(Value.DecimalValue value) -> Optional.of(value.value());
            case Cell.Empty ignored -> Optional.empty();
            default -> throw wrongKind(column, "a decimal");
        };
    }

    /**
     * Reads a date value.
     *
     * @param column the name of the column
     * @return the date; empty if there is no value
     * @throws IllegalArgumentException if the row has no such column, or it holds anything but a date
     */
    public Optional<LocalDate> date(String column) {
        return switch (cell(column)) {
            case Cell.ValueCell(Value.DateValue value) -> Optional.of(value.value());
            case Cell.Empty ignored -> Optional.empty();
            default -> throw wrongKind(column, "a date");
        };
    }

    /**
     * Reads an instant value.
     *
     * @param column the name of the column
     * @return the instant; empty if there is no value
     * @throws IllegalArgumentException if the row has no such column, or it holds anything but an instant
     */
    public Optional<Instant> instant(String column) {
        return switch (cell(column)) {
            case Cell.ValueCell(Value.InstantValue value) -> Optional.of(value.value());
            case Cell.Empty ignored -> Optional.empty();
            default -> throw wrongKind(column, "an instant");
        };
    }

    /**
     * Reads a numeric value of any numeric type as a decimal: an int, a long or a decimal.
     *
     * @param column the name of the column
     * @return the number, which is the exact value of an int or a long; empty if there is no value
     * @throws IllegalArgumentException if the row has no such column, or it holds anything but an int, a long or a
     *                                  decimal
     */
    public Optional<BigDecimal> number(String column) {
        return switch (cell(column)) {
            case Cell.ValueCell(Value.IntValue value) -> Optional.of(BigDecimal.valueOf(value.value()));
            case Cell.ValueCell(Value.LongValue value) -> Optional.of(BigDecimal.valueOf(value.value()));
            case Cell.ValueCell(Value.DecimalValue value) -> Optional.of(value.value());
            case Cell.Empty ignored -> Optional.empty();
            default -> throw wrongKind(column, "a number (an int, a long or a decimal)");
        };
    }

    /**
     * Reads a bound entity as another type, such as a generated type.
     *
     * <p>For example, {@code row.entity("manager", Person::from)} reads the binding {@code manager} as a {@code Person}.
     *
     * @param column the name of the column
     * @param as     how to read the state of the entity as the type, such as the {@code from} method of a generated
     *               type
     * @param <T>    the type
     * @return the entity as the type; empty if the binding is empty
     * @throws IllegalArgumentException if the row has no such column, or it holds no entity
     */
    public <T> Optional<T> entity(String column, Function<EntityState, T> as) {
        Objects.requireNonNull(as, "as");
        return entity(column).map(as);
    }

    /** The error for a column that holds another kind of cell than the one wanted: the column, both kinds. */
    private IllegalArgumentException wrongKind(String column, String wanted) {
        Cell found = cells.get(column);
        String holds = found instanceof Cell.ValueCell(Value value)
                ? "ValueCell with " + article(value.valueType().name().toLowerCase(Locale.ROOT)) + " value"
                : found.getClass().getSimpleName();
        return new IllegalArgumentException("The column '" + column + "' holds " + holds + ", not " + wanted + ".");
    }

    private static String article(String word) {
        return (word.startsWith("in") ? "an " : "a ") + word;
    }
}
