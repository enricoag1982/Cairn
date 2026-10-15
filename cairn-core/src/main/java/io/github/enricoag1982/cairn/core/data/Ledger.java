package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.kv.Batch;
import io.github.enricoag1982.cairn.core.kv.KeyValueStore;
import io.github.enricoag1982.cairn.core.storage.LedgerCodec;
import io.github.enricoag1982.cairn.core.storage.SegmentData;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The engine's stored state: entities and relationships with their histories on two time axes, kept as keys and values on
 * an ordered {@linkplain KeyValueStore key-value store} (ADR-0027, design D3 to D5 of
 * {@code refactor-storage-onto-key-value-store}). The {@link LedgerCodec} says what the keys and values are; this class
 * says how a commit writes them and how a reader finds an entity or relationship at a basis.
 *
 * <p><b>Writing.</b> A commit turns the previous and the new timeline of everything it changes into one {@link Batch}.
 * Each changed entity or relationship gets its current key, which holds the type, for a relationship the ends, the basis
 * of the commit, and the whole new timeline with the basis that inserted each of its segments. Each segment that the
 * previous timeline did not have, equal by start, end, values, transaction time and recorded basis, gets a row, keyed by
 * the commit's basis; each previous segment that the new timeline lacks gets a marker that says the commit's basis
 * superseded it. A marker is keyed like the row it supersedes, so the commit must know the basis that inserted that row,
 * which is not the segment's recorded basis when a later change only cut the segment: the current key therefore holds
 * the inserting basis of each segment, and a commit finds the rows it supersedes without reading the history. A new entity is added to the index of its type, a new relationship to the index of the ends at both of
 * its ends, and each value of a unique attribute that the entity or relationship has and the index does not hold for it
 * yet is added, with the commit's basis. Nothing is written for what a commit leaves as it was.
 *
 * <p><b>Changes.</b> Whenever a commit writes an entity or relationship, it also writes one {@code CHANGE} key, by the
 * commit's basis and the identifier, which says whether it is a relationship, its type, whether the commit created it, and
 * the effective periods in which it changed ({@link ChangedPeriods}); recovery writes them again as it writes the rest
 * (ADR-0029, design D2 and D6 of {@code add-change-feed}). {@link #changes} reads them, and a purge deletes the ones of
 * what it removes, at every basis that inserted a row of it or superseded one.
 *
 * <p><b>Reading.</b> A reader at a basis takes the current key if the basis of its last change is at or below the
 * reader's basis, which is the common case, else it rebuilds the timeline from the rows inserted at or below the basis
 * and not superseded at or below it. Every key and value says the bases it belongs to, so a reader that runs while a batch
 * is being applied reads the same at its basis whichever part of the batch it sees, and a commit never disturbs a view.
 * Listings and navigation read the index ranges and then each entity or relationship at the basis; the indexes give
 * candidates, and the timelines decide. A purge is the exception: it deletes keys that readers at older bases would
 * read.
 *
 * <p>One thread at a time writes, holding the store's writer lock; any number of threads read.
 */
final class Ledger {

    /** Reads everything that was written: every basis is at or below it. */
    static final Basis LATEST = new Basis(Long.MAX_VALUE);

    /** Which relationships of an entity a read asks for: the ones that have it as source, as target, or either. */
    enum Direction {
        /** The entity is the relationship's source. */
        FORWARD,
        /** The entity is the relationship's target. */
        BACKWARD,
        /** The entity is the source or the target. */
        BOTH
    }

    /**
     * A relationship at one end of an entity, as the index of ends lists it.
     *
     * @param relationship the relationship
     * @param type         its type
     * @param forward      whether the entity is its source
     */
    record End(RelationshipId relationship, TypeId type, boolean forward) {
    }

    /**
     * A timeline that a transaction left an entity or relationship with.
     *
     * @param basis    the basis of the transaction
     * @param timeline the timeline known after it
     */
    record Version(Basis basis, Timeline timeline) {
    }

    /** What the current key holds, with the basis that inserted each segment, which only the writer needs. */
    private record Current(Held held, List<Basis> insertedAt) {
    }

    /** What the key of a row or a marker names, without the identifier. */
    private record Ref(long inserted, Instant start) {
    }

    /**
     * A row, with the basis that superseded it, or null if nothing did. Its segment is decoded only when it is asked for,
     * so that a reader at an old basis does not decode the rows that were superseded by then.
     */
    private record Row(Basis inserted, Instant start, byte[] value, Basis superseded) {

        Segment segment() {
            return LogRecords.segment(LedgerCodec.decodeRow(start, value));
        }
    }

    /**
     * An entity or relationship that a basis changed, as the index of changes lists it.
     *
     * @param basis the basis of the transaction
     * @param id    the entity or relationship
     * @param type  the identifier of its type
     * @param created whether the transaction created it
     * @param periods the effective periods in which it changed
     */
    record ChangeEntry(Basis basis, Id id, TypeId type, boolean created, List<EffectivePeriod> periods) {
    }

    private final KeyValueStore store;

    /**
     * Creates the ledger of a key-value store.
     *
     * @param store the store, which holds nothing but what a ledger wrote
     */
    Ledger(KeyValueStore store) {
        this.store = store;
    }

    /** The key-value store, for tests that look at the keys. */
    KeyValueStore store() {
        return store;
    }

    // ------------------------------------------------------------------ reading one entity or relationship

    private Optional<Current> read(String id) {
        return store.get(LedgerCodec.currentKey(id)).map(value -> decode(id, value));
    }

    private static Current decode(String id, byte[] value) {
        LedgerCodec.CurrentValue current = LedgerCodec.decodeCurrent(value);
        Id typed = current.relationship() ? new RelationshipId(id) : new EntityId(id);
        List<SegmentData> segments = current.segments().stream().map(LedgerCodec.Placed::segment).toList();
        Held held = new Held(typed, current.type(), current.source().orElse(null), current.target().orElse(null),
                current.lastBasis(), LogRecords.timeline(segments));
        return new Current(held, current.segments().stream().map(LedgerCodec.Placed::insertedAt).toList());
    }

    /**
     * The entity or relationship that has an identifier, if there is one.
     *
     * @param text the identifier, which entities and relationships share a namespace of
     * @return its identifier typed as an entity's or a relationship's
     */
    Optional<Id> holder(String text) {
        return store.get(LedgerCodec.currentKey(text))
                .map(value -> LedgerCodec.isRelationship(value) ? new RelationshipId(text) : new EntityId(text));
    }

    /**
     * An entity as the latest transaction left it.
     *
     * @param id the entity's identifier
     * @return the entity, or empty if the ledger holds no entity with the identifier
     */
    Optional<Held> entity(EntityId id) {
        return read(id.value()).map(Current::held).filter(held -> !held.isRelationship());
    }

    /**
     * A relationship as the latest transaction left it.
     *
     * @param id the relationship's identifier
     * @return the relationship, or empty if the ledger holds no relationship with the identifier
     */
    Optional<Held> relationship(RelationshipId id) {
        return read(id.value()).map(Current::held).filter(Held::isRelationship);
    }

    /**
     * An entity or relationship as the latest transaction left it.
     *
     * @param id the identifier
     * @return what the ledger holds, or empty
     */
    Optional<Held> held(Id id) {
        return switch (id) {
            case EntityId entity -> entity(entity);
            case RelationshipId relationship -> relationship(relationship);
        };
    }

    /**
     * An entity as it was at a basis.
     *
     * @param id    the entity's identifier
     * @param basis the basis
     * @return the entity with the timeline known after the last transaction at or below the basis that changed it, or
     *         empty if it was not created by then
     */
    Optional<Held> entity(EntityId id, Basis basis) {
        return at(id.value(), false, basis);
    }

    /**
     * A relationship as it was at a basis.
     *
     * @param id    the relationship's identifier
     * @param basis the basis
     * @return the relationship with the timeline known after the last transaction at or below the basis that changed
     *         it, or empty if it was not created by then
     */
    Optional<Held> relationship(RelationshipId id, Basis basis) {
        return at(id.value(), true, basis);
    }

    /**
     * The timeline of an entity or relationship at a basis.
     *
     * @param id    the identifier
     * @param basis the basis
     * @return the timeline; empty if it was not created by then
     */
    Timeline timelineAt(Id id, Basis basis) {
        return at(id.value(), id instanceof RelationshipId, basis).map(Held::timeline).orElse(Timeline.EMPTY);
    }

    private Optional<Held> at(String id, boolean relationship, Basis basis) {
        Optional<Current> read = read(id);
        if (read.isEmpty() || read.get().held().isRelationship() != relationship) {
            return Optional.empty();
        }
        Held current = read.get().held();
        if (current.basis().compareTo(basis) <= 0) {
            return Optional.of(current);
        }
        return rebuilt(current, basis);
    }

    /** The timeline from the rows inserted at or below a basis and not superseded at or below it. */
    private Optional<Held> rebuilt(Held current, Basis basis) {
        List<Row> rows = rows(current.id().value(), basis);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<Segment> live = new ArrayList<>();
        long last = 0;
        for (Row row : rows) {
            last = Math.max(last, row.inserted().value());
            if (row.superseded() != null && row.superseded().compareTo(basis) <= 0) {
                last = Math.max(last, row.superseded().value());
            } else {
                live.add(row.segment());
            }
        }
        live.sort((first, second) -> first.from().compareTo(second.from()));
        return Optional.of(new Held(current.id(), current.type(), current.source(), current.target(), new Basis(last),
                Timeline.restored(live)));
    }

    /** The rows inserted at or below a basis, with the basis that superseded each, as far as markers at or below say. */
    private List<Row> rows(String id, Basis basis) {
        Map<Ref, Basis> superseded = new HashMap<>();
        try (Stream<KeyValueStore.Entry> markers = store.scan(LedgerCodec.markers(id, basis))) {
            markers.forEach(entry -> {
                LedgerCodec.RowKey key = LedgerCodec.parseRowKey(entry.key());
                superseded.put(new Ref(key.inserted().value(), key.start()), LedgerCodec.decodeBasis(entry.value()));
            });
        }
        List<Row> rows = new ArrayList<>();
        try (Stream<KeyValueStore.Entry> found = store.scan(LedgerCodec.rows(id, basis))) {
            found.forEach(entry -> {
                LedgerCodec.RowKey key = LedgerCodec.parseRowKey(entry.key());
                rows.add(new Row(key.inserted(), key.start(), entry.value(),
                        superseded.get(new Ref(key.inserted().value(), key.start()))));
            });
        }
        return rows;
    }

    /**
     * The timelines that an entity or relationship was left with by each transaction that changed it at or below a basis,
     * rebuilt from its rows and markers: what a snapshot file holds.
     *
     * @param id    the identifier
     * @param basis the highest basis
     * @return the timelines, in the order of the bases of the transactions; empty if it was not created by then
     */
    List<Version> versions(Id id, Basis basis) {
        List<Row> rows = rows(id.value(), basis);
        Map<Long, List<Segment>> inserted = new TreeMap<>();
        Map<Long, List<Segment>> removed = new HashMap<>();
        for (Row row : rows) {
            Segment segment = row.segment();
            inserted.computeIfAbsent(row.inserted().value(), key -> new ArrayList<>()).add(segment);
            if (row.superseded() != null && row.superseded().compareTo(basis) <= 0) {
                removed.computeIfAbsent(row.superseded().value(), key -> new ArrayList<>()).add(segment);
                inserted.computeIfAbsent(row.superseded().value(), key -> new ArrayList<>());
            }
        }
        TreeMap<Instant, Segment> live = new TreeMap<>();
        List<Version> versions = new ArrayList<>();
        for (Map.Entry<Long, List<Segment>> change : inserted.entrySet()) {
            for (Segment gone : removed.getOrDefault(change.getKey(), List.of())) {
                live.remove(gone.from());
            }
            for (Segment added : change.getValue()) {
                live.put(added.from(), added);
            }
            versions.add(new Version(new Basis(change.getKey()), Timeline.restored(List.copyOf(live.values()))));
        }
        return versions;
    }

    // ------------------------------------------------------------------ reading ranges

    /** The identifiers of all entities, in the order of the identifiers. */
    List<EntityId> entityIds() {
        List<EntityId> ids = new ArrayList<>();
        try (Stream<KeyValueStore.Entry> entries = store.scan(LedgerCodec.currentKeys())) {
            entries.filter(entry -> !LedgerCodec.isRelationship(entry.value()))
                    .forEach(entry -> ids.add(new EntityId(LedgerCodec.idOfCurrent(entry.key()))));
        }
        Collections.sort(ids);
        return ids;
    }

    /** The identifiers of all relationships, in the order of the identifiers. */
    List<RelationshipId> relationshipIds() {
        List<RelationshipId> ids = new ArrayList<>();
        try (Stream<KeyValueStore.Entry> entries = store.scan(LedgerCodec.currentKeys())) {
            entries.filter(entry -> LedgerCodec.isRelationship(entry.value()))
                    .forEach(entry -> ids.add(new RelationshipId(LedgerCodec.idOfCurrent(entry.key()))));
        }
        Collections.sort(ids);
        return ids;
    }

    /**
     * The entities of some types as they were at a basis, read through the index of types.
     *
     * @param types the identifiers of the types: a type and its subtypes
     * @param basis the basis
     * @return the entities that were created at or below the basis, in the order of their identifiers
     */
    List<Held> entities(Set<TypeId> types, Basis basis) {
        List<EntityId> ids = new ArrayList<>();
        for (TypeId type : types) {
            try (Stream<KeyValueStore.Entry> entries = store.scan(LedgerCodec.typeKeys(type))) {
                entries.filter(entry -> LedgerCodec.decodeBasis(entry.value()).compareTo(basis) <= 0)
                        .forEach(entry -> ids.add(new EntityId(LedgerCodec.entityOfTypeKey(entry.key()))));
            }
        }
        Collections.sort(ids);
        List<Held> found = new ArrayList<>();
        for (EntityId id : ids) {
            entity(id, basis).ifPresent(found::add);
        }
        return found;
    }

    /**
     * The relationships of some types that have an entity at an end, as they were at a basis, read through the index of
     * ends.
     *
     * @param end       the entity
     * @param types     the identifiers of the relationship types: a type and its subtypes
     * @param direction whether the entity is the source, the target, or either
     * @param basis     the basis
     * @return the relationships that were created at or below the basis, in the order of their identifiers, each once
     */
    List<Held> relationships(EntityId end, Set<TypeId> types, Direction direction, Basis basis) {
        TreeSet<RelationshipId> ids = new TreeSet<>();
        for (TypeId type : types) {
            for (boolean forward : new boolean[] {true, false}) {
                boolean wanted = direction == Direction.BOTH || (direction == Direction.FORWARD) == forward;
                if (!wanted) {
                    continue;
                }
                try (Stream<KeyValueStore.Entry> entries = store.scan(LedgerCodec.endKeys(end.value(), type, forward))) {
                    entries.filter(entry -> LedgerCodec.decodeBasis(entry.value()).compareTo(basis) <= 0)
                            .forEach(entry -> ids.add(
                                    new RelationshipId(LedgerCodec.parseEndKey(entry.key()).relationship())));
                }
            }
        }
        List<Held> found = new ArrayList<>();
        for (RelationshipId id : ids) {
            relationship(id, basis).ifPresent(found::add);
        }
        return found;
    }

    /**
     * Every relationship that has an entity at an end, of any type, as the index of ends lists it, without reading the
     * relationships.
     *
     * @param entity the entity
     * @param basis  the highest basis that added a relationship
     * @return the entries, in the order of the relationship identifiers, a relationship that has the entity at both ends
     *         twice, the entry of its source first
     */
    List<End> ends(EntityId entity, Basis basis) {
        List<End> ends = new ArrayList<>();
        try (Stream<KeyValueStore.Entry> entries = store.scan(LedgerCodec.endKeys(entity.value()))) {
            entries.filter(entry -> LedgerCodec.decodeBasis(entry.value()).compareTo(basis) <= 0).forEach(entry -> {
                LedgerCodec.EndKey key = LedgerCodec.parseEndKey(entry.key());
                ends.add(new End(new RelationshipId(key.relationship()), key.type(), key.forward()));
            });
        }
        ends.sort((first, second) -> {
            int order = first.relationship().compareTo(second.relationship());
            return order != 0 ? order : Boolean.compare(second.forward(), first.forward());
        });
        return ends;
    }

    /**
     * The identifiers of the entities or relationships that have or had a value of a unique attribute, read through the
     * index of unique values, without reading them. They are candidates: whether they have the value at an effective
     * time is for their timelines to say.
     *
     * @param attribute the unique attribute
     * @param value     the value; numbers that are equal are the same value whatever their numeric type
     * @param basis     the highest basis that added the value to them
     * @return the identifiers, in order
     */
    List<String> uniqueIds(AttributeId attribute, Value value, Basis basis) {
        TreeSet<String> ids = new TreeSet<>();
        try (Stream<KeyValueStore.Entry> entries = store.scan(LedgerCodec.uniqueKeys(attribute, value))) {
            entries.filter(entry -> LedgerCodec.decodeBasis(entry.value()).compareTo(basis) <= 0)
                    .forEach(entry -> ids.add(LedgerCodec.idOfUniqueKey(entry.key())));
        }
        return List.copyOf(ids);
    }

    /**
     * The entities or relationships that have or had a value of a unique attribute, as they were at a basis.
     *
     * @param attribute     the unique attribute
     * @param value         the value; numbers that are equal are the same value whatever their numeric type
     * @param relationships {@code true} to find relationships, {@code false} to find entities
     * @param basis         the basis
     * @return the candidates that were created at or below the basis, in the order of their identifiers
     * @see #uniqueIds
     */
    List<Held> uniqueHolders(AttributeId attribute, Value value, boolean relationships, Basis basis) {
        List<Held> found = new ArrayList<>();
        for (String id : uniqueIds(attribute, value, basis)) {
            at(id, relationships, basis).ifPresent(found::add);
        }
        return found;
    }

    // ------------------------------------------------------------------ reading changes

    /**
     * What the bases after one basis, up to and including another, changed, read from the index of changes: the entities
     * and relationships that each basis wrote, with their types, whether they were created and the periods in which they
     * changed.
     *
     * @param after the basis before the first one that is wanted, which must be below {@link Long#MAX_VALUE}
     * @param upTo  the last basis that is wanted
     * @return the entries, in the order of the bases, and within a basis in the order of the keys, which is not the order
     *         of the identifiers as text
     */
    List<ChangeEntry> changes(Basis after, Basis upTo) {
        List<ChangeEntry> entries = new ArrayList<>();
        try (Stream<KeyValueStore.Entry> found = store.scan(LedgerCodec.changeKeys(after, upTo))) {
            found.forEach(entry -> {
                LedgerCodec.ChangeKey key = LedgerCodec.parseChangeKey(entry.key());
                LedgerCodec.ChangeValue value = LedgerCodec.decodeChange(entry.value());
                Id id = value.relationship() ? new RelationshipId(key.id()) : new EntityId(key.id());
                entries.add(new ChangeEntry(key.basis(), id, value.type(), value.created(), value.periods()));
            });
        }
        return entries;
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes what a commit, or one record of a log or a snapshot file, changes, as one batch applied at once.
     *
     * @param basis   the basis of the transaction
     * @param changes the entities and relationships it creates or changes, with the timelines it leaves them
     * @param index   the schema of the transaction, which says what unique attributes the types have
     * @throws IllegalStateException if a change creates what the ledger holds, or changes what it does not hold
     */
    void write(Basis basis, List<Preparation.Change> changes, SchemaIndex index) {
        write(basis, changes, index, new Batch());
    }

    /**
     * Writes what a commit changes as one batch applied at once, together with other keys that belong to it: a store over
     * a persistent key-value store writes its metadata in the same batch as the state of the commit (ADR-0028), so that
     * the two never disagree after a crash.
     *
     * @param basis   the basis of the transaction
     * @param changes the entities and relationships it creates or changes, with the timelines it leaves them
     * @param index   the schema of the transaction, which says what unique attributes the types have
     * @param batch   the keys that are written with the commit's; the ledger adds its operations to this batch, which
     *                the caller no longer uses
     * @throws IllegalStateException if a change creates what the ledger holds, or changes what it does not hold
     */
    void write(Basis basis, List<Preparation.Change> changes, SchemaIndex index, Batch batch) {
        for (Preparation.Change change : changes) {
            add(batch, basis, change, index);
        }
        if (!batch.isEmpty()) {
            store.apply(batch);
        }
    }

    /**
     * Builds the batch that {@link #write} applies, without applying it.
     *
     * @param basis   the basis of the transaction
     * @param changes the entities and relationships it creates or changes, with the timelines it leaves them
     * @param index   the schema of the transaction
     * @return the batch; empty if the changes leave everything as it was
     * @throws IllegalStateException if a change creates what the ledger holds, or changes what it does not hold
     */
    Batch batch(Basis basis, List<Preparation.Change> changes, SchemaIndex index) {
        Batch batch = new Batch();
        for (Preparation.Change change : changes) {
            add(batch, basis, change, index);
        }
        return batch;
    }

    private void add(Batch batch, Basis basis, Preparation.Change change, SchemaIndex index) {
        String id = change.id().value();
        Optional<Current> previous = read(id);
        if (change.created() == previous.isPresent()) {
            throw new IllegalStateException(change.created()
                    ? "The ledger holds " + id + " already, so a transaction cannot create it."
                    : "The ledger does not hold " + id + ", which a transaction changes.");
        }
        List<Segment> before = previous.map(current -> current.held().timeline().segments()).orElse(List.of());
        List<Basis> beforeInsertedAt = previous.map(Current::insertedAt).orElse(List.of());
        List<Segment> after = change.timeline().segments();
        if (before.equals(after)) {
            return;
        }
        Map<Instant, Integer> beforeByStart = new HashMap<>();
        for (int i = 0; i < before.size(); i++) {
            beforeByStart.put(before.get(i).from(), i);
        }
        boolean[] kept = new boolean[before.size()];
        List<LedgerCodec.Placed> placed = new ArrayList<>();
        for (Segment segment : after) {
            Integer same = beforeByStart.get(segment.from());
            if (same != null && before.get(same).equals(segment)) {
                kept[same] = true;
                placed.add(new LedgerCodec.Placed(beforeInsertedAt.get(same), LogRecords.data(segment)));
            } else {
                SegmentData data = LogRecords.data(segment);
                placed.add(new LedgerCodec.Placed(basis, data));
                batch.put(LedgerCodec.rowKey(id, basis, segment.from()), LedgerCodec.encodeRow(data));
            }
        }
        for (int i = 0; i < before.size(); i++) {
            if (!kept[i]) {
                batch.put(LedgerCodec.supersededKey(id, beforeInsertedAt.get(i), before.get(i).from()),
                        LedgerCodec.encodeBasis(basis));
            }
        }
        boolean relationship = change.id() instanceof RelationshipId;
        batch.put(LedgerCodec.currentKey(id), LedgerCodec.encodeCurrent(new LedgerCodec.CurrentValue(relationship,
                change.type(), Optional.ofNullable(change.source()), Optional.ofNullable(change.target()), basis,
                placed)));
        List<EffectivePeriod> periods = ChangedPeriods.of(before, after);
        if (!periods.isEmpty()) {
            batch.put(LedgerCodec.changeKey(basis, id), LedgerCodec.encodeChange(new LedgerCodec.ChangeValue(
                    relationship, change.type(), change.created(), periods)));
        }
        if (change.created()) {
            byte[] added = LedgerCodec.encodeBasis(basis);
            if (relationship) {
                batch.put(LedgerCodec.endKey(change.source().value(), change.type(), true, id), added);
                batch.put(LedgerCodec.endKey(change.target().value(), change.type(), false, id), added);
            } else {
                batch.put(LedgerCodec.typeKey(change.type(), (EntityId) change.id()), added);
            }
        }
        addUniqueValues(batch, basis, change, index);
    }

    /** Indexes the values of unique attributes that the new timeline has and the index does not hold for the thing yet. */
    private void addUniqueValues(Batch batch, Basis basis, Preparation.Change change, SchemaIndex index) {
        List<Attribute> attributes = change.id() instanceof RelationshipId
                ? index.relationshipType(change.type()).map(RelationshipType::attributes).orElse(List.of())
                : index.entityType(change.type()).map(EntityType::attributes).orElse(List.of());
        Set<ByteBuffer> added = new HashSet<>();
        for (Attribute attribute : attributes) {
            if (!attribute.unique()) {
                continue;
            }
            for (Segment segment : change.timeline().segments()) {
                Value value = segment.values().get(attribute.id());
                if (value == null) {
                    continue;
                }
                byte[] key = LedgerCodec.uniqueKey(attribute.id(), value, change.id().value());
                if (added.add(ByteBuffer.wrap(key)) && (change.created() || store.get(key).isEmpty())) {
                    batch.put(key, LedgerCodec.encodeBasis(basis));
                }
            }
        }
    }

    // ------------------------------------------------------------------ purging

    /**
     * Removes entities and relationships and every key that mentions them, as one batch applied at once: the relationships
     * first and the entities after them, so that a single lookup never finds a relationship whose end is missing. For
     * each, the current key, every row and marker, the index entries (the entity's in the index of types, the
     * relationship's at both of its ends), the entries of the index of unique values for every value it ever held, and
     * its entries in the index of changes, at the bases of its rows and of the markers that superseded them.
     *
     * @param entities      the entities to remove
     * @param relationships the relationships to remove
     * @throws IllegalStateException if the ledger does not hold one of them
     */
    void purge(List<EntityId> entities, List<RelationshipId> relationships) {
        purge(entities, relationships, new Batch());
    }

    /**
     * Removes entities and relationships as {@link #purge(List, List)} does, in the same batch as other keys that belong to
     * the purge: a store over a persistent key-value store writes the purge record and the digests of the IDs, and removes
     * the transactions of idempotency keys, in the batch that deletes the keys of what it removes (ADR-0028).
     *
     * @param entities      the entities to remove
     * @param relationships the relationships to remove
     * @param batch         the keys that are written with the purge's; the ledger adds its deletes to this batch, which
     *                      the caller no longer uses
     * @throws IllegalStateException if the ledger does not hold one of them
     */
    void purge(List<EntityId> entities, List<RelationshipId> relationships, Batch batch) {
        for (RelationshipId id : relationships) {
            purge(batch, id, relationship(id).orElseThrow(() -> missing(id)));
        }
        for (EntityId id : entities) {
            purge(batch, id, entity(id).orElseThrow(() -> missing(id)));
        }
        store.apply(batch);
    }

    private static IllegalStateException missing(Id id) {
        return new IllegalStateException("The ledger does not hold " + id.value() + ", which a purge removes.");
    }

    private void purge(Batch batch, Id id, Held held) {
        String text = id.value();
        batch.delete(LedgerCodec.currentKey(text));
        Set<ByteBuffer> unique = new HashSet<>();
        // The bases that changed it: each inserted a row of it or superseded one, and has a change key.
        Set<Long> changedAt = new TreeSet<>();
        try (Stream<KeyValueStore.Entry> rows = store.scan(LedgerCodec.rows(text, LATEST))) {
            rows.forEach(entry -> {
                batch.delete(entry.key());
                LedgerCodec.RowKey key = LedgerCodec.parseRowKey(entry.key());
                changedAt.add(key.inserted().value());
                Segment segment = LogRecords.segment(LedgerCodec.decodeRow(key.start(), entry.value()));
                segment.values().forEach((attribute, value) -> {
                    byte[] valueKey = LedgerCodec.uniqueKey(attribute, value, text);
                    if (unique.add(ByteBuffer.wrap(valueKey)) && store.get(valueKey).isPresent()) {
                        batch.delete(valueKey);
                    }
                });
            });
        }
        try (Stream<KeyValueStore.Entry> markers = store.scan(LedgerCodec.markers(text, LATEST))) {
            markers.forEach(entry -> {
                batch.delete(entry.key());
                changedAt.add(LedgerCodec.decodeBasis(entry.value()).value());
            });
        }
        changedAt.forEach(basis -> batch.delete(LedgerCodec.changeKey(new Basis(basis), text)));
        if (held.isRelationship()) {
            batch.delete(LedgerCodec.endKey(held.source().value(), held.type(), true, text));
            batch.delete(LedgerCodec.endKey(held.target().value(), held.type(), false, text));
        } else {
            batch.delete(LedgerCodec.typeKey(held.type(), held.entityId()));
        }
    }
}
