package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.documents.WrittenNode.Mapping;
import io.github.enricoag1982.cairn.documents.WrittenNode.Plain;
import io.github.enricoag1982.cairn.documents.WrittenNode.Sequence;
import io.github.enricoag1982.cairn.documents.WrittenNode.Text;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the {@linkplain WrittenNode tree} of the JSON Schema (draft 2020-12) for the values of an entity type or a
 * relationship type (capability {@code json-payloads}, design D5).
 *
 * <p>The schema is an object with a property for each effective attribute, the ones the type inherits included, that
 * allows no other property, and requires the attributes that are required. A property has the value type of its
 * attribute: a {@code string}, with {@code enum} for the allowed values, a {@code pattern} translated to ECMAScript
 * ({@link EcmaScriptPatterns}), {@code minLength} and {@code maxLength}; an {@code integer} that fits 32 or 64 bits and
 * lies in the range; a {@code number} for a decimal, in the range; a {@code boolean}; a {@code string} of {@code format}
 * {@code date} or {@code date-time}, which also has a {@code pattern} ({@link #DATE_PATTERN}, {@link #INSTANT_PATTERN}),
 * because validators need not check formats. JSON Schema cannot order dates, so a range of dates or instants is said in
 * the description only. The description and the examples of the attribute are copied, and a deprecated attribute is
 * marked.
 */
final class ValuesSchema {

    /** The dialect of every generated JSON Schema. */
    static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

    /**
     * The pattern of a date, in ECMAScript, for validators that do not check {@code format}: the year, two digits of
     * the month and two of the day. The year has four digits, or a sign and more: {@code -} and four to nine digits, or
     * {@code +} and five to nine digits. The reader checks the form of a date with this pattern, and then reads it with
     * {@link java.time.LocalDate#parse}, so it accepts exactly the strings that this pattern accepts and that stand for
     * a date, on every Java version: {@code 2026-05-17}, {@code -0001-01-01} and {@code +12345-01-01}. The pattern also
     * accepts some that the reader does not, such as a date that does not exist or {@code -0000-01-01}, which is for
     * the reader to say.
     */
    static final String DATE_PATTERN = "^(?:[0-9]{4}|-[0-9]{4,9}|\\+[0-9]{5,9})-[0-9]{2}-[0-9]{2}$";

    /**
     * The pattern of an instant, in ECMAScript, for validators that do not check {@code format}: the date, {@code T},
     * the time of day with seconds and a fraction of up to nine digits if need be, and {@code Z} or an offset
     * {@code ±hh:mm}, which the reader converts to UTC. The reader checks the form of an instant with this pattern, and
     * then reads it with {@link java.time.Instant#parse}, so it accepts exactly the strings that this pattern accepts
     * and that stand for an instant, on every Java version: also {@code t} and {@code z}, an offset with seconds, and a
     * year of four to ten digits with a sign. An offset without a colon, such as {@code +0100}, is no instant, although
     * some Java versions read it. The pattern also accepts some strings that the reader does not, such as a date or an
     * offset that does not exist, or a year of five digits without a sign, which is for the reader to say.
     */
    static final String INSTANT_PATTERN = "^[+-]?[0-9]{4,10}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}"
            + "(\\.[0-9]{0,9})?([Zz]|[+-][0-9]{2}:[0-9]{2}(:[0-9]{2})?)$";

    private static final Plain FALSE = new Plain("false");

    private ValuesSchema() {
    }

    /**
     * The schema as a document of its own: with the dialect and a title, which {@link #definition} leaves out.
     *
     * @param kind        {@code entity type} or {@code relationship type}
     * @param name        the name of the type
     * @param description what the type means, if described
     * @param attributes  the effective attributes of the type
     */
    static Mapping document(String kind, String name, Optional<String> description, List<Attribute> attributes) {
        Mapping root = new Mapping().put("$schema", DIALECT).put("title", "Values of " + name);
        fill(root, kind, name, description, attributes);
        return root;
    }

    /** The schema as a definition inside another schema. */
    static Mapping definition(String kind, String name, Optional<String> description, List<Attribute> attributes) {
        Mapping mapping = new Mapping();
        fill(mapping, kind, name, description, attributes);
        return mapping;
    }

    private static void fill(
            Mapping target, String kind, String name, Optional<String> description, List<Attribute> attributes) {
        target.put("description", "The values of the " + kind + " " + name + "." + description.map(text -> " " + text).orElse(""));
        target.put("type", "object");
        target.put("additionalProperties", FALSE);
        List<Attribute> required = attributes.stream().filter(Attribute::required).toList();
        if (!required.isEmpty()) {
            target.put("required", new Sequence(required.stream().<WrittenNode>map(attribute -> new Text(attribute.name())).toList()));
        }
        Mapping properties = new Mapping();
        attributes.forEach(attribute -> properties.put(attribute.name(), attribute(attribute)));
        target.put("properties", properties);
    }

    // ------------------------------------------------------------------ attributes

    private static Mapping attribute(Attribute attribute) {
        Mapping mapping = new Mapping();
        String description = description(attribute);
        if (!description.isEmpty()) {
            mapping.put("description", description);
        }
        switch (attribute.valueType()) {
            case STRING -> {
                mapping.put("type", "string");
                if (!attribute.allowedValues().isEmpty()) {
                    mapping.put("enum", new Sequence(attribute.allowedValues().stream().<WrittenNode>map(Text::new).toList()));
                }
                attribute.pattern().ifPresent(pattern -> mapping.put("pattern", EcmaScriptPatterns.of(pattern)));
                attribute.minLength().ifPresent(length -> mapping.put("minLength", new Plain(Integer.toString(length))));
                attribute.maxLength().ifPresent(length -> mapping.put("maxLength", new Plain(Integer.toString(length))));
            }
            case BOOLEAN -> mapping.put("type", "boolean");
            case INT -> {
                mapping.put("type", "integer");
                bounds(mapping, attribute.range(), Integer.toString(Integer.MIN_VALUE), Integer.toString(Integer.MAX_VALUE));
            }
            case LONG -> {
                mapping.put("type", "integer");
                bounds(mapping, attribute.range(), Long.toString(Long.MIN_VALUE), Long.toString(Long.MAX_VALUE));
            }
            case DECIMAL -> {
                mapping.put("type", "number");
                bounds(mapping, attribute.range(), null, null);
            }
            case DATE -> mapping.put("type", "string").put("format", "date").put("pattern", DATE_PATTERN);
            case INSTANT -> mapping.put("type", "string").put("format", "date-time").put("pattern", INSTANT_PATTERN);
        }
        if (attribute.deprecated()) {
            mapping.putTrue("deprecated");
        }
        if (!attribute.examples().isEmpty()) {
            mapping.put("examples", new Sequence(attribute.examples().stream().map(DocumentWriter::literal).toList()));
        }
        return mapping;
    }

    /** The description of the attribute, with its range if JSON Schema cannot express it: for dates and instants. */
    private static String description(Attribute attribute) {
        String description = attribute.description().orElse("");
        if (isTimeLike(attribute) && attribute.range().isPresent()) {
            String range = rangeText(attribute.range().get());
            if (!range.isEmpty()) {
                description = description.isEmpty() ? range : description + " " + range;
            }
        }
        return description;
    }

    private static boolean isTimeLike(Attribute attribute) {
        return switch (attribute.valueType()) {
            case DATE, INSTANT -> true;
            default -> false;
        };
    }

    private static String rangeText(Range range) {
        List<String> parts = new ArrayList<>();
        range.lower().ifPresent(bound -> parts.add((bound.inclusive() ? "at least " : "greater than ") + text(bound.value())));
        range.upper().ifPresent(bound -> parts.add((bound.inclusive() ? "at most " : "less than ") + text(bound.value())));
        return parts.isEmpty() ? "" : "Range: " + String.join(" and ", parts) + ".";
    }

    private static String text(Value value) {
        return switch (value) {
            case Value.StringValue string -> string.value();
            case Value.BooleanValue bool -> Boolean.toString(bool.value());
            case Value.IntValue integer -> Integer.toString(integer.value());
            case Value.LongValue number -> Long.toString(number.value());
            case Value.DecimalValue decimal -> decimal.value().toString();
            case Value.DateValue date -> date.value().toString();
            case Value.InstantValue instant -> instant.value().toString();
        };
    }

    /**
     * Adds the bounds of a number: the bounds of its type, if it has any, replaced by the range's where the range is
     * inclusive and tighter, and the range's exclusive bounds besides.
     */
    private static void bounds(Mapping mapping, Optional<Range> range, String typeMinimum, String typeMaximum) {
        Optional<Bound> lower = range.flatMap(Range::lower);
        Optional<Bound> upper = range.flatMap(Range::upper);
        if (lower.isPresent() && lower.get().inclusive()) {
            mapping.put("minimum", DocumentWriter.literal(lower.get().value()));
        } else {
            if (typeMinimum != null) {
                mapping.put("minimum", new Plain(typeMinimum));
            }
            lower.ifPresent(bound -> mapping.put("exclusiveMinimum", DocumentWriter.literal(bound.value())));
        }
        if (upper.isPresent() && upper.get().inclusive()) {
            mapping.put("maximum", DocumentWriter.literal(upper.get().value()));
        } else {
            if (typeMaximum != null) {
                mapping.put("maximum", new Plain(typeMaximum));
            }
            upper.ifPresent(bound -> mapping.put("exclusiveMaximum", DocumentWriter.literal(bound.value())));
        }
    }
}
