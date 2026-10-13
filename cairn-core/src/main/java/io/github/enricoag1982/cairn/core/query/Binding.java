package io.github.enricoag1982.cairn.core.query;

import io.github.enricoag1982.cairn.schema.Attribute;
import java.util.List;

/**
 * What a name of a query is bound to, while the query is checked: where its cell is in a row, and the type whose
 * attributes the names of attributes are resolved against.
 *
 * @param name       the name; null for a binding that the query does not name, such as the relationship of a follow step
 *                   that does not bind it
 * @param kind       what the binding holds
 * @param slot       the position of its cell in a row
 * @param typeName   the name of its entity type or relationship type; null if that is unknown, because the query got it
 *                   wrong, which is a problem already
 * @param attributes the attributes of the type, with those of its supertypes and traits; null if the type is unknown or
 *                   the binding is a depth
 */
record Binding(String name, Kind kind, int slot, String typeName, List<Attribute> attributes) {

    /** What a binding holds. */
    enum Kind {
        ENTITY("an entity", "entity type"),
        RELATIONSHIP("a relationship", "relationship type"),
        DEPTH("a depth", "depth");

        private final String phrase;
        private final String typeWord;

        Kind(String phrase, String typeWord) {
            this.phrase = phrase;
            this.typeWord = typeWord;
        }

        String phrase() {
            return phrase;
        }

        String typeWord() {
            return typeWord;
        }
    }
}
