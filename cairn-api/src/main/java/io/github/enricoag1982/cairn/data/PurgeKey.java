package io.github.enricoag1982.cairn.data;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A unique key that a {@linkplain Purge purge} names an entity by, with the effective time at which it names it, if the
 * caller gives one (ADR-0033, capability {@code purge}). It is the {@link UniqueKey} of a write, such as
 * {@code Organization(lei: 5493001KJTIIGC8Y1R12)}, checked the same way, but it resolves differently: a write resolves a
 * key at the start of its effective period, and a purge has no period, because it removes whole histories.
 *
 * <p>Without an effective time, the key resolves over all of effective time, against the store at the latest basis and
 * before the purge, to the one entity of the key's type, or of a subtype, that ever held the value, so that it finds an
 * entity that has ended, such as a former employee. If several entities held the value at different times, the store
 * rejects the purge with {@code UNIQUE_KEY_AMBIGUOUS}, naming them with the periods in which each held it, because a
 * purge cannot be undone and the store never chooses between them. With an effective time, the key resolves to the
 * entity that holds the value at that time, as a {@linkplain View view} at that effective time finds it; there is at most
 * one, so the key is never ambiguous. A value that only an older basis recorded, such as one that a correction removed,
 * is not found.
 *
 * <p>The record only holds what the caller wrote. Whether the key is valid is checked against the store's schema, and
 * what it names against its entities, when the store checks the purge. The key and its value are in the request and in
 * the {@linkplain PurgeResult.Purged result} only: the store keeps them nowhere, not in its purge record, its change feed
 * or its files.
 *
 * @param key         the unique key; as in a write, it names an entity type, an attribute of it that is unique, and a value
 * @param effectiveAt the effective time at which the key names the entity, or empty to name the entity that held the
 *                    value at any effective time
 */
public record PurgeKey(UniqueKey key, Optional<Instant> effectiveAt) {

    /**
     * Rejects null components.
     *
     * @throws NullPointerException if the key or the effective time is null; an effective time that is not given is
     *                              {@link Optional#empty()}
     */
    public PurgeKey {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
    }
}
