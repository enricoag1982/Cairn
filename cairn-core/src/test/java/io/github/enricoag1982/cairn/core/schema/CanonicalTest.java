package io.github.enricoag1982.cairn.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.Literal;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import org.junit.jupiter.api.Test;

class CanonicalTest {

    private static SchemaDefinition segment(String... allowedValues) {
        return SchemaDefinition.builder()
                .entityType("CustomerRole", e -> e.attribute("segment", ValueType.STRING,
                        a -> a.allowedValues(allowedValues)))
                .build();
    }

    private static SchemaDefinition rules(String first, String second) {
        return SchemaDefinition.builder()
                .entityType("Organization", e -> e.rule(first, 1).rule(second, 2))
                .build();
    }

    @Test
    void allowedValuesAreComparedAsASet() {
        assertThat(Canonical.equal(segment("Corporate", "Retail"), segment("Retail", "Corporate"))).isTrue();
        assertThat(Canonical.equal(segment("Corporate", "Retail"), segment("Corporate"))).isFalse();
        assertThat(Canonical.equal(segment("Corporate"), segment())).isFalse();
    }

    @Test
    void ruleReferencesAreComparedAsASet() {
        assertThat(Canonical.equal(rules("lei-checksum", "name-not-blank"), rules("lei-checksum", "name-not-blank")))
                .isTrue();
        assertThat(Canonical.equal(
                SchemaDefinition.builder().entityType("Organization", e -> e.rule("a", 1).rule("b", 1)).build(),
                SchemaDefinition.builder().entityType("Organization", e -> e.rule("b", 1).rule("a", 1)).build()))
                .isTrue();
        assertThat(Canonical.equal(
                SchemaDefinition.builder().entityType("Organization", e -> e.rule("a", 1)).build(),
                SchemaDefinition.builder().entityType("Organization", e -> e.rule("a", 2)).build()))
                .isFalse();
    }

    @Test
    void examplesAreComparedAsAList() {
        SchemaDefinition ab = SchemaDefinition.builder().entityType("Account", e -> e.attribute("currency",
                ValueType.STRING, a -> a.examples(Literal.of("EUR"), Literal.of("USD")))).build();
        SchemaDefinition ba = SchemaDefinition.builder().entityType("Account", e -> e.attribute("currency",
                ValueType.STRING, a -> a.examples(Literal.of("USD"), Literal.of("EUR")))).build();

        assertThat(Canonical.equal(ab, ab)).isTrue();
        assertThat(Canonical.equal(ab, ba)).isFalse();
    }

    @Test
    void descriptionsPatternsAndLengthsMatter() {
        SchemaDefinition plain = SchemaDefinition.builder()
                .entityType("Account", e -> e.attribute("currency", ValueType.STRING)).build();

        assertThat(Canonical.equal(plain, SchemaDefinition.builder().entityType("Account", e -> e
                .attribute("currency", ValueType.STRING, a -> a.pattern("[A-Z]{3}"))).build())).isFalse();
        assertThat(Canonical.equal(plain, SchemaDefinition.builder().entityType("Account", e -> e
                .attribute("currency", ValueType.STRING, a -> a.minLength(3))).build())).isFalse();
        assertThat(Canonical.equal(plain, SchemaDefinition.builder().entityType("Account", e -> e
                .attribute("currency", ValueType.STRING, a -> a.maxLength(3))).build())).isFalse();
        assertThat(Canonical.equal(plain, SchemaDefinition.builder().entityType("Account", e -> e
                .attribute("currency", ValueType.STRING, a -> a.description("ISO 4217"))).build())).isFalse();
        assertThat(Canonical.equal(plain, SchemaDefinition.builder().entityType("Account", e -> e.description("An account")
                .attribute("currency", ValueType.STRING)).build())).isFalse();
    }
}
