package io.github.enricoag1982.cairn.data;

import java.util.Objects;

/**
 * Thrown by {@link View#entity(UniqueKey)} of a view restricted to a clearance when the key is of a unique attribute that
 * the view withholds (ADR-0036, capability {@code attribute-access}): finding an entity by the attribute's value would
 * tell the value, whether or not an entity has it, so the call fails as finding by a key that the schema does not allow
 * does. It is an {@link IllegalArgumentException}, so that code that treats the two alike does; code that tells a caller
 * that the clearance is the reason, such as an agent tool, catches this class.
 *
 * <p>A view withholds an attribute if its clearance covers neither the attribute's classification in the view's schema
 * nor the one that the store's latest schema gives it.
 */
public final class WithheldKeyException extends IllegalArgumentException {

    /** Identifies the serialized form. */
    private static final long serialVersionUID = 1L;

    /** The name of the key's entity type. */
    private final String entityType;
    /** The name of the key's attribute. */
    private final String attribute;
    /** The classification that the clearance does not cover. */
    private final String classification;

    /**
     * Creates the exception.
     *
     * @param message        what is wrong, in words
     * @param entityType     the name of the key's entity type, as the schema of the view has it
     * @param attribute      the name of the key's attribute, as the schema of the view has it
     * @param classification the classification that the clearance of the view does not cover
     */
    public WithheldKeyException(String message, String entityType, String attribute, String classification) {
        super(message);
        this.entityType = Objects.requireNonNull(entityType, "entityType");
        this.attribute = Objects.requireNonNull(attribute, "attribute");
        this.classification = Objects.requireNonNull(classification, "classification");
    }

    /**
     * The entity type of the key.
     *
     * @return the name of the entity type, as the schema of the view has it
     */
    public String entityType() {
        return entityType;
    }

    /**
     * The attribute of the key.
     *
     * @return the name of the attribute, as the schema of the view has it
     */
    public String attribute() {
        return attribute;
    }

    /**
     * The classification that the clearance of the view does not cover.
     *
     * @return the classification
     */
    public String classification() {
        return classification;
    }
}
