/**
 * The Cairn data API: stores, transactions, writes and views.
 *
 * <p>A {@link io.github.enricoag1982.cairn.data.Store} owns a
 * {@linkplain io.github.enricoag1982.cairn.data.Store#schema() schema registry} and the data of its entities. Callers
 * build a {@link io.github.enricoag1982.cairn.data.Transaction}, a list of
 * {@linkplain io.github.enricoag1982.cairn.data.Write writes}, and
 * {@linkplain io.github.enricoag1982.cairn.data.Store#commit commit} it atomically or
 * {@linkplain io.github.enricoag1982.cairn.data.Store#dryRun run it dry} (capabilities {@code transactions},
 * {@code entity-versions} and {@code relationships}). Entities are named by an
 * {@link io.github.enricoag1982.cairn.data.EntityId}, relationships by a
 * {@link io.github.enricoag1982.cairn.data.RelationshipId}, and either inside the transaction that creates it by a
 * {@link io.github.enricoag1982.cairn.data.TemporaryId}.
 *
 * <p>Data is read only through a {@link io.github.enricoag1982.cairn.data.View}, an immutable view bound to
 * {@linkplain io.github.enricoag1982.cairn.data.Coordinates coordinates}: an effective time, and a basis or
 * transaction time to read as of (capability {@code views}).
 *
 * <p>Effective times are UTC instants with microsecond precision, and effective periods are half-open
 * (ADR-0008). Problems are reported as {@link io.github.enricoag1982.cairn.schema.Problem}s.
 */
package io.github.enricoag1982.cairn.data;
