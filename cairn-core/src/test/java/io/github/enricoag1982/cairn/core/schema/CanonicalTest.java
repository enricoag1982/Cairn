package io.github.enricoag1982.cairn.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.Optional;
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

    private static SchemaDefinition balance(String classification) {
        return SchemaDefinition.builder()
                .entityType("Account", e -> e.attribute("balance", ValueType.DECIMAL, a -> {
                    if (classification != null) {
                        a.classification(classification);
                    }
                })).build();
    }

    @Test
    void classificationsAreCompared() {
        assertThat(Canonical.equal(balance("finance"), balance("finance"))).isTrue();
        assertThat(Canonical.equal(balance(null), balance(null))).isTrue();
        assertThat(Canonical.equal(balance("finance"), balance("pay"))).isFalse();
        assertThat(Canonical.equal(balance("finance"), balance(null))).isFalse();
        assertThat(Canonical.equal(balance(null), balance("finance"))).isFalse();
    }

    @Test
    void theCanonicalFormKeepsTheClassificationsOfTraitsEntityTypesAndRelationshipTypes() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .trait("Named", t -> t.attribute("name", ValueType.STRING, a -> a.classification("names")))
                .entityType("Party", e -> e.includes("Named").attribute("lei", ValueType.STRING,
                        a -> a.classification("identity")))
                .relationshipType("Owns", r -> r.from("Party").to("Party").inverse("ownedBy")
                        .attribute("share", ValueType.DECIMAL, a -> a.classification("ownership")))
                .build();

        SchemaDefinition canonical = Canonical.of(definition);

        assertThat(canonical.traits().getFirst().attributes()).extracting(attribute -> attribute.classification())
                .containsExactly(Optional.of("names"));
        assertThat(canonical.entityTypes().getFirst().attributes()).extracting(attribute -> attribute.classification())
                .containsExactly(Optional.of("identity"));
        assertThat(canonical.relationshipTypes().getFirst().attributes())
                .extracting(attribute -> attribute.classification()).containsExactly(Optional.of("ownership"));
    }

    private static SchemaDefinition currencyExamples(Value... examples) {
        return SchemaDefinition.builder().entityType("Account", e -> e.attribute("currency", ValueType.STRING,
                a -> a.examples(examples))).build();
    }

    @Test
    void examplesAreComparedAsASet() {
        SchemaDefinition ab = currencyExamples(Value.of("EUR"), Value.of("USD"));
        SchemaDefinition ba = currencyExamples(Value.of("USD"), Value.of("EUR"));

        assertThat(Canonical.equal(ab, ab)).isTrue();
        assertThat(Canonical.equal(ab, ba)).isTrue();
        assertThat(Canonical.equal(ab, currencyExamples(Value.of("EUR")))).isFalse();
        assertThat(Canonical.equal(ab, currencyExamples(Value.of("EUR"), Value.of("CHF")))).isFalse();
        assertThat(Canonical.equal(ab, currencyExamples())).isFalse();
    }

    @Test
    void examplesOfDifferentKindsAreDifferentExamples() {
        SchemaDefinition number = SchemaDefinition.builder().entityType("Account", e -> e.attribute("balance",
                ValueType.DECIMAL, a -> a.examples(Value.of(1), Value.of(2L)))).build();
        SchemaDefinition reordered = SchemaDefinition.builder().entityType("Account", e -> e.attribute("balance",
                ValueType.DECIMAL, a -> a.examples(Value.of(2L), Value.of(1)))).build();
        SchemaDefinition differentKinds = SchemaDefinition.builder().entityType("Account", e -> e.attribute("balance",
                ValueType.DECIMAL, a -> a.examples(Value.of(1L), Value.of(2)))).build();

        assertThat(Canonical.equal(number, reordered)).isTrue();
        assertThat(Canonical.equal(number, differentKinds)).isFalse();
    }

    @Test
    void anExampleListedTwiceIsOneExample() {
        assertThat(Canonical.equal(currencyExamples(Value.of("EUR"), Value.of("EUR")),
                currencyExamples(Value.of("EUR")))).isTrue();
    }

    @Test
    void whetherRelationshipsOutliveTheirTargetsMatters() {
        SchemaDefinition plain = SchemaDefinition.builder()
                .relationshipType("OpenedBy", r -> r.from("Account").to("Person").inverse("opened")).build();
        SchemaDefinition outliving = SchemaDefinition.builder()
                .relationshipType("OpenedBy", r -> r.from("Account").to("Person").inverse("opened").outlivesTarget())
                .build();

        assertThat(Canonical.equal(plain, plain)).isTrue();
        assertThat(Canonical.equal(outliving, outliving)).isTrue();
        assertThat(Canonical.equal(plain, outliving)).isFalse();
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
