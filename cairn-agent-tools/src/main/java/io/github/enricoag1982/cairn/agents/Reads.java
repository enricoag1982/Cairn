package io.github.enricoag1982.cairn.agents;

import io.github.enricoag1982.cairn.data.Change;
import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.KnownAt;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.data.WithheldKeyException;
import io.github.enricoag1982.cairn.documents.IdLookup;
import io.github.enricoag1982.cairn.documents.Payloads;
import io.github.enricoag1982.cairn.documents.Queries;
import io.github.enricoag1982.cairn.documents.QueryReadResult;
import io.github.enricoag1982.cairn.documents.UniqueKeyReadResult;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tools that read a store through a view (design D2 of {@code add-agent-tools}): an entity, a relationship, the
 * entities of a type, a timeline, navigation and traversal, and queries; and the one that reads the store's changes
 * (design D7 of {@code add-change-feed}). Every result of a read through a view reports the coordinates it was read at,
 * as {@code {effectiveAt, basis, result}}; a timeline has no effective time, so it reports the basis only. The tool that
 * reads the changes reads no view, and returns {@code {changes, next, latestBasis}}.
 *
 * <p>The tools that start from an entity, {@code cairn_read_entity}, {@code cairn_navigate} and {@code cairn_traverse},
 * name it by its ID ({@code id}) or by a unique key ({@code key}, in the form of payloads), exactly one of the two. A key
 * is found at the call's effective time, through the view; one that no entity has then reads as nothing (ADR-0026).
 *
 * <p>Every view the tools read through is restricted to the clearance of the tools (ADR-0036, design D5 of
 * {@code add-attribute-access-control}): the states in a result list the attributes they {@code withheld}, a query that
 * names a withheld attribute is a problem {@code ACCESS_DENIED}, and so is a key of one, in the result and not as an
 * error of the protocol.
 */
final class Reads {

    /** The number of changes that {@code cairn_list_changes} returns if the call gives no limit. */
    static final int DEFAULT_CHANGES = 100;

    /** The largest number of changes that {@code cairn_list_changes} returns. */
    static final int MAXIMUM_CHANGES = 1_000;

    private final Store store;
    private final Clearance clearance;

    Reads(Store store, Clearance clearance) {
        this.store = Objects.requireNonNull(store, "store");
        this.clearance = Objects.requireNonNull(clearance, "clearance");
    }

    /** {@code cairn_read_entity}. */
    ObjectNode entity(String text) {
        Arguments in = Arguments.parse(text, "id", "key", "effectiveAt", "knownAt");
        Optional<String> id = in.optionalString("id");
        Optional<String> key = in.optionalObject("key", KEY_FORM);
        Instant effectiveAt = in.instant("effectiveAt");
        KnownAt knownAt = in.knownAt();
        in.exactlyOne("id", id.isPresent(), "key", key.isPresent());
        if (!in.valid()) {
            return Operations.problemsOnly(in.problems());
        }
        View view = view(effectiveAt, knownAt);
        if (key.isPresent()) {
            return switch (find(view, key.get())) {
                case Found.Invalid invalid -> Operations.problemsOnly(invalid.problems());
                case Found.Nothing nothing -> result(view, true, null);
                case Found.Entity found -> result(view, true, json(found.state()));
            };
        }
        Optional<Problem> unknown = unknown(view, id.get(), IdLookup.Kind.ENTITY);
        if (unknown.isPresent()) {
            return Operations.problemsOnly(List.of(unknown.get()));
        }
        return result(view, true, view.entity(new EntityId(id.get())).map(Reads::json).orElse(null));
    }

    /**
     * {@code cairn_list_changes} (ADR-0029): the changes of the store after a basis, in basis order, at most a limit of
     * them, as {@code {changes, next, latestBasis}}. It reads the store's feed and no view, and only reads, so no approval
     * policy applies. {@code next} is the basis to give as {@code after} to continue: the basis of the last change, or the
     * given one if there is none. The latest basis is read after the changes, so it is never below {@code next}.
     */
    ObjectNode changes(String text) {
        Arguments in = Arguments.parse(text, "after", "limit");
        long after = in.optionalWholeNumber("after", 0, 0, Long.MAX_VALUE);
        int limit = (int) in.optionalWholeNumber("limit", DEFAULT_CHANGES, 1, MAXIMUM_CHANGES);
        if (!in.valid()) {
            return Operations.problemsOnly(in.problems());
        }
        List<Change> changes = store.changes(new Basis(after), limit);
        long next = changes.isEmpty() ? after : changes.getLast().basis().value();
        ObjectNode result = Json.object();
        result.set("changes", Json.parse(Payloads.changesToJson(changes)));
        result.put("next", next);
        result.put("latestBasis", store.latestBasis().value());
        return result;
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
        Arguments in = Arguments.parse(text, "id", "key", "name", "effectiveAt", "knownAt");
        Optional<String> id = in.optionalString("id");
        Optional<String> key = in.optionalObject("key", KEY_FORM);
        String name = in.string("name");
        Instant effectiveAt = in.instant("effectiveAt");
        KnownAt knownAt = in.knownAt();
        in.exactlyOne("id", id.isPresent(), "key", key.isPresent());
        if (!in.valid()) {
            return Operations.problemsOnly(in.problems());
        }
        View view = view(effectiveAt, knownAt);
        Optional<EntityId> start = Optional.empty();
        if (key.isPresent()) {
            Optional<Problem> unknownName = unknownName(view.schema(), name);
            if (unknownName.isPresent()) {
                return Operations.problemsOnly(List.of(unknownName.get()));
            }
            switch (find(view, key.get())) {
                case Found.Invalid invalid -> {
                    return Operations.problemsOnly(invalid.problems());
                }
                case Found.Nothing nothing -> {
                    return result(view, true, Json.parse(Payloads.linksToJson(List.of())));
                }
                case Found.Entity found -> start = Optional.of(found.state().id());
            }
        } else {
            Optional<Problem> problem = unknown(view, id.get(), IdLookup.Kind.ENTITY)
                    .or(() -> unknownName(view.schema(), name));
            if (problem.isPresent()) {
                return Operations.problemsOnly(List.of(problem.get()));
            }
            start = Optional.of(new EntityId(id.get()));
        }
        List<Link> links = view.navigate(start.get(), name);
        return result(view, true, Json.parse(Payloads.linksToJson(links)));
    }

    /**
     * {@code cairn_traverse}: everything reachable by a name, breadth first, up to a depth, or to the end if the call
     * gives none.
     */
    ObjectNode traverse(String text) {
        Arguments in = Arguments.parse(text, "id", "key", "name", "maxDepth", "effectiveAt", "knownAt");
        Optional<String> id = in.optionalString("id");
        Optional<String> key = in.optionalObject("key", KEY_FORM);
        String name = in.string("name");
        OptionalInt maxDepth = in.optionalCount("maxDepth");
        Instant effectiveAt = in.instant("effectiveAt");
        KnownAt knownAt = in.knownAt();
        in.exactlyOne("id", id.isPresent(), "key", key.isPresent());
        if (!in.valid()) {
            return Operations.problemsOnly(in.problems());
        }
        View view = view(effectiveAt, knownAt);
        Optional<EntityId> start = Optional.empty();
        if (key.isPresent()) {
            Optional<Problem> unknownName = unknownName(view.schema(), name);
            if (unknownName.isPresent()) {
                return Operations.problemsOnly(List.of(unknownName.get()));
            }
            switch (find(view, key.get())) {
                case Found.Invalid invalid -> {
                    return Operations.problemsOnly(invalid.problems());
                }
                case Found.Nothing nothing -> {
                    return result(view, true, Json.parse(Payloads.stepsToJson(List.of())));
                }
                case Found.Entity found -> start = Optional.of(found.state().id());
            }
        } else {
            Optional<Problem> problem = unknown(view, id.get(), IdLookup.Kind.ENTITY)
                    .or(() -> unknownName(view.schema(), name));
            if (problem.isPresent()) {
                return Operations.problemsOnly(List.of(problem.get()));
            }
            start = Optional.of(new EntityId(id.get()));
        }
        List<Step> steps = maxDepth.isPresent() ? view.traverse(start.get(), name, maxDepth.getAsInt())
                : view.traverse(start.get(), name);
        return result(view, true, Json.parse(Payloads.stepsToJson(steps)));
    }

    /**
     * {@code cairn_query}: runs a query. The text is read first, and a problem of reading is located in it; then the
     * view checks the query against its schema, and a problem it returns is located in the text by its path. The result
     * holds the columns and the rows, and the coordinates are reported as the other reads report them.
     */
    ObjectNode query(String text) {
        Arguments in = Arguments.parse(text, "query", "effectiveAt", "knownAt");
        String query = in.string("query");
        Instant effectiveAt = in.instant("effectiveAt");
        KnownAt knownAt = in.knownAt();
        if (!in.valid()) {
            return Operations.problemsOnly(in.problems());
        }
        return switch (Queries.read(query)) {
            case QueryReadResult.Invalid invalid -> Operations.problemsOnly(invalid.problems());
            case QueryReadResult.Read read -> {
                View view = view(effectiveAt, knownAt);
                yield switch (view.query(read.query().query())) {
                    case QueryResult.Invalid invalid ->
                            Operations.problemsOnly(read.query().locate(invalid.problems()));
                    case QueryResult.Rows rows -> {
                        ObjectNode result = (ObjectNode) Json.parse(Queries.toJson(rows));
                        result.remove("effectiveAt");
                        result.remove("basis");
                        yield result(view, true, result);
                    }
                };
            }
        };
    }

    // ------------------------------------------------------------------ keys

    private static final String KEY_FORM = "a unique key: {\"entityType\": \"Account\", \"key\": {\"number\": "
            + "\"A-1\"}}";

    /** What a unique key given to a call names. */
    private sealed interface Found {
        /** The entity that has the key's value at the view's effective time. */
        record Entity(EntityState state) implements Found {
        }

        /** No entity has the key's value at the view's effective time: the call reads as nothing. */
        record Nothing() implements Found {
        }

        /** The key cannot be read, or is not valid in the view's schema. */
        record Invalid(List<Problem> problems) implements Found {
        }
    }

    /**
     * Reads the text of a {@code key} argument as payloads read a unique key, and finds the entity that has it at the
     * view's effective time. A problem of reading or of the key is located at {@code @arguments.key}.
     */
    private static Found find(View view, String keyText) {
        UniqueKey key;
        switch (Payloads.readUniqueKey(keyText, view.schema(), "@arguments.key")) {
            case UniqueKeyReadResult.Invalid invalid -> {
                // The text is the arguments' own JSON, so the locations in it would mean nothing to the caller.
                return new Found.Invalid(invalid.problems().stream().map(problem -> new Problem(problem.code(),
                        problem.path(), problem.message(), problem.expected(), problem.found(), problem.hint())).toList());
            }
            case UniqueKeyReadResult.Read read -> key = read.key();
        }
        try {
            return view.entity(key).<Found>map(Found.Entity::new).orElseGet(Found.Nothing::new);
        } catch (WithheldKeyException withheld) {
            return new Found.Invalid(List.of(withheldKey(view, withheld)));
        } catch (IllegalArgumentException invalid) {
            return new Found.Invalid(List.of(invalidKey(view.schema(), key, invalid.getMessage())));
        }
    }

    /**
     * The problem of a key of a unique attribute that the view withholds, because its clearance covers neither the
     * classification that the view's schema gives the attribute nor the one in the store's latest schema (ADR-0036):
     * finding an entity by it would tell its value, so the call is refused as a query that names the attribute is, before
     * anything is read, whether or not an entity has the value.
     */
    private static Problem withheldKey(View view, WithheldKeyException withheld) {
        Set<String> covered = view.clearance().map(Clearance::classifications).orElse(Set.of());
        return new Problem(ProblemCode.ACCESS_DENIED, "@arguments.key", "The attribute " + withheld.attribute() + " of "
                + withheld.entityType() + " is classified " + withheld.classification()
                + ", which the clearance of this server does not cover, so an entity cannot be found by it.",
                Optional.of("a clearance that covers " + withheld.classification() + ", or the ID of the entity"),
                Optional.of(covered.isEmpty() ? "a clearance that covers no classification"
                        : "a clearance that covers " + String.join(", ", covered)),
                Optional.of("Name the entity by its ID, or by a unique key of an attribute without a classification "
                        + "or with one that the clearance covers."));
    }

    /** The problem of a key that the schema of the view does not accept, with the unique attributes of its type. */
    private static Problem invalidKey(Schema schema, UniqueKey key, String message) {
        Optional<EntityType> type = schema.entityType(key.entityType());
        List<String> unique = type.map(found -> found.attributes().stream().filter(Attribute::unique)
                .map(Attribute::name).toList()).orElse(List.of());
        String expected = type.isEmpty() ? "the name or an alias of an entity type"
                : unique.isEmpty() ? "an attribute of " + type.get().name() + " that is unique, but it has none"
                : "one of " + String.join(", ", unique);
        Optional<String> hint = type.isEmpty()
                ? Arguments.closest(key.entityType(), schema.entityTypes().stream().map(EntityType::name).toList())
                        .map(closest -> "Did you mean '" + closest + "'?")
                : Optional.of(unique.isEmpty() ? "Name the entity by its ID."
                        : "Name the entity by one of its unique attributes, or by its ID.");
        return new Problem(ProblemCode.INVALID_UNIQUE_KEY, "@arguments.key", message, Optional.of(expected),
                Optional.of(key.toString()), hint);
    }

    // ------------------------------------------------------------------ helpers

    private View view(Instant effectiveAt, KnownAt knownAt) {
        return store.view(new Coordinates(effectiveAt, knownAt)).restrictedTo(clearance);
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
