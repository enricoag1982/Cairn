package io.github.enricoag1982.cairn.schema;

import java.util.Objects;

/**
 * A problem code that a rule can report, with an explanation (capability {@code type-model}).
 *
 * @param code        the code, as the rule defines it, for example {@code invalid-check-digits}
 * @param explanation what the code means and how to resolve it
 */
public record RuleCode(String code, String explanation) {

    /**
     * Rejects null components.
     */
    public RuleCode {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(explanation, "explanation");
    }
}
