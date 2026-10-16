package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withClassification;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.List;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/**
 * What the problems of rules say to a transaction with a clearance (ADR-0036, design D4 of
 * {@code add-attribute-access-control}): a rule's problem about an attribute that the clearance does not cover loses the
 * rule's message, which can quote the value the rule read, and keeps its code, its path and the rule's code; a rule that
 * threw is reported without the message of the error. A rule's problem located elsewhere keeps its message, and a
 * transaction without a clearance gets every message. Organization's {@code lei} is {@code identity} here only. The tests
 * run against RocksDB stores too.
 */
class RedactedRuleProblemsTest {

    private static final Clearance NONE = Clearance.none();

    /** A rule that quotes the LEI it read in its messages and in the message of its exception. */
    private static final BiConsumer<EntityState, RuleContext> QUOTING = (version, context) -> {
        if (!(version.values().get("lei") instanceof Value.StringValue lei)
                || !(version.values().get("name") instanceof Value.StringValue name)) {
            return;
        }
        if (name.value().equals("Probe")) {
            context.problem("lei-bad", "lei", "The LEI " + lei.value() + " is not acceptable.");
            context.problem("name-bad", "name", "The name " + name.value() + " is not acceptable.");
            context.problem("whole-bad", "The organization has the LEI " + lei.value() + ".");
        } else if (name.value().equals("Thrower")) {
            throw new IllegalStateException("Cannot read the LEI " + lei.value() + ".");
        }
    };

    private static final TestEntityRule RULE = TestEntityRule.of("quotes-lei", "Organization", QUOTING, "lei-bad",
            "name-bad", "whole-bad");

    private static SchemaDefinition model() {
        return TestEntityRule.namedIn(withClassification(ReferenceModel.definition(), "Organization", "lei",
                "identity"), "Organization", RULE.descriptor());
    }

    private static ReferenceStore store() {
        ReferenceStore reference = ReferenceStore.withRules(model(), ReferenceStore.rulesWith(RULE),
                ReferenceModel.declaredRules());
        EntityId acme = entity(reference.createParties(), ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        reference.recordedOn(on(5, 2));
        return reference;
    }

    private static EntityId acme(ReferenceStore reference) {
        return entity(reference.parties, ACME);
    }

    private static List<Problem> rename(ReferenceStore reference, String name, Clearance clearance) {
        Transaction.Builder builder = Transaction.by(PRINCIPAL);
        if (clearance != null) {
            builder.clearance(clearance);
        }
        return reference.reject(builder.patch(acme(reference), from(5, 1), values("name", name)).build());
    }

    private static Problem at(List<Problem> problems, String suffix) {
        return problems.stream().filter(problem -> problem.path().endsWith(suffix)).findFirst()
                .orElseThrow(() -> new AssertionError("No problem at " + suffix + " in " + problems));
    }

    @Test
    void aRulesProblemAboutAnAttributeTheClearanceDoesNotCoverLosesTheRulesMessage() {
        ReferenceStore reference = store();

        List<Problem> problems = rename(reference, "Probe", NONE);

        Problem withheld = at(problems, ".lei");
        assertThat(withheld.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(withheld.ruleCode()).hasValue("quotes-lei/lei-bad");
        assertThat(withheld.message()).isEqualTo("The rule reported a problem about a withheld value.");
        assertThat(withheld.toString()).doesNotContain(LEI);
    }

    @Test
    void aRulesProblemLocatedElsewhereKeepsItsMessage() {
        ReferenceStore reference = store();

        List<Problem> problems = rename(reference, "Probe", NONE);

        assertThat(at(problems, ".name").message()).isEqualTo("The name Probe is not acceptable.");
        assertThat(at(problems, acme(reference).value()).message())
                .as("a problem about the whole organization is its author's care")
                .contains("The organization has the LEI " + LEI);
    }

    @Test
    void aTransactionWithoutAClearanceGetsEveryMessage() {
        ReferenceStore reference = store();

        List<Problem> problems = rename(reference, "Probe", null);

        assertThat(at(problems, ".lei").message()).isEqualTo("The LEI " + LEI + " is not acceptable.");
    }

    @Test
    void aClearanceThatCoversTheAttributeGetsTheMessageToo() {
        ReferenceStore reference = store();

        List<Problem> problems = rename(reference, "Probe", Clearance.of("identity"));

        assertThat(at(problems, ".lei").message()).isEqualTo("The LEI " + LEI + " is not acceptable.");
    }

    @Test
    void aRuleThatThrewIsReportedWithoutTheMessageOfTheError() {
        ReferenceStore reference = store();

        List<Problem> restricted = rename(reference, "Thrower", NONE);
        List<Problem> unrestricted = rename(reference, "Thrower", null);

        Problem failed = restricted.getFirst();
        assertThat(failed.code()).isEqualTo(ProblemCode.RULE_FAILED);
        assertThat(failed.path()).isEqualTo(acme(reference).value());
        assertThat(failed.message()).contains("quotes-lei").contains("java.lang.IllegalStateException");
        assertThat(failed.found()).isEmpty();
        assertThat(failed.toString()).doesNotContain(LEI);
        assertThat(unrestricted.getFirst().found()).hasValueSatisfying(found -> assertThat(found).contains(LEI));
    }
}
