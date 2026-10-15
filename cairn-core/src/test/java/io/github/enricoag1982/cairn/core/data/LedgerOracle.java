package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.storage.DataRecord;
import io.github.enricoag1982.cairn.core.storage.Effect;
import io.github.enricoag1982.cairn.core.storage.Journal;
import io.github.enricoag1982.cairn.core.storage.LogRecord;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the engine kept before it kept entities and relationships as keys (ADR-0027): for each of them, the whole
 * timeline that each transaction left it with, by the basis of the transaction. A test attaches it to a store as its
 * journal, or tells it the changes of a preparation, and then asks what a view at any basis must read; the answers come
 * from these timelines alone, not from the ledger.
 */
final class LedgerOracle implements Journal {

    /** An entity or relationship with the timelines it was left with. */
    static final class Recorded {
        final Id id;
        final TypeId type;
        final EntityId source;
        final EntityId target;
        final NavigableMap<Long, Timeline> versions = new TreeMap<>();

        private Recorded(Id id, TypeId type, EntityId source, EntityId target) {
            this.id = id;
            this.type = type;
            this.source = source;
            this.target = target;
        }
    }

    private final Map<Id, Recorded> recorded = new LinkedHashMap<>();
    private long latest;

    @Override
    public void requireWritable() {
    }

    @Override
    public void append(LogRecord record) {
        if (record instanceof DataRecord data) {
            for (Effect effect : data.effects()) {
                record(data.basis(), new Preparation.Change(effect.id(), effect.type(), effect.source().orElse(null),
                        effect.target().orElse(null), effect.created(), LogRecords.timeline(effect.segments())));
            }
        }
    }

    /** Remembers the timelines that a transaction left what it changed with. */
    void record(Basis basis, List<Preparation.Change> changes) {
        changes.forEach(change -> record(basis, change));
    }

    private void record(Basis basis, Preparation.Change change) {
        Recorded found = recorded.computeIfAbsent(change.id(),
                id -> new Recorded(id, change.type(), change.source(), change.target()));
        found.versions.put(basis.value(), change.timeline());
        latest = Math.max(latest, basis.value());
    }

    /** The basis of the last transaction that changed anything. */
    long latest() {
        return latest;
    }

    /** Everything that was recorded, in the order in which it was first recorded. */
    List<Recorded> all() {
        return List.copyOf(recorded.values());
    }

    /** The timeline known after the latest transaction at or below a basis; empty if it was not recorded by then. */
    Timeline at(Id id, Basis basis) {
        Recorded found = recorded.get(id);
        if (found == null) {
            return Timeline.EMPTY;
        }
        Map.Entry<Long, Timeline> entry = found.versions.floorEntry(basis.value());
        return entry == null ? Timeline.EMPTY : entry.getValue();
    }

    /** The basis of the latest transaction at or below a basis that changed it, or empty. */
    long lastChange(Id id, Basis basis) {
        Map.Entry<Long, Timeline> entry = recorded.get(id).versions.floorEntry(basis.value());
        return entry == null ? 0 : entry.getKey();
    }

    /** The entities of some types that were created at or below a basis, in the order of their IDs. */
    List<EntityId> entities(Set<TypeId> types, Basis basis) {
        List<EntityId> found = new ArrayList<>();
        for (Recorded entry : recorded.values()) {
            if (entry.id instanceof EntityId id && types.contains(entry.type)
                    && entry.versions.firstKey() <= basis.value()) {
                found.add(id);
            }
        }
        found.sort(null);
        return found;
    }

    /** The relationships of some types that have an entity at an end and were created at or below a basis. */
    List<RelationshipId> relationships(EntityId end, Set<TypeId> types, Ledger.Direction direction, Basis basis) {
        List<RelationshipId> found = new ArrayList<>();
        for (Recorded entry : recorded.values()) {
            if (entry.id instanceof RelationshipId id && types.contains(entry.type)
                    && entry.versions.firstKey() <= basis.value()) {
                boolean source = entry.source.equals(end);
                boolean target = entry.target.equals(end);
                boolean at = switch (direction) {
                    case FORWARD -> source;
                    case BACKWARD -> target;
                    case BOTH -> source || target;
                };
                if (at) {
                    found.add(id);
                }
            }
        }
        found.sort(null);
        return found;
    }

    /**
     * The IDs of the entities or relationships that held a value of an attribute in some timeline recorded at or below a
     * basis, numbers being equal if they have the same value, in the order of their IDs.
     */
    List<String> held(AttributeId attribute, Value value, Basis basis) {
        List<String> found = new ArrayList<>();
        for (Recorded entry : recorded.values()) {
            boolean held = entry.versions.headMap(basis.value(), true).values().stream()
                    .flatMap(timeline -> timeline.segments().stream())
                    .map(segment -> segment.values().get(attribute))
                    .anyMatch(each -> each != null && Conversions.same(each, value));
            if (held) {
                found.add(entry.id.value());
            }
        }
        found.sort(null);
        return found;
    }
}
