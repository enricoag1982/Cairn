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
 * {@link io.github.enricoag1982.cairn.data.TemporaryId}. An existing entity can also be named by a
 * {@link io.github.enricoag1982.cairn.data.UniqueKey}, an entity type with one of its unique attributes and a value,
 * which the store resolves when it commits (ADR-0026).
 *
 * <p>Data is read only through a {@link io.github.enricoag1982.cairn.data.View}, an immutable view bound to
 * {@linkplain io.github.enricoag1982.cairn.data.Coordinates coordinates}: an effective time, and a basis or
 * transaction time to read as of (capability {@code views}).
 *
 * <p>What each basis changed is read with {@link io.github.enricoag1982.cairn.data.Store#changes} or followed with
 * {@link io.github.enricoag1982.cairn.data.Store#changeFeed}: a {@link io.github.enricoag1982.cairn.data.Change} for each
 * basis, which is a {@link io.github.enricoag1982.cairn.data.TransactionChange} with the
 * {@linkplain io.github.enricoag1982.cairn.data.Changed entities and relationships} that it changed and the effective
 * periods in which they changed, a {@link io.github.enricoag1982.cairn.data.SchemaChange} or a
 * {@link io.github.enricoag1982.cairn.data.PurgeChange} (ADR-0029, capability {@code change-feed}).
 *
 * <p>Effective times are UTC instants with microsecond precision, and effective periods are half-open
 * (ADR-0008). Problems are reported as {@link io.github.enricoag1982.cairn.schema.Problem}s.
 */
package io.github.enricoag1982.cairn.data;
