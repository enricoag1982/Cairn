package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.Trait;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An immutable, valid schema with identifiers and the resolved type hierarchy (design D4). Besides the public
 * {@link Schema} view it keeps the definitions behind each element, for compatibility checks, and the checks and derived
 * attributes of its types compiled (design D2 of {@code add-cel-expressions} and D3 of {@code add-derived-attributes}).
 */
final class CompiledSchema implements CheckedSchema {

    /** One type with its definition record and owning origin. */
    record TypeEntry(TypeId id, Kind kind, String name, Record definition, Origin origin) {
    }

    /** One attribute declaration: the attribute as seen in the schema and its definition. */
    record Declaration(Attribute attribute, AttributeDefinition definition) {
    }

    static final CompiledSchema EMPTY =
            new CompiledSchema(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());

    private final List<EntityType> entityTypes;
    private final List<Trait> traits;
    private final List<RelationshipType> relationshipTypes;
    private final List<Origin> origins;
    private final Map<TypeId, TypeEntry> entries = new LinkedHashMap<>();
    private final Map<AttributeId, Declaration> declarations = new LinkedHashMap<>();
    private final Map<Kind, Map<String, TypeEntry>> byExactName = new HashMap<>();
    private final Map<Kind, Map<String, TypeEntry>> byNameOrAlias = new HashMap<>();
    private final Map<String, RelationshipType> byInverseName = new HashMap<>();
    private final Map<TypeId, List<Attribute>> effectiveAttributes = new HashMap<>();
    private final Map<TypeId, List<String>> subtypes = new HashMap<>();
    private final Map<TypeId, List<AppliedCheck>> checks;
    private final Map<TypeId, List<AppliedDerivation>> derivations;

    CompiledSchema(
            List<EntityType> entityTypes,
            List<Trait> traits,
            List<RelationshipType> relationshipTypes,
            List<Origin> origins,
            List<TypeEntry> typeEntries,
            List<Declaration> attributeDeclarations,
            Map<TypeId, List<AppliedCheck>> checks,
            Map<TypeId, List<AppliedDerivation>> derivations) {
        this.checks = Map.copyOf(checks);
        this.derivations = Map.copyOf(derivations);
        this.entityTypes = entityTypes.stream().sorted(Comparator.comparing(EntityType::name)).toList();
        this.traits = traits.stream().sorted(Comparator.comparing(Trait::name)).toList();
        this.relationshipTypes = relationshipTypes.stream().sorted(Comparator.comparing(RelationshipType::name)).toList();
        this.origins = origins.stream().sorted(Comparator.comparing(Origin::name)).toList();
        for (Kind kind : Kind.values()) {
            byExactName.put(kind, new HashMap<>());
            byNameOrAlias.put(kind, new HashMap<>());
        }
        for (TypeEntry entry : typeEntries) {
            entries.put(entry.id(), entry);
            byExactName.get(entry.kind()).put(Names.key(entry.name()), entry);
        }
        attributeDeclarations.forEach(declaration -> declarations.put(declaration.attribute().id(), declaration));
        for (EntityType type : this.entityTypes) {
            index(Kind.ENTITY_TYPE, type.id(), type.name(), type.aliases());
            effectiveAttributes.put(type.id(), type.attributes());
            subtypes.put(type.id(), type.subtypes());
        }
        for (Trait trait : this.traits) {
            index(Kind.TRAIT, trait.id(), trait.name(), trait.aliases());
            effectiveAttributes.put(trait.id(), trait.attributes());
        }
        for (RelationshipType type : this.relationshipTypes) {
            index(Kind.RELATIONSHIP_TYPE, type.id(), type.name(), type.aliases());
            effectiveAttributes.put(type.id(), type.attributes());
            subtypes.put(type.id(), type.subtypes());
            byInverseName.put(Names.key(type.inverseName()), type);
            type.inverseAliases().forEach(alias -> byInverseName.putIfAbsent(Names.key(alias), type));
        }
    }

    private void index(Kind kind, TypeId id, String name, List<String> aliases) {
        TypeEntry entry = entries.get(id);
        byNameOrAlias.get(kind).put(Names.key(name), entry);
        aliases.forEach(alias -> byNameOrAlias.get(kind).putIfAbsent(Names.key(alias), entry));
    }

    // ------------------------------------------------------------------ public view

    @Override
    public List<EntityType> entityTypes() {
        return entityTypes;
    }

    @Override
    public List<Trait> traits() {
        return traits;
    }

    @Override
    public List<RelationshipType> relationshipTypes() {
        return relationshipTypes;
    }

    @Override
    public List<Origin> origins() {
        return origins;
    }

    @Override
    public Optional<EntityType> entityType(String nameOrAlias) {
        return lookup(Kind.ENTITY_TYPE, nameOrAlias)
                .flatMap(entry -> entityTypes.stream().filter(type -> type.id().equals(entry.id())).findFirst());
    }

    @Override
    public Optional<Trait> trait(String nameOrAlias) {
        return lookup(Kind.TRAIT, nameOrAlias)
                .flatMap(entry -> traits.stream().filter(trait -> trait.id().equals(entry.id())).findFirst());
    }

    @Override
    public Optional<RelationshipType> relationshipType(String nameOrAlias) {
        return lookup(Kind.RELATIONSHIP_TYPE, nameOrAlias)
                .flatMap(entry -> relationshipTypes.stream().filter(type -> type.id().equals(entry.id())).findFirst());
    }

    @Override
    public Optional<RelationshipType> relationshipTypeByInverseName(String inverseNameOrAlias) {
        return Optional.ofNullable(byInverseName.get(Names.key(inverseNameOrAlias)));
    }

    @Override
    public List<AppliedCheck> checks(TypeId type) {
        return checks.getOrDefault(type, List.of());
    }

    @Override
    public List<AppliedDerivation> derivations(TypeId type) {
        return derivations.getOrDefault(type, List.of());
    }

    @Override
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public String toString() {
        return "Schema" + entries.values().stream().map(TypeEntry::name).sorted().toList();
    }

    // ------------------------------------------------------------------ internal view

    Optional<TypeEntry> lookup(Kind kind, String nameOrAlias) {
        return Optional.ofNullable(byNameOrAlias.get(kind).get(Names.key(nameOrAlias)));
    }

    Optional<TypeEntry> exactName(Kind kind, String name) {
        return Optional.ofNullable(byExactName.get(kind).get(Names.key(name)));
    }

    Optional<TypeEntry> entry(TypeId id) {
        return Optional.ofNullable(entries.get(id));
    }

    Collection<TypeEntry> entries() {
        return entries.values();
    }

    Map<AttributeId, Declaration> declarations() {
        return declarations;
    }

    /** All attributes of an entity type or relationship type, or the declared attributes of a trait. */
    List<Attribute> effectiveAttributes(TypeId id) {
        return effectiveAttributes.getOrDefault(id, List.of());
    }

    /** The identifiers of all subtypes of an entity type or relationship type. */
    List<TypeId> subtypeIds(TypeId id) {
        Kind kind = entries.get(id).kind();
        return subtypes.getOrDefault(id, List.of()).stream()
                .map(name -> exactName(kind, name).orElseThrow().id())
                .toList();
    }

    /** Tells whether a type is a concrete entity type, so that entities of it can exist. */
    boolean isConcreteEntityType(TypeId id) {
        return entityTypes.stream().anyMatch(type -> type.id().equals(id) && !type.isAbstract());
    }

    boolean isSameOrSubtype(TypeId type, TypeId ancestor) {
        return type.equals(ancestor) || entries.containsKey(ancestor) && subtypeIds(ancestor).contains(type);
    }
}
