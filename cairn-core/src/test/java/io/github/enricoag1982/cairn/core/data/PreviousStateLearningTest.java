package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.LeiKeptRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a durable store learns of the rules that read the state before the transaction (design D2 of
 * {@code add-previous-state-to-rules}, with design D5 of {@code add-rule-rechecks}): when it opens, it runs its rules
 * against the committed state with no transaction, so the committed state is both "now" and "before", and what the rules
 * read through {@code before()} is recorded like any other read.
 *
 * <p>The store has the reference model in which {@code Organization} names the test-only rule {@code lei-kept}. RD-1 and
 * the first part of RD-9, ACME's LEI, are recorded in it.
 */
class PreviousStateLearningTest {

    private static final RuleReference LEI_KEPT = new RuleReference("lei-kept", 1);

    @TempDir
    Path directory;

    private DurableFixture durable;
    private EntityId acme;
    private EntityId beta;

    @BeforeEach
    void setUp() {
        durable = new DurableFixture(directory);
    }

    @AfterEach
    void tearDown() {
        durable.closeAll();
    }

    private static SchemaDefinition model() {
        return Definitions.changeEntityType(ReferenceModel.definition(), "Organization", type -> {
            List<RuleReference> rules = new ArrayList<>(type.rules());
            rules.add(LEI_KEPT);
            return new EntityTypeDefinition(type.name(), type.isAbstract(), type.supertype(), type.traits(),
                    type.attributes(), type.aliases(), type.deprecated(), type.description(), rules);
        });
    }

    private DirectoryStore open(Rule leiKept) {
        List<Rule> rules = new ArrayList<>(ReferenceModel.executableRules());
        rules.add(leiKept);
        return durable.open(durable.clock, IdGenerators.deterministic(), rules, List.of());
    }

    private void tell(Store store) {
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), model()))
                .isInstanceOf(RegistrationResult.Applied.class);
        Receipt parties = ReferenceStories.replay(store, durable.clock, ReferenceStories.rd1Parties());
        acme = ReferenceTales.entity(parties, ACME);
        beta = ReferenceTales.entity(parties, BETA);
        ReferenceStories.replay(store, durable.clock, ReferenceStories.rd9AcmeLei(acme));
    }

    private Transaction removesAcmesLei() {
        return Transaction.by("tester").patch(acme, from(3, 1), values(), Set.of("lei"), ConflictCheck.none())
                .build();
    }

    /** {@code lei-kept} that records what the context shows, for each version that it checks. */
    private static final class Recording implements EntityRule {
        final List<String> seen = new ArrayList<>();

        @Override
        public RuleDescriptor descriptor() {
            return LeiKeptRule.DESCRIPTOR;
        }

        @Override
        public void check(EntityState version, RuleContext context) {
            Optional<EntityState> before = context.before().entity(version.id());
            Optional<EntityState> now = context.view().entity(version.id());
            seen.add(version.id() + " from " + version.period().from() + ": basis "
                    + (context.before().basis().equals(context.view().basis()) ? "same" : "differs") + ", state "
                    + (before.equals(now) && now.isPresent() ? "same" : "differs") + ", schema "
                    + (context.before().schema().equals(context.view().schema()) ? "same" : "differs") + ", at "
                    + (context.before().effectiveAt().equals(version.period().from()) ? "the start" : "elsewhere"));
        }
    }

    @Test
    void aReopenedStoreHasLearnedWhatARuleReadThroughBefore() {
        DirectoryStore first = open(new LeiKeptRule());
        tell(first);
        List<ReadIndex.Check> recorded = first.readIndex().checks(beta, LEI_KEPT);
        assertThat(recorded).as("lei-kept ran for Beta's version, which has no LEI, and read Beta").isNotEmpty();
        Set<String> before = first.readIndex().describe();
        first.close();

        DirectoryStore reopened = open(new LeiKeptRule());

        assertThat(reopened.readIndex().describe()).as("the same reads").isEqualTo(before);
        assertThat(reopened.readIndex().consistent()).isTrue();
        List<ReadIndex.Check> learned = reopened.readIndex().checks(beta, LEI_KEPT);
        assertThat(learned).isNotEmpty().allSatisfy(check -> assertThat(check.reads())
                .containsKey(new ReadKey.Entity(beta)));
        assertThat(learned.getFirst().reads().get(new ReadKey.Entity(beta)).acrossTime())
                .as("the rule read the timeline of Beta before the transaction, across effective time").isTrue();
        assertThat(reopened.readIndex().readers(new ReadKey.Entity(beta))).as("a change to Beta runs the rule again")
                .containsAll(learned);
    }

    @Test
    void aReopenedStoreRejectsWhatTheRuleThatReadsWhatWasThereBeforeObjectsTo() {
        DirectoryStore first = open(new LeiKeptRule());
        tell(first);
        first.close();

        DirectoryStore reopened = open(new LeiKeptRule());

        DryRunResult result = reopened.dryRun(removesAcmesLei());
        assertThat(result).isInstanceOf(DryRunResult.Rejected.class);
        Problem problem = ((DryRunResult.Rejected) result).problems().getFirst();
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).contains("lei-kept/lei-removed");
    }

    @Test
    void whileAStoreLearnsBeforeAndTheOtherViewsShowTheCommittedState() {
        DirectoryStore first = open(new LeiKeptRule());
        tell(first);
        Basis latest = first.latestBasis();
        first.close();
        Recording recording = new Recording();

        DirectoryStore reopened = open(recording);

        assertThat(reopened.latestBasis()).isEqualTo(latest);
        assertThat(recording.seen).as("the versions of the two organizations").containsExactlyInAnyOrder(
                acme + " from " + on(1, 1) + ": basis same, state same, schema same, at the start",
                beta + " from " + on(1, 1) + ": basis same, state same, schema same, at the start");
    }
}
