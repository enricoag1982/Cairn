package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import java.util.List;

/**
 * A rule that the service loader finds in the tests of this module, through
 * {@code META-INF/services/io.github.enricoag1982.cairn.data.Rule} in the test resources. It checks nothing. The class
 * is public with a public constructor, as the service loader demands.
 */
public final class DiscoveredTestRule implements EntityRule {

    /** The description of the rule. */
    public static final RuleDescriptor DESCRIPTOR = new RuleDescriptor("discovered-test-rule", 1,
            "A rule that only the service loader finds.", "Organization", List.of());

    @Override
    public RuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void check(EntityState version, RuleContext context) {
        // Nothing to check.
    }
}
