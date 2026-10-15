package io.github.enricoag1982.cairn.query;

import io.github.enricoag1982.cairn.schema.Value;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a filter step tests of each row (ADR-0023, capability {@code queries}): a comparison, a test for a value, a test
 * for a link or for a type, or conditions combined with and, or and not.
 *
 * <p>A condition is two-valued: a comparison with a value that is missing, or of a binding that is empty, is false, so
 * that {@link HasValue} is what tells missing values apart, and {@link Not} turns a false comparison true.
 */
public sealed interface Condition {

    /**
     * Compares two operands. Numbers compare by value whatever their value types, dates, instants and strings in their
     * order (strings by code point), and booleans for equality only.
     *
     * @param left       the left operand
     * @param comparison how to compare
     * @param right      the right operand
     */
    record Compare(Operand left, Comparison comparison, Operand right) implements Condition {

        /**
         * Rejects null components.
         */
        public Compare {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(comparison, "comparison");
            Objects.requireNonNull(right, "right");
        }
    }

    /**
     * Holds if the operand equals one of the values.
     *
     * @param operand the operand to test
     * @param values  the values to compare with; an operand that has no value equals none of them
     */
    record In(Operand operand, List<Value> values) implements Condition {

        /**
         * Rejects null components and copies the values.
         */
        public In {
            Objects.requireNonNull(operand, "operand");
            values = List.copyOf(values);
        }
    }

    /**
     * Holds if an attribute of a binding has a value.
     *
     * @param binding   the name of the binding
     * @param attribute the name or an alias of the attribute
     */
    record HasValue(String binding, String attribute) implements Condition {

        /**
         * Rejects null components.
         */
        public HasValue {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(attribute, "attribute");
        }
    }

    /**
     * Holds if a bound entity has a link by a relationship name or inverse name, as navigation finds it at the view's
     * effective time, and with {@code along}, if it has at least one such link whose relationship meets a condition.
     *
     * <p>The condition reads the relationship under the name {@code relationship}, which belongs to this condition
     * alone: it is not a binding of the query, so it may be a name that the query binds already, which it hides inside
     * {@code along} only. {@code along} can refer to that name and to literals, and to nothing else; it needs the
     * relationship to be named if it refers to the relationship.
     *
     * @param binding      the name of the binding of an entity
     * @param name         the name of a relationship type or the inverse name of one
     * @param relationship the name that {@code along} gives the relationship of a link, if there is a condition
     * @param along        the condition that the relationship of a link must meet, if the link counts only then
     */
    record HasLink(String binding, String name, Optional<String> relationship, Optional<Condition> along)
            implements Condition {

        /**
         * Rejects null components.
         */
        public HasLink {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(relationship, "relationship");
            Objects.requireNonNull(along, "along");
        }

        /**
         * A test for any link by the name.
         *
         * @param binding the name of the binding of an entity
         * @param name    the name of a relationship type or the inverse name of one
         */
        public HasLink(String binding, String name) {
            this(binding, name, Optional.empty(), Optional.empty());
        }
    }

    /**
     * Holds if a bound entity is of an entity type or of one of its subtypes.
     *
     * @param binding    the name of the binding of an entity
     * @param entityType the name or an alias of an entity type
     */
    record IsA(String binding, String entityType) implements Condition {

        /**
         * Rejects null components.
         */
        public IsA {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(entityType, "entityType");
        }
    }

    /**
     * Holds if every condition holds; with no conditions, it holds.
     *
     * @param conditions the conditions
     */
    record And(List<Condition> conditions) implements Condition {

        /**
         * Copies the conditions, rejecting nulls.
         */
        public And {
            conditions = List.copyOf(conditions);
        }
    }

    /**
     * Holds if at least one condition holds; with no conditions, it does not hold.
     *
     * @param conditions the conditions
     */
    record Or(List<Condition> conditions) implements Condition {

        /**
         * Copies the conditions, rejecting nulls.
         */
        public Or {
            conditions = List.copyOf(conditions);
        }
    }

    /**
     * Holds if the condition does not hold.
     *
     * @param condition the condition
     */
    record Not(Condition condition) implements Condition {

        /**
         * Rejects a null condition.
         */
        public Not {
            Objects.requireNonNull(condition, "condition");
        }
    }

    /**
     * Compares two operands.
     *
     * @param left       the left operand
     * @param comparison how to compare
     * @param right      the right operand
     * @return the condition
     */
    static Condition compare(Operand left, Comparison comparison, Operand right) {
        return new Compare(left, comparison, right);
    }

    /**
     * Holds if two operands are equal.
     *
     * @param left  the left operand
     * @param right the right operand
     * @return the condition
     */
    static Condition eq(Operand left, Operand right) {
        return new Compare(left, Comparison.EQ, right);
    }

    /**
     * Holds if two operands are not equal.
     *
     * @param left  the left operand
     * @param right the right operand
     * @return the condition
     */
    static Condition ne(Operand left, Operand right) {
        return new Compare(left, Comparison.NE, right);
    }

    /**
     * Holds if the left operand is less than the right one.
     *
     * @param left  the left operand
     * @param right the right operand
     * @return the condition
     */
    static Condition lt(Operand left, Operand right) {
        return new Compare(left, Comparison.LT, right);
    }

    /**
     * Holds if the left operand is less than or equal to the right one.
     *
     * @param left  the left operand
     * @param right the right operand
     * @return the condition
     */
    static Condition le(Operand left, Operand right) {
        return new Compare(left, Comparison.LE, right);
    }

    /**
     * Holds if the left operand is greater than the right one.
     *
     * @param left  the left operand
     * @param right the right operand
     * @return the condition
     */
    static Condition gt(Operand left, Operand right) {
        return new Compare(left, Comparison.GT, right);
    }

    /**
     * Holds if the left operand is greater than or equal to the right one.
     *
     * @param left  the left operand
     * @param right the right operand
     * @return the condition
     */
    static Condition ge(Operand left, Operand right) {
        return new Compare(left, Comparison.GE, right);
    }

    /**
     * Holds if an operand equals one of some values.
     *
     * @param operand the operand
     * @param values  the values
     * @return the condition
     */
    static Condition in(Operand operand, List<Value> values) {
        return new In(operand, values);
    }

    /**
     * Holds if an operand equals one of some values.
     *
     * @param operand the operand
     * @param values  the values
     * @return the condition
     */
    static Condition in(Operand operand, Value... values) {
        return new In(operand, List.of(values));
    }

    /**
     * Holds if an attribute of a binding has a value.
     *
     * @param binding   the name of the binding
     * @param attribute the name or an alias of the attribute
     * @return the condition
     */
    static Condition hasValue(String binding, String attribute) {
        return new HasValue(binding, attribute);
    }

    /**
     * Holds if a bound entity has a link by a relationship name or inverse name.
     *
     * @param binding the name of the binding of an entity
     * @param name    the name of a relationship type or the inverse name of one
     * @return the condition
     */
    static Condition hasLink(String binding, String name) {
        return new HasLink(binding, name);
    }

    /**
     * Holds if a bound entity has a link by a relationship name or inverse name whose relationship meets a condition.
     *
     * @param binding      the name of the binding of an entity
     * @param name         the name of a relationship type or the inverse name of one
     * @param relationship the name that {@code along} refers to the relationship of a link by; a name of this condition
     *                     alone, which may be a name that the query binds too
     * @param along        the condition that the relationship of a link must meet; it can refer to the binding named by
     *                     {@code relationship} and to literals, and to nothing else
     * @return the condition
     */
    static Condition hasLink(String binding, String name, String relationship, Condition along) {
        return new HasLink(binding, name, Optional.of(Objects.requireNonNull(relationship, "relationship")),
                Optional.of(Objects.requireNonNull(along, "along")));
    }

    /**
     * Holds if a bound entity is of an entity type or of one of its subtypes.
     *
     * @param binding    the name of the binding of an entity
     * @param entityType the name or an alias of an entity type
     * @return the condition
     */
    static Condition isA(String binding, String entityType) {
        return new IsA(binding, entityType);
    }

    /**
     * Holds if every condition holds.
     *
     * @param conditions the conditions
     * @return the condition
     */
    static Condition and(Condition... conditions) {
        return new And(List.of(conditions));
    }

    /**
     * Holds if every condition holds.
     *
     * @param conditions the conditions
     * @return the condition
     */
    static Condition and(List<Condition> conditions) {
        return new And(conditions);
    }

    /**
     * Holds if at least one condition holds.
     *
     * @param conditions the conditions
     * @return the condition
     */
    static Condition or(Condition... conditions) {
        return new Or(List.of(conditions));
    }

    /**
     * Holds if at least one condition holds.
     *
     * @param conditions the conditions
     * @return the condition
     */
    static Condition or(List<Condition> conditions) {
        return new Or(conditions);
    }

    /**
     * Holds if a condition does not hold.
     *
     * @param condition the condition
     * @return the condition
     */
    static Condition not(Condition condition) {
        return new Not(condition);
    }
}
