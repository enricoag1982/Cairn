package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.RelationshipRule;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.query.Column;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The test-only rule {@code account-a1-queried} for {@code Employs} (ADR-0025, capability {@code queries}): the account
 * A-1 has a balance of at least 1,000 when an employment starts. It reads the balance with a query for {@code Account}
 * that starts from the ID of A-1, so it reads A-1 and not every account: a transaction that changes another account
 * does not run it again.
 *
 * <p>Its counterpart {@code account-a1-filtered} reads the same balance with a query that starts from every account and
 * keeps A-1 with a filter on its ID, as a query had to before it could start from given entities. It reads every
 * account, so a transaction that changes any account at the instant it read runs it again. Both count their checks, for
 * the tests that need to see a re-check.
 *
 * <p>The rule needs the ID of A-1, which the store gives when it creates the account: the test tells the rule with
 * {@link #watch(EntityId)} before it records the employment.
 */
final class AccountQueriedRule implements RelationshipRule {

    /** The code the rules report when the account is missing or has less than the minimum. */
    static final String UNDERFUNDED = "underfunded";

    /** The rule that queries from A-1's ID. */
    static final RuleDescriptor FROM_ONE_ACCOUNT = new RuleDescriptor("account-a1-queried", 1,
            "The balance of the account A-1, read with a query for Account that starts from A-1's ID, is at least "
                    + "1,000 when an employment starts.", "Employs",
            List.of(new RuleCode(UNDERFUNDED, "The account A-1 has no balance of at least 1,000 at the start of the "
                    + "employment. Fund the account, or end the employment.")));

    /** The counterpart that queries from every account. */
    static final RuleDescriptor FROM_ALL_ACCOUNTS = new RuleDescriptor("account-a1-filtered", 1,
            "The balance of the account A-1, read with a query for Account that starts from every account and keeps "
                    + "A-1 with a filter on its ID, is at least 1,000 when an employment starts.", "Employs",
            List.of(new RuleCode(UNDERFUNDED, "The account A-1 has no balance of at least 1,000 at the start of the "
                    + "employment. Fund the account, or end the employment.")));

    private static final BigDecimal MINIMUM = new BigDecimal("1000");

    private final RuleDescriptor descriptor;
    private final boolean fromOneAccount;
    private final AtomicInteger checks = new AtomicInteger();
    private volatile EntityId account;

    private AccountQueriedRule(RuleDescriptor descriptor, boolean fromOneAccount) {
        this.descriptor = descriptor;
        this.fromOneAccount = fromOneAccount;
    }

    /** The rule {@code account-a1-queried}: its query starts from A-1's ID. */
    static AccountQueriedRule fromOneAccount() {
        return new AccountQueriedRule(FROM_ONE_ACCOUNT, true);
    }

    /** The rule {@code account-a1-filtered}: its query starts from every account. */
    static AccountQueriedRule fromAllAccounts() {
        return new AccountQueriedRule(FROM_ALL_ACCOUNTS, false);
    }

    /** Tells the rule the ID of the account A-1. */
    void watch(EntityId a1) {
        this.account = Objects.requireNonNull(a1, "a1");
    }

    /** How many versions the rule has checked, in transactions that were committed, rejected or run dry. */
    int checks() {
        return checks.get();
    }

    @Override
    public RuleDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void check(RelationshipState version, RuleContext context) {
        checks.incrementAndGet();
        EntityId a1 = Objects.requireNonNull(account, "The test has not told the rule the ID of A-1.");
        Query.Builder start = Query.from("Account", "a");
        Query query = (fromOneAccount ? start.ids(a1)
                : start.where(Condition.eq(Operand.id("a"), Operand.literal(a1.value()))))
                .select(Column.attribute("a", "balance", "balance"))
                .build();
        boolean funded = switch (context.view().query(query)) {
            case QueryResult.Rows rows -> rows.rows().stream()
                    .anyMatch(row -> row.decimal("balance").filter(balance -> balance.compareTo(MINIMUM) >= 0)
                            .isPresent());
            case QueryResult.Invalid invalid -> throw new IllegalStateException("The query is wrong: "
                    + invalid.problems());
        };
        if (!funded) {
            context.problem(UNDERFUNDED, "The account A-1 has no balance of at least 1,000 at "
                    + context.view().effectiveAt() + ".");
        }
    }

    /** The reference model in which {@code Employs} names this rule. */
    SchemaDefinition model() {
        return Definitions.withRule(ReferenceModel.definition(), "Employs", descriptor);
    }

    /** A store of the reference domain in which {@code Employs} names this rule, which the store has the code of. */
    ReferenceStore store() {
        return ReferenceStore.withRules(model(), ReferenceStore.rulesWith(this), ReferenceModel.declaredRules());
    }
}
