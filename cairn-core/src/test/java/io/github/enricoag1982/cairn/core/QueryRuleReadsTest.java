package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A2;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a rule that runs a query reads, with the test-only rules {@link AccountQueriedRule}: one whose query starts from
 * the ID of A-1, and one whose query starts from every account (design D3 of
 * {@code add-query-and-traversal-conveniences}, ADR-0025). Both read A-1's balance at 1 March, the start of Beta's
 * employment of Alice; they differ in what else they read, and so in which changes check them again.
 */
class QueryRuleReadsTest {

    /** A store in which {@code Employs} names a rule, with RD-1, RD-5 and RD-3 recorded, and the rule told A-1's ID. */
    private record World(ReferenceStore store, AccountQueriedRule rule, Receipt accounts) {

        static World start(AccountQueriedRule rule) {
            ReferenceStore store = rule.store();
            Receipt accounts = store.createAccounts();
            rule.watch(entity(accounts, A1));
            store.replay(ReferenceStories.rd3Employment(entity(store.parties, BETA), entity(store.parties, ALICE)));
            store.recordedOn(on(5, 20));
            return new World(store, rule, accounts);
        }

        /** Commits a change to the balance of an account from a day, and tells how many checks it caused. */
        int checksCausedByBalance(TemporaryId account, int month, int day) {
            int before = rule.checks();
            store.commit(Transaction.by("importer")
                    .patch(entity(accounts, account), from(month, day), values("balance", money("1100"))).build());
            return rule.checks() - before;
        }
    }

    @Test
    void aRuleThatQueriesFromOneAccountRunsAgainForAChangeToThatAccountAtTheInstantItRead() {
        World world = World.start(AccountQueriedRule.fromOneAccount());

        assertThat(world.checksCausedByBalance(A1, 6, 1)).as("a change after the instant it read").isZero();
        assertThat(world.checksCausedByBalance(A1, 3, 1)).as("a change at that instant").isEqualTo(1);
    }

    @Test
    void aRuleThatQueriesFromOneAccountIsNotRunAgainForAChangeToAnotherAccountOrForANewAccount() {
        World world = World.start(AccountQueriedRule.fromOneAccount());
        TemporaryId a3 = new TemporaryId("a3");
        int checked = world.rule().checks();

        assertThat(world.checksCausedByBalance(A2, 3, 1)).as("A-2, at the instant it read").isZero();
        world.store().commit(Transaction.by("importer")
                .create(a3, "Account", from(1, 1), values("number", "A-3", "currency", "EUR", "balance", money("5")))
                .relate("HeldBy", a3, entity(world.store().parties, BETA), from(1, 1), values()).build());

        assertThat(world.rule().checks()).as("a new account does not change what the rule read").isEqualTo(checked);
    }

    @Test
    void aRuleThatQueriesFromEveryAccountIsRunAgainForAChangeToAnyAccountAtTheInstantItRead() {
        World world = World.start(AccountQueriedRule.fromAllAccounts());

        assertThat(world.checksCausedByBalance(A2, 6, 1)).as("a change after the instant it read").isZero();
        assertThat(world.checksCausedByBalance(A2, 3, 1)).as("A-2 at the instant it read: the query read the type")
                .isEqualTo(1);
    }

    @Test
    void aRuleThatQueriesFromEveryAccountIsRunAgainForANewAccount() {
        World world = World.start(AccountQueriedRule.fromAllAccounts());
        TemporaryId a3 = new TemporaryId("a3");
        int checked = world.rule().checks();

        world.store().commit(Transaction.by("importer")
                .create(a3, "Account", from(1, 1), values("number", "A-3", "currency", "EUR", "balance", money("5")))
                .relate("HeldBy", a3, entity(world.store().parties, BETA), from(1, 1), values()).build());

        assertThat(world.rule().checks()).isEqualTo(checked + 1);
    }

    @Test
    void bothRulesAreRunAgainAndReportTheProblemWhenTheirAccountIsUnderfundedAtTheInstantTheyRead() {
        for (AccountQueriedRule rule : List.of(AccountQueriedRule.fromOneAccount(),
                AccountQueriedRule.fromAllAccounts())) {
            World world = World.start(rule);

            List<Problem> problems = world.store().reject(Transaction.by("importer")
                    .patch(entity(world.accounts(), A1), from(3, 1), values("balance", money("999"))).build());

            assertThat(problems).as(rule.descriptor().name()).singleElement().satisfies(problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
                assertThat(problem.ruleCode()).contains(rule.descriptor().name() + "/" + AccountQueriedRule.UNDERFUNDED);
            });
        }
    }

    @Test
    void aRuleThatQueriesFromOneAccountReportsAnAccountThatIsNotThere() {
        AccountQueriedRule rule = AccountQueriedRule.fromOneAccount();
        ReferenceStore store = rule.store();
        Receipt parties = store.createParties();
        rule.watch(ReferenceStore.id(99));

        List<Problem> problems = store.rejectReplay(ReferenceStories.rd3Employment(entity(parties, BETA),
                entity(parties, ALICE)));

        assertThat(problems).singleElement().satisfies(problem ->
                assertThat(problem.ruleCode()).contains("account-a1-queried/underfunded"));
    }
}
