/**
 * The Cairn schema API.
 *
 * <p>A {@link io.github.enricoag1982.cairn.schema.SchemaDefinition} describes entity types, traits and
 * relationship types as plain data (capability {@code type-model}). A
 * {@link io.github.enricoag1982.cairn.schema.SchemaRegistry} validates and registers definitions, keeps the
 * history of the stored {@link io.github.enricoag1982.cairn.schema.Schema} in transaction time and checks
 * that changes are compatible (capability {@code schema-evolution}); it can also
 * {@linkplain io.github.enricoag1982.cairn.schema.SchemaRegistry#plan plan} a registration without applying it.
 * Rules that types name are described by {@link io.github.enricoag1982.cairn.schema.RuleDescriptor}.
 */
package io.github.enricoag1982.cairn.schema;
