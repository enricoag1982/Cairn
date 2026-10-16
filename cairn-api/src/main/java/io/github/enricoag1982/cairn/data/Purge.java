package io.github.enricoag1982.cairn.data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A request to purge entities from a store: to remove them from all of history, for legal erasure (ADR-0024,
 * capability {@code purge}), with who asks, for whom, and why. Nothing but a purge ever changes or deletes what a store
 * recorded.
 *
 * <p>A purge names entities by ID, by {@linkplain PurgeKey unique key}, or both (ADR-0033). A key, such as
 * {@code Organization(lei: 5493001KJTIIGC8Y1R12)}, resolves differently from a key in a write: a write resolves it at
 * the start of its effective period, but a purge has no period, so a key without an effective time resolves over all of
 * effective time, at the latest basis, to the one entity that ever held the value, and one with an effective time
 * resolves to the entity that holds the value then. A key that is invalid, names no entity, or, without an effective
 * time, names several entities that held the value at different times, is rejected. An entity that a key names is
 * purged as if the purge named its ID, and the result says which entity each key named.
 *
 * <p>A purge removes every version of the named entities at every basis, every relationship from or to them with all its
 * versions, and the targets of their compositions that no other entity ever composed, together with their
 * relationships. A store refuses a purge that names an ID that no entity has, or that would leave an entity without a
 * relationship that its type requires, such as the target of a composition that another entity composed too.
 *
 * <p>The principal, the party acted for and the reason stay in the store's {@linkplain PurgeRecord purge record} for as
 * long as the store lives. Do not put the identity of the data subject in them, nor anything that identifies the person
 * whose data is erased: refer to the request by a ticket or case reference instead, such as {@code DSR-2026-0042}. The
 * keys and their values, which may be personal data, are not in the record: the store keeps them nowhere.
 *
 * <p>Build one with {@link #by(String)}. Unlike a transaction, a purge without a principal, a reason or an entity is a
 * programming error: a component that is null throws a {@code NullPointerException}, and one that is blank, or a purge
 * that names no entity and no key, throws an {@code IllegalArgumentException}, from the constructor and from the builder
 * alike.
 *
 * @param principal  who asks for the purge, for example {@code dpo}; never blank
 * @param onBehalfOf the party the principal acts for, if any, such as the controller that ordered the erasure; not the
 *                   data subject
 * @param reason     why the entities are purged, such as a ticket reference; never blank
 * @param entities   the entities to purge, by ID, in the order given. The targets of their compositions are purged with
 *                   them and are not named here
 * @param keys       the entities to purge, by unique key, in the order given. A purge names at least one entity or key
 */
public record Purge(String principal, Optional<String> onBehalfOf, String reason, List<EntityId> entities,
        List<PurgeKey> keys) {

    /**
     * Rejects null components, and copies the entities and the keys.
     *
     * @throws NullPointerException     if a component is null
     * @throws IllegalArgumentException if the principal or the reason is blank, or if no entity and no key is named
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
        keys = List.copyOf(keys);
        if (entities.isEmpty() && keys.isEmpty()) {
            throw new IllegalArgumentException("A purge needs at least one entity to purge, by ID or by unique key.");
        }
    }

    /**
     * Creates a purge that names entities by ID only.
     *
     * @param principal  who asks for the purge; must not be blank
     * @param onBehalfOf the party the principal acts for, if any
     * @param reason     why the entities are purged; must not be blank
     * @param entities   the entities to purge, by ID; must not be empty
     * @throws NullPointerException     if a component is null
     * @throws IllegalArgumentException if the principal or the reason is blank, or if no entity is named
     */
    public Purge(String principal, Optional<String> onBehalfOf, String reason, List<EntityId> entities) {
        this(principal, onBehalfOf, reason, entities, List.of());
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
        private final List<PurgeKey> keys = new ArrayList<>();

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
         * Adds an entity to purge by its ID.
         *
         * @param id the ID of the entity
         * @return this builder
         */
        public Builder entity(EntityId id) {
            entities.add(Objects.requireNonNull(id, "id"));
            return this;
        }

        /**
         * Adds an entity to purge by a unique key, which names the entity that ever held the value (ADR-0033). The key
         * resolves over all of effective time, against the store at the latest basis, unlike a key in a write, which
         * resolves at the start of the write's effective period: it names the one entity of the key's type, or of a
         * subtype, that holds the value at some effective time, even if it has ended. The store rejects the purge if the
         * key is invalid, names no entity, or names several that held the value at different times; for such a value
         * use {@link #entity(UniqueKey, Instant)}, or name the entity by ID.
         *
         * @param key the key, which a generated type can make, such as {@code Employment.byEmployeeNumber("E-007")}
         * @return this builder
         */
        public Builder entity(UniqueKey key) {
            keys.add(new PurgeKey(Objects.requireNonNull(key, "key"), Optional.empty()));
            return this;
        }

        /**
         * Adds an entity to purge by a unique key at an effective time (ADR-0033): the key names the entity of its type,
         * or of a subtype, that holds the value at that time, as a view at that effective time finds it, so it is never
         * ambiguous. The store rejects the purge if the key is invalid or no entity holds the value then.
         *
         * @param key         the key
         * @param effectiveAt the effective time at which the entity holds the value
         * @return this builder
         */
        public Builder entity(UniqueKey key, Instant effectiveAt) {
            keys.add(new PurgeKey(Objects.requireNonNull(key, "key"),
                    Optional.of(Objects.requireNonNull(effectiveAt, "effectiveAt"))));
            return this;
        }

        /**
         * Builds the purge.
         *
         * @return the purge
         * @throws NullPointerException     if the principal is null, or no reason was given
         * @throws IllegalArgumentException if the principal or the reason is blank, or if no entity and no key was added
         */
        public Purge build() {
            return new Purge(principal, onBehalfOf, reason, entities, keys);
        }
    }
}
