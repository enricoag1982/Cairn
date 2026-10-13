package io.github.enricoag1982.sysrec.core.schema;

import io.github.enricoag1982.sysrec.schema.Multiplicity;
import io.github.enricoag1982.sysrec.schema.Range;
import io.github.enricoag1982.sysrec.schema.TypeId;
import io.github.enricoag1982.sysrec.schema.ValueType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Classifies the change of one property of a definition record as compatible or breaking (design D6).
 *
 * <p>Properties are compared generically, component by component. Each changed property is classified by the rule
 * registered for it; a property without a rule is breaking, so a forgotten rule errs on the safe side.
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
        Optional<Change> classify(Context context);
    }

    private static final Rule SKIP = context -> Optional.empty();
    private static final Rule NAME = PropertyRules::rename;
    private static final Rule ALIASES = context -> aliases(context, (String) property(context.oldElement(), "name"));
    private static final Rule INVERSE_NAME = PropertyRules::renameInverse;
    private static final Rule INVERSE_ALIASES = context ->
            aliases(context, (String) property(context.oldElement(), "inverseName"));
    private static final Rule MAY_BECOME_FALSE = context -> flag(context, false);
    private static final Rule MAY_BECOME_TRUE = context -> flag(context, true);
    private static final Rule ALWAYS_BREAKING = context -> Optional.of(Change.breaking(context.path(),
            "changed " + context.property() + " from " + context.oldValue() + " to " + context.newValue()));

    private static final Map<String, Rule> RULES = Map.ofEntries(
            Map.entry("entity.name", NAME),
            Map.entry("entity.aliases", ALIASES),
            Map.entry("entity.isAbstract", MAY_BECOME_FALSE),
            Map.entry("entity.supertype", context -> sameSupertype(context, Kind.ENTITY_TYPE)),
            Map.entry("entity.traits", PropertyRules::traits),
            Map.entry("entity.attributes", SKIP),
            Map.entry("entity.deprecated", MAY_BECOME_TRUE),
            Map.entry("trait.name", NAME),
            Map.entry("trait.aliases", ALIASES),
            Map.entry("trait.attributes", SKIP),
            Map.entry("trait.deprecated", MAY_BECOME_TRUE),
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
            Map.entry("attribute.name", NAME),
            Map.entry("attribute.aliases", ALIASES),
            Map.entry("attribute.valueType", PropertyRules::widenedValueType),
            Map.entry("attribute.required", MAY_BECOME_FALSE),
            Map.entry("attribute.unique", MAY_BECOME_FALSE),
            Map.entry("attribute.range", PropertyRules::widenedRange),
            Map.entry("attribute.deprecated", MAY_BECOME_TRUE));

    private static final Set<String> WIDENINGS = Set.of("INT>LONG", "INT>DECIMAL", "LONG>DECIMAL");

    private PropertyRules() {
    }

    /** Compares two versions of a definition record, property by property. */
    static List<Change> compare(String elementKind, Record oldElement, Record newElement, String path,
            CompiledSchema oldSchema, CompiledSchema newSchema) {
        List<Change> changes = new ArrayList<>();
        for (RecordComponent component : oldElement.getClass().getRecordComponents()) {
            Object oldValue = property(oldElement, component.getName());
            Object newValue = property(newElement, component.getName());
            if (!Objects.equals(oldValue, newValue)) {
                classify(elementKind, new Context(oldSchema, newSchema, path, component.getName(), oldValue, newValue,
                        oldElement, newElement)).ifPresent(changes::add);
            }
        }
        return changes;
    }

    /** Classifies a changed property; a property without a rule is breaking. */
    static Optional<Change> classify(String elementKind, Context context) {
        Rule rule = RULES.get(elementKind + "." + context.property());
        return rule == null ? ALWAYS_BREAKING.classify(context) : rule.classify(context);
    }

    // ------------------------------------------------------------------ rules

    private static Optional<Change> rename(Context context) {
        String oldName = (String) context.oldValue();
        String newName = (String) context.newValue();
        @SuppressWarnings("unchecked")
        List<String> aliases = (List<String>) property(context.newElement(), "aliases");
        if (Names.same(oldName, newName) || Names.contains(aliases, oldName)) {
            return Optional.of(Change.compatible(context.path(), "renamed " + oldName + " to " + newName));
        }
        return Optional.of(Change.breaking(context.path(), "renamed " + oldName + " to " + newName
                + " without keeping " + oldName + " as an alias"));
    }

    private static Optional<Change> renameInverse(Context context) {
        String oldName = (String) context.oldValue();
        String newName = (String) context.newValue();
        @SuppressWarnings("unchecked")
        List<String> aliases = (List<String>) property(context.newElement(), "inverseAliases");
        if (Names.same(oldName, newName) || Names.contains(aliases, oldName)) {
            return Optional.of(Change.compatible(context.path(), "renamed inverse " + oldName + " to " + newName));
        }
        return Optional.of(Change.breaking(context.path(), "renamed inverse " + oldName + " to " + newName
                + " without keeping " + oldName + " as an inverse alias"));
    }

    /** Aliases may not be dropped; the only alias that may be added is the previous name, as part of a rename. */
    private static Optional<Change> aliases(Context context, String previousName) {
        @SuppressWarnings("unchecked")
        List<String> oldAliases = (List<String>) context.oldValue();
        @SuppressWarnings("unchecked")
        List<String> newAliases = (List<String>) context.newValue();
        List<String> removed = oldAliases.stream().filter(alias -> !Names.contains(newAliases, alias)).toList();
        List<String> added = newAliases.stream().filter(alias -> !Names.contains(oldAliases, alias)).toList();
        if (!removed.isEmpty()) {
            return Optional.of(Change.breaking(context.path(), "removed the aliases " + removed));
        }
        if (added.stream().anyMatch(alias -> !Names.same(alias, previousName))) {
            return Optional.of(Change.breaking(context.path(), "added the aliases " + added
                    + " without renaming from them"));
        }
        return Optional.of(Change.compatible(context.path(), "kept " + previousName + " as an alias"));
    }

    private static Optional<Change> flag(Context context, boolean allowedNewValue) {
        if (Boolean.valueOf(allowedNewValue).equals(context.newValue())) {
            return Optional.of(Change.compatible(context.path(), "set " + context.property() + " to " + allowedNewValue));
        }
        return Optional.of(Change.breaking(context.path(), "set " + context.property() + " to " + context.newValue()));
    }

    private static Optional<Change> sameSupertype(Context context, Kind kind) {
        Optional<TypeId> oldId = ((Optional<?>) context.oldValue())
                .flatMap(name -> context.oldSchema().lookup(kind, (String) name)).map(CompiledSchema.TypeEntry::id);
        Optional<TypeId> newId = ((Optional<?>) context.newValue())
                .flatMap(name -> context.newSchema().lookup(kind, (String) name)).map(CompiledSchema.TypeEntry::id);
        if (oldId.equals(newId)) {
            return Optional.empty();
        }
        return Optional.of(Change.breaking(context.path(), "changed the supertype from "
                + ((Optional<?>) context.oldValue()).map(Object::toString).orElse("none") + " to "
                + ((Optional<?>) context.newValue()).map(Object::toString).orElse("none")));
    }

    private static Optional<Change> traits(Context context) {
        List<TypeId> oldIds = traitIds(context.oldSchema(), context.oldValue());
        List<TypeId> newIds = traitIds(context.newSchema(), context.newValue());
        List<String> removed = oldIds.stream().filter(id -> !newIds.contains(id))
                .map(id -> context.oldSchema().entry(id).orElseThrow().name()).toList();
        if (!removed.isEmpty()) {
            return Optional.of(Change.breaking(context.path(), "no longer includes the traits " + removed));
        }
        return Optional.of(Change.compatible(context.path(), "includes more traits"));
    }

    private static List<TypeId> traitIds(CompiledSchema schema, Object names) {
        return ((List<?>) names).stream()
                .map(name -> schema.lookup(Kind.TRAIT, (String) name).orElseThrow().id())
                .toList();
    }

    /** A relationship end may move to a supertype of the old end, so every old relationship still fits. */
    private static Optional<Change> widenedEnd(Context context) {
        TypeId oldEnd = context.oldSchema().lookup(Kind.ENTITY_TYPE, (String) context.oldValue()).orElseThrow().id();
        TypeId newEnd = context.newSchema().lookup(Kind.ENTITY_TYPE, (String) context.newValue()).orElseThrow().id();
        if (oldEnd.equals(newEnd)) {
            return Optional.empty();
        }
        if (context.newSchema().entry(oldEnd).isPresent() && context.newSchema().isSameOrSubtype(oldEnd, newEnd)) {
            return Optional.of(Change.compatible(context.path(), "widened the " + context.property() + " from "
                    + context.oldValue() + " to " + context.newValue()));
        }
        return Optional.of(Change.breaking(context.path(), "changed the " + context.property() + " from "
                + context.oldValue() + " to " + context.newValue() + ", which is not one of its supertypes"));
    }

    private static Optional<Change> relaxedMultiplicity(Context context) {
        Multiplicity oldValue = (Multiplicity) context.oldValue();
        Multiplicity newValue = (Multiplicity) context.newValue();
        if (newValue.minimum() <= oldValue.minimum() && newValue.maximum() >= oldValue.maximum()) {
            return Optional.of(Change.compatible(context.path(), "relaxed " + context.property() + " from "
                    + oldValue + " to " + newValue));
        }
        return Optional.of(Change.breaking(context.path(), "narrowed " + context.property() + " from "
                + oldValue + " to " + newValue));
    }

    private static Optional<Change> widenedValueType(Context context) {
        ValueType oldType = (ValueType) context.oldValue();
        ValueType newType = (ValueType) context.newValue();
        if (WIDENINGS.contains(oldType + ">" + newType)) {
            return Optional.of(Change.compatible(context.path(), "widened the value type from " + oldType + " to " + newType));
        }
        return Optional.of(Change.breaking(context.path(), "changed the value type from " + oldType + " to " + newType));
    }

    private static Optional<Change> widenedRange(Context context) {
        @SuppressWarnings("unchecked")
        Optional<Range> oldRange = (Optional<Range>) context.oldValue();
        @SuppressWarnings("unchecked")
        Optional<Range> newRange = (Optional<Range>) context.newValue();
        if (Literals.contains(newRange, oldRange)) {
            return Optional.of(Change.compatible(context.path(), "widened the range"));
        }
        return Optional.of(Change.breaking(context.path(), "narrowed the range"));
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
