package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.core.storage.SchemaIdentifiers;
import io.github.enricoag1982.cairn.expressions.CompiledCheck;
import io.github.enricoag1982.cairn.expressions.CompiledDerivation;
import io.github.enricoag1982.cairn.expressions.ExpressionCompilationException;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.EffectiveCheck;
import io.github.enricoag1982.cairn.schema.EffectiveRule;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Trait;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.ValueType;
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
 *
 * <p>Recovery compiles a logged definition without matching anything: every element takes the identifier that the
 * logged registration gave it ({@link SchemaIdentifiers}), kept elements and new ones alike, so that recovery does not
 * depend on how the code of the version that recovers matches names and aliases or numbers elements. An element without
 * a logged identifier is a damaged record.
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
    private final ExpressionCompiler expressionCompiler;
    private long nextId;
    /** The identifiers a logged registration holds, by name key; null when matching and numbering. */
    private final Map<String, TypeId> loggedTypes;
    private final Map<String, AttributeId> loggedAttributes;

    private final Map<String, EntityTypeDefinition> entityTypes = new LinkedHashMap<>();
    private final Map<String, TraitDefinition> traits = new LinkedHashMap<>();
    private final Map<String, RelationshipTypeDefinition> relationshipTypes = new LinkedHashMap<>();
    private final Map<String, TypeId> typeIds = new HashMap<>();
    private final Map<String, AttributeId> attributeIds = new HashMap<>();

    private SchemaCompiler(SchemaDefinition definition, Function<String, Origin> originOf, CompiledSchema previous,
            long nextId, SchemaIdentifiers logged, ExpressionCompiler expressionCompiler) {
        this.definition = definition;
        this.originOf = originOf;
        this.previous = previous;
        this.expressionCompiler = expressionCompiler;
        this.nextId = nextId;
        if (logged == null) {
            loggedTypes = null;
            loggedAttributes = null;
        } else {
            loggedTypes = new HashMap<>();
            loggedAttributes = new HashMap<>();
            Set<Long> seen = new HashSet<>();
            for (SchemaIdentifiers.TypeAssignment type : logged.types()) {
                checkLogged(seen, type.id().value(), type.name(), logged.nextId());
                loggedTypes.put(Names.key(type.name()), type.id());
            }
            for (SchemaIdentifiers.AttributeAssignment attribute : logged.attributes()) {
                checkLogged(seen, attribute.id().value(), attribute.declarer() + "." + attribute.name(),
                        logged.nextId());
                loggedAttributes.put(Names.key(attribute.declarer()) + "." + Names.key(attribute.name()),
                        attribute.id());
            }
        }
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
     * @param checks     compiles the checks of the types
     */
    static Result compile(SchemaDefinition definition, Function<String, Origin> originOf, List<Origin> origins,
            CompiledSchema previous, long nextId, ExpressionCompiler checks) {
        return new SchemaCompiler(definition, originOf, previous, nextId, null, checks).run(origins);
    }

    /**
     * Compiles a definition that a log or snapshot file recorded. Every type, trait, relationship type and attribute
     * takes the identifier that the record holds; nothing is matched by name or alias against an earlier schema, and
     * nothing is numbered. The result's next identifier is the logged one.
     *
     * @param definition a definition with the types of every origin
     * @param originOf   the origin of each type, by type name
     * @param origins    the origins in the schema
     * @param logged     the identifiers of every element of the schema after the registration, and the first
     *                   identifier that was free after it
     * @param checks     compiles the checks of the types; a check that it cannot compile is kept as failed
     * @throws IllegalStateException if an element has no logged identifier, if the record gives one identifier to two
     *                               elements, or if one is not below the logged next identifier
     */
    static Result restore(SchemaDefinition definition, Function<String, Origin> originOf, List<Origin> origins,
            SchemaIdentifiers logged, ExpressionCompiler checks) {
        return new SchemaCompiler(definition, originOf, CompiledSchema.EMPTY, logged.nextId(), logged, checks)
                .run(origins);
    }

    private static void checkLogged(Set<Long> seen, long id, String element, long nextId) {
        if (id >= nextId || id < 1) {
            throw new IllegalStateException("The record of the registration gives " + element + " the identifier " + id
                    + ", which is not below its next identifier " + nextId + ".");
        }
        if (!seen.add(id)) {
            throw new IllegalStateException("The record of the registration gives the identifier " + id
                    + " to more than one element, one of them " + element + ".");
        }
    }

    private Result run(List<Origin> origins) {
        assignTypeIds();
        assignAttributeIds();
        List<CompiledSchema.TypeEntry> entries = new ArrayList<>();
        List<CompiledSchema.Declaration> declarations = new ArrayList<>();
        Map<TypeId, List<AppliedCheck>> checks = new HashMap<>();
        Map<TypeId, List<AppliedDerivation>> derivations = new HashMap<>();
        List<EntityType> compiledEntities = new ArrayList<>();
        List<Trait> compiledTraits = new ArrayList<>();
        List<RelationshipType> compiledRelationships = new ArrayList<>();

        for (TraitDefinition trait : traits.values()) {
            List<Attribute> attributes = trait.attributes().stream()
                    .map(attribute -> attribute(new Declared(trait.name(), attribute))).toList();
            compiledTraits.add(new Trait(typeId(trait.name()), trait.name(), trait.aliases(), trait.deprecated(),
                    trait.description(), attributes, originOf.apply(trait.name())));
            entries.add(new CompiledSchema.TypeEntry(typeId(trait.name()), Kind.TRAIT, trait.name(), trait,
                    originOf.apply(trait.name())));
            trait.attributes().forEach(attribute -> declarations.add(declaration(new Declared(trait.name(), attribute))));
        }
        for (EntityTypeDefinition type : entityTypes.values()) {
            List<String> supertypes = entitySupertypes(type.name());
            compiledEntities.add(new EntityType(typeId(type.name()), type.name(), type.aliases(), type.isAbstract(),
                    type.deprecated(), type.description(), supertypes, entitySubtypes(type.name()),
                    type.traits().stream().map(this::canonical).toList(),
                    entityAttributes(type.name()).stream().map(this::attribute).toList(), type.rules(),
                    effectiveRules(type.name(), type.rules(), supertypes, name -> entityTypes.get(Names.key(name)).rules()),
                    type.checks(),
                    effectiveChecks(type.name(), type.checks(), supertypes,
                            name -> entityTypes.get(Names.key(name)).checks()),
                    originOf.apply(type.name())));
            if (!type.isAbstract()) {
                checks.put(typeId(type.name()), applied(compiledEntities.getLast().effectiveChecks(),
                        attributeTypes(entityAttributes(type.name()))));
                derivations.put(typeId(type.name()), appliedDerivations(entityAttributes(type.name())));
            }
            entries.add(new CompiledSchema.TypeEntry(typeId(type.name()), Kind.ENTITY_TYPE, type.name(), type,
                    originOf.apply(type.name())));
            type.attributes().forEach(attribute -> declarations.add(declaration(new Declared(type.name(), attribute))));
        }
        for (RelationshipTypeDefinition type : relationshipTypes.values()) {
            List<String> supertypes = relationshipSupertypes(type.name());
            compiledRelationships.add(new RelationshipType(typeId(type.name()), type.name(), type.aliases(),
                    canonical(type.source()), canonical(type.target()), type.targetsPerSource(), type.sourcesPerTarget(),
                    type.inverseName(), type.inverseAliases(), type.kind(), type.symmetric(), type.outlivesTarget(),
                    type.deprecated(), type.description(), supertypes, relationshipSubtypes(type.name()),
                    relationshipAttributes(type.name()).stream().map(this::attribute).toList(), type.rules(),
                    effectiveRules(type.name(), type.rules(), supertypes,
                            name -> relationshipTypes.get(Names.key(name)).rules()),
                    type.checks(),
                    effectiveChecks(type.name(), type.checks(), supertypes,
                            name -> relationshipTypes.get(Names.key(name)).checks()),
                    originOf.apply(type.name())));
            checks.put(typeId(type.name()), applied(compiledRelationships.getLast().effectiveChecks(),
                    attributeTypes(relationshipAttributes(type.name()))));
            derivations.put(typeId(type.name()), appliedDerivations(relationshipAttributes(type.name())));
            entries.add(new CompiledSchema.TypeEntry(typeId(type.name()), Kind.RELATIONSHIP_TYPE, type.name(), type,
                    originOf.apply(type.name())));
            type.attributes().forEach(attribute -> declarations.add(declaration(new Declared(type.name(), attribute))));
        }
        CompiledSchema schema = new CompiledSchema(compiledEntities, compiledTraits, compiledRelationships, origins,
                entries, declarations, checks, derivations);
        return new Result(schema, nextId);
    }

    // ------------------------------------------------------------------ identifiers

    private void assignTypeIds() {
        if (loggedTypes != null) {
            List<String> names = new ArrayList<>();
            entityTypes.values().forEach(type -> names.add(type.name()));
            traits.values().forEach(trait -> names.add(trait.name()));
            relationshipTypes.values().forEach(type -> names.add(type.name()));
            for (String name : names) {
                TypeId logged = loggedTypes.get(Names.key(name));
                if (logged == null) {
                    throw new IllegalStateException("The record of the registration has no identifier for the type "
                            + name + ".");
                }
                typeIds.put(Names.key(name), logged);
            }
            return;
        }
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
        if (loggedAttributes != null) {
            for (Declared declared : declaredAttributes()) {
                AttributeId logged = loggedAttributes.get(declared.key());
                if (logged == null) {
                    throw new IllegalStateException("The record of the registration has no identifier for the "
                            + "attribute " + declared.declarer() + "." + declared.attribute().name() + ".");
                }
                attributeIds.put(declared.key(), logged);
            }
            return;
        }
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
        for (Declared declared : declaredAttributes()) {
            attributeIds.computeIfAbsent(declared.key(), key -> new AttributeId(nextId++));
        }
    }

    /** Every attribute that the definition declares, with the trait or type that declares it. */
    private List<Declared> declaredAttributes() {
        List<Declared> all = new ArrayList<>();
        traits.values().forEach(trait -> trait.attributes().forEach(a -> all.add(new Declared(trait.name(), a))));
        entityTypes.values().forEach(type -> type.attributes().forEach(a -> all.add(new Declared(type.name(), a))));
        relationshipTypes.values().forEach(type -> type.attributes().forEach(a -> all.add(new Declared(type.name(), a))));
        return all;
    }

    private TypeId typeId(String name) {
        return typeIds.get(Names.key(canonical(name)));
    }

    private Attribute attribute(Declared declared) {
        AttributeDefinition definition = declared.attribute();
        return new Attribute(attributeIds.get(declared.key()), definition.name(), definition.aliases(),
                definition.valueType(), definition.required(), definition.unique(), definition.range(),
                definition.deprecated(), definition.description(), definition.examples(), definition.allowedValues(),
                definition.pattern(), definition.minLength(), definition.maxLength(), definition.derived(),
                declared.declarer());
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

    /** The rules a type names itself, then those of its supertypes, nearest first. */
    private static List<EffectiveRule> effectiveRules(String name, List<RuleReference> own, List<String> supertypes,
            Function<String, List<RuleReference>> rulesOf) {
        List<EffectiveRule> result = new ArrayList<>();
        own.forEach(rule -> result.add(new EffectiveRule(rule, name)));
        for (String supertype : supertypes) {
            rulesOf.apply(supertype).forEach(rule -> result.add(new EffectiveRule(rule, supertype)));
        }
        return result;
    }

    /** The checks a type declares itself, then those of its supertypes, nearest first. */
    private static List<EffectiveCheck> effectiveChecks(String name, List<Check> own, List<String> supertypes,
            Function<String, List<Check>> checksOf) {
        List<EffectiveCheck> result = new ArrayList<>();
        own.forEach(check -> result.add(new EffectiveCheck(check, name)));
        for (String supertype : supertypes) {
            checksOf.apply(supertype).forEach(check -> result.add(new EffectiveCheck(check, supertype)));
        }
        return result;
    }

    /** The value type of each attribute of a type, derived ones included, by the attribute's name: what checks read. */
    private static Map<String, ValueType> attributeTypes(List<Declared> attributes) {
        Map<String, ValueType> types = new LinkedHashMap<>();
        attributes.forEach(declared -> types.put(declared.attribute().name(), declared.attribute().valueType()));
        return types;
    }

    /** The value type of each attribute of a type that is not derived, by the attribute's name: what derivations read. */
    private static Map<String, ValueType> readableTypes(List<Declared> attributes) {
        Map<String, ValueType> types = new LinkedHashMap<>();
        attributes.stream().filter(declared -> declared.attribute().derived().isEmpty())
                .forEach(declared -> types.put(declared.attribute().name(), declared.attribute().valueType()));
        return types;
    }

    /**
     * Compiles the checks that apply to a concrete type against the attributes it has. A check that cannot be compiled is
     * kept with the reason: a schema that was valid when it was registered can fail to compile when it is restored by
     * another version of the expression language. Without an expression language, the checks are kept as unavailable.
     */
    private List<AppliedCheck> applied(List<EffectiveCheck> effective, Map<String, ValueType> attributes) {
        List<AppliedCheck> result = new ArrayList<>();
        for (EffectiveCheck check : effective) {
            if (!expressionCompiler.available()) {
                result.add(new AppliedCheck(check, Optional.empty(), Optional.empty()));
                continue;
            }
            try {
                CompiledCheck compiled = expressionCompiler.compile(check.check().expression(), attributes);
                result.add(new AppliedCheck(check, Optional.of(compiled), Optional.empty()));
            } catch (ExpressionCompilationException e) {
                result.add(new AppliedCheck(check, Optional.empty(), Optional.of(e.getMessage())));
            }
        }
        return result;
    }

    /**
     * Compiles the derived attributes of a concrete type against the attributes it has that are not derived. A
     * derivation that cannot be compiled is kept with the reason: a schema that was valid when it was registered can fail
     * to compile when it is restored by another version of the expression language. Without an expression language, the
     * derivations are kept as unavailable.
     */
    private List<AppliedDerivation> appliedDerivations(List<Declared> attributes) {
        Map<String, ValueType> readable = readableTypes(attributes);
        List<AppliedDerivation> result = new ArrayList<>();
        for (Declared declared : attributes) {
            if (declared.attribute().derived().isEmpty()) {
                continue;
            }
            Attribute attribute = attribute(declared);
            if (!expressionCompiler.available()) {
                result.add(new AppliedDerivation(attribute, Optional.empty(), Optional.empty()));
                continue;
            }
            try {
                CompiledDerivation compiled = expressionCompiler.compileDerivation(declared.attribute().derived().get(),
                        readable, declared.attribute().valueType());
                result.add(new AppliedDerivation(attribute, Optional.of(compiled), Optional.empty()));
            } catch (ExpressionCompilationException e) {
                result.add(new AppliedDerivation(attribute, Optional.empty(), Optional.of(e.getMessage())));
            }
        }
        return result;
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
