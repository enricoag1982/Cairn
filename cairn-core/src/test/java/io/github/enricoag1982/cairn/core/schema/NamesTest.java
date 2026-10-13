package io.github.enricoag1982.cairn.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NamesTest {

    @Test
    void ruleNamesAreLowercaseLettersDigitsAndHyphensStartingWithALetter() {
        assertThat(Names.isValidRuleName("lei-checksum")).isTrue();
        assertThat(Names.isValidRuleName("total-ownership-at-most-100")).isTrue();
        assertThat(Names.isValidRuleName("a")).isTrue();
        assertThat(Names.isValidRuleName("a".repeat(128))).isTrue();
    }

    @Test
    void otherRuleNamesAreRejected() {
        assertThat(Names.isValidRuleName("")).isFalse();
        assertThat(Names.isValidRuleName("LEI-checksum")).isFalse();
        assertThat(Names.isValidRuleName("1st-rule")).isFalse();
        assertThat(Names.isValidRuleName("-rule")).isFalse();
        assertThat(Names.isValidRuleName("lei_checksum")).isFalse();
        assertThat(Names.isValidRuleName("lei checksum")).isFalse();
        assertThat(Names.isValidRuleName("lei-prüfsumme")).isFalse();
        assertThat(Names.isValidRuleName("a".repeat(129))).isFalse();
    }
}
