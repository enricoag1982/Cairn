package io.github.enricoag1982.cairn.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClosestNameTest {

    @Test
    void theDistanceCountsInsertionsDeletionsAndSubstitutions() {
        assertThat(ClosestName.distance("party", "party")).isZero();
        assertThat(ClosestName.distance("prty", "party")).isEqualTo(1);
        assertThat(ClosestName.distance("partyy", "party")).isEqualTo(1);
        assertThat(ClosestName.distance("parts", "party")).isEqualTo(1);
        assertThat(ClosestName.distance("kitten", "sitting")).isEqualTo(3);
        assertThat(ClosestName.distance("", "abc")).isEqualTo(3);
        assertThat(ClosestName.distance("abc", "")).isEqualTo(3);
    }

    @Test
    void findsTheNameWithinTwoLetters() {
        assertThat(ClosestName.of("Prty", List.of("Party", "Person", "Account"))).contains("Party");
        assertThat(ClosestName.of("Acount", List.of("Party", "Person", "Account"))).contains("Account");
        assertThat(ClosestName.of("Persn", List.of("Party", "Person", "Account"))).contains("Person");
    }

    @Test
    void doesNotSuggestAMoreDistantName() {
        assertThat(ClosestName.of("Parties", List.of("Party"))).isEmpty();
        assertThat(ClosestName.of("Parti", List.of("Party"))).contains("Party");
        assertThat(ClosestName.of("Pardd", List.of("Party"))).contains("Party");
        assertThat(ClosestName.of("Parddd", List.of("Party"))).isEmpty();
    }

    @Test
    void ignoresCase() {
        assertThat(ClosestName.of("PARTY", List.of("Party"))).contains("Party");
        assertThat(ClosestName.of("prty", List.of("Party"))).contains("Party");
    }

    @Test
    void prefersTheCloserName() {
        assertThat(ClosestName.of("Cat", List.of("Cart", "Cat", "Cats"))).contains("Cat");
        assertThat(ClosestName.of("Cato", List.of("Cart", "Cat", "Cats"))).contains("Cat");
    }

    @Test
    void tiesGoToTheAlphabeticallyFirstName() {
        assertThat(ClosestName.of("Bat", List.of("Hat", "Cat", "Mat"))).contains("Cat");
        assertThat(ClosestName.of("Bat", List.of("mat", "Hat", "cat"))).contains("cat");
    }

    @Test
    void suggestsTheNameOfAnElementWhoseAliasIsClosest() {
        List<ClosestName.Candidate> candidates = ClosestName.candidates("Party", List.of("Actor"));

        assertThat(ClosestName.ofCandidates("Acter", candidates)).contains("Party");
        assertThat(ClosestName.ofCandidates("Prty", candidates)).contains("Party");
    }

    @Test
    void nothingIsSuggestedForAnEmptyNameOrNoCandidates() {
        assertThat(ClosestName.of("", List.of("ab"))).isEmpty();
        assertThat(ClosestName.of("Party", List.of())).isEmpty();
    }
}
