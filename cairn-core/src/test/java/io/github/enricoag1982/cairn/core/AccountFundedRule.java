package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.data.RelationshipRule;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A test-only rule for {@code Employs} that finds an entity by a unique key (ADR-0026, capability
 * {@code entity-rules}): the account with the number {@code A-1} must have a balance of at least 1,000 when an
 * employment starts. It reads nothing but through that key, which counts as a read of the accounts, so a transaction that
 * changes an account at that instant runs it again. It counts its checks, for the tests that need to see a re-check.
 */
final class AccountFundedRule implements RelationshipRule {

    /** The code the rule reports when the account is missing or has less than the minimum. */
    static final String UNDERFUNDED = "underfunded";

    static final RuleDescriptor DESCRIPTOR = new RuleDescriptor("account-a1-funded", 1,
            "The account with the number A-1 has a balance of at least 1,000 when an employment starts.", "Employs",
            List.of(new RuleCode(UNDERFUNDED, "The account with the number A-1 has no balance of at least 1,000 at "
                    + "the start of the employment. Fund the account, or end the employment.")));

    private static final UniqueKey A1 = new UniqueKey("Account", "number", Value.of("A-1"));
    private static final BigDecimal MINIMUM = new BigDecimal("1000");

    private final AtomicInteger checks = new AtomicInteger();

    /** How many versions the rule has checked, in transactions that were committed, rejected or run dry. */
    int checks() {
        return checks.get();
    }

    @Override
    public RuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void check(RelationshipState version, RuleContext context) {
        checks.incrementAndGet();
        boolean funded = context.view().entity(A1)
                .map(account -> account.values().get("balance"))
                .filter(Value.DecimalValue.class::isInstance)
                .map(balance -> ((Value.DecimalValue) balance).value().compareTo(MINIMUM) >= 0)
                .orElse(false);
        if (!funded) {
            context.problem(UNDERFUNDED, "The account A-1 has no balance of at least 1,000 at "
                    + context.view().effectiveAt() + ".");
        }
    }

    /** The reference model in which {@code Employs} names this rule. */
    static SchemaDefinition model() {
        return Definitions.withRule(ReferenceModel.definition(), "Employs",
                DESCRIPTOR);
    }
}
