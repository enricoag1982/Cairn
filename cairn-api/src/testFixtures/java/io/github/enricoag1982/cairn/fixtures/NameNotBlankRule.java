package io.github.enricoag1982.cairn.fixtures;

import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.List;

/**
 * The rule {@code name-not-blank} (ADR-0018, capability {@code entity-rules}): the name of a party has at least one
 * character that is not white space. It is for Party, so that it applies to persons and organizations when Party names
 * it. The reference model does not name it; tests that need a rule named by a supertype add it to Party.
 */
public final class NameNotBlankRule implements EntityRule {

    /** The code the rule reports when the name is blank. */
    public static final String BLANK_NAME = "blank-name";

    /** The description of the rule. */
    public static final RuleDescriptor DESCRIPTOR = new RuleDescriptor("name-not-blank", 1,
            "The name has at least one character that is not white space.",
            "Party",
            List.of(new RuleCode(BLANK_NAME, "The name consists of white space only. Give the party a name.")));

    @Override
    public RuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void check(EntityState version, RuleContext context) {
        if (version.values().get("name") instanceof Value.StringValue name && name.value().isBlank()) {
            context.problem(BLANK_NAME, "name", "The name is blank.");
        }
    }
}
