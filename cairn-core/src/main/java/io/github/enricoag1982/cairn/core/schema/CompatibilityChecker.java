package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compares the types of one origin in two compiled schemas and classifies every difference (design D6,
 * capability {@code schema-evolution}). Elements are matched by their identifiers.
 */
final class CompatibilityChecker {

    private final CompiledSchema oldSchema;
    private final CompiledSchema newSchema;
    private final String origin;
    private final List<Change> changes = new ArrayList<>();
    private final Set<TypeId> removedTypes = new HashSet<>();

    private CompatibilityChecker(CompiledSchema oldSchema, CompiledSchema newSchema, String origin) {
        this.oldSchema = oldSchema;
        this.newSchema = newSchema;
        this.origin = origin;
    }

    /**
     * Lists the differences in an origin's types from {@code oldSchema} to {@code newSchema}.
     *
     * @param oldSchema the schema before the change
     * @param newSchema the schema after the change, compiled with identifiers matched to {@code oldSchema}
     * @param origin    the name of the origin whose types changed
     */
    static List<Change> diff(CompiledSchema oldSchema, CompiledSchema newSchema, String origin) {
        return new CompatibilityChecker(oldSchema, newSchema, origin).run();
    }

    private List<Change> run() {
        compareTypes();
        compareAttributes();
        compareAttributeSets();
        return List.copyOf(changes);
    }

    private void compareTypes() {
        for (CompiledSchema.TypeEntry old : owned(oldSchema)) {
            if (newSchema.entry(old.id()).isEmpty()) {
                removedTypes.add(old.id());
                changes.add(Change.breaking(old.name(), "removed the " + old.kind().label() + " " + old.name()));
            }
        }
        for (CompiledSchema.TypeEntry type : owned(newSchema)) {
            var old = oldSchema.entry(type.id());
            if (old.isEmpty()) {
                added(type);
            } else {
                changes.addAll(PropertyRules.compare(elementKind(type.kind()), old.get().definition(), type.definition(),
                        type.name(), oldSchema, newSchema));
            }
        }
    }

    private void added(CompiledSchema.TypeEntry type) {
        if (type.definition() instanceof RelationshipTypeDefinition relationship) {
            List<String> required = new ArrayList<>();
            if (relationship.targetsPerSource().minimum() == 1 && existedBefore(relationship.source())) {
                required.add("every existing " + relationship.source() + " to have a target");
            }
            if (relationship.sourcesPerTarget().minimum() == 1 && existedBefore(relationship.target())) {
                required.add("every existing " + relationship.target() + " to have a source");
            }
            if (!required.isEmpty()) {
                changes.add(Change.breaking(type.name(), "added the relationship type " + type.name()
                        + ", which requires " + String.join(" and ", required)));
                return;
            }
        }
        changes.add(Change.compatible(type.name(), "added the " + type.kind().label() + " " + type.name()));
    }

    /** Tells whether entities of a type, or of any of its subtypes, could already exist. */
    private boolean existedBefore(String entityType) {
        TypeId id = newSchema.lookup(Kind.ENTITY_TYPE, entityType).orElseThrow().id();
        return oldSchema.isConcreteEntityType(id)
                || newSchema.subtypeIds(id).stream().anyMatch(oldSchema::isConcreteEntityType);
    }

    private void compareAttributes() {
        Map<AttributeId, CompiledSchema.Declaration> oldDeclarations = ownedDeclarations(oldSchema);
        Map<AttributeId, CompiledSchema.Declaration> newDeclarations = newSchema.declarations();
        oldDeclarations.forEach((id, old) -> {
            Attribute attribute = old.attribute();
            CompiledSchema.Declaration current = newDeclarations.get(id);
            if (current == null) {
                boolean declarerRemoved = oldSchema.entries().stream()
                        .anyMatch(entry -> Names.same(entry.name(), attribute.declaredBy()) && removedTypes.contains(entry.id()));
                if (!declarerRemoved) {
                    changes.add(Change.breaking(Names.path(attribute.declaredBy(), attribute.name()),
                            "removed the attribute " + attribute.name() + " of " + attribute.declaredBy()));
                }
            } else {
                changes.addAll(PropertyRules.compare("attribute", old.definition(), current.definition(),
                        Names.path(current.attribute().declaredBy(), current.attribute().name()), oldSchema, newSchema));
            }
        });
    }

    /**
     * Detects types that existed before and now have more or fewer attributes. Only types that can have data count
     * as existing for added attributes: concrete entity types and relationship types.
     */
    private void compareAttributeSets() {
        Map<AttributeId, List<String>> lost = new LinkedHashMap<>();
        Map<AttributeId, List<String>> gained = new LinkedHashMap<>();
        for (CompiledSchema.TypeEntry type : newSchema.entries()) {
            if (type.kind() == Kind.TRAIT || oldSchema.entry(type.id()).isEmpty()) {
                continue;
            }
            Set<AttributeId> before = ids(oldSchema.effectiveAttributes(type.id()));
            Set<AttributeId> after = ids(newSchema.effectiveAttributes(type.id()));
            before.stream().filter(id -> !after.contains(id) && newSchema.declarations().containsKey(id))
                    .forEach(id -> lost.computeIfAbsent(id, key -> new ArrayList<>()).add(type.name()));
            boolean canHaveData = type.kind() == Kind.RELATIONSHIP_TYPE || oldSchema.isConcreteEntityType(type.id());
            after.stream().filter(id -> !before.contains(id) && canHaveData)
                    .forEach(id -> gained.computeIfAbsent(id, key -> new ArrayList<>()).add(type.name()));
        }
        lost.forEach((id, types) -> {
            Attribute attribute = newSchema.declarations().get(id).attribute();
            changes.add(Change.breaking(Names.path(types.getFirst(), attribute.name()),
                    "the attribute " + attribute.name() + " no longer applies to " + types));
        });
        gained.forEach((id, types) -> {
            Attribute attribute = newSchema.declarations().get(id).attribute();
            String path = Names.path(attribute.declaredBy(), attribute.name());
            boolean existed = oldSchema.declarations().containsKey(id);
            if (existed && (attribute.required() || attribute.unique())) {
                changes.add(Change.breaking(path, "the " + (attribute.required() ? "required" : "unique")
                        + " attribute " + attribute.name() + " now also applies to the existing types " + types));
            } else if (!existed && attribute.required()) {
                changes.add(Change.breaking(path, "added the required attribute " + attribute.name()
                        + " to the existing types " + types));
            } else {
                changes.add(Change.compatible(path, "the attribute " + attribute.name() + " now applies to " + types));
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private List<CompiledSchema.TypeEntry> owned(CompiledSchema schema) {
        return schema.entries().stream().filter(entry -> entry.origin().name().equals(origin)).toList();
    }

    private Map<AttributeId, CompiledSchema.Declaration> ownedDeclarations(CompiledSchema schema) {
        Set<String> declarers = owned(schema).stream().map(entry -> Names.key(entry.name())).collect(Collectors.toSet());
        Map<AttributeId, CompiledSchema.Declaration> result = new LinkedHashMap<>();
        schema.declarations().forEach((id, declaration) -> {
            if (declarers.contains(Names.key(declaration.attribute().declaredBy()))) {
                result.put(id, declaration);
            }
        });
        return result;
    }

    private static Set<AttributeId> ids(List<Attribute> attributes) {
        return attributes.stream().map(Attribute::id).collect(Collectors.toCollection(HashSet::new));
    }

    private static String elementKind(Kind kind) {
        return switch (kind) {
            case ENTITY_TYPE -> "entity";
            case TRAIT -> "trait";
            case RELATIONSHIP_TYPE -> "relationship";
        };
    }
}
