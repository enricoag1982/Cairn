/**
 * The generator of Java types from schema documents (ADR-0021, capability {@code java-types}).
 *
 * <p>{@link io.github.enricoag1982.cairn.codegen.JavaTypes#generate} reads schema documents, checks them together as a
 * schema registry would register them, and writes the Java source of the types they define: an interface per entity
 * type, trait and relationship type, with a snapshot record and builders of writes, and a base class per rule that the
 * documents name. {@link io.github.enricoag1982.cairn.codegen.CairnCodegen} runs it from the command line. The
 * generated source depends on the Cairn API only.
 */
package io.github.enricoag1982.cairn.codegen;
