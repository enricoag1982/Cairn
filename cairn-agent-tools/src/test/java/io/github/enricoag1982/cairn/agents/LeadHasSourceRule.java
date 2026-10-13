package io.github.enricoag1982.cairn.agents;

import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.List;

/**
 * A rule that the server finds on the class path in {@link CairnAgentServerTest}, through the service file in the
 * test resources: a lead has a source that is not blank. Nothing in the reference domain; the rule exists to show that
 * the server runs the rules it finds.
 */
public final class LeadHasSourceRule implements EntityRule {

    /** The description of the rule. */
    public static final RuleDescriptor DESCRIPTOR = new RuleDescriptor("lead-has-source", 1,
            "A lead says where it came from.", "Lead",
            List.of(new RuleCode("missing-source", "The lead has no source. Give it one.")));

    @Override
    public RuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void check(EntityState version, RuleContext context) {
        if (!(version.values().get("source") instanceof Value.StringValue source) || source.value().isBlank()) {
            context.problem("missing-source", "source", "The lead has no source.");
        }
    }
}
