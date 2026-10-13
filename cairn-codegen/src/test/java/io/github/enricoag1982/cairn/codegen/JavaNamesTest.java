package io.github.enricoag1982.cairn.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The small rules for names that need no schema (design D4 of {@code add-java-type-generation}). */
class JavaNamesTest {

    @Test
    void aRuleNameBecomesPascalCase() {
        assertThat(JavaNames.pascal("lei-checksum")).isEqualTo("LeiChecksum");
        assertThat(JavaNames.pascal("total-ownership-at-most-100")).isEqualTo("TotalOwnershipAtMost100");
        assertThat(JavaNames.pascal("single")).isEqualTo("Single");
        assertThat(JavaNames.pascal("a--b-")).isEqualTo("AB");
        assertThat(JavaNames.pascal("x1")).isEqualTo("X1");
    }

    @Test
    void keywordsAndLiteralsAreKeywordsButContextualWordsAreNotPartsOfPackageNames() {
        assertThat(JavaNames.isKeyword("class")).isTrue();
        assertThat(JavaNames.isKeyword("default")).isTrue();
        assertThat(JavaNames.isKeyword("true")).isTrue();
        assertThat(JavaNames.isKeyword("null")).isTrue();
        assertThat(JavaNames.isKeyword("record")).isFalse();
        assertThat(JavaNames.isKeyword("Class")).isFalse();
    }
}
