package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.query.QueryRunner;
import io.github.enricoag1982.cairn.core.query.Withholding;
import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.data.WithheldKeyException;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A view restricted to a clearance (ADR-0036, design D2 and D3 of {@code add-attribute-access-control}): it reads through
 * another view and withholds, from every state that view returns, the values of the attributes whose classification the
 * clearance does not cover. It is written once, over the reads that every view has, so that every store, in memory,
 * durable or in RocksDB, withholds alike.
 *
 * <p>Every method that returns states restricts them: entities, entities by unique key, timelines, relationships and
 * their timelines, navigation, navigation across time and traversals, whose condition sees the restricted state of each
 * relationship. A query runs on the evaluator that every view shares, through this view's own reads, so its rows hold
 * restricted states, and its check knows the clearance and refuses to name an attribute that is withheld. A state lists
 * the names of the attributes it withheld, in the order of the attributes of its type in the schema, whether the version
 * had a value or not.
 *
 * <p>A view withholds an attribute unless its clearance covers the attribute's classification both in the view's schema
 * and in the store's latest schema, matched by the attribute's identifier, which a rename does not change (design D2 of
 * {@code add-attribute-access-control}). A view at an old basis, from before an attribute was classified, so withholds
 * what is classified now; a classification that has been removed since still applies to the views of the time that it
 * held. Restricting, the query check and the check of a unique key all follow this rule.
 *
 * <p>Restricting a restricted view restricts the view underneath to the intersection of the clearances, so the states
 * are restricted once. The views that rules read through are never restricted by the engine.
 */
final class RestrictedView implements View {

    private final NavigableView view;
    private final Clearance clearance;
    /** The classifications of the latest schema by attribute, for the schema that was latest when they were last read. */
    private volatile Latest latest = new Latest(null, Map.of());

    /** The classified attributes of a schema, by identifier. */
    private record Latest(Schema schema, Map<AttributeId, String> classifications) {
    }

    /**
     * Restricts a view.
     *
     * @param view      the view whose states are restricted; not itself restricted
     * @param clearance the classifications that may be read
     */
    RestrictedView(NavigableView view, Clearance clearance) {
        this.view = Objects.requireNonNull(view, "view");
        this.clearance = Objects.requireNonNull(clearance, "clearance");
    }

    @Override
    public Instant effectiveAt() {
        return view.effectiveAt();
    }

    @Override
    public Basis basis() {
        return view.basis();
    }

    @Override
    public Schema schema() {
        return view.schema();
    }

    @Override
    public View restrictedTo(Clearance other) {
        Objects.requireNonNull(other, "clearance");
        return new RestrictedView(view, clearance.intersect(other));
    }

    @Override
    public Optional<Clearance> clearance() {
        return Optional.of(clearance);
    }

    @Override
    public Optional<EntityState> entity(EntityId id) {
        return view.entity(id).map(this::restrict);
    }

    /**
     * Finds an entity by a unique key, unless the key is of an attribute that this view withholds: finding by its value
     * would tell it, so the call fails as a key that the schema does not allow does.
     */
    @Override
    public Optional<EntityState> entity(UniqueKey key) {
        Objects.requireNonNull(key, "key");
        if (UniqueKeys.check(schema(), view.schemaName(), key) instanceof UniqueKeys.Valid valid
                && blockedBy(valid.attribute()).isPresent()) {
            String classification = blockedBy(valid.attribute()).orElseThrow();
            throw new WithheldKeyException("The attribute " + valid.attribute().name() + " of " + valid.type().name()
                    + " is classified " + classification + ", which the clearance of "
                    + "this view does not cover, so the unique key " + key + " cannot be used to find an entity.",
                    valid.type().name(), valid.attribute().name(), classification);
        }
        return view.entity(key).map(this::restrict);
    }

    @Override
    public List<EntityState> entities(String entityType) {
        return view.entities(entityType).stream().map(this::restrict).toList();
    }

    @Override
    public List<EntityState> timeline(EntityId id) {
        return view.timeline(id).stream().map(this::restrict).toList();
    }

    @Override
    public Optional<RelationshipState> relationship(RelationshipId id) {
        return view.relationship(id).map(this::restrict);
    }

    @Override
    public List<RelationshipState> relationshipTimeline(RelationshipId id) {
        return view.relationshipTimeline(id).stream().map(this::restrict).toList();
    }

    @Override
    public List<Link> navigate(EntityId from, String name) {
        return view.navigate(from, name).stream()
                .map(link -> new Link(restrict(link.relationship()), restrict(link.entity()))).toList();
    }

    @Override
    public List<RelationshipState> navigateTimeline(EntityId from, String name) {
        return view.navigateTimeline(from, name).stream().map(this::restrict).toList();
    }

    @Override
    public List<Step> traverse(EntityId from, String name, Predicate<RelationshipState> follow, int maxDepth) {
        Objects.requireNonNull(follow, "follow");
        return view.traverse(from, name, relationship -> follow.test(restrict(relationship)), maxDepth).stream()
                .map(step -> new Step(restrict(step.entity()), step.depth(), restrict(step.via()))).toList();
    }

    @Override
    public QueryResult query(Query query) {
        return QueryRunner.run(this, query, Optional.of(new Withholding() {
            @Override
            public Optional<String> blockedBy(Attribute attribute) {
                return RestrictedView.this.blockedBy(attribute);
            }

            @Override
            public Set<String> covered() {
                return clearance.classifications();
            }
        }));
    }

    // ------------------------------------------------------------------ withholding

    /** The state without the values of the attributes that the clearance does not cover, and what it withheld. */
    private EntityState restrict(EntityState state) {
        List<Attribute> attributes = view.schema().entityType(state.entityType()).map(type -> type.attributes())
                .orElse(List.of());
        Set<String> withheld = withheld(attributes);
        if (withheld.isEmpty()) {
            return state;
        }
        return new EntityState(state.id(), state.entityType(), without(state.values(), withheld), state.period(),
                state.recordedAt(), state.recordedBasis(), withheld);
    }

    /** The state without the values of the attributes that the clearance does not cover, and what it withheld. */
    private RelationshipState restrict(RelationshipState state) {
        List<Attribute> attributes = view.schema().relationshipType(state.relationshipType())
                .map(type -> type.attributes()).orElse(List.of());
        Set<String> withheld = withheld(attributes);
        if (withheld.isEmpty()) {
            return state;
        }
        return new RelationshipState(state.id(), state.relationshipType(), state.source(), state.target(),
                without(state.values(), withheld), state.period(), state.recordedAt(), state.recordedBasis(), withheld);
    }

    /** The names of the attributes that this view withholds, in the order of the schema. */
    private Set<String> withheld(List<Attribute> attributes) {
        Set<String> withheld = new LinkedHashSet<>();
        for (Attribute attribute : attributes) {
            if (blockedBy(attribute).isPresent()) {
                withheld.add(attribute.name());
            }
        }
        return withheld;
    }

    /**
     * The classification that makes this view withhold an attribute of its schema: the attribute's classification in the
     * view's schema, or in the store's latest schema, if the clearance does not cover it. Empty if the view reads the
     * attribute.
     */
    private Optional<String> blockedBy(Attribute attribute) {
        Optional<String> own = attribute.classification().filter(classification -> !covers(classification));
        if (own.isPresent()) {
            return own;
        }
        return Optional.ofNullable(latestClassifications().get(attribute.id()))
                .filter(classification -> !covers(classification));
    }

    private boolean covers(String classification) {
        return clearance.classifications().contains(classification);
    }

    /** The classified attributes of the store's latest schema by identifier. */
    private Map<AttributeId, String> latestClassifications() {
        Schema latestSchema = view.latestSchema();
        Latest known = latest;
        if (known.schema() == latestSchema) {
            return known.classifications();
        }
        Map<AttributeId, String> classifications = new HashMap<>();
        latestSchema.entityTypes().forEach(type -> classified(type.attributes(), classifications));
        latestSchema.traits().forEach(trait -> classified(trait.attributes(), classifications));
        latestSchema.relationshipTypes().forEach(type -> classified(type.attributes(), classifications));
        latest = new Latest(latestSchema, Map.copyOf(classifications));
        return latest.classifications();
    }

    private static void classified(List<Attribute> attributes, Map<AttributeId, String> into) {
        for (Attribute attribute : attributes) {
            attribute.classification().ifPresent(classification -> into.putIfAbsent(attribute.id(), classification));
        }
    }

    private static Map<String, Value> without(Map<String, Value> values, Set<String> withheld) {
        Map<String, Value> kept = new LinkedHashMap<>();
        values.forEach((name, value) -> {
            if (!withheld.contains(name)) {
                kept.put(name, value);
            }
        });
        return kept;
    }
}
