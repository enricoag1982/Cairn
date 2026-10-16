package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.schema.ClosestName;
import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What every kind of write checks about its periods and values (design D5 of {@code add-entity-versions}): that a
 * period ends after it starts, and that the names of values are attributes of the type, which are not derived and are
 * converted to the value type of their attribute (design D4 of {@code add-derived-attributes}). Shared by
 * {@link Creations} and {@link Amendments}.
 */
final class WriteChecks {

    /**
     * Values of a write resolved to attributes. The values of derived attributes are left out: a write cannot set them.
     *
     * @param values          the values by attribute
     * @param attributes      the attributes that the write sets
     * @param unresolvedNames whether a name is no attribute of the type
     * @param derivedNamed    whether a name is a derived attribute of the type, which was reported
     */
    record Resolved(Map<AttributeId, Value> values, List<Attribute> attributes, boolean unresolvedNames,
            boolean derivedNamed) {
    }

    private final ProblemLog problems;

    WriteChecks(ProblemLog problems) {
        this.problems = problems;
    }

    /** Reports a period that does not end after it starts; tells whether the period is valid. */
    boolean checkPeriod(String path, EffectivePeriod period) {
        if (period.endsAfterStart()) {
            return true;
        }
        problems.add(Problems.of(ProblemCode.INVALID_PERIOD, path, "The effective period from " + period.from()
                + " until " + period.until().orElseThrow() + " does not end after it starts.",
                "an end after " + period.from(), period.until().orElseThrow().toString(), null));
        return false;
    }

    /**
     * Resolves the names of values to attributes of the type, reporting names that are unknown and attributes that are
     * deprecated. A name given twice, or an alias together with the name, keeps the later value. Values are converted
     * to the value type of their attribute; a value that does not fit is kept as it is, for validation to report. An
     * attribute that is derived is reported with {@code DERIVED_ATTRIBUTE_WRITTEN} and its value is dropped.
     */
    Resolved resolveValues(String path, SubjectType type, Map<String, Value> values) {
        Map<AttributeId, Value> resolved = new LinkedHashMap<>();
        List<Attribute> attributes = new ArrayList<>();
        boolean unresolved = false;
        boolean derivedNamed = false;
        for (Map.Entry<String, Value> entry : values.entrySet()) {
            Optional<Attribute> found = type.attribute(entry.getKey());
            if (found.isEmpty()) {
                unknownAttribute(path, type, entry.getKey());
                unresolved = true;
                continue;
            }
            Attribute attribute = found.get();
            if (attribute.derived().isPresent()) {
                derivedAttributeWritten(path, type, attribute);
                derivedNamed = true;
                continue;
            }
            if (attribute.deprecated()) {
                problems.add(Problems.of(ProblemCode.DEPRECATED_ATTRIBUTE, attributePath(path, attribute),
                        "The attribute " + attribute.name() + " of " + type.name() + " is deprecated, so a write may "
                        + "not set it.", "a write without the attribute " + attribute.name(),
                        "a value for " + attribute.name(), null));
            }
            resolved.put(attribute.id(), Conversions.convert(entry.getValue(), attribute.valueType()));
            if (!attributes.contains(attribute)) {
                attributes.add(attribute);
            }
        }
        return new Resolved(resolved, attributes, unresolved, derivedNamed);
    }

    /** Reports that a write names a derived attribute: the store computes its value, and no write sets or clears it. */
    void derivedAttributeWritten(String path, SubjectType type, Attribute attribute) {
        problems.add(Problems.of(ProblemCode.DERIVED_ATTRIBUTE_WRITTEN, attributePath(path, attribute), "The attribute "
                + attribute.name() + " of " + type.name() + " is derived, so a write cannot set it: the store computes it "
                + "from the other values of the version when the transaction commits.",
                "a write that leaves out the attribute " + attribute.name(), "a write that names " + attribute.name(),
                "Leave " + attribute.name() + " out of the write."));
    }

    void unknownAttribute(String path, SubjectType type, String name) {
        List<ClosestName.Candidate> candidates = type.attributes().stream()
                .flatMap(attribute -> ClosestName.candidates(attribute.name(), attribute.aliases()).stream())
                .toList();
        String names = type.attributes().stream().map(Attribute::name).collect(Collectors.joining(", "));
        problems.add(Problems.of(ProblemCode.UNKNOWN_ATTRIBUTE, path + "." + name, "The " + type.kind() + " type "
                + type.name() + " has no attribute '" + name + "'.", names.isEmpty() ? "an attribute of " + type.name()
                : "one of " + names, name, ClosestName.ofCandidates(name, candidates)
                        .map(closest -> "Did you mean '" + closest + "'?").orElse(null)));
    }

    /** The path of a problem about an attribute of the subject that is called {@code path}. */
    static String attributePath(String path, Attribute attribute) {
        return path + "." + attribute.name();
    }
}
