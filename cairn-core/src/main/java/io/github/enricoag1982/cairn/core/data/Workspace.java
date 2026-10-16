package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.core.schema.Values;
import io.github.enricoag1982.cairn.core.storage.IdDigests;
import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.KeyResolution;
import io.github.enricoag1982.cairn.data.Ref;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The entities and relationships that a transaction touches, with what it needs to find them (design D5 of
 * {@code add-entity-versions} and D2 of {@code add-relationships}): the store's {@link Ledger}, from which
 * a {@link Working} copy is made at the first touch, the temporary IDs of the ones the transaction creates, and the
 * namespace of IDs, which entities and relationships share. It also resolves the unique keys that writes name entities
 * by (ADR-0026, design D2 of {@code add-unique-key-references}). The parts of a {@link Preparation} work on one
 * workspace.
 *
 * <p>Used by one {@link Preparation}, in the thread that holds the store's writer lock.
 */
final class Workspace {

    private final SchemaIndex index;
    private final Ledger ledger;
    private final Set<String> purgedDigests;
    private final IdGenerator generator;
    private final ProblemLog problems;
    /** The clearance of the transaction, if it has one: a write cannot name an entity by a key it does not cover. */
    private final Optional<Clearance> clearance;
    /** The entities and relationships the transaction touches, in the order of the first touch. */
    private final Map<Id, Working> working = new LinkedHashMap<>();
    /** Entities and relationships share one namespace of temporary IDs. */
    private final Map<TemporaryId, Working> temporaries = new HashMap<>();
    /** What the unique keys that writes name resolved to, in the order of the writes. */
    private final List<KeyResolution> keyResolutions = new ArrayList<>();

    /**
     * Creates the workspace of a transaction.
     *
     * @param index               the schema the transaction is validated against
     * @param ledger              the entities and relationships of the store
     * @param purgedDigests       the digests of the IDs of the entities and relationships that purges removed, which are
     *                            never given to a new entity or relationship
     * @param generator           a copy of the store's ID generator, which the preparation advances
     * @param problems            where problems about references and IDs are reported
     * @param clearance           the clearance of the transaction, if it has one
     */
    Workspace(SchemaIndex index, Ledger ledger, Set<String> purgedDigests, IdGenerator generator,
            ProblemLog problems, Optional<Clearance> clearance) {
        this.index = index;
        this.ledger = ledger;
        this.purgedDigests = purgedDigests;
        this.generator = generator;
        this.problems = problems;
        this.clearance = clearance;
    }

    SchemaIndex index() {
        return index;
    }

    /** The entities and relationships of the store, as committed before the transaction. */
    Ledger ledger() {
        return ledger;
    }

    IdGenerator generator() {
        return generator;
    }

    ProblemLog problems() {
        return problems;
    }

    /** The entities and relationships the transaction touches, in the order of the first touch. */
    Map<Id, Working> working() {
        return working;
    }

    /**
     * Tells whether the transaction has a clearance that does not cover an attribute. A problem about such an attribute
     * carries no value of it (ADR-0036, design D4 of {@code add-attribute-access-control}).
     */
    boolean withheld(Attribute attribute) {
        return clearance.isPresent() && !clearance.get().covers(attribute);
    }

    /**
     * Tells whether the transaction has a clearance that does not cover one of the attributes of a type that an
     * expression reads, so that the expression's failure is reported without the detail of the evaluation.
     *
     * @param type  the type of the thing the expression is evaluated for
     * @param names the names of the attributes that the expression reads
     */
    boolean readsWithheld(SubjectType type, Set<String> names) {
        if (clearance.isEmpty()) {
            return false;
        }
        for (String name : names) {
            if (type.attribute(name).filter(this::withheld).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * What the unique keys that the writes name resolved to, in the order of the writes and, within a relate, the source
     * before the target (ADR-0026): what the receipt of a committed transaction lists.
     */
    List<KeyResolution> keyResolutions() {
        return List.copyOf(keyResolutions);
    }

    // ------------------------------------------------------------------ references

    /** What problems about the target of a write call it: its temporary ID or its ID. */
    static String pathOf(Ref ref) {
        return switch (ref) {
            case TemporaryId temporaryId -> temporaryId.toString();
            case EntityId id -> id.value();
            case RelationshipId id -> id.value();
            case UniqueKey key -> key.toString(); // A write is resolved to the entity's ID before problems name it.
        };
    }

    /** What problems about a reference that a write could not resolve call the write: {@code @write.<position>}. */
    static String writePath(int position) {
        return "@write." + position;
    }

    /**
     * Resolves the unique key that a write names an entity by, if the reference is one, so that the write goes on as if
     * it named the entity's ID (ADR-0026). A key resolves at the start of the write's effective period, against the
     * entities as the store held them before the transaction, never against what this transaction writes, so the result
     * does not depend on the order of the writes. Any other reference is returned as it is. Each resolution is kept for
     * the receipt, with the position of the write.
     *
     * <p>A transaction with a clearance cannot name an entity by a key of an attribute that the clearance does not cover
     * (ADR-0036, design D4 of {@code add-attribute-access-control}): the key is refused with {@code ACCESS_DENIED} before
     * anything is looked up, so the problem is the same whether or not an entity has the value.
     *
     * @param ref      the reference that the write names
     * @param at       the start of the write's effective period
     * @param position the position of the write in the transaction, from 0, which locates the problems
     * @return the reference to go on with: the entity's ID for a key that names one; null, after the problem was
     *         reported, for a key that is invalid, names no entity or is of an attribute that the clearance does not
     *         cover
     */
    Ref resolveKey(Ref ref, Instant at, int position) {
        if (!(ref instanceof UniqueKey key)) {
            return ref;
        }
        String path = writePath(position);
        switch (UniqueKeys.check(index.schema(), "The schema", key)) {
            case UniqueKeys.Invalid invalid -> {
                problems.add(Problems.of(ProblemCode.INVALID_UNIQUE_KEY, path, invalid.message(), invalid.expected(),
                        invalid.found(), invalid.hint()));
                return null;
            }
            case UniqueKeys.Valid valid when clearance.isPresent() && !clearance.get().covers(valid.attribute()) -> {
                String classification = valid.attribute().classification().orElseThrow();
                Set<String> covered = clearance.get().classifications();
                problems.add(Problems.of(ProblemCode.ACCESS_DENIED, path, "The write names an entity by the unique key "
                        + key + ", but the attribute " + valid.attribute().name() + " of " + valid.type().name()
                        + " is classified " + classification + " and the clearance of the transaction does not cover "
                        + classification + ", so the key is not looked up.",
                        "a transaction whose clearance covers " + classification + ", or a reference by ID",
                        covered.isEmpty() ? "a clearance that covers no classification"
                                : "a clearance that covers " + String.join(", ", covered),
                        "Name the entity by its ID or by a unique key of an attribute that the clearance covers, or "
                        + "submit the transaction with a clearance that covers " + classification + "."));
                return null;
            }
            case UniqueKeys.Valid valid -> {
                Set<TypeId> types = index.typeAndSubtypes(valid.type());
                for (Held entity : ledger.uniqueHolders(valid.attribute().id(), valid.value(), false,
                        Ledger.LATEST)) {
                    if (types.contains(entity.type()) && entity.timeline().at(at).filter(valid::heldBy).isPresent()) {
                        keyResolutions.add(new KeyResolution(position, key, entity.entityId()));
                        return entity.entityId();
                    }
                }
                problems.add(Problems.of(ProblemCode.UNIQUE_KEY_NOT_FOUND, path, "No entity has the unique key " + key
                        + " at " + at + ", the start of the write's effective period, in the store as committed "
                        + "before this transaction.", "an entity of " + valid.type().name() + ", or of a subtype, "
                        + "that has the " + valid.attribute().name() + " " + describeValue(valid) + " at " + at,
                        "no such entity", "A value of a unique attribute can belong to another entity at another time; "
                        + "check the value and the start of the period."));
                return null;
            }
        }
    }

    private static String describeValue(UniqueKeys.Valid valid) {
        return Values.describe(valid.value());
    }

    /**
     * Finds the entity or relationship a write targets, or reports that there is none. A unique key must be resolved
     * first, with {@link #resolveKey}.
     */
    Working resolve(Ref ref) {
        Working found = switch (ref) {
            case TemporaryId temporaryId -> resolveTemporaryId(temporaryId);
            case EntityId id -> loadEntity(id, true);
            case RelationshipId id -> loadRelationship(id, true);
            case UniqueKey key -> throw new IllegalStateException("The unique key " + key + " was not resolved.");
        };
        if (found != null) {
            found.written = true;
        }
        return found;
    }

    Working resolveTemporaryId(TemporaryId temporaryId) {
        Working found = temporaries.get(temporaryId);
        if (found == null) {
            problems.add(Problems.of(ProblemCode.UNKNOWN_TEMPORARY_ID, temporaryId.toString(), "The temporary ID "
                    + temporaryId + " is not created by an earlier write of this transaction.",
                    "a temporary ID that an earlier create or relate of this transaction names",
                    temporaryId.toString(), null));
        }
        return found;
    }

    /** Finds an entity the transaction touches or the store holds, and starts to touch it. */
    Working loadEntity(EntityId id, boolean report) {
        Working found = working.get(id);
        if (found != null) {
            return found;
        }
        Held entity = ledger.entity(id).orElse(null);
        if (entity == null) {
            if (report) {
                problems.add(Problems.of(ProblemCode.UNKNOWN_ENTITY, id.value(), "No entity has the ID " + id.value()
                        + ".", "the ID of an entity of the store", id.value(), null));
            }
            return null;
        }
        Working existing = Working.existingEntity(entity, index.entityType(entity.type()).orElse(null));
        working.put(id, existing);
        return existing;
    }

    /**
     * Finds a relationship the transaction touches or the store holds, and starts to touch it together with its ends,
     * whose multiplicities are checked because a relationship of theirs may change.
     */
    Working loadRelationship(RelationshipId id, boolean report) {
        Working found = working.get(id);
        if (found != null) {
            return found;
        }
        Held relationship = ledger.relationship(id).orElse(null);
        if (relationship == null) {
            if (report) {
                problems.add(Problems.of(ProblemCode.UNKNOWN_RELATIONSHIP, id.value(), "No relationship has the ID "
                        + id.value() + ".", "the ID of a relationship of the store", id.value(), null));
            }
            return null;
        }
        Working existing = Working.existingRelationship(relationship,
                index.relationshipType(relationship.type()).orElse(null));
        working.put(id, existing);
        loadEntity(relationship.source(), false);
        loadEntity(relationship.target(), false);
        return existing;
    }

    // ------------------------------------------------------------------ temporary IDs and IDs

    boolean hasTemporaryId(TemporaryId temporaryId) {
        return temporaries.containsKey(temporaryId);
    }

    void putTemporaryId(TemporaryId temporaryId, Working made) {
        temporaries.put(temporaryId, made);
    }

    /** Makes the transaction touch something it created. */
    void touch(Id id, Working made) {
        working.put(id, made);
    }

    /** What holds an ID in the store or in this transaction: {@code an entity} or {@code a relationship}; else null. */
    String holder(String text) {
        EntityId entity = new EntityId(text);
        RelationshipId relationship = new RelationshipId(text);
        Id stored = ledger.holder(text).orElse(null);
        if (working.containsKey(entity) || stored instanceof EntityId) {
            return "an entity";
        }
        if (working.containsKey(relationship) || stored instanceof RelationshipId) {
            return "a relationship";
        }
        return null;
    }

    boolean inStore(String text) {
        return ledger.holder(text).isPresent();
    }

    /**
     * Tells whether a purge removed the entity or relationship that had an ID (capability {@code purge}), by the digest
     * of the ID, which is all the store keeps of it. A store that never purged computes nothing.
     */
    boolean purged(String text) {
        return !purgedDigests.isEmpty() && purgedDigests.contains(IdDigests.of(text));
    }

    /**
     * Draws an ID from the generator's copy that no entity or relationship has, also not one that a caller supplied, and
     * that no purge removed.
     */
    String drawId() {
        String id;
        do {
            id = generator.next();
        } while (holder(id) != null || purged(id));
        return id;
    }
}
