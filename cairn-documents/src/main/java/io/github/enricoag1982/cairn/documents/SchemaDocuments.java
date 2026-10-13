package io.github.enricoag1982.cairn.documents;

import java.util.Objects;

/**
 * Reads schema documents (capability {@code schema-documents}).
 *
 * <p>A schema document is YAML 1.2, or JSON, which is valid YAML 1.2: a mapping with the key {@code cairn}, whose
 * value is the format version 1, the key {@code origin}, and the optional keys {@code traits}, {@code entityTypes}
 * and {@code relationshipTypes}. A type definition uses the names of the schema definition's properties.
 */
public final class SchemaDocuments {

    private SchemaDocuments() {
    }

    /**
     * Reads a schema document into an origin and a schema definition.
     *
     * <p>Reading is strict. It reports every problem it finds, each with its location in the text, and returns no
     * definition if there is one: an unknown key (with the closest known key as a hint), a missing key that is
     * required, a value of the wrong kind, a multiplicity or range written in another form, and YAML features outside
     * the supported subset (anchors, aliases, explicit tags, merge keys, duplicate keys and more than one document).
     * A YAML syntax error stops reading and is the only problem reported. A format version other than 1 also ends
     * reading with that problem alone, because the keys of other versions are not known.
     *
     * <p>A literal of the wrong value type, such as a string example for a date attribute, is not a problem of the
     * document: it is read as a literal of its own type, and validating the definition reports it.
     *
     * @param text the document
     * @return the document with its locations, or the problems
     */
    public static DocumentReadResult read(String text) {
        return DocumentReader.read(Objects.requireNonNull(text, "text"));
    }
}
