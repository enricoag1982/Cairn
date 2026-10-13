package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lookups in a {@link Schema} by identifier, which entities store: the entity type of a {@link TypeId}, and a type with
 * its subtypes. Built once for a schema and safe for use by several threads.
 */
final class SchemaIndex {

    private final Schema schema;
    private final Map<TypeId, EntityType> types = new HashMap<>();
    private final Map<TypeId, Set<TypeId>> withSubtypes = new ConcurrentHashMap<>();

    SchemaIndex(Schema schema) {
        this.schema = Objects.requireNonNull(schema, "schema");
        schema.entityTypes().forEach(type -> types.put(type.id(), type));
    }

    Schema schema() {
        return schema;
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
}
