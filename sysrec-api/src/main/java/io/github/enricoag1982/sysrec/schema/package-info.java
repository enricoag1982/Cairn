/**
 * The sysRec schema API.
 *
 * <p>A {@link io.github.enricoag1982.sysrec.schema.SchemaDefinition} describes entity types, traits and
 * relationship types as plain data (capability {@code type-model}). A
 * {@link io.github.enricoag1982.sysrec.schema.SchemaRegistry} validates and registers definitions, keeps the
 * history of the stored {@link io.github.enricoag1982.sysrec.schema.Schema} in transaction time and checks
 * that changes are compatible (capability {@code schema-evolution}).
 */
package io.github.enricoag1982.sysrec.schema;
