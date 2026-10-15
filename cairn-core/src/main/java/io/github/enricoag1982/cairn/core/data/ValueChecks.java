package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.schema.IRegexp;
import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.core.schema.Values;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Checks a value against what its attribute demands (design D6): the value type, where an int fits a long or decimal
 * attribute and a long fits a decimal attribute, then the range, the allowed values, the pattern and the lengths in
 * Unicode code points. Patterns are compiled once (ADR-0020).
 */
final class ValueChecks {

    private final ConcurrentMap<String, Optional<IRegexp>> patterns = new ConcurrentHashMap<>();

    /**
     * Checks a value.
     *
     * @param path      the path of the attribute of the entity, such as {@code $acme.name}
     * @param attribute the attribute in the schema that is checked against
     * @param value     the value
     * @return the problems, none if the value is acceptable; a value of the wrong type is reported alone
     */
    List<Problem> check(String path, Attribute attribute, Value value) {
        List<Problem> problems = new ArrayList<>();
        String shown = Values.describe(value);
        if (!Values.fits(value, attribute.valueType())) {
            problems.add(Problems.of(ProblemCode.VALUE_TYPE_MISMATCH, path, "The attribute " + path + " takes "
                    + withArticle(attribute.valueType()) + ", but the value " + shown + " is "
                    + withArticle(value.valueType()) + ".", withArticle(attribute.valueType()),
                    withArticle(value.valueType()) + " (" + shown + ")", null));
            return problems;
        }
        if (attribute.range().isPresent() && !Values.within(value, attribute.range().get())) {
            problems.add(Problems.of(ProblemCode.VALUE_OUT_OF_RANGE, path, "The value " + shown + " of " + path
                    + " is outside the range of the attribute.",
                    "a value " + Values.describe(attribute.range().get()), shown, null));
        }
        if (value instanceof Value.StringValue(String text)) {
            checkText(problems, path, attribute, text);
        }
        return problems;
    }

    private void checkText(List<Problem> problems, String path, Attribute attribute, String text) {
        if (!attribute.allowedValues().isEmpty() && !attribute.allowedValues().contains(text)) {
            problems.add(Problems.of(ProblemCode.VALUE_NOT_ALLOWED, path, "The value '" + text + "' of " + path
                    + " is not one of the allowed values.", "one of " + String.join(", ", attribute.allowedValues()),
                    text, null));
        }
        if (attribute.pattern().isPresent()) {
            String pattern = attribute.pattern().get();
            Optional<IRegexp> compiled = patterns.computeIfAbsent(pattern, ValueChecks::compile);
            if (compiled.isPresent() && !compiled.get().matches(text)) {
                problems.add(Problems.of(ProblemCode.VALUE_PATTERN_MISMATCH, path, "The value '" + text + "' of "
                        + path + " does not match the pattern " + pattern + ".", "a value matching " + pattern, text,
                        null));
            }
        }
        int length = text.codePointCount(0, text.length());
        if (attribute.minLength().isPresent() && length < attribute.minLength().getAsInt()) {
            problems.add(Problems.of(ProblemCode.VALUE_LENGTH, path, "The value '" + text + "' of " + path
                    + " is shorter than the minimum length " + attribute.minLength().getAsInt() + ".",
                    "at least " + attribute.minLength().getAsInt() + " characters",
                    length + " characters", null));
        }
        if (attribute.maxLength().isPresent() && length > attribute.maxLength().getAsInt()) {
            problems.add(Problems.of(ProblemCode.VALUE_LENGTH, path, "The value '" + text + "' of " + path
                    + " is longer than the maximum length " + attribute.maxLength().getAsInt() + ".",
                    "at most " + attribute.maxLength().getAsInt() + " characters",
                    length + " characters", null));
        }
    }

    /** A pattern of a registered schema is valid; one that is not cannot be checked and is left out. */
    private static Optional<IRegexp> compile(String pattern) {
        try {
            return Optional.of(IRegexp.of(pattern));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** A value type with its indefinite article, such as {@code a string} or {@code an int}. */
    static String withArticle(ValueType type) {
        String name = Values.typeName(type);
        return (type == ValueType.INT || type == ValueType.INSTANT ? "an " : "a ") + name;
    }
}
