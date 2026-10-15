package io.github.enricoag1982.cairn.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClosestKeyTest {

    private static final List<String> ATTRIBUTE_KEYS = Keys.ATTRIBUTE;

    @Test
    void theDistanceCountsInsertionsDeletionsAndSubstitutions() {
        assertThat(ClosestKey.distance("required", "required")).isZero();
        assertThat(ClosestKey.distance("requird", "required")).isEqualTo(1);
        assertThat(ClosestKey.distance("requiredd", "required")).isEqualTo(1);
        assertThat(ClosestKey.distance("requirad", "required")).isEqualTo(1);
        assertThat(ClosestKey.distance("kitten", "sitting")).isEqualTo(3);
        assertThat(ClosestKey.distance("", "abc")).isEqualTo(3);
        assertThat(ClosestKey.distance("abc", "")).isEqualTo(3);
    }

    @Test
    void findsTheKeyWithinTwoLetters() {
        assertThat(ClosestKey.of("requird", ATTRIBUTE_KEYS)).contains("required");
        assertThat(ClosestKey.of("allowdValues", ATTRIBUTE_KEYS)).contains("allowedValues");
        assertThat(ClosestKey.of("minLenght", ATTRIBUTE_KEYS)).contains("minLength");
        assertThat(ClosestKey.of("ty", ATTRIBUTE_KEYS)).contains("type");
    }

    @Test
    void doesNotSuggestAMoreDistantKey() {
        assertThat(ClosestKey.of("mandatory", ATTRIBUTE_KEYS)).isEmpty();
        assertThat(ClosestKey.of("requ", ATTRIBUTE_KEYS)).isEmpty();
        assertThat(ClosestKey.of("", ATTRIBUTE_KEYS)).isEmpty();
    }

    @Test
    void ignoresCase() {
        assertThat(ClosestKey.of("Required", ATTRIBUTE_KEYS)).contains("required");
        assertThat(ClosestKey.of("ALLOWEDVALUES", ATTRIBUTE_KEYS)).contains("allowedValues");
    }

    @Test
    void rankingListsEveryKeyTheClosestFirst() {
        assertThat(ClosestKey.ranked("creat", PayloadKeys.KINDS)).startsWith("create").containsExactlyInAnyOrderElementsOf(
                PayloadKeys.KINDS);
        assertThat(ClosestKey.ranked("pach", PayloadKeys.KINDS)).startsWith("patch");
        assertThat(ClosestKey.ranked("bat", List.of("mat", "Hat", "cat", "battery"))).containsExactly(
                "cat", "Hat", "mat", "battery");
    }

    @Test
    void theCloserKeyWinsAndTiesGoToTheAlphabeticallyFirst() {
        assertThat(ClosestKey.of("cat", List.of("cart", "cat", "cats"))).contains("cat");
        assertThat(ClosestKey.of("bat", List.of("hat", "cat", "mat"))).contains("cat");
        assertThat(ClosestKey.of("bat", List.of("mat", "Hat", "cat"))).contains("cat");
    }
}
