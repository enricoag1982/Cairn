package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.id;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a rule sees through {@code before()} and {@code beforeAt(Instant)} (design D1 and D2 of
 * {@code add-previous-state-to-rules}): the store as committed before the transaction, in commits, in dry runs and in
 * re-checks, and that what a rule reads through those views is recorded like any other read (ADR-0025). The scenarios
 * of the {@code entity-rules} spec that use the state before the transaction are in {@link EntityRulesScenariosTest}.
 *
 * <p>The probe is an entity of the type {@code Probe}, whose rule runs a function that the test sets, and records the
 * start of each version it checks. As in {@link RuleRechecksTest}, a test commits a transaction that does not write the
 * probe and sees whether its rule ran again; reads are made at 1 June unless a test says otherwise.
 */
class PreviousStateRulesTest {

    private static final TemporaryId PROBE = new TemporaryId("probe");
    private static final TemporaryId NEW = new TemporaryId("new");
    private static final Instant JUNE_1 = on(6, 1);

    /** The reference domain plus the type of the probes. */
    private static SchemaDefinition model() {
        return Definitions.plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .entityType("Probe", e -> e.attribute("label", ValueType.STRING).rule("entity-probe", 1))
                .build());
    }

    /** What the probe's rule does, set by each test before the probe is created; it does nothing until then. */
    private BiConsumer<EntityState, RuleContext> reading = (version, context) -> { };

    /** The runs of the probe's rule, one entry for each: {@code <ID> <start of the version>}. */
    private final List<String> runs = new ArrayList<>();

    private ReferenceStore reference;
    private EntityId acme;
    private EntityId beta;
    private EntityId alice;

    @BeforeEach
    void theWorldOfRd1() {
        TestEntityRule probe = TestEntityRule.of("entity-probe", "Probe", (version, context) -> {
            runs.add(version.id() + " " + version.period().from());
            reading.accept(version, context);
        }, "bad");
        reference = ReferenceStore.withRules(model(), ReferenceStore.rulesWith(probe), ReferenceModel.declaredRules());
        Receipt parties = reference.createParties();
        acme = entity(parties, ACME);
        beta = entity(parties, BETA);
        alice = entity(parties, ALICE);
    }

    // ------------------------------------------------------------------ helpers

    private static Transaction.Builder by() {
        return Transaction.by("tester");
    }

    /** Creates the probe, from 1 January, which makes its rule read. */
    private EntityId probe() {
        EntityId probe = entity(reference.commit(by().create(PROBE, "Probe", from(1, 1), values("label", "a")).build()),
                PROBE);
        runs.clear();
        return probe;
    }

    private Transaction rename(EntityId id, EffectivePeriod period, String name) {
        return by().patch(id, period, values("name", name)).build();
    }

    /** Commits a transaction and tells whether it ran the probe's rule again. */
    private boolean rechecked(Transaction transaction) {
        runs.clear();
        reference.commit(transaction);
        return !runs.isEmpty();
    }

    /** The name of an entity, or {@code none} if the view has no such entity. */
    private static String name(Optional<EntityState> entity) {
        return entity.map(state -> ((Value.StringValue) state.values().get("name")).value()).orElse("none");
    }

    private Transaction newOrganization(EntityId id, Instant from) {
        return by().create(id, "Organization", EffectivePeriod.from(from), values("name", "New")).build();
    }

    private RelationshipId owns(EntityId owner, EntityId owned, Instant from) {
        return relationship(reference.commit(by().relate(NEW, "Owns", owner, owned, EffectivePeriod.from(from),
                values("share", 40)).build()), NEW);
    }

    // ------------------------------------------------------------------ what the views show

    @Test
    void beforeShowsTheCommittedStateAndViewTheStateTheTransactionWouldProduce() {
        List<String> seen = new ArrayList<>();
        reading = (version, context) -> seen.add("now " + name(context.view().entity(acme)) + ", before "
                + name(context.before().entity(acme)));

        reference.commit(by().patch(acme, from(1, 1), values("name", "Renamed"))
                .create(PROBE, "Probe", from(1, 1), values("label", "a")).build());

        assertThat(seen).containsExactly("now Renamed, before ACME");
    }

    @Test
    void beforeIsEffectiveAtTheStartOfTheVersionAndBeforeAtAtTheInstantGiven() {
        reference.commit(rename(acme, from(3, 1), "Spring"));
        List<String> seen = new ArrayList<>();
        reading = (version, context) -> {
            seen.add("before at " + context.before().effectiveAt() + ": " + name(context.before().entity(acme)));
            seen.add("beforeAt at " + context.beforeAt(on(4, 1)).effectiveAt() + ": "
                    + name(context.beforeAt(on(4, 1)).entity(acme)));
        };

        reference.commit(by().create(PROBE, "Probe", from(2, 1), values("label", "a")).build());

        assertThat(seen).containsExactly("before at " + on(2, 1) + ": ACME", "beforeAt at " + on(4, 1) + ": Spring");
    }

    @Test
    void theBasisOfBeforeIsTheLatestCommittedBasisAndItsSchemaIsTheSchemaOfTheTransaction() {
        Basis latest = reference.store.latestBasis();
        List<Basis> bases = new ArrayList<>();
        List<Boolean> sameSchema = new ArrayList<>();
        reading = (version, context) -> {
            bases.add(context.before().basis());
            bases.add(context.beforeAt(on(4, 1)).basis());
            bases.add(context.view().basis());
            sameSchema.add(context.before().schema().equals(context.view().schema()));
        };

        reference.commit(by().create(PROBE, "Probe", from(1, 1), values("label", "a")).build());

        assertThat(bases).containsExactly(latest, latest, new Basis(latest.value() + 1));
        assertThat(sameSchema).containsExactly(true);
        assertThat(reference.store.latestBasis()).isEqualTo(new Basis(latest.value() + 1));
    }

    @Test
    void beforeShowsNothingOfAnEntityThatTheTransactionCreatesAndEverythingOfOneItDoesNotChange() {
        List<String> seen = new ArrayList<>();
        reading = (version, context) -> {
            seen.add("itself " + context.before().entity(version.id()).isPresent());
            seen.add("beta " + context.before().entity(beta).equals(context.view().entity(beta)));
        };

        reference.commit(by().create(PROBE, "Probe", from(1, 1), values("label", "a")).build());

        assertThat(seen).containsExactly("itself false", "beta true");
    }

    // ------------------------------------------------------------------ dry runs

    @Test
    void aDryRunReadsTheCommittedStateAsACommitDoes() {
        List<String> seen = new ArrayList<>();
        reading = (version, context) -> seen.add("now " + name(context.view().entity(acme)) + ", before "
                + name(context.before().entity(acme)) + ", basis " + context.before().basis().value());
        Transaction transaction = by().patch(acme, from(1, 1), values("name", "Renamed"))
                .create(PROBE, "Probe", from(1, 1), values("label", "a")).build();
        Basis latest = reference.store.latestBasis();

        DryRunResult dryRun = reference.store.dryRun(transaction);
        List<String> inTheDryRun = List.copyOf(seen);
        seen.clear();
        reference.commit(transaction);

        assertThat(dryRun).isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(inTheDryRun).containsExactly("now Renamed, before ACME, basis " + latest.value());
        assertThat(seen).as("the commit that follows the dry run").isEqualTo(inTheDryRun);
    }

    @Test
    void aDryRunReportsTheProblemOfARuleThatReadsWhatWasThereBefore() {
        ReferenceStore leiKept = ReferenceStore.withLeiKept();
        EntityId organization = entity(leiKept.createParties(), ACME);
        leiKept.replay(ReferenceStories.rd9AcmeLei(organization));
        Basis latest = leiKept.store.latestBasis();

        DryRunResult result = leiKept.store.dryRun(by()
                .patch(organization, from(3, 1), values(), Set.of("lei"), ConflictCheck.none()).build());

        assertThat(result).isInstanceOf(DryRunResult.Rejected.class);
        Problem problem = ((DryRunResult.Rejected) result).problems().getFirst();
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).contains("lei-kept/lei-removed");
        assertThat(leiKept.store.latestBasis()).as("a dry run records nothing").isEqualTo(latest);
    }

    // ------------------------------------------------------------------ re-checks

    @Test
    void aRecheckReadsTheStateBeforeTheTransactionThatRunsItAgain() {
        List<String> seen = new ArrayList<>();
        reading = (version, context) -> seen.add(name(context.view().entity(acme)) + " after, "
                + name(context.before().entity(acme)) + " before");
        probe();
        seen.clear();

        reference.commit(rename(acme, from(1, 1), "First"));
        List<String> first = List.copyOf(seen);
        seen.clear();
        reference.commit(rename(acme, from(1, 1), "Second"));

        assertThat(first).as("the probe was not written, and its rule ran again").containsExactly(
                "First after, ACME before");
        assertThat(seen).as("before is the state before this transaction, not the one that wrote the probe")
                .containsExactly("Second after, First before");
    }

    @Test
    void aRecheckReadsAVersionThatTheTransactionDoesNotChangeAsItIs() {
        List<String> seen = new ArrayList<>();
        reading = (version, context) -> {
            Optional<EntityState> before = context.before().entity(version.id());
            seen.add(before.isEmpty() ? "none before" : before.equals(context.view().entity(version.id()))
                    ? "as it is" : "differs");
            context.view().entity(acme);
        };
        probe();
        assertThat(seen).as("the run that created the probe").containsExactly("none before");
        seen.clear();

        reference.commit(rename(acme, from(1, 1), "Renamed"));

        assertThat(seen).containsExactly("as it is");
    }

    // ------------------------------------------------------------------ reads are recorded

    @Test
    void anEntityReadThroughBeforeAtAnInstantIsReachedByAChangeInAStretchThatContainsTheInstant() {
        reading = (version, context) -> context.beforeAt(JUNE_1).entity(acme);
        probe();

        assertThat(rechecked(rename(beta, from(1, 1), "Other"))).as("another entity").isFalse();
        assertThat(rechecked(rename(acme, from(7, 1), "After"))).as("from 1 July").isFalse();
        assertThat(rechecked(rename(acme, from(6, 1), "From"))).as("from 1 June").isTrue();
    }

    @Test
    void anEntityReadThroughBeforeIsReadAtTheStartOfTheVersionsPeriod() {
        reading = (version, context) -> context.before().entity(acme);
        probe();

        assertThat(rechecked(rename(acme, EffectivePeriod.between(on(3, 1), on(4, 1)), "Spring")))
                .as("a change that starts after the version").isFalse();
        assertThat(rechecked(rename(acme, from(1, 1), "From the start"))).as("a change from 1 January").isTrue();
    }

    @Test
    void anEntityThatWasNotThereBeforeIsReachedWhenItIsCreated() {
        EntityId later = id(500);
        EntityId muchLater = id(501);
        reading = (version, context) -> {
            context.beforeAt(JUNE_1).entity(later);
            context.beforeAt(JUNE_1).entity(muchLater);
        };
        probe();

        assertThat(rechecked(newOrganization(muchLater, on(7, 1)))).as("created from 1 July").isFalse();
        assertThat(rechecked(newOrganization(later, JUNE_1))).as("created from 1 June").isTrue();
    }

    @Test
    void aTimelineReadThroughBeforeIsReachedByAnyChangeOfTheEntity() {
        reading = (version, context) -> context.before().timeline(acme);
        probe();

        assertThat(rechecked(rename(beta, from(1, 1), "Other"))).as("another entity").isFalse();
        assertThat(rechecked(rename(acme, EffectivePeriod.from(on(2030, 1, 1)), "Far away"))).isTrue();
    }

    @Test
    void theEntitiesOfATypeListedThroughBeforeAreReachedByAnEntityOfThatType() {
        reading = (version, context) -> context.beforeAt(JUNE_1).entities("Organization");
        probe();

        assertThat(rechecked(rename(alice, from(6, 1), "Not listed"))).as("a person is not listed").isFalse();
        assertThat(rechecked(rename(acme, from(7, 1), "After"))).as("a listed entity, after").isFalse();
        assertThat(rechecked(rename(acme, from(6, 1), "From"))).as("a listed entity, at the instant").isTrue();
    }

    @Test
    void whatNavigationThroughBeforeFindsIsReachedByTheRelationshipsItFound() {
        RelationshipId owns = owns(acme, beta, on(2, 1));
        reading = (version, context) -> context.beforeAt(JUNE_1).navigate(acme, "Owns");
        probe();

        assertThat(rechecked(by().patch(owns, from(7, 1), values("share", 41)).build())).as("from 1 July").isFalse();
        assertThat(rechecked(by().patch(owns, from(6, 1), values("share", 42)).build())).as("from 1 June").isTrue();
        assertThat(rechecked(by().relate("Owns", acme, alice, from(6, 1), values("share", 10)).build()))
                .as("a new relationship from the entity by the name").isTrue();
    }

    @Test
    void whatARuleReadsThroughBeforeAndThroughViewIsReachedByTheSameChanges() {
        reading = (version, context) -> {
            context.beforeAt(JUNE_1).entity(acme);
            context.viewAt(JUNE_1).entity(beta);
        };
        probe();

        assertThat(rechecked(rename(acme, from(6, 1), "Read before"))).isTrue();
        assertThat(rechecked(rename(beta, from(6, 1), "Read now"))).isTrue();
        assertThat(rechecked(rename(alice, from(6, 1), "Not read"))).isFalse();
    }

    @Test
    void whatARuleReadsThroughAViewAfterItReturnedIsNotRecorded() {
        List<View> kept = new ArrayList<>();
        reading = (version, context) -> kept.add(context.before());
        probe();
        kept.getFirst().entity(acme);

        assertThat(rechecked(rename(acme, from(1, 1), "Renamed"))).isFalse();
    }
}
