package io.github.enricoag1982.cairn.core.schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds the existing name that a misspelled name most likely stands for (design D5).
 *
 * <p>Names are compared in lower case by their Levenshtein distance, which must be at most {@value #MAX_DISTANCE}.
 * When several names are equally close, the alphabetically first wins.
 */
final class ClosestName {

    /** The largest distance at which a name is still suggested. */
    static final int MAX_DISTANCE = 2;

    /**
     * A name, or an alias, that could be meant, and the name to suggest for it.
     *
     * @param text       what the given name is compared with
     * @param suggestion what to suggest when it is the closest
     */
    record Candidate(String text, String suggestion) {
    }

    private ClosestName() {
    }

    /** Finds the closest of some names. */
    static Optional<String> of(String given, Collection<String> names) {
        return ofCandidates(given, names.stream().map(name -> new Candidate(name, name)).toList());
    }

    /** Finds the closest of some candidates, each of which can be a name or an alias of the thing to suggest. */
    static Optional<String> ofCandidates(String given, Collection<Candidate> candidates) {
        if (given.isEmpty()) {
            return Optional.empty();
        }
        String lowered = given.toLowerCase(Locale.ROOT);
        Comparator<Candidate> byName = Comparator
                .comparing((Candidate candidate) -> candidate.suggestion(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Candidate::suggestion);
        return candidates.stream()
                .filter(candidate -> distance(lowered, candidate.text().toLowerCase(Locale.ROOT)) <= MAX_DISTANCE)
                .min(Comparator.comparingInt((Candidate candidate) ->
                        distance(lowered, candidate.text().toLowerCase(Locale.ROOT))).thenComparing(byName))
                .map(Candidate::suggestion);
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

    /** Candidates for a list of names that each have aliases. */
    static List<Candidate> candidates(String name, List<String> aliases) {
        List<Candidate> result = new ArrayList<>();
        result.add(new Candidate(name, name));
        aliases.forEach(alias -> result.add(new Candidate(alias, name)));
        return result;
    }
}
