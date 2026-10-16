package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.expressions.ExpressionCompilationException;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RelationshipKind;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates a complete schema definition and reports every problem (capability {@code type-model}, design D3).
 *
 * <p>Phases: names and syntax, references, hierarchies, attributes, relationship rules, rule references, checks, derived
 * attributes. A later phase skips only the elements an earlier phase found broken, so one mistake does not cause a
 * cascade of follow-up problems.
 */
final class SchemaValidator {

    /** The form of the expression of a check, as text for problem details. */
    private static final String EXPRESSION_FORM =
            "a non-blank expression over self with at most 4,000 characters, whose result is a boolean";

    private record TypeRef(Kind kind, String name) {
    }

    private record Entry(String name, String owner, String path, String label, boolean inverse) {
    }

    private record Declared(String declarer, String declarerLabel, AttributeDefinition attribute) {
    }

    /** A derived attribute with the element that declares it. */
    private record DerivedAttribute(String declarer, String declarerKey, AttributeDefinition attribute) {
        String path() {
            return Names.path(declarer, attribute.name());
        }
    }

    /** The string constraints of an attribute that can be checked; a constraint that is itself invalid is left out. */
    private record TextConstraints(
            List<String> allowedValues, Optional<IRegexp> pattern, OptionalInt minLength, OptionalInt maxLength) {
        static final TextConstraints NONE =
                new TextConstraints(List.of(), Optional.empty(), OptionalInt.empty(), OptionalInt.empty());
    }

    private final SchemaDefinition definition;
    private final List<RuleDescriptor> availableRules;
    private final ExpressionCompiler expressionCompiler;
    /** The keys of the names of the types whose checks and derived attributes need a language, or null if all of them do. */
    private final Set<String> checkedTypes;
    private final List<Problem> problems = new ArrayList<>();
    private final Map<String, TypeRef> types = new HashMap<>();
    private final Map<String, String> entitySupertype = new HashMap<>();
    private final Map<String, List<String>> entityTraits = new HashMap<>();
    private final Map<String, String> relationshipSupertype = new HashMap<>();
    private final Map<String, String[]> relationshipEnds = new HashMap<>();
    private final Set<String> broken = new HashSet<>();
    private final Map<String, Map<String, Declared>> effective = new HashMap<>();
    private final Set<String> reportedConflicts = new HashSet<>();

    private SchemaValidator(SchemaDefinition definition, List<RuleDescriptor> availableRules,
            ExpressionCompiler expressionCompiler, Set<String> checkedTypes) {
        this.definition = definition;
        this.availableRules = availableRules;
        this.expressionCompiler = expressionCompiler;
        this.checkedTypes = checkedTypes;
    }

    /**
     * Validates a definition.
     *
     * @param definition     the types of every origin
     * @param availableRules the rules that types may name
     * @return every problem found
     */
    static List<Problem> validate(SchemaDefinition definition, List<RuleDescriptor> availableRules) {
        return validate(definition, availableRules, ExpressionCompiler.NONE);
    }

    /**
     * Validates a definition, compiling its checks and derived attributes.
     *
     * @param definition         the types of every origin
     * @param availableRules     the rules that types may name
     * @param expressionCompiler compiles the expressions of checks and derived attributes; without an expression
     *                           language, a check or a derived attribute is a problem
     * @return every problem found
     */
    static List<Problem> validate(SchemaDefinition definition, List<RuleDescriptor> availableRules,
            ExpressionCompiler expressionCompiler) {
        return validate(definition, availableRules, expressionCompiler, null);
    }

    /**
     * Validates a definition, compiling its checks and derived attributes, of which a registry without an expression
     * language reports only those of some types: the types of the origin that is registered. The types of other origins
     * were validated when those origins were registered, so their checks and derived attributes do not stop this
     * registration.
     *
     * @param definition         the types of every origin
     * @param availableRules     the rules that types may name
     * @param expressionCompiler compiles the expressions of checks and derived attributes
     * @param checkedTypes       the keys of the names of the types whose checks and derived attributes are a problem if
     *                           there is no expression language; {@code null} for every type
     * @return every problem found
     */
    static List<Problem> validate(SchemaDefinition definition, List<RuleDescriptor> availableRules,
            ExpressionCompiler expressionCompiler, Set<String> checkedTypes) {
        return new SchemaValidator(definition, availableRules, expressionCompiler, checkedTypes).run();
    }

    private List<Problem> run() {
        checkNames();
        resolveReferences();
        checkCycles(entitySupertype, Kind.ENTITY_TYPE);
        checkCycles(relationshipSupertype, Kind.RELATIONSHIP_TYPE);
        checkAttributes();
        checkRelationships();
        checkRules();
        checkChecks();
        checkDerivations();
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
        types.putIfAbsent(Names.key(name), new TypeRef(kind, name));
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
        problems.add(Problems.of(ProblemCode.INVALID_NAME, path, "The " + what + " '" + name
                + "' must start with a letter and contain only letters, digits and underscores (at most 128).",
                Names.NAME_FORM, name, null));
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

    private Optional<TypeRef> resolve(String reference) {
        TypeRef named = types.get(Names.key(reference));
        if (named != null) {
            return Optional.of(named);
        }
        for (EntityTypeDefinition type : definition.entityTypes()) {
            if (Names.contains(type.aliases(), reference)) {
                return Optional.of(new TypeRef(Kind.ENTITY_TYPE, type.name()));
            }
        }
        for (TraitDefinition trait : definition.traits()) {
            if (Names.contains(trait.aliases(), reference)) {
                return Optional.of(new TypeRef(Kind.TRAIT, trait.name()));
            }
        }
        for (RelationshipTypeDefinition type : definition.relationshipTypes()) {
            if (Names.contains(type.aliases(), reference)) {
                return Optional.of(new TypeRef(Kind.RELATIONSHIP_TYPE, type.name()));
            }
        }
        return Optional.empty();
    }

    private void resolveReferences() {
        for (EntityTypeDefinition type : definition.entityTypes()) {
            String key = Names.key(type.name());
            type.supertype().ifPresent(reference -> resolveKind(reference, type.name(), "supertype",
                    Kind.ENTITY_TYPE, ProblemCode.SUPERTYPE_NOT_ENTITY_TYPE, false)
                    .ifPresent(name -> entitySupertype.put(key, Names.key(name))));
            List<String> traits = new ArrayList<>();
            for (String reference : type.traits()) {
                resolveKind(reference, type.name(), "included trait", Kind.TRAIT, ProblemCode.NOT_A_TRAIT, true)
                        .ifPresent(name -> traits.add(Names.key(name)));
            }
            entityTraits.put(key, traits);
        }
        for (RelationshipTypeDefinition type : definition.relationshipTypes()) {
            String key = Names.key(type.name());
            Optional<String> source = resolveKind(type.source(), type.name(), "source", Kind.ENTITY_TYPE,
                    ProblemCode.RELATIONSHIP_END_NOT_ENTITY_TYPE, true);
            Optional<String> target = resolveKind(type.target(), type.name(), "target", Kind.ENTITY_TYPE,
                    ProblemCode.RELATIONSHIP_END_NOT_ENTITY_TYPE, true);
            if (source.isPresent() && target.isPresent()) {
                relationshipEnds.put(key, new String[] {Names.key(source.get()), Names.key(target.get())});
            } else {
                broken.add(key);
            }
            type.supertype().ifPresent(reference -> resolveKind(reference, type.name(), "supertype",
                    Kind.RELATIONSHIP_TYPE, ProblemCode.SUPERTYPE_NOT_RELATIONSHIP_TYPE, false)
                    .ifPresent(name -> relationshipSupertype.put(key, Names.key(name))));
        }
    }

    /**
     * Resolves a reference that must name a type of a kind.
     *
     * @param selfAllowed {@code false} if the owner itself cannot be meant, as for a supertype; it is then not suggested
     */
    private Optional<String> resolveKind(String reference, String owner, String role, Kind expected,
            ProblemCode wrongKind, boolean selfAllowed) {
        if (reference.isEmpty()) {
            problems.add(new Problem(ProblemCode.UNKNOWN_REFERENCE, owner, "The " + role + " of " + owner + " is missing."));
            broken.add(Names.key(owner));
            return Optional.empty();
        }
        Optional<TypeRef> named = resolve(reference);
        if (named.isEmpty()) {
            String hint = closestType(reference, expected, selfAllowed ? null : owner)
                    .map(closest -> "Did you mean '" + closest + "'?").orElse(null);
            problems.add(Problems.of(ProblemCode.UNKNOWN_REFERENCE, owner,
                    "The " + role + " '" + reference + "' of " + owner + " does not exist.", null, reference, hint));
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

    /** The existing type of a kind whose name or alias is closest to a reference, shown by its name. */
    private Optional<String> closestType(String reference, Kind kind, String excluded) {
        List<ClosestName.Candidate> candidates = new ArrayList<>();
        switch (kind) {
            case ENTITY_TYPE -> definition.entityTypes()
                    .forEach(type -> candidates.addAll(ClosestName.candidates(type.name(), type.aliases())));
            case TRAIT -> definition.traits()
                    .forEach(trait -> candidates.addAll(ClosestName.candidates(trait.name(), trait.aliases())));
            case RELATIONSHIP_TYPE -> definition.relationshipTypes()
                    .forEach(type -> candidates.addAll(ClosestName.candidates(type.name(), type.aliases())));
        }
        if (excluded != null) {
            candidates.removeIf(candidate -> Names.same(candidate.suggestion(), excluded));
        }
        return ClosestName.ofCandidates(reference, candidates);
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

    /** Reports attributes that one element declares twice, by name or alias, and checks their ranges and constraints. */
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
            checkValues(owner, attribute);
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

    /** Checks what an attribute allows as a value: its range, string constraints and examples. */
    private void checkValues(String owner, AttributeDefinition attribute) {
        Optional<Range> range = checkRange(owner, attribute);
        TextConstraints text = checkText(owner, attribute);
        checkExamples(owner, attribute, range, text);
    }

    /** Checks the range of an attribute and returns it if it can be used to check examples. */
    private Optional<Range> checkRange(String owner, AttributeDefinition attribute) {
        if (attribute.range().isEmpty()) {
            return Optional.empty();
        }
        String path = Names.path(owner, attribute.name());
        Range range = attribute.range().get();
        if (!attribute.valueType().isOrdered()) {
            problems.add(Problems.of(ProblemCode.RANGE_NOT_ALLOWED, path, "The " + attribute.valueType()
                    + " attribute " + path + " cannot have a range; only numbers, dates and instants are ordered.",
                    "an int, long, decimal, date or instant attribute", "a " + Values.typeName(attribute.valueType())
                    + " attribute", null));
            return Optional.empty();
        }
        boolean fits = true;
        for (Bound bound : List.of(range.lower(), range.upper()).stream().flatMap(Optional::stream).toList()) {
            if (!Values.fits(bound.value(), attribute.valueType())) {
                fits = false;
                problems.add(Problems.of(ProblemCode.RANGE_LITERAL_MISMATCH, path, "The range bound "
                        + Values.describe(bound.value()) + " (" + Values.typeName(bound.value().valueType())
                        + ") does not fit the " + attribute.valueType() + " attribute " + path + ".",
                        "a " + Values.typeName(attribute.valueType()) + " value", Values.describe(bound.value()),
                        null));
            }
        }
        if (fits && Values.isEmpty(range)) {
            problems.add(new Problem(ProblemCode.RANGE_EMPTY, path,
                    "The range of " + path + " is empty: its lower bound is not below its upper bound."));
            return Optional.empty();
        }
        return fits ? Optional.of(range) : Optional.empty();
    }

    /** Checks allowed values, pattern and lengths, and returns those that can be used to check examples. */
    private TextConstraints checkText(String owner, AttributeDefinition attribute) {
        boolean declared = !attribute.allowedValues().isEmpty() || attribute.pattern().isPresent()
                || attribute.minLength().isPresent() || attribute.maxLength().isPresent();
        if (!declared) {
            return TextConstraints.NONE;
        }
        String path = Names.path(owner, attribute.name());
        if (attribute.valueType() != ValueType.STRING) {
            List<String> constraints = new ArrayList<>();
            if (!attribute.allowedValues().isEmpty()) {
                constraints.add("allowed values");
            }
            if (attribute.pattern().isPresent()) {
                constraints.add("a pattern");
            }
            if (attribute.minLength().isPresent() || attribute.maxLength().isPresent()) {
                constraints.add("a length");
            }
            problems.add(Problems.of(ProblemCode.TEXT_CONSTRAINT_NOT_ALLOWED, path, "The "
                    + attribute.valueType() + " attribute " + path + " cannot have " + String.join(", ", constraints)
                    + "; only string attributes can.", "a string attribute",
                    "a " + Values.typeName(attribute.valueType()) + " attribute", null));
            return TextConstraints.NONE;
        }
        return new TextConstraints(distinctAllowedValues(path, attribute), pattern(path, attribute),
                lengths(path, attribute, true), lengths(path, attribute, false));
    }

    private List<String> distinctAllowedValues(String path, AttributeDefinition attribute) {
        Set<String> seen = new HashSet<>();
        Set<String> reported = new HashSet<>();
        for (String value : attribute.allowedValues()) {
            if (!seen.add(value) && reported.add(value)) {
                problems.add(Problems.of(ProblemCode.DUPLICATE_ALLOWED_VALUE, path, "The allowed value '" + value
                        + "' of " + path + " is listed more than once.", "distinct allowed values", value, null));
            }
        }
        return List.copyOf(seen);
    }

    /** Checks the pattern against the I-Regexp grammar (ADR-0020) and returns it if it can be used to check examples. */
    private Optional<IRegexp> pattern(String path, AttributeDefinition attribute) {
        if (attribute.pattern().isEmpty()) {
            return Optional.empty();
        }
        String regularExpression = attribute.pattern().get();
        return switch (IRegexp.parse(regularExpression)) {
            case IRegexp.Parsed.Valid(IRegexp valid) -> Optional.of(valid);
            case IRegexp.Parsed.Invalid(IRegexpSyntax.Violation violation) -> {
                problems.add(Problems.of(ProblemCode.INVALID_PATTERN, path, "The pattern '" + regularExpression + "' of "
                        + path + " is not a valid pattern (I-Regexp, RFC 9485): " + violation.reason() + " (at index "
                        + violation.index() + ").", violation.expected(), violation.found(),
                        violation.hint().orElse(null)));
                yield Optional.empty();
            }
        };
    }

    /**
     * Checks one length and returns it if it can be used. A minimum above the maximum is reported once, by the
     * minimum, and makes both unusable.
     */
    private OptionalInt lengths(String path, AttributeDefinition attribute, boolean minimum) {
        OptionalInt length = minimum ? attribute.minLength() : attribute.maxLength();
        if (length.isEmpty()) {
            return OptionalInt.empty();
        }
        String what = minimum ? "minimum" : "maximum";
        if (length.getAsInt() < 0) {
            problems.add(Problems.of(ProblemCode.INVALID_LENGTH, path, "The " + what + " length of " + path
                    + " is negative.", "a length of 0 or more", String.valueOf(length.getAsInt()), null));
            return OptionalInt.empty();
        }
        OptionalInt other = minimum ? attribute.maxLength() : attribute.minLength();
        boolean crossed = other.isPresent() && other.getAsInt() >= 0
                && attribute.minLength().getAsInt() > attribute.maxLength().getAsInt();
        if (crossed && minimum) {
            problems.add(Problems.of(ProblemCode.INVALID_LENGTH, path, "The minimum length " + length.getAsInt()
                    + " of " + path + " is above its maximum length " + other.getAsInt() + ".",
                    "a minimum length of at most " + other.getAsInt(), String.valueOf(length.getAsInt()), null));
        }
        return crossed ? OptionalInt.empty() : length;
    }

    /** Checks that each example has the attribute's value type and satisfies the constraints that are valid. */
    private void checkExamples(String owner, AttributeDefinition attribute, Optional<Range> range, TextConstraints text) {
        String path = Names.path(owner, attribute.name());
        for (Value example : attribute.examples()) {
            String shown = Values.describe(example);
            if (!Values.fits(example, attribute.valueType())) {
                problems.add(Problems.of(ProblemCode.INVALID_EXAMPLE, path, "The example '" + shown + "' ("
                        + Values.typeName(example.valueType()) + ") of " + path + " does not fit the "
                        + attribute.valueType() + " attribute.", "a " + Values.typeName(attribute.valueType())
                        + " value", shown, null));
                continue;
            }
            if (range.isPresent() && !Values.within(example, range.get())) {
                invalidExample(path, shown, "is outside the range of the attribute",
                        "a value " + Values.describe(range.get()));
            }
            if (!text.allowedValues().isEmpty() && !text.allowedValues().contains(shown)) {
                invalidExample(path, shown, "is not one of the allowed values",
                        "one of " + text.allowedValues().stream().sorted().collect(Collectors.joining(", ")));
            }
            if (text.pattern().isPresent() && !text.pattern().get().matches(shown)) {
                invalidExample(path, shown, "does not match the pattern " + text.pattern().get().source(),
                        "a value matching " + text.pattern().get().source());
            }
            if (example instanceof Value.StringValue(String value)) {
                checkLengths(path, value, text);
            }
        }
    }

    private void checkLengths(String path, String value, TextConstraints text) {
        int length = value.codePointCount(0, value.length());
        if (text.minLength().isPresent() && length < text.minLength().getAsInt()) {
            invalidExample(path, value, "is shorter than the minimum length " + text.minLength().getAsInt(),
                    "at least " + text.minLength().getAsInt() + " characters");
        }
        if (text.maxLength().isPresent() && length > text.maxLength().getAsInt()) {
            invalidExample(path, value, "is longer than the maximum length " + text.maxLength().getAsInt(),
                    "at most " + text.maxLength().getAsInt() + " characters");
        }
    }

    private void invalidExample(String path, String example, String reason, String expected) {
        problems.add(Problems.of(ProblemCode.INVALID_EXAMPLE, path,
                "The example '" + example + "' of " + path + " " + reason + ".", expected, example, null));
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

    // ------------------------------------------------------------------ phase 6: rule references

    private void checkRules() {
        for (EntityTypeDefinition type : definition.entityTypes()) {
            checkRules(type.name(), Kind.ENTITY_TYPE, type.rules(), entitySupertype);
        }
        for (RelationshipTypeDefinition type : definition.relationshipTypes()) {
            checkRules(type.name(), Kind.RELATIONSHIP_TYPE, type.rules(), relationshipSupertype);
        }
    }

    /**
     * Checks the rules a type names: their form, that they are available, that they are for this type or a
     * supertype, and that neither the type nor a supertype names them already. Rules whose reference is malformed are
     * not checked further. The checks that need the supertypes are skipped if the hierarchy is broken.
     */
    private void checkRules(String typeName, Kind kind, List<RuleReference> references, Map<String, String> supertypes) {
        String key = Names.key(typeName);
        boolean hierarchyUsable = !isBroken(key, supertypes);
        Set<String> named = new HashSet<>();
        for (RuleReference reference : references) {
            String path = Names.path(typeName, reference.name());
            boolean wellFormed = true;
            if (!Names.isValidRuleName(reference.name())) {
                wellFormed = false;
                problems.add(Problems.of(ProblemCode.INVALID_RULE_NAME, path, "The rule name '" + reference.name()
                        + "' named by " + typeName + " must be lowercase letters, digits and hyphens, starting with a "
                        + "letter (at most 128).", Names.RULE_NAME_FORM, reference.name(), null));
            }
            if (reference.version() < 1) {
                wellFormed = false;
                problems.add(Problems.of(ProblemCode.INVALID_RULE_NAME, path, "The version " + reference.version()
                        + " of the rule " + reference.name() + " named by " + typeName + " must be at least 1.",
                        "a whole number of at least 1", String.valueOf(reference.version()), null));
            }
            if (!wellFormed) {
                continue;
            }
            Optional<RuleDescriptor> available = availableRules.stream()
                    .filter(rule -> rule.name().equals(reference.name()) && rule.version() == reference.version())
                    .findFirst();
            if (available.isEmpty()) {
                problems.add(Problems.of(ProblemCode.UNKNOWN_RULE, path, "The rule " + reference.name() + " in version "
                        + reference.version() + " named by " + typeName + " is not available.", null,
                        reference.name() + " " + reference.version(), unknownRuleHint(reference)));
            } else if (hierarchyUsable && !isForTypeOrSupertype(available.get(), key, kind, supertypes)) {
                problems.add(Problems.of(ProblemCode.RULE_TYPE_MISMATCH, path, "The rule " + reference.name()
                        + " applies to " + available.get().appliesTo() + ", so " + typeName + " cannot name it "
                        + "because it is neither that type nor one of its subtypes.",
                        available.get().appliesTo() + " or one of its subtypes", typeName, null));
            }
            if (!named.add(reference.name())) {
                problems.add(Problems.of(ProblemCode.DUPLICATE_RULE, path, "The rule " + reference.name()
                        + " is named more than once by " + typeName + ".", null, reference.name(), null));
            } else if (hierarchyUsable) {
                nearestSupertypeNaming(reference.name(), kind, key, supertypes).ifPresent(supertype ->
                        problems.add(Problems.of(ProblemCode.DUPLICATE_RULE, path, "The rule " + reference.name()
                                + " named by " + typeName + " already applies through its supertype "
                                + supertype + ", which names it.", null, reference.name(),
                                "Remove the rule from " + typeName + " or from " + supertype + ".")));
            }
        }
    }

    /** The available versions of the rule, or else the closest rule name, as a hint for an unknown rule. */
    private String unknownRuleHint(RuleReference reference) {
        List<Integer> versions = availableRules.stream()
                .filter(rule -> rule.name().equals(reference.name()))
                .map(RuleDescriptor::version)
                .sorted()
                .toList();
        if (!versions.isEmpty()) {
            return "Available " + (versions.size() == 1 ? "version" : "versions") + " of " + reference.name() + ": "
                    + versions.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ".";
        }
        return ClosestName.of(reference.name(), availableRules.stream().map(RuleDescriptor::name).distinct().toList())
                .map(closest -> "Did you mean the rule '" + closest + "'?").orElse(null);
    }

    /** Tells whether a rule is for a type or for one of its supertypes. */
    private boolean isForTypeOrSupertype(RuleDescriptor rule, String key, Kind kind, Map<String, String> supertypes) {
        Optional<TypeRef> appliesTo = resolve(rule.appliesTo()).filter(type -> type.kind() == kind);
        if (appliesTo.isEmpty()) {
            return false;
        }
        String target = Names.key(appliesTo.get().name());
        Set<String> seen = new HashSet<>();
        String current = key;
        while (current != null && seen.add(current)) {
            if (current.equals(target)) {
                return true;
            }
            current = supertypes.get(current);
        }
        return false;
    }

    /** The name of the nearest supertype of a type that names a rule, if any. */
    private Optional<String> nearestSupertypeNaming(String rule, Kind kind, String key, Map<String, String> supertypes) {
        Set<String> seen = new HashSet<>(Set.of(key));
        String current = supertypes.get(key);
        while (current != null && seen.add(current)) {
            String name = kind == Kind.ENTITY_TYPE ? entityType(current).name() : relationshipType(current).name();
            List<RuleReference> named = kind == Kind.ENTITY_TYPE ? entityType(current).rules() : relationshipType(current).rules();
            if (named.stream().anyMatch(reference -> reference.name().equals(rule))) {
                return Optional.of(name);
            }
            current = supertypes.get(current);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ phase 7: checks

    private void checkChecks() {
        for (EntityTypeDefinition type : definition.entityTypes()) {
            checkChecks(type.name(), Kind.ENTITY_TYPE, type.checks(), entitySupertype);
        }
        for (RelationshipTypeDefinition type : definition.relationshipTypes()) {
            checkChecks(type.name(), Kind.RELATIONSHIP_TYPE, type.checks(), relationshipSupertype);
        }
    }

    /**
     * Checks the checks a type declares: their names, that neither the type nor a supertype declares the name already
     * or uses it for an attribute or a rule, their expressions' length, and that the expressions compile against the
     * attributes of the type. Checks whose name or expression is malformed are not compiled. The checks that need the
     * supertypes or the attributes are skipped if the hierarchy is broken.
     */
    private void checkChecks(String typeName, Kind kind, List<Check> declared, Map<String, String> supertypes) {
        String key = Names.key(typeName);
        boolean hierarchyUsable = !isBroken(key, supertypes);
        Set<String> named = new HashSet<>();
        for (Check check : declared) {
            String path = Names.path(typeName, check.name());
            if (!Names.isValidRuleName(check.name())) {
                problems.add(Problems.of(ProblemCode.INVALID_CHECK_NAME, path, "The check name '" + check.name()
                        + "' declared by " + typeName + " must be lowercase letters, digits and hyphens, starting with "
                        + "a letter (at most 128).", Names.RULE_NAME_FORM, check.name(), null));
                continue;
            }
            if (!named.add(check.name())) {
                problems.add(Problems.of(ProblemCode.DUPLICATE_CHECK, path, "The check " + check.name()
                        + " is declared more than once by " + typeName + ".", null, check.name(), null));
                continue;
            }
            Optional<String> supertype = hierarchyUsable
                    ? nearestSupertypeDeclaring(check.name(), kind, key, supertypes) : Optional.empty();
            if (supertype.isPresent()) {
                problems.add(Problems.of(ProblemCode.DUPLICATE_CHECK, path, "The check " + check.name()
                        + " declared by " + typeName + " already applies through its supertype " + supertype.get()
                        + ", which declares it.", null, check.name(),
                        "Remove the check from " + typeName + " or from " + supertype.get() + "."));
                continue;
            }
            Optional<String> taken = nameTakenBy(check.name(), kind, key, supertypes, hierarchyUsable);
            if (taken.isPresent()) {
                problems.add(Problems.of(ProblemCode.DUPLICATE_CHECK, path, "The check name '" + check.name()
                        + "' declared by " + typeName + " is already taken by " + taken.get() + ".",
                        "a name that no attribute, rule or check of " + typeName + " and its supertypes has",
                        check.name(), "Rename the check."));
                continue;
            }
            checkExpression(typeName, check, path, hierarchyUsable ? effective.get(key) : null,
                    checkedTypes == null || checkedTypes.contains(key));
        }
    }

    /**
     * Tells what a check name is taken by, if a type or one of its supertypes uses it for an attribute (or an alias of
     * one, compared without regard to case) or for a rule. The supertypes are looked at only if the hierarchy is usable.
     *
     * @return what takes the name, such as {@code an attribute: balance, declared by entity type Account}
     */
    private Optional<String> nameTakenBy(String name, Kind kind, String key, Map<String, String> supertypes,
            boolean hierarchyUsable) {
        Declared attribute = hierarchyUsable && effective.get(key) != null ? effective.get(key).get(Names.key(name)) : null;
        if (attribute != null) {
            return Optional.of("an attribute: " + attribute.attribute().name() + ", declared by "
                    + attribute.declarerLabel());
        }
        List<AttributeDefinition> own = kind == Kind.ENTITY_TYPE ? entityType(key).attributes()
                : relationshipType(key).attributes();
        if (!hierarchyUsable) {
            for (AttributeDefinition candidate : own) {
                if (Names.same(candidate.name(), name) || Names.contains(candidate.aliases(), name)) {
                    return Optional.of("an attribute: " + candidate.name() + ", declared by "
                            + (kind == Kind.ENTITY_TYPE ? entityType(key).name() : relationshipType(key).name()));
                }
            }
        }
        List<RuleReference> ownRules = kind == Kind.ENTITY_TYPE ? entityType(key).rules() : relationshipType(key).rules();
        if (ownRules.stream().anyMatch(rule -> rule.name().equals(name))) {
            return Optional.of("a rule: " + name + ", which the type names");
        }
        if (hierarchyUsable) {
            return nearestSupertypeNaming(name, kind, key, supertypes)
                    .map(supertype -> "a rule: " + name + ", which its supertype " + supertype + " names");
        }
        return Optional.empty();
    }

    /**
     * Reports an expression that is blank, too long, or does not compile against the attributes of its type, or reports
     * that there is no expression language to compile it with.
     *
     * @param attributes the attributes of the type; null if the type's attributes cannot be known
     * @param needsLanguage whether a missing expression language is a problem of this type's checks
     */
    private void checkExpression(String typeName, Check check, String path, Map<String, Declared> attributes,
            boolean needsLanguage) {
        String expression = check.expression();
        if (expression.isBlank()) {
            problems.add(Problems.of(ProblemCode.INVALID_CHECK, path, "The expression of the check " + check.name()
                    + " of " + typeName + " is blank.", EXPRESSION_FORM, "a blank expression", null));
            return;
        }
        int length = expression.codePointCount(0, expression.length());
        if (length > Check.MAXIMUM_EXPRESSION_LENGTH) {
            problems.add(Problems.of(ProblemCode.INVALID_CHECK, path, "The expression of the check " + check.name()
                    + " of " + typeName + " has " + length + " characters, but at most "
                    + Check.MAXIMUM_EXPRESSION_LENGTH + " are allowed.", EXPRESSION_FORM, length + " characters",
                    null));
            return;
        }
        if (!expressionCompiler.available()) {
            if (needsLanguage) {
                problems.add(Problems.of(ProblemCode.EXPRESSIONS_UNAVAILABLE, path, "The check " + check.name() + " of "
                        + typeName + " cannot be compiled: this schema registry has no expression language.",
                        "a schema registry with an expression language", "no expression language",
                        "Put the module cairn-cel on the class path, or give the store settings an "
                        + "expression language, or remove the checks from the definition."));
            }
            return;
        }
        if (attributes == null) {
            return;
        }
        Map<String, ValueType> types = new LinkedHashMap<>();
        attributes.values().forEach(declared -> types.put(declared.attribute().name(), declared.attribute().valueType()));
        try {
            expressionCompiler.compile(expression, types);
        } catch (ExpressionCompilationException e) {
            String position = e.line().isPresent() && e.column().isPresent()
                    ? " at line " + e.line().getAsInt() + ", column " + e.column().getAsInt() : "";
            problems.add(Problems.of(ProblemCode.INVALID_CHECK, path, "The expression of the check " + check.name()
                    + " of " + typeName + " does not compile" + position + ": " + e.getMessage(), EXPRESSION_FORM,
                    expression, attributesHint(typeName, types)));
        }
    }

    /** What an expression may read, as a hint for an expression that does not compile. */
    private static String attributesHint(String typeName, Map<String, ValueType> attributes) {
        return attributes.isEmpty()
                ? typeName + " has no attributes to read."
                : "Read the attributes of " + typeName + " as self.<name>, and test an optional one with has(self.<name>): "
                        + String.join(", ", attributes.keySet()) + ".";
    }

    /** The name of the nearest supertype of a type that declares a check, if any. */
    private Optional<String> nearestSupertypeDeclaring(String check, Kind kind, String key,
            Map<String, String> supertypes) {
        Set<String> seen = new HashSet<>(Set.of(key));
        String current = supertypes.get(key);
        while (current != null && seen.add(current)) {
            String name = kind == Kind.ENTITY_TYPE ? entityType(current).name() : relationshipType(current).name();
            List<Check> declared = kind == Kind.ENTITY_TYPE ? entityType(current).checks() : relationshipType(current).checks();
            if (declared.stream().anyMatch(candidate -> candidate.name().equals(check))) {
                return Optional.of(name);
            }
            current = supertypes.get(current);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ phase 8: derived attributes

    /**
     * Checks the derived attributes (design D3 of {@code add-derived-attributes}): that each expression is not blank and
     * not too long, that there is an expression language to compile it, and that it compiles for each concrete type that
     * has the attribute, against the attributes of that type that are not derived, with a result that can be a value of
     * the attribute's type. A derived attribute of a trait, or inherited, is compiled for each type that includes or
     * inherits it, because only a type knows what the expression may read. A problem is located at the attribute of the
     * element that declares it. The types whose attributes cannot be known, because the hierarchy is broken, are skipped.
     */
    private void checkDerivations() {
        List<DerivedAttribute> declared = new ArrayList<>();
        definition.traits().forEach(trait -> derivedOf(trait.name(), trait.attributes(), declared));
        definition.entityTypes().forEach(type -> derivedOf(type.name(), type.attributes(), declared));
        definition.relationshipTypes().forEach(type -> derivedOf(type.name(), type.attributes(), declared));
        if (declared.isEmpty()) {
            return;
        }
        Set<DerivedAttribute> usable = new HashSet<>();
        for (DerivedAttribute derived : declared) {
            if (expressionIsUsable(derived)) {
                usable.add(derived);
            }
        }
        if (!expressionCompiler.available() || usable.isEmpty()) {
            return;
        }
        Set<String> reported = new HashSet<>();
        for (EntityTypeDefinition type : definition.entityTypes()) {
            String key = Names.key(type.name());
            if (!type.isAbstract() && !isBroken(key, entitySupertype)) {
                compileDerivations(type.name(), effective.get(key), usable, reported);
            }
        }
        for (RelationshipTypeDefinition type : definition.relationshipTypes()) {
            String key = Names.key(type.name());
            if (!isBroken(key, relationshipSupertype)) {
                compileDerivations(type.name(), effective.get(key), usable, reported);
            }
        }
    }

    private static void derivedOf(String declarer, List<AttributeDefinition> attributes, List<DerivedAttribute> into) {
        for (AttributeDefinition attribute : attributes) {
            if (attribute.derived().isPresent()) {
                into.add(new DerivedAttribute(declarer, Names.key(declarer), attribute));
            }
        }
    }

    /**
     * Reports an expression that is blank or too long, or that there is no expression language to compile; tells whether
     * the expression can be compiled.
     */
    private boolean expressionIsUsable(DerivedAttribute derived) {
        AttributeDefinition attribute = derived.attribute();
        String expression = attribute.derived().orElseThrow();
        String what = "the derived attribute " + attribute.name() + " of " + derived.declarer();
        String form = derivationForm(attribute.valueType());
        if (expression.isBlank()) {
            problems.add(Problems.of(ProblemCode.INVALID_DERIVATION, derived.path(), "The expression of " + what
                    + " is blank.", form, "a blank expression", null));
            return false;
        }
        int length = expression.codePointCount(0, expression.length());
        if (length > Check.MAXIMUM_EXPRESSION_LENGTH) {
            problems.add(Problems.of(ProblemCode.INVALID_DERIVATION, derived.path(), "The expression of " + what + " has "
                    + length + " characters, but at most " + Check.MAXIMUM_EXPRESSION_LENGTH + " are allowed.", form,
                    length + " characters", null));
            return false;
        }
        if (!expressionCompiler.available()) {
            if (checkedTypes == null || checkedTypes.contains(derived.declarerKey())) {
                problems.add(Problems.of(ProblemCode.EXPRESSIONS_UNAVAILABLE, derived.path(), "The expression of " + what
                        + " cannot be compiled: this schema registry has no expression language.",
                        "a schema registry with an expression language", "no expression language",
                        "Put the module cairn-cel on the class path, or give the store settings an "
                        + "expression language, or remove the derived attributes from the definition."));
            }
            return false;
        }
        return true;
    }

    /**
     * Compiles the derived attributes that a concrete type has, whoever declares them, against its attributes that are
     * not derived. A problem is reported once for the attribute and the error, whatever the type.
     */
    private void compileDerivations(String typeName, Map<String, Declared> attributes, Set<DerivedAttribute> usable,
            Set<String> reported) {
        if (attributes == null) {
            return;
        }
        Map<String, ValueType> readable = new LinkedHashMap<>();
        List<String> derivedNames = new ArrayList<>();
        Set<Declared> seen = new HashSet<>();
        for (Declared declared : attributes.values()) {
            if (!seen.add(declared)) {
                continue;
            }
            if (declared.attribute().derived().isPresent()) {
                derivedNames.add(declared.attribute().name());
            } else {
                readable.put(declared.attribute().name(), declared.attribute().valueType());
            }
        }
        seen.clear();
        for (Declared declared : attributes.values()) {
            AttributeDefinition attribute = declared.attribute();
            DerivedAttribute derived = new DerivedAttribute(declared.declarer(), Names.key(declared.declarer()), attribute);
            if (attribute.derived().isEmpty() || !usable.contains(derived) || !seen.add(declared)) {
                continue;
            }
            String expression = attribute.derived().get();
            try {
                expressionCompiler.compileDerivation(expression, readable, attribute.valueType());
            } catch (ExpressionCompilationException e) {
                String position = e.line().isPresent() && e.column().isPresent()
                        ? " at line " + e.line().getAsInt() + ", column " + e.column().getAsInt() : "";
                String where = Names.same(typeName, declared.declarer()) ? "" : " for " + typeName;
                if (reported.add(derived.path() + "|" + position + "|" + e.getMessage())) {
                    problems.add(Problems.of(ProblemCode.INVALID_DERIVATION, derived.path(), "The expression of the "
                            + "derived attribute " + attribute.name() + " of " + declared.declarer() + " does not compile"
                            + where + position + ": " + e.getMessage(), derivationForm(attribute.valueType()),
                            expression, derivationHint(typeName, readable, derivedNames, expression)));
                }
            }
        }
    }

    /** What the expression of a derived attribute must be, as text for problem details. */
    private static String derivationForm(ValueType type) {
        return "a non-blank expression over self with at most 4,000 characters, whose result is "
                + switch (type) {
                    case STRING -> "a string";
                    case BOOLEAN -> "a bool";
                    case INT -> "an int";
                    case LONG -> "an int (an integer in CEL)";
                    case DECIMAL -> "a double";
                    case DATE -> "a timestamp at the start of a day in UTC";
                    case INSTANT -> "a timestamp";
                };
    }

    /**
     * What an expression may read, as a hint for a derivation that does not compile: the attributes that are not derived,
     * and, if the expression names derived ones, that it cannot read them.
     */
    private static String derivationHint(String typeName, Map<String, ValueType> readable, List<String> derivedNames,
            String expression) {
        List<String> read = derivedNames.stream()
                .filter(name -> expression.matches("(?s).*\\bself\\s*\\.\\s*" + Pattern.quote(name) + "\\b.*"))
                .toList();
        String derivedHint = read.isEmpty() ? ""
                : "It reads the derived " + (read.size() == 1 ? "attribute " : "attributes ") + String.join(", ", read)
                        + ", but self holds only the attributes that are not derived. ";
        return derivedHint + (readable.isEmpty()
                ? typeName + " has no attributes to read."
                : "Read the attributes of " + typeName + " that are not derived as self.<name>, and test an optional one "
                        + "with has(self.<name>): " + String.join(", ", readable.keySet()) + ".");
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
