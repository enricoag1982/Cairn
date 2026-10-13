package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.schema.ValueType.STRING;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.schema.IRegexp;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The translation of I-Regexp to ECMAScript ({@link EcmaScriptPatterns}) is a copy of the engine's, because this module
 * does not depend on the engine (ADR-0020, design D5 of {@code add-json-payloads}). The engine's is not exported, so the
 * tests reach it by reflection on the class path and compare the two on sample patterns; and they compare what a
 * validator says about sample values, with the pattern in a generated JSON Schema, with what the engine says.
 */
class EcmaScriptPatternsTest {

    /** Valid I-Regexp patterns: anchors, dots, classes, escapes, categories, groups, alternatives and repeats. */
    private static final List<String> PATTERNS = List.of(
            "", "a", "abc", "[A-Z]{3}", "[A-Z0-9]{18}[0-9]{2}", "a|b", "(ab)+c?", "(a|b)*c", "x{2,3}", "x{2,}", "a{3}",
            "a.b", ".*", ".", "..", "\\.", "[.]", "[a.]b.", "\\\\.", "\\p{L}.", "\\p{Lu}\\P{Nd}", "[\\p{L}.]", "[\\p{L}-]+",
            "a$", "$", "^a", "^", "^$", "[^a$]", "[a^]", "[^^]", "\\^", "a\\-b", "[\\-a]", "[a\\-]", "\\\\-", "\\-",
            "\\(\\)\\[\\]\\{\\}\\|\\*\\+\\?", "[\\]]", "[\\[]", "[a-z]+(\\.[a-z]+)*", "\\n\\r\\t", "[\\n\\r]", "(.|\\n)*");

    private static String enginesTranslation(String pattern) {
        try {
            Method ecmaScript = IRegexp.class.getDeclaredMethod("ecmaScript");
            ecmaScript.setAccessible(true);
            return (String) ecmaScript.invoke(IRegexp.of(pattern));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError("The engine's translation is out of reach", e);
        } catch (InvocationTargetException e) {
            throw new AssertionError("The engine's translation of '" + pattern + "' failed", e.getCause());
        }
    }

    @Test
    void theTranslationIsTheEnginesOnSamplePatterns() {
        for (String pattern : PATTERNS) {
            assertThat(EcmaScriptPatterns.of(pattern)).as("translation of '%s'", pattern)
                    .isEqualTo(enginesTranslation(pattern));
        }
        assertThat(PATTERNS.size()).isGreaterThan(40);
    }

    @Test
    void thePatternIsAnchoredAtBothEnds() {
        assertThat(EcmaScriptPatterns.of("[A-Z]{3}")).isEqualTo("^(?:[A-Z]{3})$");
        assertThat(EcmaScriptPatterns.of("")).isEqualTo("^(?:)$");
        assertThat(EcmaScriptPatterns.of("a|b")).isEqualTo("^(?:a|b)$");
    }

    @Test
    void aDotOutsideAClassBecomesAClassWithoutLineBreaks() {
        assertThat(EcmaScriptPatterns.of("a.b")).isEqualTo("^(?:a[^\\n\\r]b)$");
        assertThat(EcmaScriptPatterns.of("\\.")).isEqualTo("^(?:\\.)$");
        assertThat(EcmaScriptPatterns.of("[a.]b.")).isEqualTo("^(?:[a.]b[^\\n\\r])$");
        assertThat(EcmaScriptPatterns.of("\\\\.")).isEqualTo("^(?:\\\\[^\\n\\r])$");
    }

    @Test
    void caretAndDollarOutsideAClassAreEscapedBecauseTheyAreLiteral() {
        assertThat(EcmaScriptPatterns.of("a$")).isEqualTo("^(?:a\\$)$");
        assertThat(EcmaScriptPatterns.of("^a")).isEqualTo("^(?:\\^a)$");
        assertThat(EcmaScriptPatterns.of("[^a$]")).isEqualTo("^(?:[^a$])$");
        assertThat(EcmaScriptPatterns.of("\\^")).isEqualTo("^(?:\\^)$");
    }

    @Test
    void aHyphenEscapeOutsideAClassIsWrittenPlain() {
        assertThat(EcmaScriptPatterns.of("a\\-b")).isEqualTo("^(?:a-b)$");
        assertThat(EcmaScriptPatterns.of("[\\-a]")).isEqualTo("^(?:[\\-a])$");
        assertThat(EcmaScriptPatterns.of("\\\\-")).isEqualTo("^(?:\\\\-)$");
    }

    @Test
    void categoriesAreKeptWhole() {
        assertThat(EcmaScriptPatterns.of("\\p{Lu}\\P{Nd}")).isEqualTo("^(?:\\p{Lu}\\P{Nd})$");
        assertThat(EcmaScriptPatterns.of("[\\p{L}.]")).isEqualTo("^(?:[\\p{L}.])$");
    }

    @Test
    void aTruncatedPatternDoesNotBreakTheTranslation() {
        assertThat(EcmaScriptPatterns.of("a\\")).isEqualTo("^(?:a\\)$");
        assertThat(EcmaScriptPatterns.of("\\p{L")).isEqualTo("^(?:\\p{L)$");
    }

    // ------------------------------------------------------------------ a validator and the engine

    /** A pattern and values to match, some of which match. */
    private record Sample(String pattern, List<String> values) {
    }

    private static final List<Sample> SAMPLES = List.of(
            new Sample("[A-Z]{3}", List.of("EUR", "eur", "EURO", "EU", "", "EU1")),
            new Sample("[A-Z0-9]{18}[0-9]{2}", List.of("5493001KJTIIGC8Y1R12", "5493001kjtiigc8y1r12", "5493001KJTIIGC8Y1R1X")),
            new Sample("a|b", List.of("a", "b", "ab", "")),
            new Sample("a.b", List.of("axb", "a.b", "ab", "a\nb", "a\rb", "a b", "axxb")),
            new Sample("\\.", List.of(".", "x", "")),
            new Sample("\\p{L}.", List.of("xy", "1y", "x1", "x\n", "x")),
            new Sample("a$", List.of("a$", "a", "$")),
            new Sample("^a", List.of("^a", "a", "^")),
            new Sample("[^a$]", List.of("b", "a", "$", "")),
            new Sample("a\\-b", List.of("a-b", "ab", "a\\-b")),
            new Sample("[\\-a]", List.of("-", "a", "b")),
            new Sample("", List.of("", "a")),
            new Sample("[a-z]+(\\.[a-z]+)*", List.of("a", "a.b.c", "a.", ".a", "A")),
            new Sample("x{2,3}", List.of("x", "xx", "xxx", "xxxx")));

    @Test
    void aValidatorMatchesSampleValuesAsTheEngineDoes() {
        int compared = 0;
        for (Sample sample : SAMPLES) {
            Schema schema = PayloadFixtures.schemaOf(SchemaDefinition.builder()
                    .entityType("Thing", thing -> thing.attribute("code", STRING, a -> a.pattern(sample.pattern())))
                    .build());
            String jsonSchema = JsonSchemas.values(schema, "Thing");
            IRegexp engine = IRegexp.of(sample.pattern());
            for (String value : sample.values()) {
                String json = "{\"code\": " + Quoting.doubleQuoted(value) + "}";

                assertThat(GeneratedSchemaCheck.isValid(jsonSchema, json))
                        .as("'%s' against the pattern '%s'", value, sample.pattern())
                        .isEqualTo(engine.matches(value));
                compared++;
            }
        }
        assertThat(compared).isGreaterThan(50);
    }
}
