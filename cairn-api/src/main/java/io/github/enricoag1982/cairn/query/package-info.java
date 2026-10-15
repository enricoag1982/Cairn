/**
 * Queries as data (ADR-0023, capability {@code queries}): a {@link io.github.enricoag1982.cairn.query.Query} starts
 * from an entity type, and a pipeline of steps over named bindings filters, follows and traverses relationships, groups
 * and aggregates; it is built in Java and run by a {@linkplain io.github.enricoag1982.cairn.data.View#query view}, which
 * returns a {@link io.github.enricoag1982.cairn.query.QueryResult}: rows with named columns, or problems.
 *
 * <p>Queries are immutable records. {@link io.github.enricoag1982.cairn.query.Query#from} starts a builder, and
 * {@link io.github.enricoag1982.cairn.query.AttributeRef} gives conditions, columns, keys, orders and aggregates
 * compile-time types over attribute values; both build the same records.
 *
 * <p>A query names types, attributes, relationship names and bindings as text, and does not check them when it is
 * built: the view that runs it checks them against its schema, reports every problem at once, and reads nothing then.
 */
package io.github.enricoag1982.cairn.query;
