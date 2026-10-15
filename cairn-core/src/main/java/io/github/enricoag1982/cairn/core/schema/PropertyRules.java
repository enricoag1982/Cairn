package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaChange;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Classifies the change of one property of a definition record as compatible or breaking (design D6).
 *
 * <p>Properties are compared generically, component by component. Each changed property is classified by the rule
 * registered for it; a property without a rule is breaking, so a forgotten rule errs on the safe side. A rule
 * returns the changes it finds: usually one, none if the new value only reorders the old one, and one per rule
 * reference for the rules of a type.
 */
final class PropertyRules {

    /** What a rule needs to know about the element whose property changed. */
    record Context(
            CompiledSchema oldSchema,
            CompiledSchema newSchema,
            String path,
            String property,
            Object oldValue,
            Object newValue,
            Record oldElement,
            Record newElement) {
    }

    @FunctionalInterface
    interface Rule {
        List<SchemaChange> classify(Context context);
    }

    private static final Rule SKIP = context -> List.of();
    private static final Rule NAME = PropertyRules::rename;
    private static final Rule ALIASES = context -> aliases(context, (String) property(context.oldElement(), "name"));
    private static final Rule INVERSE_NAME = PropertyRules::renameInverse;
    private static final Rule INVERSE_ALIASES = context ->
            aliases(context, (String) property(context.oldElement(), "inverseName"));
    private static final Rule MAY_BECOME_FALSE = context -> flag(context, false);
    private static final Rule MAY_BECOME_TRUE = context -> flag(context, true);
    private static final Rule ALWAYS_BREAKING = context -> List.of(Changes.breaking(context.path(),
            "changed " + context.property() + " from " + context.oldValue() + " to " + context.newValue()));
    private static final Rule ALWAYS_COMPATIBLE = context -> List.of(Changes.compatible(context.path(),
            "changed the " + context.property()));

    private static final Map<String, Rule> RULES = Map.ofEntries(
            Map.entry("entity.name", NAME),
            Map.entry("entity.aliases", ALIASES),
            Map.entry("entity.isAbstract", MAY_BECOME_FALSE),
            Map.entry("entity.supertype", context -> sameSupertype(context, Kind.ENTITY_TYPE)),
            Map.entry("entity.traits", PropertyRules::traits),
            Map.entry("entity.attributes", SKIP),
            Map.entry("entity.deprecated", MAY_BECOME_TRUE),
            Map.entry("entity.description", PropertyRules::description),
            Map.entry("entity.rules", PropertyRules::rules),
            Map.entry("entity.checks", PropertyRules::checks),
            Map.entry("trait.name", NAME),
            Map.entry("trait.aliases", ALIASES),
            Map.entry("trait.attributes", SKIP),
            Map.entry("trait.deprecated", MAY_BECOME_TRUE),
            Map.entry("trait.description", PropertyRules::description),
            Map.entry("relationship.name", NAME),
            Map.entry("relationship.aliases", ALIASES),
            Map.entry("relationship.source", PropertyRules::widenedEnd),
            Map.entry("relationship.target", PropertyRules::widenedEnd),
            Map.entry("relationship.targetsPerSource", PropertyRules::relaxedMultiplicity),
            Map.entry("relationship.sourcesPerTarget", PropertyRules::relaxedMultiplicity),
            Map.entry("relationship.inverseName", INVERSE_NAME),
            Map.entry("relationship.inverseAliases", INVERSE_ALIASES),
            Map.entry("relationship.kind", ALWAYS_BREAKING),
            Map.entry("relationship.symmetric", ALWAYS_BREAKING),
            Map.entry("relationship.supertype", context -> sameSupertype(context, Kind.RELATIONSHIP_TYPE)),
            Map.entry("relationship.attributes", SKIP),
            Map.entry("relationship.deprecated", MAY_BECOME_TRUE),
            Map.entry("relationship.description", PropertyRules::description),
            Map.entry("relationship.rules", PropertyRules::rules),
            Map.entry("relationship.checks", PropertyRules::checks),
            Map.entry("attribute.name", NAME),
            Map.entry("attribute.aliases", ALIASES),
            Map.entry("attribute.valueType", PropertyRules::widenedValueType),
            Map.entry("attribute.required", MAY_BECOME_FALSE),
            Map.entry("attribute.unique", MAY_BECOME_FALSE),
            Map.entry("attribute.range", PropertyRules::widenedRange),
            Map.entry("attribute.deprecated", MAY_BECOME_TRUE),
            Map.entry("attribute.description", PropertyRules::description),
            Map.entry("attribute.examples", ALWAYS_COMPATIBLE),
            Map.entry("attribute.allowedValues", PropertyRules::allowedValues),
            Map.entry("attribute.pattern", PropertyRules::pattern),
            Map.entry("attribute.minLength", PropertyRules::minLength),
            Map.entry("attribute.maxLength", PropertyRules::maxLength));

    private static final Set<String> WIDENINGS = Set.of("INT>LONG", "INT>DECIMAL", "LONG>DECIMAL");

    private PropertyRules() {
    }

    /** Compares two versions of a definition record, property by property. */
    static List<SchemaChange> compare(String elementKind, Record oldElement, Record newElement, String path,
            CompiledSchema oldSchema, CompiledSchema newSchema) {
        List<SchemaChange> changes = new ArrayList<>();
        for (RecordComponent component : oldElement.getClass().getRecordComponents()) {
            Object oldValue = property(oldElement, component.getName());
            Object newValue = property(newElement, component.getName());
            if (!Objects.equals(oldValue, newValue)) {
                changes.addAll(classify(elementKind, new Context(oldSchema, newSchema, path, component.getName(),
                        oldValue, newValue, oldElement, newElement)));
            }
        }
        return changes;
    }

    /** Tells whether a property of an element kind has a rule of its own, rather than being breaking by default. */
    static boolean hasRule(String elementKind, String property) {
        return RULES.containsKey(elementKind + "." + property);
    }

    /** Classifies a changed property; a property without a rule is breaking. */
    static List<SchemaChange> classify(String elementKind, Context context) {
        Rule rule = RULES.get(elementKind + "." + context.property());
        return rule == null ? ALWAYS_BREAKING.classify(context) : rule.classify(context);
    }

    // ------------------------------------------------------------------ rules

    private static List<SchemaChange> rename(Context context) {
        String oldName = (String) context.oldValue();
        String newName = (String) context.newValue();
        @SuppressWarnings("unchecked")
        List<String> aliases = (List<String>) property(context.newElement(), "aliases");
        if (Names.same(oldName, newName) || Names.contains(aliases, oldName)) {
            return List.of(Changes.compatible(context.path(), "renamed " + oldName + " to " + newName));
        }
        return List.of(Changes.breaking(context.path(), "renamed " + oldName + " to " + newName
                + " without keeping " + oldName + " as an alias", "Keep " + oldName + " as an alias of " + newName + "."));
    }

    private static List<SchemaChange> renameInverse(Context context) {
        String oldName = (String) context.oldValue();
        String newName = (String) context.newValue();
        @SuppressWarnings("unchecked")
        List<String> aliases = (List<String>) property(context.newElement(), "inverseAliases");
        if (Names.same(oldName, newName) || Names.contains(aliases, oldName)) {
            return List.of(Changes.compatible(context.path(), "renamed inverse " + oldName + " to " + newName));
        }
        return List.of(Changes.breaking(context.path(), "renamed inverse " + oldName + " to " + newName
                + " without keeping " + oldName + " as an inverse alias",
                "Keep " + oldName + " as an inverse alias of " + newName + "."));
    }

    /** Aliases may not be dropped; the only alias that may be added is the previous name, as part of a rename. */
    private static List<SchemaChange> aliases(Context context, String previousName) {
        @SuppressWarnings("unchecked")
        List<String> oldAliases = (List<String>) context.oldValue();
        @SuppressWarnings("unchecked")
        List<String> newAliases = (List<String>) context.newValue();
        List<String> removed = oldAliases.stream().filter(alias -> !Names.contains(newAliases, alias)).toList();
        List<String> added = newAliases.stream().filter(alias -> !Names.contains(oldAliases, alias)).toList();
        if (!removed.isEmpty()) {
            return List.of(Changes.breaking(context.path(), "removed the aliases " + removed));
        }
        if (added.stream().anyMatch(alias -> !Names.same(alias, previousName))) {
            return List.of(Changes.breaking(context.path(), "added the aliases " + added
                    + " without renaming from them"));
        }
        return List.of(Changes.compatible(context.path(), "kept " + previousName + " as an alias"));
    }

    private static List<SchemaChange> flag(Context context, boolean allowedNewValue) {
        if (Boolean.valueOf(allowedNewValue).equals(context.newValue())) {
            return List.of(Changes.compatible(context.path(), "set " + context.property() + " to " + allowedNewValue));
        }
        String description = "set " + context.property() + " to " + context.newValue();
        boolean stricter = context.property().equals("required") || context.property().equals("unique");
        return List.of(stricter
                ? Changes.breaking(context.path(), description, Changes.KEEP_UNCONSTRAINED)
                : Changes.breaking(context.path(), description));
    }

    private static List<SchemaChange> sameSupertype(Context context, Kind kind) {
        Optional<TypeId> oldId = ((Optional<?>) context.oldValue())
                .flatMap(name -> context.oldSchema().lookup(kind, (String) name)).map(CompiledSchema.TypeEntry::id);
        Optional<TypeId> newId = ((Optional<?>) context.newValue())
                .flatMap(name -> context.newSchema().lookup(kind, (String) name)).map(CompiledSchema.TypeEntry::id);
        if (oldId.equals(newId)) {
            return List.of();
        }
        return List.of(Changes.breaking(context.path(), "changed the supertype from "
                + ((Optional<?>) context.oldValue()).map(Object::toString).orElse("none") + " to "
                + ((Optional<?>) context.newValue()).map(Object::toString).orElse("none")));
    }

    private static List<SchemaChange> traits(Context context) {
        List<TypeId> oldIds = traitIds(context.oldSchema(), context.oldValue());
        List<TypeId> newIds = traitIds(context.newSchema(), context.newValue());
        List<String> removed = oldIds.stream().filter(id -> !newIds.contains(id))
                .map(id -> context.oldSchema().entry(id).orElseThrow().name()).toList();
        if (!removed.isEmpty()) {
            return List.of(Changes.breaking(context.path(), "no longer includes the traits " + removed));
        }
        return List.of(Changes.compatible(context.path(), "includes more traits"));
    }

    private static List<TypeId> traitIds(CompiledSchema schema, Object names) {
        return ((List<?>) names).stream()
                .map(name -> schema.lookup(Kind.TRAIT, (String) name).orElseThrow().id())
                .toList();
    }

    /** A relationship end may move to a supertype of the old end, so every old relationship still fits. */
    private static List<SchemaChange> widenedEnd(Context context) {
        TypeId oldEnd = context.oldSchema().lookup(Kind.ENTITY_TYPE, (String) context.oldValue()).orElseThrow().id();
        TypeId newEnd = context.newSchema().lookup(Kind.ENTITY_TYPE, (String) context.newValue()).orElseThrow().id();
        if (oldEnd.equals(newEnd)) {
            return List.of();
        }
        if (context.newSchema().entry(oldEnd).isPresent() && context.newSchema().isSameOrSubtype(oldEnd, newEnd)) {
            String multiplicity = context.property().equals("source") ? "targetsPerSource" : "sourcesPerTarget";
            if (((Multiplicity) property(context.newElement(), multiplicity)).minimum() == 1) {
                List<String> extended = extendedExistingTypes(context, oldEnd, newEnd);
                if (!extended.isEmpty()) {
                    return List.of(Changes.breaking(context.path(), "widened the " + context.property() + " from "
                            + context.oldValue() + " to " + context.newValue() + ", which requires a relationship for "
                            + "the existing types " + extended));
                }
            }
            return List.of(Changes.compatible(context.path(), "widened the " + context.property() + " from "
                    + context.oldValue() + " to " + context.newValue()));
        }
        return List.of(Changes.breaking(context.path(), "changed the " + context.property() + " from "
                + context.oldValue() + " to " + context.newValue() + ", which is not one of its supertypes"));
    }

    /** Concrete entity types under the new end, but not under the old one, that existed before. */
    private static List<String> extendedExistingTypes(Context context, TypeId oldEnd, TypeId newEnd) {
        CompiledSchema schema = context.newSchema();
        List<TypeId> before = new ArrayList<>(schema.subtypeIds(oldEnd));
        before.add(oldEnd);
        List<TypeId> after = new ArrayList<>(schema.subtypeIds(newEnd));
        after.add(newEnd);
        return after.stream()
                .filter(id -> !before.contains(id) && context.oldSchema().isConcreteEntityType(id))
                .map(id -> schema.entry(id).orElseThrow().name())
                .sorted()
                .toList();
    }

    private static List<SchemaChange> relaxedMultiplicity(Context context) {
        Multiplicity oldValue = (Multiplicity) context.oldValue();
        Multiplicity newValue = (Multiplicity) context.newValue();
        if (newValue.minimum() <= oldValue.minimum() && newValue.maximum() >= oldValue.maximum()) {
            return List.of(Changes.compatible(context.path(), "relaxed " + context.property() + " from "
                    + oldValue + " to " + newValue));
        }
        return List.of(Changes.breaking(context.path(), "narrowed " + context.property() + " from "
                + oldValue + " to " + newValue));
    }

    private static List<SchemaChange> widenedValueType(Context context) {
        ValueType oldType = (ValueType) context.oldValue();
        ValueType newType = (ValueType) context.newValue();
        if (WIDENINGS.contains(oldType + ">" + newType)) {
            return List.of(Changes.compatible(context.path(), "widened the value type from " + oldType + " to " + newType));
        }
        return List.of(Changes.breaking(context.path(), "changed the value type from " + oldType + " to " + newType));
    }

    private static List<SchemaChange> widenedRange(Context context) {
        @SuppressWarnings("unchecked")
        Optional<Range> oldRange = (Optional<Range>) context.oldValue();
        @SuppressWarnings("unchecked")
        Optional<Range> newRange = (Optional<Range>) context.newValue();
        if (Values.contains(newRange, oldRange)) {
            return List.of(Changes.compatible(context.path(), "widened the range"));
        }
        return List.of(Changes.breaking(context.path(), "narrowed the range", Changes.KEEP_UNCONSTRAINED));
    }

    private static List<SchemaChange> description(Context context) {
        Optional<?> oldValue = (Optional<?>) context.oldValue();
        Optional<?> newValue = (Optional<?>) context.newValue();
        String what = oldValue.isEmpty() ? "added the description"
                : newValue.isEmpty() ? "removed the description" : "changed the description";
        return List.of(Changes.compatible(context.path(), what));
    }

    /**
     * Allowed values may be given up, or added to when there already were some. Giving them to an attribute that had
     * none, or removing one, would make existing values invalid.
     */
    private static List<SchemaChange> allowedValues(Context context) {
        List<?> oldValues = (List<?>) context.oldValue();
        List<?> newValues = (List<?>) context.newValue();
        if (newValues.isEmpty()) {
            return List.of(Changes.compatible(context.path(), "removed the allowed values " + oldValues));
        }
        if (oldValues.isEmpty()) {
            return List.of(Changes.breaking(context.path(), "restricted the attribute to the allowed values "
                    + newValues, Changes.KEEP_UNCONSTRAINED));
        }
        List<?> removed = oldValues.stream().filter(value -> !newValues.contains(value)).toList();
        if (!removed.isEmpty()) {
            return List.of(Changes.breaking(context.path(), "removed the allowed values " + removed,
                    Changes.KEEP_UNCONSTRAINED));
        }
        List<?> added = newValues.stream().filter(value -> !oldValues.contains(value)).toList();
        return added.isEmpty() ? List.of()
                : List.of(Changes.compatible(context.path(), "added the allowed values " + added));
    }

    /** A pattern may be removed; adding or changing one could make existing values invalid. */
    private static List<SchemaChange> pattern(Context context) {
        Optional<?> oldValue = (Optional<?>) context.oldValue();
        Optional<?> newValue = (Optional<?>) context.newValue();
        if (newValue.isEmpty()) {
            return List.of(Changes.compatible(context.path(), "removed the pattern " + oldValue.orElseThrow()));
        }
        String description = oldValue.isEmpty() ? "added the pattern " + newValue.get()
                : "changed the pattern from " + oldValue.get() + " to " + newValue.get();
        return List.of(Changes.breaking(context.path(), description, Changes.KEEP_UNCONSTRAINED));
    }

    /** A minimum length may be lowered or removed. */
    private static List<SchemaChange> minLength(Context context) {
        return length(context, "minimum", true);
    }

    /** A maximum length may be raised or removed. */
    private static List<SchemaChange> maxLength(Context context) {
        return length(context, "maximum", false);
    }

    private static List<SchemaChange> length(Context context, String what, boolean loweringRelaxes) {
        OptionalInt oldValue = (OptionalInt) context.oldValue();
        OptionalInt newValue = (OptionalInt) context.newValue();
        if (newValue.isEmpty()) {
            return List.of(Changes.compatible(context.path(), "removed the " + what + " length"));
        }
        if (oldValue.isEmpty()) {
            return List.of(Changes.breaking(context.path(), "added the " + what + " length " + newValue.getAsInt(),
                    Changes.KEEP_UNCONSTRAINED));
        }
        boolean looser = loweringRelaxes
                ? newValue.getAsInt() < oldValue.getAsInt()
                : newValue.getAsInt() > oldValue.getAsInt();
        String description = (looser ? "relaxed" : "tightened") + " the " + what + " length from " + oldValue.getAsInt()
                + " to " + newValue.getAsInt();
        return List.of(looser
                ? Changes.compatible(context.path(), description)
                : Changes.breaking(context.path(), description, Changes.KEEP_UNCONSTRAINED));
    }

    /**
     * Rules may be removed. Adding one, or changing the version of one, would check existing data in a new way. One
     * change is reported for each rule, located at the type and the rule's name.
     */
    private static List<SchemaChange> rules(Context context) {
        List<?> oldRules = (List<?>) context.oldValue();
        List<?> newRules = (List<?>) context.newValue();
        List<SchemaChange> changes = new ArrayList<>();
        for (Object element : newRules) {
            RuleReference rule = (RuleReference) element;
            if (oldRules.contains(rule)) {
                continue;
            }
            String path = Names.path(context.path(), rule.name());
            Optional<RuleReference> previous = oldRules.stream().map(RuleReference.class::cast)
                    .filter(candidate -> candidate.name().equals(rule.name())).findFirst();
            changes.add(previous.isPresent()
                    ? Changes.breaking(path, "changed the version of the rule " + rule.name() + " from "
                            + previous.get().version() + " to " + rule.version(), Changes.KEEP_UNCONSTRAINED)
                    : Changes.breaking(path, "added the rule " + rule.name() + " in version " + rule.version(),
                            Changes.KEEP_UNCONSTRAINED));
        }
        for (Object element : oldRules) {
            RuleReference rule = (RuleReference) element;
            if (newRules.stream().map(RuleReference.class::cast).noneMatch(candidate -> candidate.name().equals(rule.name()))) {
                changes.add(Changes.compatible(Names.path(context.path(), rule.name()), "removed the rule " + rule.name()
                        + " in version " + rule.version()));
            }
        }
        return changes;
    }

    /**
     * Checks may be removed, and their messages may change. Adding one, or changing the expression of one, would check
     * existing data in a new way (design D5 of {@code add-cel-expressions}). One change is reported for each check,
     * located at the type and the check's name.
     */
    private static List<SchemaChange> checks(Context context) {
        List<?> oldChecks = (List<?>) context.oldValue();
        List<?> newChecks = (List<?>) context.newValue();
        List<SchemaChange> changes = new ArrayList<>();
        for (Object element : newChecks) {
            Check check = (Check) element;
            if (oldChecks.contains(check)) {
                continue;
            }
            String path = Names.path(context.path(), check.name());
            Optional<Check> previous = oldChecks.stream().map(Check.class::cast)
                    .filter(candidate -> candidate.name().equals(check.name())).findFirst();
            if (previous.isEmpty()) {
                changes.add(Changes.breaking(path, "added the check " + check.name(), Changes.KEEP_UNCONSTRAINED));
            } else if (!previous.get().expression().equals(check.expression())) {
                changes.add(Changes.breaking(path, "changed the expression of the check " + check.name() + " from "
                        + previous.get().expression() + " to " + check.expression(), Changes.KEEP_UNCONSTRAINED));
            } else {
                changes.add(Changes.compatible(path, "changed the message of the check " + check.name()));
            }
        }
        for (Object element : oldChecks) {
            Check check = (Check) element;
            if (newChecks.stream().map(Check.class::cast).noneMatch(candidate -> candidate.name().equals(check.name()))) {
                changes.add(Changes.compatible(Names.path(context.path(), check.name()),
                        "removed the check " + check.name()));
            }
        }
        return changes;
    }

    // ------------------------------------------------------------------ reflection

    static Object property(Record element, String name) {
        try {
            for (RecordComponent component : element.getClass().getRecordComponents()) {
                if (component.getName().equals(name)) {
                    return component.getAccessor().invoke(element);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read " + name + " of " + element.getClass().getSimpleName(), e);
        }
        throw new IllegalArgumentException(element.getClass().getSimpleName() + " has no property " + name);
    }
}
