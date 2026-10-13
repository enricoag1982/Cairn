package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Trait;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Compiles a valid schema definition into a {@link CompiledSchema}, giving each element an identifier (design D5).
 *
 * <p>Elements keep the identifier of the matching element in the previous schema: a type of the same kind with the
 * same name, or whose name is one of the new type's aliases; an attribute with the same name or alias among the
 * attributes of the type that declared it before. Everything else gets a new identifier.
 */
final class SchemaCompiler {

    /** A compiled schema and the next free identifier. */
    record Result(CompiledSchema schema, long nextId) {
    }

    /** An attribute and the element that declares it. */
    private record Declared(String declarer, AttributeDefinition attribute) {
        String key() {
            return Names.key(declarer) + "." + Names.key(attribute.name());
        }
    }

    private final SchemaDefinition definition;
    private final Function<String, Origin> originOf;
    private final CompiledSchema previous;
    private long nextId;

    private final Map<String, EntityTypeDefinition> entityTypes = new LinkedHashMap<>();
    private final Map<String, TraitDefinition> traits = new LinkedHashMap<>();
    private final Map<String, RelationshipTypeDefinition> relationshipTypes = new LinkedHashMap<>();
    private final Map<String, TypeId> typeIds = new HashMap<>();
    private final Map<String, AttributeId> attributeIds = new HashMap<>();

    private SchemaCompiler(SchemaDefinition definition, Function<String, Origin> originOf, CompiledSchema previous,
            long nextId) {
        this.definition = definition;
        this.originOf = originOf;
        this.previous = previous;
        this.nextId = nextId;
        definition.entityTypes().forEach(type -> entityTypes.put(Names.key(type.name()), type));
        definition.traits().forEach(trait -> traits.put(Names.key(trait.name()), trait));
        definition.relationshipTypes().forEach(type -> relationshipTypes.put(Names.key(type.name()), type));
    }

    /**
     * Compiles a definition.
     *
     * @param definition a valid definition with the types of every origin
     * @param originOf   the origin of each type, by type name
     * @param origins    the origins in the schema
     * @param previous   the schema whose identifiers are kept
     * @param nextId     the first identifier that may be given out
     */
    static Result compile(SchemaDefinition definition, Function<String, Origin> originOf, List<Origin> origins,
            CompiledSchema previous, long nextId) {
        return new SchemaCompiler(definition, originOf, previous, nextId).run(origins);
    }

    private Result run(List<Origin> origins) {
        assignTypeIds();
        assignAttributeIds();
        List<CompiledSchema.TypeEntry> entries = new ArrayList<>();
        List<CompiledSchema.Declaration> declarations = new ArrayList<>();
        List<EntityType> compiledEntities = new ArrayList<>();
        List<Trait> compiledTraits = new ArrayList<>();
        List<RelationshipType> compiledRelationships = new ArrayList<>();

        for (TraitDefinition trait : traits.values()) {
            List<Attribute> attributes = trait.attributes().stream()
                    .map(attribute -> attribute(new Declared(trait.name(), attribute))).toList();
            compiledTraits.add(new Trait(typeId(trait.name()), trait.name(), trait.aliases(), trait.deprecated(),
                    attributes, originOf.apply(trait.name())));
            entries.add(new CompiledSchema.TypeEntry(typeId(trait.name()), Kind.TRAIT, trait.name(), trait,
                    originOf.apply(trait.name())));
            trait.attributes().forEach(attribute -> declarations.add(declaration(new Declared(trait.name(), attribute))));
        }
        for (EntityTypeDefinition type : entityTypes.values()) {
            compiledEntities.add(new EntityType(typeId(type.name()), type.name(), type.aliases(), type.isAbstract(),
                    type.deprecated(), entitySupertypes(type.name()), entitySubtypes(type.name()), type.traits().stream().map(this::canonical).toList(),
                    entityAttributes(type.name()).stream().map(this::attribute).toList(), originOf.apply(type.name())));
            entries.add(new CompiledSchema.TypeEntry(typeId(type.name()), Kind.ENTITY_TYPE, type.name(), type,
                    originOf.apply(type.name())));
            type.attributes().forEach(attribute -> declarations.add(declaration(new Declared(type.name(), attribute))));
        }
        for (RelationshipTypeDefinition type : relationshipTypes.values()) {
            compiledRelationships.add(new RelationshipType(typeId(type.name()), type.name(), type.aliases(),
                    canonical(type.source()), canonical(type.target()), type.targetsPerSource(), type.sourcesPerTarget(),
                    type.inverseName(), type.inverseAliases(), type.kind(), type.symmetric(), type.deprecated(),
                    relationshipSupertypes(type.name()), relationshipSubtypes(type.name()),
                    relationshipAttributes(type.name()).stream().map(this::attribute).toList(),
                    originOf.apply(type.name())));
            entries.add(new CompiledSchema.TypeEntry(typeId(type.name()), Kind.RELATIONSHIP_TYPE, type.name(), type,
                    originOf.apply(type.name())));
            type.attributes().forEach(attribute -> declarations.add(declaration(new Declared(type.name(), attribute))));
        }
        CompiledSchema schema = new CompiledSchema(compiledEntities, compiledTraits, compiledRelationships, origins,
                entries, declarations);
        return new Result(schema, nextId);
    }

    // ------------------------------------------------------------------ identifiers

    private void assignTypeIds() {
        Set<TypeId> used = new HashSet<>();
        Map<String, Kind> kinds = new LinkedHashMap<>();
        Map<String, List<String>> aliases = new HashMap<>();
        entityTypes.values().forEach(type -> { kinds.put(type.name(), Kind.ENTITY_TYPE); aliases.put(type.name(), type.aliases()); });
        traits.values().forEach(trait -> { kinds.put(trait.name(), Kind.TRAIT); aliases.put(trait.name(), trait.aliases()); });
        relationshipTypes.values().forEach(type -> { kinds.put(type.name(), Kind.RELATIONSHIP_TYPE); aliases.put(type.name(), type.aliases()); });
        // Exact names first, so that a rename cannot take the identifier of a type that kept its name.
        kinds.forEach((name, kind) -> previous.exactName(kind, name).ifPresent(entry -> {
            typeIds.put(Names.key(name), entry.id());
            used.add(entry.id());
        }));
        kinds.forEach((name, kind) -> {
            if (typeIds.containsKey(Names.key(name))) {
                return;
            }
            for (String alias : aliases.get(name)) {
                Optional<CompiledSchema.TypeEntry> match = previous.exactName(kind, alias)
                        .filter(entry -> !used.contains(entry.id()));
                if (match.isPresent()) {
                    typeIds.put(Names.key(name), match.get().id());
                    used.add(match.get().id());
                    return;
                }
            }
            typeIds.put(Names.key(name), new TypeId(nextId++));
        });
    }

    private void assignAttributeIds() {
        Map<TypeId, String> newNames = new HashMap<>();
        typeIds.forEach((key, id) -> newNames.put(id, key));
        List<CompiledSchema.Declaration> previousDeclarations = previous.declarations().values().stream()
                .sorted(Comparator.comparingLong(declaration -> declaration.attribute().id().value()))
                .toList();
        Set<AttributeId> used = new HashSet<>();
        for (CompiledSchema.Declaration old : previousDeclarations) {
            Attribute attribute = old.attribute();
            Optional<CompiledSchema.TypeEntry> declarer = previous.entries().stream()
                    .filter(entry -> Names.same(entry.name(), attribute.declaredBy())).findFirst();
            if (declarer.isEmpty() || !newNames.containsKey(declarer.get().id())) {
                continue;
            }
            String newType = newNames.get(declarer.get().id());
            List<Declared> candidates = switch (declarer.get().kind()) {
                case ENTITY_TYPE -> entityTypes.containsKey(newType) ? entityAttributes(newType) : List.of();
                case TRAIT -> traits.containsKey(newType)
                        ? traits.get(newType).attributes().stream().map(a -> new Declared(traits.get(newType).name(), a)).toList()
                        : List.of();
                case RELATIONSHIP_TYPE -> relationshipTypes.containsKey(newType) ? relationshipAttributes(newType) : List.of();
            };
            candidates.stream()
                    .filter(candidate -> Names.same(candidate.attribute().name(), attribute.name())
                            || Names.contains(candidate.attribute().aliases(), attribute.name()))
                    .filter(candidate -> !attributeIds.containsKey(candidate.key()) && !used.contains(attribute.id()))
                    .findFirst()
                    .ifPresent(candidate -> {
                        attributeIds.put(candidate.key(), attribute.id());
                        used.add(attribute.id());
                    });
        }
        List<Declared> all = new ArrayList<>();
        traits.values().forEach(trait -> trait.attributes().forEach(a -> all.add(new Declared(trait.name(), a))));
        entityTypes.values().forEach(type -> type.attributes().forEach(a -> all.add(new Declared(type.name(), a))));
        relationshipTypes.values().forEach(type -> type.attributes().forEach(a -> all.add(new Declared(type.name(), a))));
        for (Declared declared : all) {
            attributeIds.computeIfAbsent(declared.key(), key -> new AttributeId(nextId++));
        }
    }

    private TypeId typeId(String name) {
        return typeIds.get(Names.key(canonical(name)));
    }

    private Attribute attribute(Declared declared) {
        AttributeDefinition definition = declared.attribute();
        return new Attribute(attributeIds.get(declared.key()), definition.name(), definition.aliases(),
                definition.valueType(), definition.required(), definition.unique(), definition.range(),
                definition.deprecated(), declared.declarer());
    }

    private CompiledSchema.Declaration declaration(Declared declared) {
        return new CompiledSchema.Declaration(attribute(declared), declared.attribute());
    }

    // ------------------------------------------------------------------ resolution

    /** The declared name of the type a reference (a name or an alias) points to. */
    private String canonical(String reference) {
        String key = Names.key(reference);
        if (entityTypes.containsKey(key)) {
            return entityTypes.get(key).name();
        }
        if (traits.containsKey(key)) {
            return traits.get(key).name();
        }
        if (relationshipTypes.containsKey(key)) {
            return relationshipTypes.get(key).name();
        }
        for (EntityTypeDefinition type : definition.entityTypes()) {
            if (Names.contains(type.aliases(), reference)) {
                return type.name();
            }
        }
        for (TraitDefinition trait : definition.traits()) {
            if (Names.contains(trait.aliases(), reference)) {
                return trait.name();
            }
        }
        for (RelationshipTypeDefinition type : definition.relationshipTypes()) {
            if (Names.contains(type.aliases(), reference)) {
                return type.name();
            }
        }
        throw new IllegalStateException("unresolved reference in a validated definition: " + reference);
    }

    private List<String> entitySupertypes(String name) {
        List<String> result = new ArrayList<>();
        Optional<String> supertype = entityTypes.get(Names.key(name)).supertype().map(this::canonical);
        while (supertype.isPresent()) {
            result.add(supertype.get());
            supertype = entityTypes.get(Names.key(supertype.get())).supertype().map(this::canonical);
        }
        return result;
    }

    private List<String> relationshipSupertypes(String name) {
        List<String> result = new ArrayList<>();
        Optional<String> supertype = relationshipTypes.get(Names.key(name)).supertype().map(this::canonical);
        while (supertype.isPresent()) {
            result.add(supertype.get());
            supertype = relationshipTypes.get(Names.key(supertype.get())).supertype().map(this::canonical);
        }
        return result;
    }

    private List<String> entitySubtypes(String name) {
        return entityTypes.values().stream().map(EntityTypeDefinition::name)
                .filter(candidate -> Names.contains(entitySupertypes(candidate), name))
                .sorted()
                .toList();
    }

    private List<String> relationshipSubtypes(String name) {
        return relationshipTypes.values().stream().map(RelationshipTypeDefinition::name)
                .filter(candidate -> Names.contains(relationshipSupertypes(candidate), name))
                .sorted()
                .toList();
    }

    /** Supertype attributes first, then those of included traits, then the type's own. */
    private List<Declared> entityAttributes(String name) {
        EntityTypeDefinition type = entityTypes.get(Names.key(name));
        Map<String, Declared> result = new LinkedHashMap<>();
        type.supertype().map(this::canonical).ifPresent(supertype ->
                entityAttributes(supertype).forEach(declared -> result.putIfAbsent(declared.key(), declared)));
        for (String reference : type.traits()) {
            TraitDefinition trait = traits.get(Names.key(canonical(reference)));
            trait.attributes().forEach(attribute -> {
                Declared declared = new Declared(trait.name(), attribute);
                result.putIfAbsent(declared.key(), declared);
            });
        }
        type.attributes().forEach(attribute -> {
            Declared declared = new Declared(type.name(), attribute);
            result.putIfAbsent(declared.key(), declared);
        });
        return List.copyOf(result.values());
    }

    private List<Declared> relationshipAttributes(String name) {
        RelationshipTypeDefinition type = relationshipTypes.get(Names.key(name));
        Map<String, Declared> result = new LinkedHashMap<>();
        type.supertype().map(this::canonical).ifPresent(supertype ->
                relationshipAttributes(supertype).forEach(declared -> result.putIfAbsent(declared.key(), declared)));
        type.attributes().forEach(attribute -> {
            Declared declared = new Declared(type.name(), attribute);
            result.putIfAbsent(declared.key(), declared);
        });
        return List.copyOf(result.values());
    }
}
