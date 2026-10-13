package io.github.enricoag1982.cairn.documents;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds the known key that a misspelled key most likely stands for (design D5).
 *
 * <p>Keys are compared in lower case by their Levenshtein distance, which must be at most {@value #MAX_DISTANCE}.
 * When several keys are equally close, the alphabetically first wins. This is the rule the engine uses for names; the
 * module does not depend on the engine, so the rule is repeated here.
 */
final class ClosestKey {

    /** The largest distance at which a key is still suggested. */
    static final int MAX_DISTANCE = 2;

    private ClosestKey() {
    }

    /** Finds the closest of the known keys, if one is within {@value #MAX_DISTANCE} letters of the given key. */
    static Optional<String> of(String given, Collection<String> known) {
        if (given.isEmpty()) {
            return Optional.empty();
        }
        String lowered = given.toLowerCase(Locale.ROOT);
        return known.stream()
                .filter(key -> distance(lowered, key.toLowerCase(Locale.ROOT)) <= MAX_DISTANCE)
                .min(Comparator.comparingInt((String key) -> distance(lowered, key.toLowerCase(Locale.ROOT)))
                        .thenComparing(String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Comparator.naturalOrder()));
    }

    /**
     * Orders all the known keys by how close they are to the given key, the closest first, with the order of
     * {@link #of} for keys that are equally close. Unlike {@link #of} it keeps keys that are further than
     * {@value #MAX_DISTANCE} letters away, so that a hint can list every choice.
     */
    static List<String> ranked(String given, Collection<String> known) {
        String lowered = given.toLowerCase(Locale.ROOT);
        return known.stream()
                .sorted(Comparator.comparingInt((String key) -> distance(lowered, key.toLowerCase(Locale.ROOT)))
                        .thenComparing(String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /** The Levenshtein distance: the fewest single-character insertions, deletions and substitutions between two texts. */
    static int distance(String first, String second) {
        int[] previous = new int[second.length() + 1];
        int[] current = new int[second.length() + 1];
        for (int j = 0; j <= second.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= first.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= second.length(); j++) {
                int substitution = previous[j - 1] + (first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[second.length()];
    }
}
