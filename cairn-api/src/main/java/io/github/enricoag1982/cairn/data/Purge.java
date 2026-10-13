package io.github.enricoag1982.cairn.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A request to purge entities from a store: to remove them from all of history, for legal erasure (ADR-0024,
 * capability {@code purge}), with who asks, for whom, and why. Nothing but a purge ever changes or deletes what a store
 * recorded.
 *
 * <p>A purge removes every version of the named entities at every basis, every relationship from or to them with all its
 * versions, and the targets of their compositions that no other entity ever composed, together with their
 * relationships. A store refuses a purge that names an ID that no entity has, or that would leave an entity without a
 * relationship that its type requires, such as the target of a composition that another entity composed too.
 *
 * <p>The principal, the party acted for and the reason stay in the store's {@linkplain PurgeRecord purge record} for as
 * long as the store lives. Do not put the identity of the data subject in them, nor anything that identifies the person
 * whose data is erased: refer to the request by a ticket or case reference instead, such as {@code DSR-2026-0042}.
 *
 * <p>Build one with {@link #by(String)}. Unlike a transaction, a purge without a principal, a reason or an entity is a
 * programming error: a component that is null throws a {@code NullPointerException}, and one that is blank, or an empty
 * list of entities, throws an {@code IllegalArgumentException}, from the constructor and from the builder alike.
 *
 * @param principal  who asks for the purge, for example {@code dpo}; never blank
 * @param onBehalfOf the party the principal acts for, if any, such as the controller that ordered the erasure; not the
 *                   data subject
 * @param reason     why the entities are purged, such as a ticket reference; never blank
 * @param entities   the entities to purge, by ID, in the order given; never empty. The targets of their compositions
 *                   are purged with them and are not named here
 */
public record Purge(String principal, Optional<String> onBehalfOf, String reason, List<EntityId> entities) {

    /**
     * Rejects null components, and copies the entities.
     *
     * @throws NullPointerException     if a component is null
     * @throws IllegalArgumentException if the principal or the reason is blank, or if no entity is named
     */
    public Purge {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(onBehalfOf, "onBehalfOf");
        Objects.requireNonNull(reason, "reason");
        if (principal.isBlank()) {
            throw new IllegalArgumentException("A purge needs a principal.");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("A purge needs a reason that is not blank.");
        }
        entities = List.copyOf(entities);
        if (entities.isEmpty()) {
            throw new IllegalArgumentException("A purge needs at least one entity to purge.");
        }
    }

    /**
     * Starts building a purge.
     *
     * @param principal who asks for the purge; must not be blank
     * @return the builder
     */
    public static Builder by(String principal) {
        return new Builder(principal);
    }

    /**
     * Builds a {@link Purge}. A builder is not safe for use by several threads. Every method but {@link #build()}
     * returns the builder.
     */
    public static final class Builder {

        private final String principal;
        private Optional<String> onBehalfOf = Optional.empty();
        private String reason;
        private final List<EntityId> entities = new ArrayList<>();

        private Builder(String principal) {
            this.principal = principal;
        }

        /**
         * Names the party the principal acts for.
         *
         * @param party the party, for example the controller that ordered the erasure; not the data subject
         * @return this builder
         */
        public Builder onBehalfOf(String party) {
            this.onBehalfOf = Optional.of(Objects.requireNonNull(party, "party"));
            return this;
        }

        /**
         * Gives the reason for the purge.
         *
         * @param reason the reason, in words or as a ticket reference; must not be blank, and must not identify the
         *               data subject
         * @return this builder
         */
        public Builder reason(String reason) {
            this.reason = Objects.requireNonNull(reason, "reason");
            return this;
        }

        /**
         * Adds an entity to purge.
         *
         * @param id the ID of the entity
         * @return this builder
         */
        public Builder entity(EntityId id) {
            entities.add(Objects.requireNonNull(id, "id"));
            return this;
        }

        /**
         * Builds the purge.
         *
         * @return the purge
         * @throws NullPointerException     if the principal is null, or no reason was given
         * @throws IllegalArgumentException if the principal or the reason is blank, or if no entity was added
         */
        public Purge build() {
            return new Purge(principal, onBehalfOf, reason, entities);
        }
    }
}
