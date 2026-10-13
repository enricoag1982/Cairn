package io.github.enricoag1982.sysrec.core.schema;

/** The three kinds of type in a schema. */
enum Kind {
    ENTITY_TYPE("entity type"),
    TRAIT("trait"),
    RELATIONSHIP_TYPE("relationship type");

    private final String label;

    Kind(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
