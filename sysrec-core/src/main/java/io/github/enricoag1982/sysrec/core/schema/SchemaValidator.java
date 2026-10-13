package io.github.enricoag1982.sysrec.core.schema;

import io.github.enricoag1982.sysrec.schema.AttributeDefinition;
import io.github.enricoag1982.sysrec.schema.Bound;
import io.github.enricoag1982.sysrec.schema.EntityTypeDefinition;
import io.github.enricoag1982.sysrec.schema.Multiplicity;
import io.github.enricoag1982.sysrec.schema.Problem;
import io.github.enricoag1982.sysrec.schema.ProblemCode;
import io.github.enricoag1982.sysrec.schema.Range;
import io.github.enricoag1982.sysrec.schema.RelationshipKind;
import io.github.enricoag1982.sysrec.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.sysrec.schema.SchemaDefinition;
import io.github.enricoag1982.sysrec.schema.TraitDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a complete schema definition and reports every problem (capability {@code type-model}, design D3).
 *
 * <p>Phases: names and syntax, references, hierarchies, attributes, relationship rules. A later phase skips only
 * the elements an earlier phase found broken, so one mistake does not cause a cascade of follow-up problems.
 */
final class SchemaValidator {

    private record Named(Kind kind, String name) {
    }

    private record Entry(String name, String owner, String path, String label, boolean inverse) {
    }

    private record Declared(String declarer, String declarerLabel, AttributeDefinition attribute) {
    }

    private final SchemaDefinition definition;
    private final List<Problem> problems = new ArrayList<>();
    private final Map<String, Named> types = new HashMap<>();
    private final Map<String, String> entitySupertype = new HashMap<>();
    private final Map<String, List<String>> entityTraits = new HashMap<>();
    private final Map<String, String> relationshipSupertype = new HashMap<>();
    private final Map<String, String[]> relationshipEnds = new HashMap<>();
    private final Set<String> broken = new HashSet<>();
    private final Map<String, Map<String, Declared>> effective = new HashMap<>();
    private final Set<String> reportedConflicts = new HashSet<>();

    private SchemaValidator(SchemaDefinition definition) {
        this.definition = definition;
    }

    static List<Problem> validate(SchemaDefinition definition) {
        return new SchemaValidator(definition).run();
    }

    private List<Problem> run() {
        checkNames();
        resolveReferences();
        checkCycles(entitySupertype, Kind.ENTITY_TYPE);
        checkCycles(relationshipSupertype, Kind.RELATIONSHIP_TYPE);
        checkAttributes();
        checkRelationships();
        return List.copyOf(problems);
    }

    // ------------------------------------------------------------------ phase 1: names

    private void checkNames() {
        List<Entry> typeNames = new ArrayList<>();
        List<Entry> navigationNames = new ArrayList<>();
        for (EntityTypeDefinition type : definition.entityTypes()) {
            nameAndAliases(type.name(), type.aliases(), Kind.ENTITY_TYPE, typeNames);
            type.attributes().forEach(attribute -> attributeNames(type.name(), attribute));
        }
        for (TraitDefinition trait : definition.traits()) {
            nameAndAliases(trait.name(), trait.aliases(), Kind.TRAIT, typeNames);
            trait.attributes().forEach(attribute -> attributeNames(trait.name(), attribute));
        }
        for (RelationshipTypeDefinition type : definition.relationshipTypes()) {
            nameAndAliases(type.name(), type.aliases(), Kind.RELATIONSHIP_TYPE, typeNames);
            type.attributes().forEach(attribute -> attributeNames(type.name(), attribute));
            String inversePath = type.inverseName().isEmpty() ? type.name() : Names.path(type.name(), type.inverseName());
            if (!Names.isValid(type.inverseName())) {
                invalidName(inversePath, "inverse name", type.inverseName());
            }
            for (String alias : type.inverseAliases()) {
                if (!Names.isValid(alias)) {
                    invalidName(inversePath, "inverse alias", alias);
                }
            }
            navigationNames.add(new Entry(type.name(), type.name(), type.name(), "relationship type " + type.name(), false));
            type.aliases().forEach(alias -> navigationNames.add(
                    new Entry(alias, type.name(), type.name(), "alias " + alias + " of relationship type " + type.name(), false)));
            if (!type.inverseName().isEmpty()) {
                navigationNames.add(new Entry(type.inverseName(), type.name(), inversePath,
                        "inverse name " + type.inverseName() + " of " + type.name(), true));
            }
            type.inverseAliases().forEach(alias -> navigationNames.add(new Entry(alias, type.name(), inversePath,
                    "inverse alias " + alias + " of " + type.name(), true)));
        }
        reportDuplicates(typeNames, false);
        reportDuplicates(navigationNames, true);
    }

    private void nameAndAliases(String name, List<String> aliases, Kind kind, List<Entry> entries) {
        if (!Names.isValid(name)) {
            invalidName(name, kind.label() + " name", name);
        }
        entries.add(new Entry(name, name, name, kind.label() + " " + name, false));
        types.putIfAbsent(Names.key(name), new Named(kind, name));
        for (String alias : aliases) {
            if (!Names.isValid(alias)) {
                invalidName(name, "alias", alias);
            }
            entries.add(new Entry(alias, name, name, "alias " + alias + " of " + kind.label() + " " + name, false));
        }
    }

    private void attributeNames(String owner, AttributeDefinition attribute) {
        String path = Names.path(owner, attribute.name());
        if (!Names.isValid(attribute.name())) {
            invalidName(path, "attribute name", attribute.name());
        }
        for (String alias : attribute.aliases()) {
            if (!Names.isValid(alias)) {
                invalidName(path, "attribute alias", alias);
            }
        }
    }

    private void invalidName(String path, String what, String name) {
        problems.add(new Problem(ProblemCode.INVALID_NAME, path, "The " + what + " '" + name
                + "' must start with a letter and contain only letters, digits and underscores (at most 128)."));
    }

    private void reportDuplicates(List<Entry> entries, boolean onlyWithInverse) {
        Map<String, List<Entry>> byKey = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (!entry.name().isEmpty()) {
                byKey.computeIfAbsent(Names.key(entry.name()), key -> new ArrayList<>()).add(entry);
            }
        }
        for (List<Entry> group : byKey.values()) {
            if (group.size() < 2 || onlyWithInverse && group.stream().noneMatch(Entry::inverse)) {
                continue;
            }
            Entry located = group.stream().filter(Entry::inverse).findFirst().orElse(group.getFirst());
            problems.add(new Problem(ProblemCode.DUPLICATE_NAME, located.path(), "The name '" + group.getFirst().name()
                    + "' is used more than once (names ignore case): "
                    + group.stream().map(Entry::label).collect(Collectors.joining(", ")) + "."));
        }
    }

    // ------------------------------------------------------------------ phase 2: references

    private Optional<Named> resolve(String reference) {
        Named named = types.get(Names.key(reference));
        if (named != null) {
            return Optional.of(named);
        }
        for (EntityTypeDefinition type : definition.entityTypes()) {
            if (Names.contains(type.aliases(), reference)) {
                return Optional.of(new Named(Kind.ENTITY_TYPE, type.name()));
            }
        }
        for (TraitDefinition trait : definition.traits()) {
            if (Names.contains(trait.aliases(), reference)) {
                return Optional.of(new Named(Kind.TRAIT, trait.name()));
            }
        }
        for (RelationshipTypeDefinition type : definition.relationshipTypes()) {
            if (Names.contains(type.aliases(), reference)) {
                return Optional.of(new Named(Kind.RELATIONSHIP_TYPE, type.name()));
            }
        }
        return Optional.empty();
    }

    private void resolveReferences() {
        for (EntityTypeDefinition type : definition.entityTypes()) {
            String key = Names.key(type.name());
            type.supertype().ifPresent(reference -> resolveKind(reference, type.name(), "supertype",
                    Kind.ENTITY_TYPE, ProblemCode.SUPERTYPE_NOT_ENTITY_TYPE)
                    .ifPresent(name -> entitySupertype.put(key, Names.key(name))));
            List<String> traits = new ArrayList<>();
            for (String reference : type.traits()) {
                resolveKind(reference, type.name(), "included trait", Kind.TRAIT, ProblemCode.NOT_A_TRAIT)
                        .ifPresent(name -> traits.add(Names.key(name)));
            }
            entityTraits.put(key, traits);
        }
        for (RelationshipTypeDefinition type : definition.relationshipTypes()) {
            String key = Names.key(type.name());
            Optional<String> source = resolveKind(type.source(), type.name(), "source", Kind.ENTITY_TYPE,
                    ProblemCode.RELATIONSHIP_END_NOT_ENTITY_TYPE);
            Optional<String> target = resolveKind(type.target(), type.name(), "target", Kind.ENTITY_TYPE,
                    ProblemCode.RELATIONSHIP_END_NOT_ENTITY_TYPE);
            if (source.isPresent() && target.isPresent()) {
                relationshipEnds.put(key, new String[] {Names.key(source.get()), Names.key(target.get())});
            } else {
                broken.add(key);
            }
            type.supertype().ifPresent(reference -> resolveKind(reference, type.name(), "supertype",
                    Kind.RELATIONSHIP_TYPE, ProblemCode.SUPERTYPE_NOT_RELATIONSHIP_TYPE)
                    .ifPresent(name -> relationshipSupertype.put(key, Names.key(name))));
        }
    }

    private Optional<String> resolveKind(String reference, String owner, String role, Kind expected, ProblemCode wrongKind) {
        if (reference.isEmpty()) {
            problems.add(new Problem(ProblemCode.UNKNOWN_REFERENCE, owner, "The " + role + " of " + owner + " is missing."));
            broken.add(Names.key(owner));
            return Optional.empty();
        }
        Optional<Named> named = resolve(reference);
        if (named.isEmpty()) {
            problems.add(new Problem(ProblemCode.UNKNOWN_REFERENCE, owner,
                    "The " + role + " '" + reference + "' of " + owner + " does not exist."));
            broken.add(Names.key(owner));
            return Optional.empty();
        }
        if (named.get().kind() != expected) {
            problems.add(new Problem(wrongKind, owner, "The " + role + " '" + reference + "' of " + owner + " is a "
                    + named.get().kind().label() + ", but must be a " + expected.label() + "."));
            broken.add(Names.key(owner));
            return Optional.empty();
        }
        return Optional.of(named.get().name());
    }

    // ------------------------------------------------------------------ phase 3: hierarchies

    private void checkCycles(Map<String, String> supertypes, Kind kind) {
        Set<String> done = new HashSet<>();
        for (String start : supertypes.keySet().stream().sorted().toList()) {
            List<String> path = new ArrayList<>();
            String current = start;
            while (current != null && !done.contains(current) && !path.contains(current)) {
                path.add(current);
                current = supertypes.get(current);
            }
            if (current != null && path.contains(current)) {
                List<String> cycle = new ArrayList<>(path.subList(path.indexOf(current), path.size()));
                List<String> names = cycle.stream().map(key -> types.get(key).name()).toList();
                String first = names.stream().sorted().findFirst().orElseThrow();
                problems.add(new Problem(ProblemCode.SUPERTYPE_CYCLE, first, "The supertypes of these "
                        + kind.label() + "s form a cycle: " + String.join(" -> ", names) + " -> " + names.getFirst() + "."));
                broken.addAll(cycle);
            }
            if (current != null && broken.contains(current)) {
                broken.addAll(path);
            }
            done.addAll(path);
        }
    }

    private boolean isBroken(String key, Map<String, String> supertypes) {
        Set<String> seen = new HashSet<>();
        String current = key;
        while (current != null && seen.add(current)) {
            if (broken.contains(current)) {
                return true;
            }
            current = supertypes.get(current);
        }
        return current != null;
    }

    private boolean isSameOrSubtype(String key, String ancestor) {
        Set<String> seen = new HashSet<>();
        String current = key;
        while (current != null && seen.add(current)) {
            if (current.equals(ancestor)) {
                return true;
            }
            current = entitySupertype.get(current);
        }
        return false;
    }

    // ------------------------------------------------------------------ phase 4: attributes

    private void checkAttributes() {
        for (TraitDefinition trait : definition.traits()) {
            checkOwnAttributes(trait.name(), "trait " + trait.name(), trait.attributes());
        }
        for (EntityTypeDefinition type : definition.entityTypes()) {
            checkOwnAttributes(type.name(), "entity type " + type.name(), type.attributes());
            String key = Names.key(type.name());
            if (!isBroken(key, entitySupertype)) {
                entityAttributes(key);
            }
        }
        for (RelationshipTypeDefinition type : definition.relationshipTypes()) {
            checkOwnAttributes(type.name(), "relationship type " + type.name(), type.attributes());
            String key = Names.key(type.name());
            if (!isBroken(key, relationshipSupertype)) {
                relationshipAttributes(key);
            }
        }
    }

    private Map<String, Declared> entityAttributes(String key) {
        Map<String, Declared> cached = effective.get(key);
        if (cached != null) {
            return cached;
        }
        EntityTypeDefinition type = entityType(key);
        Map<String, Declared> result = new LinkedHashMap<>();
        String supertype = entitySupertype.get(key);
        if (supertype != null) {
            entityAttributes(supertype).values().forEach(declared -> add(result, declared, type.name()));
        }
        for (String traitKey : entityTraits.getOrDefault(key, List.of())) {
            TraitDefinition trait = trait(traitKey);
            trait.attributes().forEach(attribute ->
                    add(result, new Declared(trait.name(), "trait " + trait.name(), attribute), type.name()));
        }
        type.attributes().forEach(attribute ->
                add(result, new Declared(type.name(), "entity type " + type.name(), attribute), type.name()));
        effective.put(key, result);
        return result;
    }

    private Map<String, Declared> relationshipAttributes(String key) {
        Map<String, Declared> cached = effective.get(key);
        if (cached != null) {
            return cached;
        }
        RelationshipTypeDefinition type = relationshipType(key);
        Map<String, Declared> result = new LinkedHashMap<>();
        String supertype = relationshipSupertype.get(key);
        if (supertype != null) {
            relationshipAttributes(supertype).values().forEach(declared -> add(result, declared, type.name()));
        }
        type.attributes().forEach(attribute ->
                add(result, new Declared(type.name(), "relationship type " + type.name(), attribute), type.name()));
        effective.put(key, result);
        return result;
    }

    /** Reports attributes that one element declares twice, by name or alias, and checks their ranges. */
    private void checkOwnAttributes(String owner, String label, List<AttributeDefinition> attributes) {
        Map<String, Integer> seen = new HashMap<>();
        for (int index = 0; index < attributes.size(); index++) {
            AttributeDefinition attribute = attributes.get(index);
            List<String> names = new ArrayList<>();
            names.add(attribute.name());
            names.addAll(attribute.aliases());
            for (String name : names) {
                Integer first = seen.putIfAbsent(Names.key(name), index);
                if (first != null && first != index) {
                    problems.add(new Problem(ProblemCode.DUPLICATE_ATTRIBUTE, Names.path(owner, name),
                            "The attribute name '" + name + "' is declared twice by " + label + "."));
                }
            }
            checkRange(owner, attribute);
        }
    }

    /**
     * Adds an attribute to an attribute set, reporting a clash with another element's declaration once. Clashes
     * within one element are reported by {@link #checkOwnAttributes}.
     */
    private void add(Map<String, Declared> attributes, Declared declared, String owner) {
        List<String> names = new ArrayList<>();
        names.add(declared.attribute().name());
        names.addAll(declared.attribute().aliases());
        for (String name : names) {
            Declared existing = attributes.get(Names.key(name));
            if (existing != null && Names.same(existing.declarer(), declared.declarer())) {
                return;
            }
            if (existing != null) {
                String pair = List.of(describe(existing), describe(declared)).stream().sorted()
                        .collect(Collectors.joining("|"));
                if (reportedConflicts.add(pair)) {
                    problems.add(new Problem(ProblemCode.DUPLICATE_ATTRIBUTE, Names.path(owner, name),
                            "The attribute name '" + name + "' is declared by " + existing.declarerLabel()
                                    + " and by " + declared.declarerLabel() + "."));
                }
                return;
            }
        }
        for (String name : names) {
            attributes.putIfAbsent(Names.key(name), declared);
        }
    }

    private static String describe(Declared declared) {
        return declared.declarer() + "." + declared.attribute().name();
    }

    private void checkRange(String owner, AttributeDefinition attribute) {
        if (attribute.range().isEmpty()) {
            return;
        }
        String path = Names.path(owner, attribute.name());
        Range range = attribute.range().get();
        if (!attribute.valueType().isOrdered()) {
            problems.add(new Problem(ProblemCode.RANGE_NOT_ALLOWED, path, "The " + attribute.valueType()
                    + " attribute " + path + " cannot have a range; only numbers, dates and instants are ordered."));
            return;
        }
        boolean fits = true;
        for (Bound bound : List.of(range.lower(), range.upper()).stream().flatMap(Optional::stream).toList()) {
            if (!Literals.fits(bound.value(), attribute.valueType())) {
                fits = false;
                problems.add(new Problem(ProblemCode.RANGE_LITERAL_MISMATCH, path, "The range bound "
                        + bound.value() + " does not fit the " + attribute.valueType() + " attribute " + path + "."));
            }
        }
        if (fits && Literals.isEmpty(range)) {
            problems.add(new Problem(ProblemCode.RANGE_EMPTY, path,
                    "The range of " + path + " is empty: its lower bound is not below its upper bound."));
        }
    }

    // ------------------------------------------------------------------ phase 5: relationship rules

    private void checkRelationships() {
        for (RelationshipTypeDefinition type : definition.relationshipTypes()) {
            String key = Names.key(type.name());
            if (type.kind() == RelationshipKind.COMPOSITION) {
                if (!type.sourcesPerTarget().equals(Multiplicity.EXACTLY_ONE)) {
                    problems.add(new Problem(ProblemCode.COMPOSITION_MULTIPLICITY, type.name(), "The composition "
                            + type.name() + " must give each target exactly one source, not " + type.sourcesPerTarget() + "."));
                }
                if (type.symmetric()) {
                    problems.add(new Problem(ProblemCode.COMPOSITION_SYMMETRIC, type.name(),
                            "The composition " + type.name() + " cannot be symmetric."));
                }
            }
            String[] ends = relationshipEnds.get(key);
            if (type.symmetric() && ends != null
                    && (!ends[0].equals(ends[1]) || !type.targetsPerSource().equals(type.sourcesPerTarget()))) {
                problems.add(new Problem(ProblemCode.SYMMETRIC_ENDS, type.name(), "The symmetric relationship type "
                        + type.name() + " must have the same source and target and the same multiplicity at both ends."));
            }
            String supertype = relationshipSupertype.get(key);
            if (supertype != null && ends != null && !isBroken(key, relationshipSupertype)) {
                String[] superEnds = relationshipEnds.get(supertype);
                if (superEnds != null && !(isSameOrSubtype(ends[0], superEnds[0]) && isSameOrSubtype(ends[1], superEnds[1]))) {
                    problems.add(new Problem(ProblemCode.RELATIONSHIP_SUBTYPE_ENDS, type.name(), "The source and target of "
                            + type.name() + " must be the same as, or subtypes of, those of its supertype "
                            + relationshipType(supertype).name() + "."));
                }
            }
        }
    }

    // ------------------------------------------------------------------ lookups

    private EntityTypeDefinition entityType(String key) {
        return definition.entityTypes().stream().filter(type -> Names.key(type.name()).equals(key)).findFirst().orElseThrow();
    }

    private TraitDefinition trait(String key) {
        return definition.traits().stream().filter(trait -> Names.key(trait.name()).equals(key)).findFirst().orElseThrow();
    }

    private RelationshipTypeDefinition relationshipType(String key) {
        return definition.relationshipTypes().stream()
                .filter(type -> Names.key(type.name()).equals(key)).findFirst().orElseThrow();
    }
}
