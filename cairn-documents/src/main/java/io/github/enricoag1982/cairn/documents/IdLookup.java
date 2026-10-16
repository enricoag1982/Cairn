package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.View;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Tells what an ID names, for reading a payload (capability {@code json-payloads}, design D2).
 *
 * <p>A reference in a payload is a text, but the transaction API types it: an {@code EntityId} or a
 * {@code RelationshipId}. A lookup answers, for the text of an ID, whether the store holds an entity or a relationship
 * under it and the name of its type, so that the reader builds the right kind of reference and types the values of a
 * {@code patch} or {@code replace} by the attributes of that type. An ID that the lookup does not know is read as an
 * entity ID, so that the transaction's validation reports it as unknown.
 *
 * <p>A lookup that is made from a store also answers for a {@linkplain UniqueKey unique key}, at an instant, with the ID
 * of the entity that has it. The reader uses it to type the values of a write that names its target by a key by the type
 * of the entity that the key names, and to locate the problems of the transaction about that entity at the write
 * (ADR-0026, design D5 of {@code add-unique-key-references}).
 */
@FunctionalInterface
public interface IdLookup {

    /** What an ID names. */
    enum Kind {
        /** The ID of an entity. */
        ENTITY,
        /** The ID of a relationship. */
        RELATIONSHIP
    }

    /**
     * What a store holds under an ID.
     *
     * @param kind     whether the ID names an entity or a relationship
     * @param typeName the name of its type, as the store's schema calls it
     */
    record Known(Kind kind, String typeName) {
        /**
         * Rejects null components.
         */
        public Known {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(typeName, "typeName");
        }
    }

    /**
     * Finds what an ID names.
     *
     * @param id the text of the ID
     * @return what the store holds under the ID, or empty if it holds nothing
     */
    Optional<Known> find(String id);

    /**
     * The entity that a unique key names at an instant.
     *
     * @param id       the text of the entity's ID
     * @param typeName the name of the entity's type, as the store's schema calls it
     */
    record Named(String id, String typeName) {
        /**
         * Rejects null components.
         */
        public Named {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(typeName, "typeName");
        }
    }

    /**
     * Finds the entity that a unique key names at an instant, as the store holds it now: what a commit would find at the
     * start of a write's period, if nothing changes in between.
     *
     * <p>The default knows no key, so that a lookup written as a lambda keeps working: every key is then unresolved, and
     * the values of a write to its entity are read by the key's type, or by their own YAML types if the schema does not
     * have it.
     *
     * @param key         the key
     * @param effectiveAt the instant at which the key is resolved
     * @return the entity, or empty if no entity has the key's value then, or the key is invalid
     */
    default Optional<Named> find(UniqueKey key, Instant effectiveAt) {
        return Optional.empty();
    }

    /**
     * A lookup that knows no ID: every ID is read as an entity ID, and the values of a {@code patch} or {@code replace}
     * of an entity that the payload does not create are read by their own YAML types.
     *
     * @return the lookup
     */
    static IdLookup none() {
        return id -> Optional.empty();
    }

    /**
     * A lookup that asks the latest views of a store: an ID as {@link #from(View)} does, with a view that it makes at
     * each call, and a unique key through a view at the instant that the key is resolved at. It answers from what the
     * store holds when it is asked.
     *
     * @param store the store
     * @return the lookup
     */
    static IdLookup fromStore(Store store) {
        return fromStore(store, view -> view);
    }

    /**
     * A lookup like {@link #fromStore(Store)} that asks views restricted to a clearance (ADR-0036): a unique key of an
     * attribute that the clearance does not cover finds nothing, whether or not an entity has the value, so reading a
     * payload tells the caller nothing about it. The IDs it knows are those of the restricted views, which are all the
     * IDs there are: restricting a view withholds values and not entities.
     *
     * @param store     the store
     * @param clearance the clearance of the caller
     * @return the lookup
     */
    static IdLookup fromStore(Store store, Clearance clearance) {
        Objects.requireNonNull(clearance, "clearance");
        return fromStore(store, view -> view.restrictedTo(clearance));
    }

    private static IdLookup fromStore(Store store, UnaryOperator<View> restrict) {
        Objects.requireNonNull(store, "store");
        return new IdLookup() {
            @Override
            public Optional<Known> find(String id) {
                return from(restrict.apply(store.view(Coordinates.effectiveAt(Instant.EPOCH)))).find(id);
            }

            @Override
            public Optional<Named> find(UniqueKey key, Instant effectiveAt) {
                try {
                    return restrict.apply(store.view(Coordinates.effectiveAt(effectiveAt))).entity(key)
                            .map(state -> new Named(state.id().value(), state.entityType()));
                } catch (IllegalArgumentException invalid) {
                    return Optional.empty();
                }
            }
        };
    }

    /**
     * A lookup that asks the timelines of a view. An ID is known if the view's timeline of an entity or of a
     * relationship under that ID is not empty, whatever the view's effective time, so an entity or relationship that has
     * ended, or is not yet effective, is found too. Its type is that of any of its versions: the type of an entity or
     * relationship never changes.
     *
     * <p>The view should be the latest one, so that it knows everything the store holds.
     *
     * @param view the view
     * @return the lookup
     */
    static IdLookup from(View view) {
        Objects.requireNonNull(view, "view");
        return id -> {
            List<EntityState> entity = view.timeline(new EntityId(id));
            if (!entity.isEmpty()) {
                return Optional.of(new Known(Kind.ENTITY, entity.getFirst().entityType()));
            }
            List<RelationshipState> relationship = view.relationshipTimeline(new RelationshipId(id));
            return relationship.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new Known(Kind.RELATIONSHIP, relationship.getFirst().relationshipType()));
        };
    }
}
