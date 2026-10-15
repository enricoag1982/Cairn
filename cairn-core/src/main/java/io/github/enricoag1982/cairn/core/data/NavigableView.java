package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.query.QueryRunner;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The navigation and traversal of relationships that both views of the in-memory store share (design D1 and D2 of
 * {@code add-relationships}). A view says which relationships exist at its effective time; this class turns a name into
 * the relationship types it stands for, picks the end that leads away from the entity, and walks breadth first.
 *
 * <p>Queries run on the evaluator of {@code core.query}, which both views share, through the reads of the view.
 *
 * <p>Navigation reads the relationships of the named type and of its subtypes that have the entity as an end, through
 * the index of ends of the {@link Ledger}, in the direction that the name asks for. A name stands for a relationship
 * type if it is the type's name or an alias, and the direction is from source to target; or for the inverse name or an
 * inverse alias, with the direction from target to source. For a symmetric type, both names lead from both ends.
 */
abstract class NavigableView implements View {

    /** A relationship type that a name stands for, and whether the name is its inverse name. */
    private record Match(RelationshipType type, boolean inverse) {
    }

    /** A version of a relationship that navigation leads along, and the entity at its other end. */
    private record Led(RelationshipState relationship, EntityId other) {
    }

    /** The schema of this view with lookups by identifier. */
    abstract SchemaIndex index();

    /** Names the schema in messages: {@code The schema at basis 3}. */
    abstract String schemaName();

    /**
     * Every version, at any effective time, of the relationships of the given types that have an entity as their source,
     * their target or either, as known at this view's basis.
     *
     * @param types     the identifiers of the relationship types
     * @param end       the entity
     * @param direction whether the entity is the relationships' source, their target, or either
     * @return the states of the versions, ordered by the ID of the relationship and then by the start of the version's
     *         period; relationships whose type the schema of this view does not have are left out
     */
    abstract List<RelationshipState> relationshipVersions(Set<TypeId> types, EntityId end, Ledger.Direction direction);

    /**
     * Called for every navigation from an entity, with the relationship type that the name stands for, before it reads
     * the relationships, so that a view that records the reads of a rule can say what was read (design D1 of
     * {@code add-rule-rechecks}). Does nothing by default.
     *
     * @param from        the entity navigated from
     * @param type        the relationship type that the name resolved to
     * @param inverse     whether the name is the inverse name of the type
     * @param acrossTime  whether the navigation reads every effective time, as {@link #navigateTimeline} does, or the
     *                    view's effective time
     */
    void navigating(EntityId from, RelationshipType type, boolean inverse, boolean acrossTime) {
    }

    /**
     * Called for every state of a relationship that a navigation returns, for the same purpose as
     * {@link #navigating}. Does nothing by default.
     *
     * @param relationship the state returned
     * @param acrossTime   whether it is returned by a navigation across effective time
     */
    void navigated(RelationshipState relationship, boolean acrossTime) {
    }

    /**
     * Finds an entity by a unique key by listing the entities of the key's type at the view's effective time and taking
     * the one that has the value (ADR-0026, design D3 of {@code add-unique-key-references}), so a view that records the
     * reads of a rule records this as it records listing the type: the type key and the entity key of each entity
     * listed.
     */
    @Override
    public Optional<EntityState> entity(UniqueKey key) {
        Objects.requireNonNull(key, "key");
        return switch (UniqueKeys.check(schema(), schemaName(), key)) {
            case UniqueKeys.Invalid invalid -> throw new IllegalArgumentException(invalid.message());
            case UniqueKeys.Valid valid -> findByKey(valid);
        };
    }

    /**
     * Finds the entity that has the value of a valid unique key at the view's effective time, if there is one. This lists
     * the entities of the key's type; a view that records no reads can use the index of unique values instead.
     */
    Optional<EntityState> findByKey(UniqueKeys.Valid valid) {
        for (EntityState state : entities(valid.type().name())) {
            Value held = state.values().get(valid.attribute().name());
            if (held != null && Conversions.same(held, valid.value())) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<Link> navigate(EntityId from, String name) {
        Objects.requireNonNull(from, "from");
        return links(from, matches(name));
    }

    @Override
    public List<RelationshipState> navigateTimeline(EntityId from, String name) {
        Objects.requireNonNull(from, "from");
        List<Match> matches = matches(name);
        for (Match match : matches) {
            navigating(from, match.type(), match.inverse(), true);
        }
        List<RelationshipState> versions = leading(from, matches).stream().map(Led::relationship).toList();
        versions.forEach(version -> navigated(version, true));
        return versions;
    }

    @Override
    public QueryResult query(Query query) {
        return QueryRunner.run(this, query);
    }

    @Override
    public List<Step> traverse(EntityId from, String name, Predicate<RelationshipState> follow, int maxDepth) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(follow, "follow");
        List<Match> matches = matches(name);
        if (maxDepth < 0) {
            throw new IllegalArgumentException("The maximum depth of a traversal is 0 or more, but it is " + maxDepth
                    + ".");
        }
        Set<EntityId> visited = new HashSet<>();
        visited.add(from);
        List<Step> steps = new ArrayList<>();
        List<EntityId> level = List.of(from);
        for (int depth = 1; depth <= maxDepth && !level.isEmpty(); depth++) {
            List<EntityId> next = new ArrayList<>();
            for (EntityId current : level) {
                for (Link link : links(current, matches)) {
                    EntityId reached = link.entity().id();
                    if (!visited.contains(reached) && follow.test(link.relationship())) {
                        visited.add(reached);
                        steps.add(new Step(link.entity(), depth, link.relationship()));
                        next.add(reached);
                    }
                }
            }
            level = next;
        }
        return List.copyOf(steps);
    }

    /** The relationship types a name stands for; fails if there are none. */
    private List<Match> matches(String name) {
        Objects.requireNonNull(name, "name");
        Schema schema = schema();
        List<Match> matches = new ArrayList<>();
        schema.relationshipType(name).ifPresent(type -> matches.add(new Match(type, false)));
        schema.relationshipTypeByInverseName(name).ifPresent(type -> matches.add(new Match(type, true)));
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(schemaName() + " has no relationship type or inverse name '" + name
                    + "'.");
        }
        return matches;
    }

    /** The relationships effective now that lead away from an entity by the matched names, with the entities at the end. */
    private List<Link> links(EntityId from, List<Match> matches) {
        List<Link> links = new ArrayList<>();
        for (Match match : matches) {
            navigating(from, match.type(), match.inverse(), false);
        }
        for (Led led : leading(from, matches)) {
            if (led.relationship().period().contains(effectiveAt())) {
                navigated(led.relationship(), false);
                entity(led.other()).ifPresent(entity -> links.add(new Link(led.relationship(), entity)));
            }
        }
        return List.copyOf(links);
    }

    /**
     * Every version of the relationships that lead away from an entity by the matched names, whenever they are
     * effective, ordered by the ID of the relationship and then by the start of the period. A relationship that more
     * than one match leads along is listed for the first.
     */
    private List<Led> leading(EntityId from, List<Match> matches) {
        Map<RelationshipId, List<Led>> found = new TreeMap<>();
        for (Match match : matches) {
            Set<TypeId> types = index().typeAndSubtypes(match.type());
            Set<TypeId> bothWays = symmetricTypes(match, types);
            Set<String> symmetric = new HashSet<>();
            bothWays.forEach(id -> index().relationshipType(id).ifPresent(type -> symmetric.add(type.name())));
            Set<TypeId> oneWay = new HashSet<>(types);
            oneWay.removeAll(bothWays);
            Map<RelationshipId, List<Led>> mine = new TreeMap<>();
            List<RelationshipState> versions = new ArrayList<>();
            if (!bothWays.isEmpty()) {
                versions.addAll(relationshipVersions(bothWays, from, Ledger.Direction.BOTH));
            }
            if (!oneWay.isEmpty()) {
                versions.addAll(relationshipVersions(oneWay, from,
                        match.inverse() ? Ledger.Direction.BACKWARD : Ledger.Direction.FORWARD));
            }
            for (RelationshipState version : versions) {
                EntityId other = otherEnd(version, from, match.inverse(),
                        symmetric.contains(version.relationshipType()));
                if (other != null && !found.containsKey(version.id())) {
                    mine.computeIfAbsent(version.id(), id -> new ArrayList<>()).add(new Led(version, other));
                }
            }
            found.putAll(mine);
        }
        return found.values().stream().flatMap(List::stream).toList();
    }

    /**
     * The types among those a match covers that are symmetric, which lead from both ends: all of them if the matched type
     * is, else the subtypes that are.
     */
    private Set<TypeId> symmetricTypes(Match match, Set<TypeId> types) {
        Set<TypeId> symmetric = new HashSet<>();
        for (TypeId id : types) {
            Optional<RelationshipType> type = index().relationshipType(id);
            if (type.isPresent() && (match.type().symmetric() || type.get().symmetric())) {
                symmetric.add(id);
            }
        }
        return symmetric;
    }

    /** The end of a relationship that is not the entity navigated from; null if the navigation does not lead there. */
    private static EntityId otherEnd(RelationshipState relationship, EntityId from, boolean inverse,
            boolean symmetric) {
        if (symmetric) {
            return relationship.source().equals(from) ? relationship.target() : relationship.source();
        }
        if (inverse) {
            return relationship.target().equals(from) ? relationship.source() : null;
        }
        return relationship.source().equals(from) ? relationship.target() : null;
    }
}
