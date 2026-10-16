package io.github.enricoag1982.cairn.agents;

import io.github.enricoag1982.cairn.data.KnownAt;
import io.github.enricoag1982.cairn.documents.Instants;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The arguments of one tool call, read from JSON text (design D2 of {@code add-agent-tools}). Reading never throws:
 * a missing argument, one of the wrong kind or an unknown one becomes a problem with the path
 * {@code @arguments.<name>}, and the accessors return {@code null} (or empty) for it.
 */
final class Arguments {

    private static final String EXAMPLE_INSTANT = "2026-03-01T00:00:00Z";
    private static final String EXAMPLE_OFFSET_INSTANT = "2026-03-01T00:00:00+01:00";

    private final ObjectNode values;
    private final List<Problem> problems = new ArrayList<>();

    private Arguments(ObjectNode values) {
        this.values = values;
    }

    /**
     * Reads the arguments of a call.
     *
     * @param text  the arguments as JSON text; null or blank is the empty object
     * @param known the names of the arguments the tool takes; any other name is a problem
     */
    static Arguments parse(String text, String... known) {
        if (text == null || text.isBlank()) {
            return new Arguments(Json.object());
        }
        JsonNode node;
        try {
            node = Json.parse(text);
        } catch (JacksonException e) {
            Arguments broken = new Arguments(Json.object());
            broken.problems.add(new Problem(ProblemCode.DOCUMENT_SYNTAX, "@arguments",
                    "The arguments are not valid JSON: " + firstLine(e.getOriginalMessage()),
                    Optional.of("a JSON object"), Optional.empty(),
                    Optional.of("Pass the arguments of the tool as one JSON object.")));
            return broken;
        }
        if (!(node instanceof ObjectNode object)) {
            Arguments broken = new Arguments(Json.object());
            broken.problems.add(new Problem(ProblemCode.INVALID_VALUE, "@arguments",
                    "The arguments must be a JSON object.", Optional.of("a JSON object"),
                    Optional.of(describe(node)), Optional.of("Pass the arguments of the tool as one JSON object.")));
            return broken;
        }
        Arguments arguments = new Arguments(object);
        for (String name : object.propertyNames()) {
            if (!Set.of(known).contains(name)) {
                arguments.problems.add(new Problem(ProblemCode.UNKNOWN_KEY, "@arguments." + name,
                        "The tool takes no argument '" + name + "'.",
                        Optional.of(known.length == 0 ? "no arguments" : String.join(", ", known)),
                        Optional.of(name), closest(name, known)
                                .map(closest -> "Did you mean '" + closest + "'?")));
            }
        }
        return arguments;
    }

    /** Tells whether the arguments are fine: no problem has been found so far. */
    boolean valid() {
        return problems.isEmpty();
    }

    /** The problems found so far, in the order in which the arguments were read. */
    List<Problem> problems() {
        return List.copyOf(problems);
    }

    /** A required argument that is text; null, with a problem, if it is missing or of another kind. */
    String string(String name) {
        JsonNode node = values.get(name);
        if (node == null || node.isNull()) {
            problems.add(missing(name, "a string"));
            return null;
        }
        if (!node.isString()) {
            problems.add(wrongKind(name, "a string", node));
            return null;
        }
        return node.stringValue();
    }

    /** An optional argument that is text; empty, with a problem if it is of another kind. */
    Optional<String> optionalString(String name) {
        JsonNode node = values.get(name);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!node.isString()) {
            problems.add(wrongKind(name, "a string", node));
            return Optional.empty();
        }
        return Optional.of(node.stringValue());
    }

    /**
     * An optional argument that is a JSON object, as JSON text; empty, with a problem, if it is of another kind.
     *
     * @param name     the name of the argument
     * @param expected what the object is, for the problem
     */
    Optional<String> optionalObject(String name, String expected) {
        JsonNode node = values.get(name);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!(node instanceof ObjectNode)) {
            problems.add(wrongKind(name, expected, node));
            return Optional.empty();
        }
        return Optional.of(Json.render(node));
    }

    /**
     * Checks that a call names an entity by exactly one of two arguments, an ID and a unique key: when no problem has
     * been found so far, a call with neither or with both has a problem at the path of the ID.
     *
     * @param id  the name of the argument that holds the ID
     * @param key the name of the argument that holds the key
     * @param hasId  whether the call gave the ID
     * @param hasKey whether the call gave the key
     */
    void exactlyOne(String id, boolean hasId, String key, boolean hasKey) {
        if (!valid() || hasId != hasKey) {
            return;
        }
        String hint = "Use '" + id + "' for the ID of an entity, or '" + key + "' for a unique key such as "
                + "{\"entityType\": \"Account\", \"key\": {\"number\": \"A-1\"}}.";
        if (hasId) {
            problems.add(new Problem(ProblemCode.INVALID_VALUE, "@arguments." + id,
                    "The call gives both '" + id + "' and '" + key + "', but names the entity in one way.",
                    Optional.of("exactly one of " + id + " and " + key), Optional.of("both"), Optional.of(hint)));
        } else {
            problems.add(new Problem(ProblemCode.MISSING_KEY, "@arguments." + id,
                    "The call must name the entity by '" + id + "' or by '" + key + "'.",
                    Optional.of("exactly one of " + id + " and " + key), Optional.empty(), Optional.of(hint)));
        }
    }

    /**
     * An optional argument that is a whole number of at least 0; empty if it is absent or null, and, with a problem, if
     * it is not.
     */
    OptionalInt optionalCount(String name) {
        JsonNode node = values.get(name);
        if (node == null || node.isNull()) {
            return OptionalInt.empty();
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0) {
            problems.add(wrongKind(name, "a whole number of at least 0", node));
            return OptionalInt.empty();
        }
        return OptionalInt.of(node.intValue());
    }

    /**
     * An optional argument that is a whole number in a range; the default if it is absent, and, with a problem, if it is
     * not a whole number or is outside the range.
     *
     * @param name         the name of the argument
     * @param defaultValue what a call that does not give it gets
     * @param minimum      the smallest value
     * @param maximum      the largest value
     * @return the value, or the default if the argument is absent or wrong
     */
    long optionalWholeNumber(String name, long defaultValue, long minimum, long maximum) {
        JsonNode node = values.get(name);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        String expected = maximum == Long.MAX_VALUE ? "a whole number of at least " + minimum
                : "a whole number from " + minimum + " to " + maximum;
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            problems.add(wrongKind(name, expected, node));
            return defaultValue;
        }
        long value = node.longValue();
        if (value < minimum || value > maximum) {
            problems.add(new Problem(ProblemCode.INVALID_VALUE, "@arguments." + name,
                    "The argument '" + name + "' is out of range.", Optional.of(expected),
                    Optional.of(String.valueOf(value)), Optional.empty()));
            return defaultValue;
        }
        return value;
    }

    /**
     * A required argument that is an instant, in the form that payloads use ({@link Instants}); null, with a problem, if
     * it is missing or not one.
     */
    Instant instant(String name) {
        JsonNode node = values.get(name);
        if (node == null || node.isNull()) {
            problems.add(missing(name, "an ISO 8601 instant such as " + EXAMPLE_INSTANT));
            return null;
        }
        return instant("@arguments." + name, node);
    }

    /**
     * The optional argument {@code knownAt}: {@code {"basis": n}} or {@code {"transactionTime": "..."}}. Absent means
     * the latest basis.
     */
    KnownAt knownAt() {
        JsonNode node = values.get("knownAt");
        if (node == null || node.isNull()) {
            return KnownAt.latest();
        }
        String path = "@arguments.knownAt";
        String expected = "{\"basis\": <whole number>} or {\"transactionTime\": \"<ISO 8601 instant>\"}";
        if (!(node instanceof ObjectNode object) || object.size() != 1) {
            problems.add(new Problem(ProblemCode.INVALID_VALUE, path,
                    "knownAt must be an object with exactly one of basis and transactionTime.",
                    Optional.of(expected), Optional.of(describe(node)),
                    Optional.of("Leave knownAt out to read as of the latest basis.")));
            return KnownAt.latest();
        }
        JsonNode basis = object.get("basis");
        if (basis != null) {
            if (!basis.isIntegralNumber() || !basis.canConvertToLong() || basis.longValue() < 0) {
                problems.add(wrongKind("knownAt.basis", "a whole number of at least 0", basis));
                return KnownAt.latest();
            }
            return KnownAt.basis(new Basis(basis.longValue()));
        }
        JsonNode time = object.get("transactionTime");
        if (time != null) {
            Instant instant = instant(path + ".transactionTime", time);
            return instant == null ? KnownAt.latest() : KnownAt.transactionTime(instant);
        }
        String key = object.propertyNames().iterator().next();
        problems.add(new Problem(ProblemCode.UNKNOWN_KEY, path + "." + key,
                "knownAt takes basis or transactionTime, not '" + key + "'.", Optional.of("basis, transactionTime"),
                Optional.of(key), closest(key, "basis", "transactionTime")
                        .map(closest -> "Did you mean '" + closest + "'?")));
        return KnownAt.latest();
    }

    private Instant instant(String path, JsonNode node) {
        String expected = "an ISO 8601 instant such as " + EXAMPLE_INSTANT;
        if (!node.isString()) {
            problems.add(new Problem(ProblemCode.INVALID_VALUE, path, "The value must be text.",
                    Optional.of(expected), Optional.of(describe(node)), Optional.empty()));
            return null;
        }
        Optional<Instant> instant = Instants.read(node.stringValue());
        if (instant.isPresent()) {
            return instant.get();
        }
        problems.add(new Problem(ProblemCode.INVALID_VALUE, path,
                "The value is not an instant in ISO 8601 form, with seconds, and with Z or an offset with a colon.",
                Optional.of(expected), Optional.of(node.stringValue()),
                Optional.of("Write the date, T, the time with seconds, and Z or an offset with a colon, such as "
                        + EXAMPLE_INSTANT + " or " + EXAMPLE_OFFSET_INSTANT + ".")));
        return null;
    }

    private static Problem missing(String name, String expected) {
        return new Problem(ProblemCode.MISSING_KEY, "@arguments." + name, "The argument '" + name + "' is required.",
                Optional.of(expected), Optional.empty(), Optional.of("Add '" + name + "' to the arguments."));
    }

    private static Problem wrongKind(String name, String expected, JsonNode found) {
        return new Problem(ProblemCode.INVALID_VALUE, "@arguments." + name,
                "The argument '" + name + "' has the wrong kind of value.", Optional.of(expected),
                Optional.of(describe(found)), Optional.empty());
    }

    private static String describe(JsonNode node) {
        return switch (node.getNodeType()) {
            case STRING -> "a string";
            case NUMBER -> "the number " + node;
            case BOOLEAN -> "a boolean";
            case ARRAY -> "an array";
            case OBJECT -> "an object";
            case NULL -> "null";
            default -> node.getNodeType().name().toLowerCase(Locale.ROOT);
        };
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "syntax error";
        }
        int end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }

    /** The candidate closest to a name, if one is near enough to be a misspelling of it. */
    static Optional<String> closest(String name, String... candidates) {
        return closest(name, List.of(candidates));
    }

    /** The candidate closest to a name, if one is near enough to be a misspelling of it. */
    static Optional<String> closest(String name, Iterable<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = distance(name.toLowerCase(Locale.ROOT),
                    candidate.toLowerCase(Locale.ROOT));
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best != null && bestDistance <= Math.max(2, name.length() / 3) ? Optional.of(best) : Optional.empty();
    }

    /** The Levenshtein distance of two texts. */
    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
