package io.github.enricoag1982.cairn.agents;

import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.KnownAt;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.documents.IdLookup;
import io.github.enricoag1982.cairn.documents.Payloads;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tools that read a store through a view (design D2 of {@code add-agent-tools}): an entity, a relationship, the
 * entities of a type, a timeline, navigation and traversal. Every result reports the coordinates it was read at, as
 * {@code {effectiveAt, basis, result}}; a timeline has no effective time, so it reports the basis only.
 */
final class Reads {

    private final Store store;

    Reads(Store store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** {@code cairn_read_entity}. */
    ObjectNode entity(String text) {
        Arguments in = Arguments.parse(text, "id", "effectiveAt", "knownAt");
        String id = in.string("id");
        Instant effectiveAt = in.instant("effectiveAt");
        KnownAt knownAt = in.knownAt();
        if (!in.valid()) {
            return Operations.problemsOnly(in.problems());
        }
        View view = view(effectiveAt, knownAt);
        Optional<Problem> unknown = unknown(view, id, IdLookup.Kind.ENTITY);
        if (unknown.isPresent()) {
            return Operations.problemsOnly(List.of(unknown.get()));
        }
        return result(view, true, view.entity(new EntityId(id)).map(Reads::json).orElse(null));
    }

    /** {@code cairn_read_relationship}. */
    ObjectNode relationship(String text) {
        Arguments in = Arguments.parse(text, "id", "effectiveAt", "knownAt");
        String id = in.string("id");
        Instant effectiveAt = in.instant("effectiveAt");
        KnownAt knownAt = in.knownAt();
        if (!in.valid()) {
            return Operations.problemsOnly(in.problems());
        }
        View view = view(effectiveAt, knownAt);
        Optional<Problem> unknown = unknown(view, id, IdLookup.Kind.RELATIONSHIP);
        if (unknown.isPresent()) {
            return Operations.problemsOnly(List.of(unknown.get()));
        }
        return result(view, true, view.relationship(new RelationshipId(id)).map(Reads::json).orElse(null));
    }

    /** {@code cairn_list_entities}. */
    ObjectNode entities(String text) {
        Arguments in = Arguments.parse(text, "type", "effectiveAt", "knownAt");
        String type = in.string("type");
        Instant effectiveAt = in.instant("effectiveAt");
        KnownAt knownAt = in.knownAt();
        if (!in.valid()) {
            return Operations.problemsOnly(in.problems());
        }
        View view = view(effectiveAt, knownAt);
        if (view.schema().entityType(type).isEmpty()) {
            return Operations.problemsOnly(List.of(unknownEntityType(view.schema(), type)));
        }
        List<EntityState> states = view.entities(type);
        return result(view, true, Json.parse(Payloads.entityStatesToJson(states)));
    }

    /** {@code cairn_timeline}: the versions of an entity or a relationship across effective time. */
    ObjectNode timeline(String text) {
        Arguments in = Arguments.parse(text, "id", "knownAt");
        String id = in.string("id");
        KnownAt knownAt = in.knownAt();
        if (!in.valid()) {
            return Operations.problemsOnly(in.problems());
        }
        View view = view(Instant.EPOCH, knownAt);
        Optional<IdLookup.Known> known = IdLookup.from(view).find(id);
        if (known.isEmpty()) {
            return Operations.problemsOnly(List.of(unknownId(view, id, "entity or relationship")));
        }
        boolean entity = known.get().kind() == IdLookup.Kind.ENTITY;
        JsonNode versions = Json.parse(entity
                ? Payloads.entityStatesToJson(view.timeline(new EntityId(id)))
                : Payloads.relationshipStatesToJson(view.relationshipTimeline(new RelationshipId(id))));
        ObjectNode result = result(view, false, versions);
        result.put("kind", entity ? "entity" : "relationship");
        return result;
    }

    /** {@code cairn_navigate}: the relationships of an entity by a name, and the entities at their other ends. */
    ObjectNode navigate(String text) {
        Arguments in = Arguments.parse(text, "id", "name", "effectiveAt", "knownAt");
        String id = in.string("id");
        String name = in.string("name");
        Instant effectiveAt = in.instant("effectiveAt");
        KnownAt knownAt = in.knownAt();
        if (!in.valid()) {
            return Operations.problemsOnly(in.problems());
        }
        View view = view(effectiveAt, knownAt);
        Optional<Problem> problem = unknown(view, id, IdLookup.Kind.ENTITY)
                .or(() -> unknownName(view.schema(), name));
        if (problem.isPresent()) {
            return Operations.problemsOnly(List.of(problem.get()));
        }
        List<Link> links = view.navigate(new EntityId(id), name);
        return result(view, true, Json.parse(Payloads.linksToJson(links)));
    }

    /** {@code cairn_traverse}: everything reachable by a name, breadth first, up to a depth. */
    ObjectNode traverse(String text) {
        Arguments in = Arguments.parse(text, "id", "name", "maxDepth", "effectiveAt", "knownAt");
        String id = in.string("id");
        String name = in.string("name");
        Integer maxDepth = in.count("maxDepth");
        Instant effectiveAt = in.instant("effectiveAt");
        KnownAt knownAt = in.knownAt();
        if (!in.valid()) {
            return Operations.problemsOnly(in.problems());
        }
        View view = view(effectiveAt, knownAt);
        Optional<Problem> problem = unknown(view, id, IdLookup.Kind.ENTITY)
                .or(() -> unknownName(view.schema(), name));
        if (problem.isPresent()) {
            return Operations.problemsOnly(List.of(problem.get()));
        }
        List<Step> steps = view.traverse(new EntityId(id), name, relationship -> true, maxDepth);
        return result(view, true, Json.parse(Payloads.stepsToJson(steps)));
    }

    // ------------------------------------------------------------------ helpers

    private View view(Instant effectiveAt, KnownAt knownAt) {
        return store.view(new Coordinates(effectiveAt, knownAt));
    }

    private static ObjectNode result(View view, boolean withEffectiveAt, JsonNode result) {
        ObjectNode node = Json.object();
        if (withEffectiveAt) {
            node.put("effectiveAt", view.effectiveAt().toString());
        }
        node.put("basis", view.basis().value());
        if (result == null) {
            node.putNull("result");
        } else {
            node.set("result", result);
        }
        return node;
    }

    private static JsonNode json(EntityState state) {
        return Json.parse(Payloads.toJson(state));
    }

    private static JsonNode json(RelationshipState state) {
        return Json.parse(Payloads.toJson(state));
    }

    /** A problem if the ID is not that of the wanted kind in the view, or of no kind at all; empty if it is. */
    private static Optional<Problem> unknown(View view, String id, IdLookup.Kind wanted) {
        Optional<IdLookup.Known> known = IdLookup.from(view).find(id);
        boolean entity = wanted == IdLookup.Kind.ENTITY;
        String wantedName = entity ? "entity" : "relationship";
        if (known.isEmpty()) {
            return Optional.of(unknownId(view, id, wantedName));
        }
        if (known.get().kind() != wanted) {
            String other = entity ? "relationship" : "entity";
            return Optional.of(new Problem(entity ? ProblemCode.UNKNOWN_ENTITY : ProblemCode.UNKNOWN_RELATIONSHIP,
                    "@arguments.id", "The ID " + id + " is the ID of " + withArticle(other) + ", not of "
                            + withArticle(wantedName) + ".",
                    Optional.of("the ID of " + withArticle(wantedName)), Optional.of("the ID of " + withArticle(other)),
                    Optional.of(entity
                            ? "Use cairn_read_relationship or cairn_timeline for a relationship."
                            : "Use cairn_read_entity or cairn_timeline for an entity.")));
        }
        return Optional.empty();
    }

    private static Problem unknownId(View view, String id, String kind) {
        return new Problem(
                kind.equals("relationship") ? ProblemCode.UNKNOWN_RELATIONSHIP : ProblemCode.UNKNOWN_ENTITY,
                "@arguments.id", "The store has no " + kind + " with the ID " + id + " as known at basis "
                        + view.basis().value() + ".",
                Optional.of("the ID of " + withArticle(kind)), Optional.of(id),
                Optional.of("Use an ID from the receipt of a commit, or from cairn_list_entities."));
    }

    private static String withArticle(String noun) {
        return (noun.startsWith("e") ? "an " : "a ") + noun;
    }

    private static Problem unknownEntityType(Schema schema, String type) {
        List<String> names = schema.entityTypes().stream().map(entityType -> entityType.name()).toList();
        return new Problem(ProblemCode.UNKNOWN_ENTITY_TYPE, "@arguments.type",
                "The schema has no entity type '" + type + "'.", Optional.of(String.join(", ", names)),
                Optional.of(type), Arguments.closest(type, names).map(closest -> "Did you mean '" + closest + "'?"));
    }

    /** A problem if no relationship type has the name, or the inverse name, in the schema. */
    private static Optional<Problem> unknownName(Schema schema, String name) {
        if (schema.relationshipType(name).isPresent() || schema.relationshipTypeByInverseName(name).isPresent()) {
            return Optional.empty();
        }
        TreeSet<String> names = new TreeSet<>();
        List<RelationshipType> types = schema.relationshipTypes();
        for (RelationshipType type : types) {
            names.add(type.name());
            names.add(type.inverseName());
        }
        return Optional.of(new Problem(ProblemCode.UNKNOWN_RELATIONSHIP_TYPE, "@arguments.name",
                "The schema has no relationship type or inverse name '" + name + "'.",
                Optional.of(String.join(", ", new ArrayList<>(names))), Optional.of(name),
                Arguments.closest(name, names).map(closest -> "Did you mean '" + closest + "'?")));
    }
}
