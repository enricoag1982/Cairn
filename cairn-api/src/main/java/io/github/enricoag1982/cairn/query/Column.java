package io.github.enricoag1982.cairn.query;

import java.util.Objects;

/**
 * A column of a query's result, or what an {@link Order} sorts by (ADR-0023, capability {@code queries}). Each has a
 * name, which names the column in the result. An ungrouped query has columns of bindings, attributes of bindings and
 * depths; a grouped query has columns of its keys and aggregates.
 */
public sealed interface Column {

    /**
     * The name of the column in the result.
     *
     * @return the name
     */
    String name();

    /**
     * An entity or a relationship that is bound, as its state; empty for an empty binding.
     *
     * @param binding the name of the binding
     * @param name    the name of the column
     */
    record BindingColumn(String binding, String name) implements Column {

        /**
         * Rejects null components.
         */
        public BindingColumn {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * The value of an attribute of a bound entity or relationship; empty if it has none.
     *
     * @param binding   the name of the binding
     * @param attribute the name or an alias of the attribute
     * @param name      the name of the column
     */
    record AttributeColumn(String binding, String attribute, String name) implements Column {

        /**
         * Rejects null components.
         */
        public AttributeColumn {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(attribute, "attribute");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * The depth that a traversal bound, as an int value.
     *
     * @param binding the name that the depth is bound to
     * @param name    the name of the column
     */
    record DepthColumn(String binding, String name) implements Column {

        /**
         * Rejects null components.
         */
        public DepthColumn {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * A group key of a grouped query.
     *
     * @param key  the name of the group key, which is the name of its column in a grouped row
     * @param name the name of the column
     */
    record KeyColumn(String key, String name) implements Column {

        /**
         * Rejects null components.
         */
        public KeyColumn {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * An aggregate of a grouped query.
     *
     * @param aggregate the name of the aggregate, which is the name of its column in a grouped row
     * @param name      the name of the column
     */
    record AggregateColumn(String aggregate, String name) implements Column {

        /**
         * Rejects null components.
         */
        public AggregateColumn {
            Objects.requireNonNull(aggregate, "aggregate");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * A binding, as a column of the binding's name.
     *
     * @param binding the name of the binding
     * @return the column
     */
    static Column binding(String binding) {
        return new BindingColumn(binding, binding);
    }

    /**
     * A binding.
     *
     * @param binding the name of the binding
     * @param name    the name of the column
     * @return the column
     */
    static Column binding(String binding, String name) {
        return new BindingColumn(binding, name);
    }

    /**
     * An attribute of a binding, as a column named like {@code a.balance}.
     *
     * @param binding   the name of the binding
     * @param attribute the name or an alias of the attribute
     * @return the column
     */
    static Column attribute(String binding, String attribute) {
        return new AttributeColumn(binding, attribute, binding + "." + attribute);
    }

    /**
     * An attribute of a binding.
     *
     * @param binding   the name of the binding
     * @param attribute the name or an alias of the attribute
     * @param name      the name of the column
     * @return the column
     */
    static Column attribute(String binding, String attribute, String name) {
        return new AttributeColumn(binding, attribute, name);
    }

    /**
     * A depth, as a column of the depth binding's name.
     *
     * @param binding the name that the depth is bound to
     * @return the column
     */
    static Column depth(String binding) {
        return new DepthColumn(binding, binding);
    }

    /**
     * A depth.
     *
     * @param binding the name that the depth is bound to
     * @param name    the name of the column
     * @return the column
     */
    static Column depth(String binding, String name) {
        return new DepthColumn(binding, name);
    }

    /**
     * A group key, as a column of the key's name.
     *
     * @param key the name of the group key
     * @return the column
     */
    static Column key(String key) {
        return new KeyColumn(key, key);
    }

    /**
     * A group key.
     *
     * @param key  the name of the group key
     * @param name the name of the column
     * @return the column
     */
    static Column key(String key, String name) {
        return new KeyColumn(key, name);
    }

    /**
     * An aggregate, as a column of the aggregate's name.
     *
     * @param aggregate the name of the aggregate
     * @return the column
     */
    static Column aggregate(String aggregate) {
        return new AggregateColumn(aggregate, aggregate);
    }

    /**
     * An aggregate.
     *
     * @param aggregate the name of the aggregate
     * @param name      the name of the column
     * @return the column
     */
    static Column aggregate(String aggregate, String name) {
        return new AggregateColumn(aggregate, name);
    }
}
