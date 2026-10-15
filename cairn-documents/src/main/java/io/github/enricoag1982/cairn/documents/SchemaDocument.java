package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.util.List;
import java.util.Objects;

/**
 * A schema document that was read: the origin, the schema definition and where in the text each element is
 * (capability {@code schema-documents}).
 *
 * @param origin     the origin, with its version
 * @param definition the types the document defines
 * @param locations  where the elements of the definition are in the text
 */
public record SchemaDocument(Origin origin, SchemaDefinition definition, DocumentLocations locations) {

    /**
     * Rejects null components.
     */
    public SchemaDocument {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(locations, "locations");
    }

    /**
     * Locates problems in this document: the problems that validating, planning or registering its definition
     * reports. A problem about a type, attribute, inverse name, rule or check is located at that element, a problem
     * about the expression of a check at the key {@code expression} of the check, with the position in the document of
     * the position in the expression where it is known, a problem about the origin at the key {@code origin}, a problem
     * about an element that the document does not have, such as an attribute that a new version removes, at the closest
     * enclosing element that the document has, and a problem whose path names no element of the document at the root of
     * the document. A problem that is located already keeps its location.
     *
     * @param problems the problems, for example those of {@code SchemaRegistry.validate} or of a rejected registration
     * @return the same problems, in the same order, each with its location
     */
    public List<Problem> locate(List<Problem> problems) {
        return locations.locate(problems);
    }
}
