package io.github.enricoag1982.cairn.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.Origin;
import org.junit.jupiter.api.Test;

class PropertyRulesTest {

    private record Element(String name, String colour) {
    }

    @Test
    void aPropertyWithoutARuleIsBreaking() {
        PropertyRules.Context context = new PropertyRules.Context(CompiledSchema.EMPTY, CompiledSchema.EMPTY, "Party",
                "colour", "red", "blue", new Element("Party", "red"), new Element("Party", "blue"));

        assertThat(PropertyRules.classify("entity", context)).hasValueSatisfying(change -> {
            assertThat(change.breaking()).isTrue();
            assertThat(change.path()).isEqualTo("Party");
            assertThat(change.description()).contains("colour", "red", "blue");
        });
    }

    @Test
    void anUnknownElementKindIsBreakingToo() {
        PropertyRules.Context context = new PropertyRules.Context(CompiledSchema.EMPTY, CompiledSchema.EMPTY, "x",
                "name", "a", "b", new Origin("a", "1"), new Origin("b", "1"));

        assertThat(PropertyRules.classify("view", context)).hasValueSatisfying(change ->
                assertThat(change.breaking()).isTrue());
    }

    @Test
    void comparesEveryComponentOfARecord() {
        assertThat(PropertyRules.compare("entity", new Element("Party", "red"), new Element("Party", "blue"), "Party",
                CompiledSchema.EMPTY, CompiledSchema.EMPTY)).singleElement()
                .satisfies(change -> assertThat(change.breaking()).isTrue());
    }
}
