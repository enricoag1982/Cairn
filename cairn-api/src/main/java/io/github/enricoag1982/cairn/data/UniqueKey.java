package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A reference to an existing entity by one of its unique attributes (ADR-0026, capability {@code transactions}): an
 * entity type, an attribute that is unique in that type, and a value. The store resolves it when it commits, at the start
 * of the write's effective period and against what it held before the transaction, to the entity of that type, or of one
 * of its subtypes, that has the value then. A {@linkplain View view} finds the entity at its effective time with
 * {@link View#entity(UniqueKey)}. A {@linkplain Purge purge} names entities by key too, but there is no effective period
 * to resolve it at: it resolves over all of effective time (see {@link PurgeKey}, ADR-0033).
 *
 * <p>The record only holds what the caller wrote. Whether the type has the attribute, whether the attribute is unique
 * and whether the value fits are checked against a schema, by the store when it commits or by a view: a key that does
 * not pass is rejected with the problem {@code INVALID_UNIQUE_KEY}, and one that names no entity with
 * {@code UNIQUE_KEY_NOT_FOUND}. A key names entities only, and resolves against the store before the transaction, so it
 * does not see what the same transaction creates or writes: temporary IDs name what it creates.
 *
 * @param entityType the name or an alias of the entity type, compared without regard to case
 * @param attribute  the name or an alias of the attribute, compared without regard to case
 * @param value      the value; a narrower number fits a wider numeric attribute, as it does when written
 */
public record UniqueKey(String entityType, String attribute, Value value) implements Ref {

    /**
     * Rejects null components.
     *
     * @throws NullPointerException if a component is null
     */
    public UniqueKey {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(attribute, "attribute");
        Objects.requireNonNull(value, "value");
    }

    /**
     * The key as problems and messages call it, such as {@code Account(number: A-1)}.
     *
     * @return the text
     */
    @Override
    public String toString() {
        return entityType + "(" + attribute + ": " + describe(value) + ")";
    }

    private static String describe(Value value) {
        return switch (value) {
            case Value.StringValue(String text) -> text;
            case Value.BooleanValue(boolean flag) -> String.valueOf(flag);
            case Value.IntValue(int number) -> String.valueOf(number);
            case Value.LongValue(long number) -> String.valueOf(number);
            case Value.DecimalValue(BigDecimal number) -> number.toPlainString();
            case Value.DateValue(LocalDate date) -> date.toString();
            case Value.InstantValue(Instant instant) -> instant.toString();
        };
    }
}
