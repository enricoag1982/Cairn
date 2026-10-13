/**
 * Schema documents (capability {@code schema-documents}).
 *
 * <p>{@link io.github.enricoag1982.cairn.documents.SchemaDocuments#read(String)} reads a schema document, written
 * in YAML 1.2 or as JSON, into an origin and a schema definition. It reads strictly and reports every problem it finds
 * with its place in the text. A {@link io.github.enricoag1982.cairn.documents.SchemaDocument} that was read can
 * {@linkplain io.github.enricoag1982.cairn.documents.SchemaDocument#locate(java.util.List) locate} the problems that
 * validating, planning or registering its definition reports.
 */
package io.github.enricoag1982.cairn.documents;
