/**
 * Schema documents (capability {@code schema-documents}).
 *
 * <p>{@link io.github.enricoag1982.cairn.documents.SchemaDocuments#read(String)} reads a schema document, written
 * in YAML 1.2 or as JSON, into an origin and a schema definition. It reads strictly and reports every problem it finds
 * with its place in the text. A {@link io.github.enricoag1982.cairn.documents.SchemaDocument} that was read can
 * {@linkplain io.github.enricoag1982.cairn.documents.SchemaDocument#locate(java.util.List) locate} the problems that
 * validating, planning or registering its definition reports.
 *
 * <p>{@link io.github.enricoag1982.cairn.documents.SchemaDocuments#writeYaml(io.github.enricoag1982.cairn.schema.Origin,
 * io.github.enricoag1982.cairn.schema.SchemaDefinition)} and
 * {@link io.github.enricoag1982.cairn.documents.SchemaDocuments#writeJson(io.github.enricoag1982.cairn.schema.Origin,
 * io.github.enricoag1982.cairn.schema.SchemaDefinition)} write an origin and a definition as a document in a fixed
 * order, leaving out every default, so that reading the text gives back an equal origin and definition.
 * {@link io.github.enricoag1982.cairn.documents.SchemaDocuments#jsonSchema()} returns the published JSON Schema
 * (draft 2020-12, {@code urn:cairn:schema-document:1}) that describes the documents.
 */
package io.github.enricoag1982.cairn.documents;
