package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Reads and writes schema documents (capability {@code schema-documents}).
 *
 * <p>A schema document is YAML 1.2, or JSON, which is valid YAML 1.2: a mapping with the key {@code cairn}, whose
 * value is the format version 1, the key {@code origin}, and the optional keys {@code traits}, {@code entityTypes}
 * and {@code relationshipTypes}. A type definition uses the names of the schema definition's properties.
 */
public final class SchemaDocuments {

    private static final String JSON_SCHEMA_RESOURCE = "schema-document-1.schema.json";

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

    /**
     * Writes an origin and a schema definition as a schema document in YAML 1.2, in block style.
     *
     * <p>The keys are in the order {@code cairn}, {@code origin}, {@code traits}, {@code entityTypes} and
     * {@code relationshipTypes}; types and attributes are in the order of the definition, and the properties of an
     * element are in a fixed order. A property that has its default is left out: a boolean that is {@code false}, a
     * list that is empty, an optional that is absent and a relationship kind that is an association. Strings are
     * quoted only where YAML 1.2 would read them as another type or where they contain special characters, and the
     * version of the origin and the multiplicities always are. The text ends in a line break.
     *
     * <p>{@linkplain #read(String) Reading} the text gives back an equal origin and an equal definition, provided
     * that every literal has the value type of its attribute: a literal of another type is written as its own type
     * and may be read as a literal of the attribute's type. Writing does not validate the definition; a definition
     * with two types of the same name is written as it is, and reading the text then reports the duplicate.
     *
     * @param origin     the origin, with its version
     * @param definition the types to write
     * @return the document
     */
    public static String writeYaml(Origin origin, SchemaDefinition definition) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(definition, "definition");
        return DocumentWriter.yaml(origin, definition);
    }

    /**
     * Writes an origin and a schema definition as a schema document in JSON, with two-space indentation.
     *
     * <p>The document says the same as the one {@link #writeYaml(Origin, SchemaDefinition)} writes, with the same
     * keys in the same order and the same properties left out, and {@linkplain #read(String) reading} it gives the
     * same result. The text ends in a line break.
     *
     * @param origin     the origin, with its version
     * @param definition the types to write
     * @return the document
     */
    public static String writeJson(Origin origin, SchemaDefinition definition) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(definition, "definition");
        return DocumentWriter.json(origin, definition);
    }

    /**
     * Returns the published JSON Schema (draft 2020-12) that describes schema documents of format version 1.
     *
     * <p>Its {@code $id} is {@code urn:cairn:schema-document:1}. Every document that {@link #read(String)} reads
     * without problems is valid against it, as JSON or as YAML converted to JSON; a document with an unknown key, a
     * missing required key or a value of the wrong kind is invalid. It does not check what only the engine knows: names,
     * the patterns of {@code pattern} attributes, and whether literals fit their attribute.
     *
     * @return the JSON Schema, as JSON text
     */
    public static String jsonSchema() {
        try (InputStream stream = SchemaDocuments.class.getResourceAsStream(JSON_SCHEMA_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("The resource " + JSON_SCHEMA_RESOURCE + " is missing from the module.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The resource " + JSON_SCHEMA_RESOURCE + " cannot be read.", e);
        }
    }
}
