package io.github.enricoag1982.cairn.data;

import java.util.Objects;

/**
 * A name that a transaction gives to an entity it creates, so that its other writes can refer to the entity before it
 * has an ID (ADR-0019, capability {@code transactions}). The receipt maps every temporary ID to the entity's ID.
 *
 * <p>A temporary ID is valid in the transaction that names it only. In problem paths it is written with a leading
 * dollar sign, such as {@code $acme}.
 *
 * @param name the name, not blank
 */
public record TemporaryId(String name) implements Ref {

    /**
     * Rejects a null or blank name.
     *
     * @throws NullPointerException     if the name is null
     * @throws IllegalArgumentException if the name is blank
     */
    public TemporaryId {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("A temporary ID needs a name.");
        }
    }

    @Override
    public String toString() {
        return "$" + name;
    }
}
