package io.github.enricoag1982.cairn.query;

import java.util.Objects;
import java.util.Optional;

/**
 * What rows are grouped by (ADR-0023, capability {@code queries}): a binding, or an attribute of a binding. Rows with the
 * same key form a group; rows whose attribute has no value, or whose binding is empty, form a group of their own.
 *
 * @param binding   the name of the binding
 * @param attribute the name or an alias of the attribute of the binding, if the key is an attribute; empty if the key is
 *                  the binding itself, whose entity or relationship is told apart by its ID
 * @param name      the name of the column that the key gives to a grouped row
 */
public record GroupKey(String binding, Optional<String> attribute, String name) {

    /**
     * Rejects null components.
     */
    public GroupKey {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(attribute, "attribute");
        Objects.requireNonNull(name, "name");
    }

    /**
     * A key that is a binding, whose column has the binding's name.
     *
     * @param binding the name of the binding
     * @return the key
     */
    public static GroupKey binding(String binding) {
        return binding(binding, binding);
    }

    /**
     * A key that is a binding.
     *
     * @param binding the name of the binding
     * @param name    the name of the column
     * @return the key
     */
    public static GroupKey binding(String binding, String name) {
        return new GroupKey(binding, Optional.empty(), name);
    }

    /**
     * A key that is an attribute of a binding, whose column is named like {@code party.name}.
     *
     * @param binding   the name of the binding
     * @param attribute the name or an alias of the attribute
     * @return the key
     */
    public static GroupKey attribute(String binding, String attribute) {
        return attribute(binding, attribute, binding + "." + attribute);
    }

    /**
     * A key that is an attribute of a binding.
     *
     * @param binding   the name of the binding
     * @param attribute the name or an alias of the attribute
     * @param name      the name of the column
     * @return the key
     */
    public static GroupKey attribute(String binding, String attribute, String name) {
        return new GroupKey(binding, Optional.of(Objects.requireNonNull(attribute, "attribute")), name);
    }
}
