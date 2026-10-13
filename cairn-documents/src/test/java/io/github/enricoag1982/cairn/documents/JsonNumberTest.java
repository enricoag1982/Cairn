package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.document;
import static io.github.enricoag1982.cairn.documents.Documents.problem;
import static io.github.enricoag1982.cairn.documents.Documents.valid;
import static io.github.enricoag1982.cairn.schema.ProblemCode.INVALID_VALUE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Numbers follow the JSON number grammar {@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?}: the forms that only YAML
 * knows are values of the wrong kind wherever a number is expected, and wherever a plain scalar would be read as a number
 * (design D6).
 */
class JsonNumberTest {

    /** A YAML number, and how JSON writes it. */
    static Stream<Arguments> yamlOnlyNumbers() {
        return Stream.of(
                Arguments.of("0x10", "16"),
                Arguments.of("0x1F", "31"),
                Arguments.of("0o17", "15"),
                Arguments.of("+1", "1"),
                Arguments.of("+1.5", "1.5"),
                Arguments.of("+1e3", "1e3"),
                Arguments.of(".5", "0.5"),
                Arguments.of("-.5", "-0.5"),
                Arguments.of("1.", "1"),
                Arguments.of("-1.", "-1"),
                Arguments.of("1.e3", "1e3"),
                Arguments.of("01", "1"),
                Arguments.of("-01", "-1"),
                Arguments.of("00", "0"),
                Arguments.of("007.50", "7.50"));
    }

    static Stream<String> jsonNumbers() {
        return Stream.of("0", "-0", "7", "-3", "10", "123456789012345678901234567890", "0.5", "-0.25", "1.50", "0.0",
                "1e3", "1E3", "1e+3", "1E-3", "0.5e10", "-1.5E+3", "10e0");
    }

    private static String attribute(String type, String properties) {
        return document("entityTypes:", "  Thing:", "    attributes:", "      value: { type: " + type + ", " + properties + " }");
    }

    private static AttributeDefinition read(String type, String properties) {
        return valid(attribute(type, properties)).definition().entityTypes().getFirst().attributes().getFirst();
    }

    /** Asserts that a text is rejected as an INVALID_VALUE at a place, and that the hint shows the JSON number. */
    private static void assertRejected(String text, String path, String pointer, String json) {
        Problem problem = problem(text);

        assertThat(problem.code()).as(text).isEqualTo(INVALID_VALUE);
        assertThat(problem.path()).as(text).isEqualTo(path);
        assertThat(problem.location().orElseThrow().pointer()).as(text).isEqualTo(pointer);
        assertThat(problem.found()).as(text).hasValueSatisfying(found -> assertThat(found).startsWith("the number "));
        assertThat(problem.hint()).as(text).contains("Write it as a JSON number, such as " + json + ".");
    }

    // ------------------------------------------------------------------ accepted

    @ParameterizedTest(name = "{0}")
    @MethodSource("jsonNumbers")
    void aNumberInTheFormOfJsonIsReadAsADecimal(String number) {
        AttributeDefinition decimal = read("decimal",
                "examples: [" + number + "], range: { atLeast: " + number + ", lessThan: " + number + " }");

        assertThat(decimal.examples()).containsExactly(Value.of(new BigDecimal(number)));
        assertThat(decimal.range()).contains(Range.between(
                Bound.inclusive(Value.of(new BigDecimal(number))), Bound.exclusive(Value.of(new BigDecimal(number)))));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0", "-0", "7", "-3", "10", "2147483647", "-2147483648"})
    void anIntegerInTheFormOfJsonIsReadAsAnInt(String number) {
        AttributeDefinition string = read("string", "minLength: " + number + ", maxLength: " + number);
        AttributeDefinition integer = read("int", "examples: [" + number + "]");

        assertThat(string.minLength()).hasValue(Integer.parseInt(number));
        assertThat(string.maxLength()).hasValue(Integer.parseInt(number));
        assertThat(integer.examples()).containsExactly(Value.of(Integer.parseInt(number)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0", "-0", "7", "-3", "3000000000", "-9223372036854775808", "9223372036854775807"})
    void anIntegerInTheFormOfJsonIsReadAsALong(String number) {
        assertThat(read("long", "examples: [" + number + "]").examples()).containsExactly(Value.of(Long.parseLong(number)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("jsonNumbers")
    void theDocumentIsTheSameAsJson(String number) {
        String yaml = attribute("decimal", "examples: [" + number + "]");
        String json = "{\"cairn\": 1, \"origin\": {\"name\": \"test-model\", \"version\": \"1.0.0\"}, \"entityTypes\": "
                + "{\"Thing\": {\"attributes\": {\"value\": {\"type\": \"decimal\", \"examples\": [" + number + "]}}}}}";

        assertThat(valid(json).definition()).isEqualTo(valid(yaml).definition());
    }

    // ------------------------------------------------------------------ rejected

    @ParameterizedTest(name = "{0}")
    @MethodSource("yamlOnlyNumbers")
    void aYamlOnlyNumberIsNotAnExampleOfADecimal(String number, String json) {
        assertRejected(attribute("decimal", "examples: [" + number + "]"), "Thing.value",
                "/entityTypes/Thing/attributes/value/examples/0", json);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("yamlOnlyNumbers")
    void aYamlOnlyNumberIsNotAnExampleWhateverTheValueType(String number, String json) {
        for (String type : List.of("string", "boolean", "int", "long", "date", "instant")) {
            assertRejected(attribute(type, "examples: [" + number + "]"), "Thing.value",
                    "/entityTypes/Thing/attributes/value/examples/0", json);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("yamlOnlyNumbers")
    void aYamlOnlyNumberIsNotABound(String number, String json) {
        assertRejected(attribute("decimal", "range: { atLeast: " + number + " }"), "Thing.value",
                "/entityTypes/Thing/attributes/value/range/atLeast", json);
        assertRejected(attribute("int", "range: { greaterThan: 0, lessThan: " + number + " }"), "Thing.value",
                "/entityTypes/Thing/attributes/value/range/lessThan", json);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("yamlOnlyNumbers")
    void aYamlOnlyNumberIsNotALength(String number, String json) {
        assertRejected(attribute("string", "minLength: " + number), "Thing.value",
                "/entityTypes/Thing/attributes/value/minLength", json);
        assertRejected(attribute("string", "maxLength: " + number), "Thing.value",
                "/entityTypes/Thing/attributes/value/maxLength", json);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("yamlOnlyNumbers")
    void aYamlOnlyNumberIsNotTheVersionOfARule(String number, String json) {
        assertRejected(document("entityTypes:", "  Thing:", "    rules:", "      - { name: a-rule, version: " + number + " }"),
                "Thing.a-rule", "/entityTypes/Thing/rules/0/version", json);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("yamlOnlyNumbers")
    void aYamlOnlyNumberIsNotTheFormatVersion(String number, String json) {
        assertRejected("cairn: " + number + "\norigin: { name: test-model, version: \"1.0.0\" }\n", "@document", "/cairn",
                json);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("yamlOnlyNumbers")
    void aYamlOnlyNumberIsNoStringEither(String number, String json) {
        Problem version = problem("cairn: 1\norigin: { name: test-model, version: " + number + " }\n");
        Problem description = problem(document("entityTypes:", "  Thing: { description: " + number + " }"));

        assertThat(version.code()).isEqualTo(INVALID_VALUE);
        assertThat(version.path()).isEqualTo("@origin");
        assertThat(version.location().orElseThrow().pointer()).isEqualTo("/origin/version");
        assertThat(version.expected()).contains("a string");
        assertThat(version.hint()).contains("Put the value in quotes: \"" + number + "\".");
        assertThat(description.code()).isEqualTo(INVALID_VALUE);
        assertThat(description.hint()).contains("Put the value in quotes: \"" + number + "\".");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {".inf", "-.inf", ".nan", ".Inf", ".NaN"})
    void infinityAndNotANumberAreNoNumbersAtAll(String number) {
        Problem problem = problem(attribute("decimal", "examples: [" + number + "]"));

        assertThat(problem.code()).isEqualTo(INVALID_VALUE);
        assertThat(problem.found()).hasValueSatisfying(found -> assertThat(found).startsWith("the number " + number));
        assertThat(problem.hint()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("yamlOnlyNumbers")
    void aYamlOnlyNumberInQuotesIsJustAString(String number, String json) {
        assertThat(read("string", "examples: [\"" + number + "\"], allowedValues: ['" + number + "']").examples())
                .containsExactly(Value.of(number));
        assertThat(read("date", "examples: ['" + number + "']").examples()).containsExactly(Value.of(number));
        assertThat(valid(document("entityTypes:", "  Thing: { description: \"" + number + "\" }")).definition()
                .entityTypes().getFirst().description()).isEqualTo(Optional.of(number));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"1_000", "0b11", "1,000", "1 000", "0x", "e5", "1e", "0xG", "1.2.3", "1-2"})
    void textThatIsNoYamlNumberIsAString(String text) {
        assertThat(read("string", "examples: [" + (text.contains(",") ? "\"" + text + "\"" : text) + "]").examples())
                .containsExactly(Value.of(text));
    }
}
