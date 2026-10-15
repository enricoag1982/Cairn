package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.document;
import static io.github.enricoag1982.cairn.documents.Documents.problem;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.SchemaRegistries;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaDefinitionBuilder;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reader's rule for the closest key ({@link ClosestKey}) is a copy of the engine's rule for the closest name, because
 * this module does not depend on the engine (design D5). The engine's rule is not reachable from here, so the two are
 * compared through what each reports: the hint of an {@code UNKNOWN_REFERENCE} problem for a supertype that does not exist,
 * with the entity types as the names to choose from, and the hint of an {@code UNKNOWN_KEY} problem for a key that does
 * not exist, with the known keys to choose from.
 */
class ClosestNameAgreementTest {

    private static final Origin ORIGIN = new Origin("probe-model", "1.0.0");

    /** The entity type whose supertype is misspelled; it is never one of the names to choose from. */
    private static final String OWNER = "Zzz_owner";

    private static final Pattern SUGGESTION = Pattern.compile("^Did you mean '(.*)'\\?$");

    /** The known keys that are also names the engine accepts, per position of a document. */
    private static final List<List<String>> KEY_SETS = List.of(
            List.of("cairn", "origin", "traits", "entityTypes", "relationshipTypes"),
            Keys.ORIGIN, Keys.TRAIT, Keys.ENTITY_TYPE, Keys.RELATIONSHIP_TYPE, Keys.ATTRIBUTE, Keys.RANGE, Keys.RULE,
            Keys.VALUE_TYPES, Keys.KINDS);

    private final SchemaRegistry registry = SchemaRegistries.inMemory();

    /**
     * What the engine suggests for a supertype that is misspelled, among entity types that have the given names: the name
     * in its hint, or, if the engine finds the supertype itself, because only the case differs, that name.
     */
    private Optional<String> engineSuggests(String given, List<String> names) {
        SchemaDefinitionBuilder definition = SchemaDefinition.builder();
        names.forEach(definition::entityType);
        definition.entityType(OWNER, owner -> owner.supertype(given));

        List<Problem> problems = registry.validate(ORIGIN, definition.build());

        Optional<Problem> unknown = problems.stream()
                .filter(problem -> problem.code() == ProblemCode.UNKNOWN_REFERENCE && problem.path().equals(OWNER))
                .findFirst();
        if (unknown.isEmpty()) {
            assertThat(problems).as("problems for %s among %s", given, names).isEmpty();
            return names.stream().filter(name -> name.equalsIgnoreCase(given)).findFirst();
        }
        return suggestion(unknown.get());
    }

    private static Optional<String> suggestion(Problem problem) {
        return problem.hint().map(SUGGESTION::matcher).filter(Matcher::matches).map(matcher -> matcher.group(1));
    }

    /** Variants of a key at distance 0 to 3 from it, in the other case, and in the case of a name. */
    private static List<String> variants(String key) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(key);
        variants.add(key.toUpperCase(Locale.ROOT));
        variants.add(Character.toUpperCase(key.charAt(0)) + key.substring(1));
        variants.add(key.substring(1));
        variants.add(key.substring(0, key.length() - 1));
        variants.add(key + "q");
        variants.add("q" + key + "q");
        variants.add("qq" + key);
        variants.add(key + "qqq");
        variants.add(key.substring(0, key.length() / 2) + "q" + key.substring(key.length() / 2 + 1));
        variants.add(key.substring(0, key.length() / 2) + "qqq" + key.substring(Math.min(key.length(), key.length() / 2 + 3)));
        variants.add(key.substring(0, key.length() / 2) + key.substring(Math.min(key.length(), key.length() / 2 + 2)));
        if (key.length() > 3) {
            variants.add(key.substring(1, 2) + key.charAt(0) + key.substring(2));
            variants.add(key.substring(0, 1) + key.substring(2));
            variants.add(key.substring(0, key.length() - 2));
            variants.add(key.substring(0, key.length() - 3));
            variants.add(key.substring(0, key.length() - 2).toUpperCase(Locale.ROOT));
        }
        variants.removeIf(variant -> variant.isEmpty() || variant.equalsIgnoreCase(OWNER));
        return new ArrayList<>(variants);
    }

    @Test
    @DisplayName("The reader suggests the key that the engine suggests as a name, for variants of every known key")
    void theReaderSuggestsWhatTheEngineSuggests() {
        int compared = 0;
        int suggested = 0;
        for (List<String> keys : KEY_SETS) {
            for (String key : keys) {
                for (String given : variants(key)) {
                    Optional<String> engine = engineSuggests(given, keys);

                    assertThat(ClosestKey.of(given, keys)).as("'%s' among %s", given, keys).isEqualTo(engine);
                    compared++;
                    suggested += engine.isPresent() ? 1 : 0;
                }
            }
        }
        // The samples include keys that are suggested and keys that are too far from every known key.
        assertThat(compared).isGreaterThan(500);
        assertThat(suggested).isPositive().isLessThan(compared);
    }

    @Test
    @DisplayName("The reader and the engine agree on distances, ties and case")
    void theReaderAndTheEngineAgreeOnDistancesTiesAndCase() {
        record Sample(String given, List<String> among, String expected) {
        }
        List<Sample> samples = List.of(
                // distance 0, 1, 2 and 3
                new Sample("abcd", List.of("abcd"), "abcd"),
                new Sample("abcx", List.of("abcd"), "abcd"),
                new Sample("abxy", List.of("abcd"), "abcd"),
                new Sample("axyz", List.of("abcd"), null),
                // the closer of two
                new Sample("abcd", List.of("abxy", "abcx"), "abcx"),
                new Sample("abcd", List.of("abcx", "abxy"), "abcx"),
                new Sample("abcd", List.of("axyz", "abxy"), "abxy"),
                // ties go to the alphabetically first
                new Sample("bat", List.of("hat", "cat", "mat"), "cat"),
                new Sample("bat", List.of("mat", "hat", "cat"), "cat"),
                new Sample("dog", List.of("dot", "dig", "dug"), "dig"),
                new Sample("cat", List.of("cart", "cat", "cats"), "cat"),
                new Sample("cats", List.of("cars", "cart", "cats"), "cats"),
                new Sample("cat", List.of("cars", "cart"), "cart"),
                // ties are decided without regard to case
                new Sample("bat", List.of("mat", "Hat", "cat"), "cat"),
                new Sample("bat", List.of("Mat", "hat", "Cat"), "Cat"),
                new Sample("bat", List.of("Zat", "Yat", "Xat"), "Xat"),
                // case differences
                new Sample("CAT", List.of("cart", "cat", "cats"), "cat"),
                new Sample("Cart", List.of("cart", "Cat", "cars"), "cart"),
                new Sample("BAT", List.of("hat", "Cat", "mat"), "Cat"),
                new Sample("CRT", List.of("cart", "Cat", "cars"), "cart"),
                // nothing to choose from, and too short to be close to anything
                new Sample("anything", List.of(), null),
                new Sample("x", List.of("abc", "def"), null),
                new Sample("ab", List.of("abc", "def"), "abc"),
                new Sample("a", List.of("ab", "bc"), "ab"));

        for (Sample sample : samples) {
            Optional<String> engine = engineSuggests(sample.given(), sample.among());

            assertThat(engine).as("engine: '%s' among %s", sample.given(), sample.among())
                    .isEqualTo(Optional.ofNullable(sample.expected()));
            assertThat(ClosestKey.of(sample.given(), sample.among())).as("reader: '%s' among %s", sample.given(), sample.among())
                    .isEqualTo(engine);
        }
    }

    @Test
    @DisplayName("The hint of an unknown key in a document is the closest key by the engine's rule")
    void theHintOfAnUnknownKeyIsWhatTheEngineSuggestsForTheSameName() {
        int compared = 0;
        for (String key : Keys.ATTRIBUTE) {
            for (String given : variants(key)) {
                if (Keys.ATTRIBUTE.contains(given)) {
                    continue;
                }
                Problem unknown = problem(document(
                        "entityTypes:",
                        "  Thing:",
                        "    attributes:",
                        "      value: { type: string, " + given + ": true }"));

                assertThat(unknown.code()).as(given).isEqualTo(ProblemCode.UNKNOWN_KEY);
                assertThat(suggestion(unknown)).as("hint of '%s': %s", given, unknown.hint())
                        .isEqualTo(engineSuggests(given, Keys.ATTRIBUTE));
                compared++;
            }
        }
        assertThat(compared).isGreaterThan(100);
    }
}
