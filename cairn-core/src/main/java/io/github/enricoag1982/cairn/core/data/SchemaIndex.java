package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.schema.AppliedCheck;
import io.github.enricoag1982.cairn.core.schema.AppliedDerivation;
import io.github.enricoag1982.cairn.core.schema.CheckedSchema;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lookups in a {@link Schema} by identifier, which entities and relationships store: the entity or relationship type of
 * a {@link TypeId}, and a type with its subtypes. Built once for a schema and safe for use by several threads.
 */
final class SchemaIndex {

    private final Schema schema;
    private final Map<TypeId, EntityType> types = new HashMap<>();
    private final Map<TypeId, RelationshipType> relationshipTypes = new HashMap<>();
    private final Map<TypeId, Set<TypeId>> withSubtypes = new ConcurrentHashMap<>();
    private final Map<TypeId, Set<TypeId>> relationshipWithSubtypes = new ConcurrentHashMap<>();

    SchemaIndex(Schema schema) {
        this.schema = Objects.requireNonNull(schema, "schema");
        schema.entityTypes().forEach(type -> types.put(type.id(), type));
        schema.relationshipTypes().forEach(type -> relationshipTypes.put(type.id(), type));
    }

    Schema schema() {
        return schema;
    }

    /**
     * The checks that apply to an entity type or relationship type, compiled, in the order of the type's effective checks;
     * none if the schema does not keep compiled checks.
     */
    List<AppliedCheck> checks(TypeId id) {
        return schema instanceof CheckedSchema checked ? checked.checks(id) : List.of();
    }

    /**
     * The derived attributes of an entity type or relationship type, compiled, in the order of the type's attributes;
     * none if the schema does not keep compiled derivations.
     */
    List<AppliedDerivation> derivations(TypeId id) {
        return schema instanceof CheckedSchema checked ? checked.derivations(id) : List.of();
    }

    Optional<EntityType> entityType(TypeId id) {
        return Optional.ofNullable(types.get(id));
    }

    /** The identifiers of an entity type and of all its subtypes. */
    Set<TypeId> typeAndSubtypes(EntityType type) {
        return withSubtypes.computeIfAbsent(type.id(), id -> {
            Set<TypeId> ids = new HashSet<>();
            ids.add(type.id());
            for (String subtype : type.subtypes()) {
                schema.entityType(subtype).ifPresent(found -> ids.add(found.id()));
            }
            return Set.copyOf(ids);
        });
    }

    Optional<RelationshipType> relationshipType(TypeId id) {
        return Optional.ofNullable(relationshipTypes.get(id));
    }

    /** The identifiers of a relationship type and of all its subtypes. */
    Set<TypeId> typeAndSubtypes(RelationshipType type) {
        return relationshipWithSubtypes.computeIfAbsent(type.id(), id -> {
            Set<TypeId> ids = new HashSet<>();
            ids.add(type.id());
            for (String subtype : type.subtypes()) {
                schema.relationshipType(subtype).ifPresent(found -> ids.add(found.id()));
            }
            return Set.copyOf(ids);
        });
    }
}
