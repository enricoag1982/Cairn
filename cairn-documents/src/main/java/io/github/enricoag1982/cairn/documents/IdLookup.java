package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.View;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Tells what an ID names, for reading a payload (capability {@code json-payloads}, design D2).
 *
 * <p>A reference in a payload is a text, but the transaction API types it: an {@code EntityId} or a
 * {@code RelationshipId}. A lookup answers, for the text of an ID, whether the store holds an entity or a relationship
 * under it and the name of its type, so that the reader builds the right kind of reference and types the values of a
 * {@code patch} or {@code replace} by the attributes of that type. An ID that the lookup does not know is read as an
 * entity ID, so that the transaction's validation reports it as unknown.
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
     * A lookup that knows no ID: every ID is read as an entity ID, and the values of a {@code patch} or {@code replace}
     * of an entity that the payload does not create are read by their own YAML types.
     *
     * @return the lookup
     */
    static IdLookup none() {
        return id -> Optional.empty();
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
