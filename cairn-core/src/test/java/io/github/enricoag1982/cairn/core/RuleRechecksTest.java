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

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * When a rule is run again (design D1, D2 and D4 of {@code add-rule-rechecks}, ADR-0025): which reads are reached by
 * which changes, and exactly where. The granularity is the specification, so each kind of read and each kind of change
 * has a test that says whether a probe rule is checked again.
 *
 * <p>The probe is an entity of the type {@code Probe}, created from 1 January, and a relationship of the type
 * {@code Watches} between probes. The tests give it a rule that reads something, and then commit a transaction that
 * never writes the probe itself, and see whether the probe's rule ran again. Reads are made at 1 June unless a test
 * says otherwise. The world is the reference domain with some types it does not have: the probe types, a symmetric
 * type, and a relationship type with a subtype.
 */
class RuleRechecksTest {

    private static final TemporaryId PROBE = new TemporaryId("probe");
    private static final TemporaryId ANOTHER = new TemporaryId("another");
    private static final TemporaryId NEW = new TemporaryId("new");
    private static final Instant JUNE_1 = on(6, 1);

    /** The reference domain plus the types of the probes and the types whose relationships are read. */
    private static SchemaDefinition model() {
        return Definitions.plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .entityType("Probe", e -> e.attribute("label", ValueType.STRING)
                        .rule("entity-probe", 1).rule("entity-probe-b", 1))
                .relationshipType("Watches", r -> r.from("Probe").to("Probe")
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("watchedBy").rule("relationship-probe", 1))
                .relationshipType("RelatedParty", r -> r.from("Party").to("Party").symmetric()
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("relatedParties"))
                .relationshipType("Link", r -> r.from("Party").to("Party")
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("linkedFrom"))
                .relationshipType("Peer", r -> r.supertype("Link").from("Party").to("Party").symmetric()
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("peers"))
                .relationshipType("Bond", r -> r.supertype("RelatedParty").from("Party").to("Party")
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("bonds"))
                .relationshipType("WorksFor", r -> r.from("Person").to("Organization")
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("workers"))
                .relationshipType("Contracts", r -> r.supertype("WorksFor").from("Person").to("Organization")
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("contractors"))
                .build());
    }

    // What the probe rules read, set by each test before the probe is created; they do nothing until then.
    private BiConsumer<EntityState, RuleContext> reading = (version, context) -> { };
    private BiConsumer<EntityState, RuleContext> readingB = (version, context) -> { };
    private BiConsumer<RelationshipState, RuleContext> readingRelationship = (version, context) -> { };

    /** The checks of the probe rules, one entry for each run: {@code entity-probe <ID> <start of the version>}. */
    private final List<String> runs = new ArrayList<>();
    private final AtomicReference<Store> itsStore = new AtomicReference<>();

    private ReferenceStore reference;
    private EntityId acme;
    private EntityId beta;
    private EntityId alice;

    @BeforeEach
    void theWorldOfRd1() {
        TestEntityRule probe = TestEntityRule.of("entity-probe", "Probe", (version, context) -> {
            runs.add("entity-probe " + version.id() + " " + version.period().from());
            reading.accept(version, context);
        }, "bad");
        TestEntityRule probeB = TestEntityRule.of("entity-probe-b", "Probe", (version, context) -> {
            runs.add("entity-probe-b " + version.id() + " " + version.period().from());
            readingB.accept(version, context);
        }, "bad");
        TestRelationshipRule watches = TestRelationshipRule.of("relationship-probe", "Watches",
                (version, context) -> {
                    runs.add("relationship-probe " + version.id() + " " + version.period().from());
                    readingRelationship.accept(version, context);
                }, "bad");
        reference = ReferenceStore.withRules(model(), ReferenceStore.rulesWith(probe, probeB, watches),
                ReferenceModel.declaredRules());
        itsStore.set(reference.store);
        Receipt parties = reference.createParties();
        acme = entity(parties, ACME);
        beta = entity(parties, BETA);
        alice = entity(parties, ALICE);
    }

    // ------------------------------------------------------------------ helpers

    /** The period that starts on 1 January of a year. */
    private static EffectivePeriod startOf(int year) {
        return EffectivePeriod.from(on(year, 1, 1));
    }

    private static Transaction.Builder by() {
        return Transaction.by("tester");
    }

    /** Creates the probe, from 1 January, which makes its rules read. */
    private EntityId probe() {
        EntityId probe = entity(reference.commit(by().create(PROBE, "Probe", from(1, 1), values("label", "a")).build()),
                PROBE);
        runs.clear();
        return probe;
    }

    /** Commits a transaction and tells whether it ran the probe's rules again. */
    private boolean rechecked(Transaction transaction) {
        runs.clear();
        reference.commit(transaction);
        return !runs.isEmpty();
    }

    private Transaction rename(EntityId id, EffectivePeriod period, String name) {
        return by().patch(id, period, values("name", name)).build();
    }

    private Transaction newOrganization(EntityId id, Instant from) {
        return by().create(id, "Organization", EffectivePeriod.from(from), values("name", "New")).build();
    }

    private RelationshipId owns(EntityId owner, EntityId owned, Instant from) {
        return relationship(reference.commit(by()
                .relate(NEW, "Owns", owner, owned, EffectivePeriod.from(from), values("share", 40)).build()), NEW);
    }

    /** Creates an organization, effective from 1 January. */
    private EntityId organization(String name) {
        return entity(reference.commit(by().create(NEW, "Organization", from(1, 1), values("name", name)).build()),
                NEW);
    }

    private EntityId person(String name) {
        return entity(reference.commit(by().create(NEW, "Person", from(1, 1), values("name", name)).build()), NEW);
    }

    // ------------------------------------------------------------------ an entity

    @Test
    void anEntityReadAtAnInstantIsReachedByAChangeOfItsValuesInAStretchThatContainsTheInstant() {
        reading = (version, context) -> context.viewAt(JUNE_1).entity(acme);
        probe();

        assertThat(rechecked(rename(acme, from(7, 1), "After"))).as("from 1 July").isFalse();
        assertThat(rechecked(rename(acme, EffectivePeriod.between(on(3, 1), JUNE_1), "Until"))).as("until 1 June")
                .isFalse();
        assertThat(rechecked(rename(acme, EffectivePeriod.between(on(3, 1), on(6, 2)), "Beyond")))
                .as("until 2 June").isTrue();
        assertThat(rechecked(rename(acme, from(6, 1), "From"))).as("from 1 June").isTrue();
        assertThat(rechecked(rename(acme, from(5, 1), "Before"))).as("from 1 May").isTrue();
    }

    @Test
    void anEntityReadAtAnInstantIsNotReachedByAWriteThatChangesNothingOrBySplittingItsVersion() {
        reading = (version, context) -> context.viewAt(on(4, 1)).entity(acme);
        probe();
        EffectivePeriod spring = EffectivePeriod.between(on(3, 1), JUNE_1);

        // The change of the name from 1 August is real, so the transaction records the timeline, with the split.
        boolean rechecked = rechecked(by().patch(acme, spring, values("name", "Temporary"))
                .patch(acme, spring, values("name", "ACME")).patch(acme, from(8, 1), values("name", "Later")).build());

        assertThat(reference.view(on(4, 1)).timeline(acme)).as("the versions were split by the recordings")
                .hasSize(4);
        assertThat(rechecked).as("the values are the same at 1 April").isFalse();
        assertThat(rechecked(rename(acme, from(9, 1), "Later"))).as("a patch of the values it has").isFalse();
        assertThat(rechecked(rename(acme, EffectivePeriod.between(on(3, 1), on(4, 2)), "Spring"))).as("a change")
                .isTrue();
    }

    @Test
    void anEntityReadAcrossEffectiveTimeIsReachedByAnyChangeOfItsValues() {
        reading = (version, context) -> context.view().timeline(acme);
        probe();

        assertThat(rechecked(rename(beta, from(1, 1), "Other"))).as("another entity").isFalse();
        assertThat(rechecked(rename(acme, startOf(2030), "Far away"))).as("a change in 2030").isTrue();
        assertThat(rechecked(rename(acme, startOf(2031), "Farther away"))).as("a change in 2031").isTrue();
    }

    @Test
    void anEntityThatDidNotExistWhenItWasReadIsReachedWhenItIsCreated() {
        EntityId later = id(500);
        EntityId muchLater = id(501);
        reading = (version, context) -> {
            context.viewAt(JUNE_1).entity(later);
            context.viewAt(JUNE_1).entity(muchLater);
        };
        probe();

        assertThat(rechecked(newOrganization(muchLater, on(7, 1)))).as("created from 1 July").isFalse();
        assertThat(rechecked(newOrganization(later, JUNE_1))).as("created from 1 June").isTrue();
    }

    @Test
    void endingAnEntityIsAChangeFromTheInstantItEnds() {
        reading = (version, context) -> context.viewAt(JUNE_1).entity(beta);
        probe();

        assertThat(rechecked(by().endDate(beta, on(7, 1)).build())).as("ending on 1 July").isFalse();
        assertThat(rechecked(by().endDate(beta, JUNE_1).build())).as("ending on 1 June").isTrue();
    }

    @Test
    void everyEntityStateThatAReadReturnsCountsAsAReadOfThatEntity() {
        // The rule lists Organization, then asks about nothing else: ACME and Beta are read because they are returned.
        reading = (version, context) -> context.viewAt(JUNE_1).entities("Organization");
        probe();

        assertThat(rechecked(rename(alice, from(6, 1), "Not listed"))).as("a person is not listed").isFalse();
        assertThat(rechecked(rename(acme, from(7, 1), "After"))).as("a listed entity, after").isFalse();
        assertThat(rechecked(rename(acme, from(6, 1), "From"))).as("a listed entity, at the instant").isTrue();
    }

    // ------------------------------------------------------------------ a relationship

    @Test
    void aRelationshipReadAtAnInstantIsReachedByAChangeOfItsValuesOrItsEnd() {
        RelationshipId owns = owns(acme, beta, on(2, 1));
        reading = (version, context) -> context.viewAt(JUNE_1).relationship(owns);
        probe();

        assertThat(rechecked(by().patch(owns, from(7, 1), values("share", 41)).build())).as("from 1 July").isFalse();
        assertThat(rechecked(by().patch(owns, from(6, 1), values("share", 42)).build())).as("from 1 June").isTrue();
        assertThat(rechecked(by().endDate(owns, on(7, 1)).build())).as("ending on 1 July").isFalse();
        assertThat(rechecked(by().endDate(owns, JUNE_1).build())).as("ending on 1 June").isTrue();
    }

    @Test
    void aRelationshipReadAcrossEffectiveTimeIsReachedByAnyChangeOfItsValues() {
        RelationshipId owns = owns(acme, beta, on(2, 1));
        RelationshipId other = owns(beta, acme, on(2, 1));
        reading = (version, context) -> context.view().relationshipTimeline(owns);
        probe();

        assertThat(rechecked(by().patch(other, startOf(2030), values("share", 41)).build())).as("another one")
                .isFalse();
        assertThat(rechecked(by().patch(owns, startOf(2030), values("share", 41)).build())).as("a change in 2030")
                .isTrue();
    }

    @Test
    void everyRelationshipStateThatNavigationReturnsCountsAsAReadOfThatRelationship() {
        RelationshipId owns = owns(acme, beta, on(2, 1));
        reading = (version, context) -> context.viewAt(JUNE_1).navigate(acme, "Owns");
        probe();

        assertThat(rechecked(by().patch(owns, from(7, 1), values("share", 41)).build())).as("from 1 July").isFalse();
        assertThat(rechecked(by().patch(owns, from(6, 1), values("share", 42)).build())).as("from 1 June").isTrue();
    }

    @Test
    void everyRelationshipStateThatANavigationAcrossTimeReturnsCountsAsAReadAcrossTime() {
        RelationshipId owns = owns(acme, beta, on(2, 1));
        reading = (version, context) -> context.view().navigateTimeline(acme, "Owns");
        probe();

        assertThat(rechecked(by().patch(owns, startOf(2030), values("share", 41)).build())).isTrue();
    }

    // ------------------------------------------------------------------ what navigation finds

    @Test
    void whatNavigationFindsAtAnInstantIsReachedByARelationshipThatStartsThereOrBefore() {
        reading = (version, context) -> context.viewAt(JUNE_1).navigate(beta, "Owns");
        probe();
        EntityId gamma = organization("Gamma");
        EntityId delta = organization("Delta");

        assertThat(rechecked(by().relate("Owns", beta, gamma, from(7, 1), values("share", 10)).build()))
                .as("a relationship that starts after the instant").isFalse();
        assertThat(rechecked(by().relate("Owns", beta, delta, from(6, 1), values("share", 10)).build()))
                .as("a relationship that starts at the instant").isTrue();
    }

    @Test
    void whatNavigationFindsIsReachedOnlyByRelationshipsThatLeadAwayFromTheEntityInTheDirectionOfTheName() {
        reading = (version, context) -> {
            context.viewAt(JUNE_1).navigate(beta, "Owns");
            context.viewAt(JUNE_1).navigate(acme, "ownedBy");
        };
        probe();
        EntityId gamma = organization("Gamma");

        assertThat(rechecked(by().relate("Owns", gamma, beta, from(6, 1), values("share", 10)).build()))
                .as("Gamma owns Beta: Beta is the target, and the rule navigated forward from it").isFalse();
        assertThat(rechecked(by().relate("Owns", acme, gamma, from(6, 1), values("share", 10)).build()))
                .as("ACME owns Gamma: ACME is the source, and the rule navigated backwards from it").isFalse();
        assertThat(rechecked(by().relate("Owns", beta, gamma, from(6, 1), values("share", 10)).build()))
                .as("Beta owns Gamma: Beta is the source").isTrue();
    }

    @Test
    void navigationByAnInverseNameIsReachedByRelationshipsThatLeadToTheEntity() {
        reading = (version, context) -> context.viewAt(JUNE_1).navigate(beta, "ownedBy");
        probe();
        EntityId gamma = organization("Gamma");

        assertThat(rechecked(by().relate("Owns", beta, gamma, from(6, 1), values("share", 10)).build()))
                .as("Beta owns Gamma").isFalse();
        assertThat(rechecked(by().relate("Owns", gamma, beta, from(6, 1), values("share", 10)).build()))
                .as("Gamma owns Beta").isTrue();
    }

    @Test
    void whatNavigationFindsAcrossEffectiveTimeIsReachedByAnyRelationshipOfTheEntityAndName() {
        reading = (version, context) -> context.view().navigateTimeline(beta, "Owns");
        probe();
        EntityId gamma = organization("Gamma");

        assertThat(rechecked(by().relate("Owns", acme, gamma, startOf(2030), values("share", 10)).build()))
                .as("another entity").isFalse();
        assertThat(rechecked(by().relate("Owns", beta, gamma, startOf(2030), values("share", 10)).build()))
                .as("a relationship from 2030").isTrue();
    }

    @Test
    void aNavigationByTheNameOfASupertypeIsReachedByRelationshipsOfItsSubtypes() {
        reading = (version, context) -> context.viewAt(JUNE_1).navigate(alice, "WorksFor");
        probe();

        assertThat(rechecked(by().relate("Contracts", alice, beta, from(6, 1), values()).build())).isTrue();
    }

    @Test
    void aNavigationByTheNameOfASubtypeIsNotReachedByRelationshipsOfItsSupertype() {
        reading = (version, context) -> context.viewAt(JUNE_1).navigate(alice, "Contracts");
        probe();

        assertThat(rechecked(by().relate("WorksFor", alice, beta, from(6, 1), values()).build())).isFalse();
        assertThat(rechecked(by().relate("Contracts", alice, acme, from(6, 1), values()).build())).isTrue();
    }

    @Test
    void aNavigationAlongASymmetricTypeIsReachedByRelationshipsAtEitherEndByEitherName() {
        reading = (version, context) -> {
            context.viewAt(JUNE_1).navigate(alice, "RelatedParty");
            context.viewAt(JUNE_1).navigate(acme, "relatedParties");
        };
        probe();
        EntityId gamma = organization("Gamma");

        assertThat(rechecked(by().relate("RelatedParty", beta, alice, from(6, 1), values()).build()))
                .as("Alice is the target").isTrue();
        assertThat(rechecked(by().relate("RelatedParty", beta, acme, from(6, 1), values()).build()))
                .as("ACME is the target, and the rule used the inverse name").isTrue();
        assertThat(rechecked(by().relate("RelatedParty", alice, gamma, from(7, 1), values()).build()))
                .as("Alice is the source, but from 1 July").isFalse();
        assertThat(rechecked(by().relate("RelatedParty", beta, gamma, from(6, 1), values()).build()))
                .as("neither Alice nor ACME").isFalse();
    }

    @Test
    void aNavigationByASupertypeThatIsNotSymmetricIsReachedFromEitherEndByARelationshipOfASymmetricSubtype() {
        reading = (version, context) -> context.viewAt(JUNE_1).navigate(alice, "Link");
        probe();

        assertThat(rechecked(by().relate("Peer", beta, alice, from(6, 1), values()).build()))
                .as("Alice is the target of a symmetric Peer, which navigation by Link finds from either end")
                .isTrue();
    }

    @Test
    void aNavigationBySymmetricTypeIsReachedFromEitherEndByARelationshipOfANonSymmetricSubtype() {
        reading = (version, context) -> context.viewAt(JUNE_1).navigate(alice, "RelatedParty");
        probe();

        assertThat(rechecked(by().relate("Bond", beta, alice, from(6, 1), values()).build()))
                .as("Alice is the target of a Bond, which navigation by the symmetric RelatedParty finds").isTrue();
    }

    // ------------------------------------------------------------------ the entities of a type

    @Test
    void theEntitiesOfATypeAreReachedByAnEntityOfThatTypeThatStartsAtOrBeforeTheInstant() {
        reading = (version, context) -> context.viewAt(JUNE_1).entities("Organization");
        probe();

        assertThat(rechecked(by().create("Organization", from(7, 1), values("name", "July")).build()))
                .as("an organization from 1 July").isFalse();
        assertThat(rechecked(by().create("Organization", from(6, 1), values("name", "June")).build()))
                .as("an organization from 1 June").isTrue();
        assertThat(rechecked(by().create("Person", from(6, 1), values("name", "Bob")).build()))
                .as("a person").isFalse();
    }

    @Test
    void theEntitiesOfASupertypeAreReachedByEntitiesOfItsSubtypesButNotTheOtherWayRound() {
        reading = (version, context) -> context.viewAt(JUNE_1).entities("Party");
        probe();
        assertThat(rechecked(by().create("Person", from(6, 1), values("name", "Bob")).build())).as("a person")
                .isTrue();
        assertThat(rechecked(by().create("Organization", from(6, 1), values("name", "Gamma")).build()))
                .as("an organization").isTrue();

        reading = (version, context) -> context.viewAt(JUNE_1).entities("Person");
        reference.commit(by().patch(entity(reference.parties, ALICE), from(6, 1), values("name", "Alice A")).build());
        assertThat(rechecked(by().create("Organization", from(6, 1), values("name", "Delta")).build()))
                .as("the rule lists persons, and an organization is created").isFalse();
    }

    @Test
    void anEndedEntityThatWasListedIsAChange() {
        reading = (version, context) -> context.viewAt(JUNE_1).entities("Organization");
        probe();

        assertThat(rechecked(by().endDate(beta, JUNE_1).build())).isTrue();
    }

    // ------------------------------------------------------------------ traversals and queries

    @Test
    void aTraversalCountsAsTheNavigationsItIsMadeOf() {
        owns(acme, beta, on(2, 1));
        EntityId gamma = organization("Gamma");
        EntityId delta = organization("Delta");
        reading = (version, context) -> context.viewAt(JUNE_1).traverse(acme, "Owns", relationship -> true, 5);
        probe();

        assertThat(rechecked(by().relate("Owns", beta, gamma, from(7, 1), values("share", 10)).build()))
                .as("the second step, but from 1 July").isFalse();
        assertThat(rechecked(by().relate("Owns", beta, delta, from(6, 1), values("share", 10)).build()))
                .as("the second step").isTrue();
    }

    @Test
    void aTraversalThatStopsBeforeAStepDoesNotReadIt() {
        owns(acme, beta, on(2, 1));
        EntityId gamma = organization("Gamma");
        reading = (version, context) -> context.viewAt(JUNE_1).traverse(acme, "Owns", relationship -> true, 1);
        probe();

        assertThat(rechecked(by().relate("Owns", beta, gamma, from(6, 1), values("share", 10)).build()))
                .as("one step does not navigate from Beta").isFalse();
    }

    @Test
    void theStartOfAQueryCountsAsTheEntitiesOfItsType() {
        reading = (version, context) -> context.viewAt(JUNE_1).query(Query.from("Organization", "o").build());
        probe();

        assertThat(rechecked(by().create("Organization", from(7, 1), values("name", "July")).build())).isFalse();
        assertThat(rechecked(by().create("Organization", from(6, 1), values("name", "June")).build())).isTrue();
    }

    @Test
    void aFollowStepOfAQueryCountsAsANavigation() {
        reading = (version, context) -> context.viewAt(JUNE_1)
                .query(Query.from("Organization", "o").follow("o", "Owns", "x").build());
        probe();
        EntityId gamma = organization("Gamma");
        EntityId delta = organization("Delta");

        assertThat(rechecked(by().relate("Owns", beta, gamma, from(7, 1), values("share", 10)).build()))
                .as("from 1 July").isFalse();
        assertThat(rechecked(by().relate("Owns", beta, delta, from(6, 1), values("share", 10)).build()))
                .as("Beta owns Delta, which changes no list of organizations").isTrue();
    }

    // ------------------------------------------------------------------ what is not a read

    @Test
    void theSchemaTheBasisAndTheEffectiveTimeAreNotReads() {
        reading = (version, context) -> {
            context.viewAt(JUNE_1).schema();
            context.viewAt(JUNE_1).basis();
            context.viewAt(JUNE_1).effectiveAt();
        };
        probe();

        assertThat(rechecked(rename(acme, from(1, 1), "Renamed"))).isFalse();
        assertThat(rechecked(by().create("Organization", from(1, 1), values("name", "Gamma")).build())).isFalse();
    }

    @Test
    void aViewThatDoesNotBelongToARuleContextRecordsNothing() {
        reading = (version, context) -> itsStore.get().view(Coordinates.effectiveAt(JUNE_1)).entity(acme);
        probe();

        assertThat(rechecked(rename(acme, from(1, 1), "Renamed"))).isFalse();
    }

    @Test
    void whatARuleReadsAfterItReturnedIsNotRecorded() {
        List<View> kept = new ArrayList<>();
        reading = (version, context) -> kept.add(context.viewAt(JUNE_1));
        probe();
        kept.getFirst().entity(acme);

        assertThat(rechecked(rename(acme, from(1, 1), "Renamed"))).isFalse();
    }

    // ------------------------------------------------------------------ a check replaces the reads of the version

    @Test
    void aVersionThatNoLongerReadsAnEntityIsNotRunAgainWhenItChanges() {
        reading = (version, context) -> {
            if (version.values().get("label").equals(Value.of("a"))) {
                context.viewAt(JUNE_1).entity(acme);
            } else {
                context.viewAt(JUNE_1).entity(beta);
            }
        };
        EntityId probe = probe();
        assertThat(rechecked(rename(acme, from(6, 1), "ACME 2"))).as("it reads ACME").isTrue();

        assertThat(rechecked(by().patch(probe, from(1, 1), values("label", "b")).build()))
                .as("the probe itself changes: its rule runs").isTrue();

        assertThat(rechecked(rename(acme, from(6, 1), "ACME 3"))).as("it reads Beta now").isFalse();
        assertThat(rechecked(rename(beta, from(6, 1), "Beta 2"))).isTrue();
    }

    @Test
    void aRecheckRecordsWhatItReadsLikeAnyOtherRun() {
        reading = (version, context) -> {
            EntityState acmeNow = context.viewAt(JUNE_1).entity(acme).orElseThrow();
            if (acmeNow.values().get("name").equals(Value.of("ACME Holdings"))) {
                context.viewAt(JUNE_1).entity(beta);
            }
        };
        probe();
        assertThat(rechecked(rename(beta, from(6, 1), "Beta 2"))).as("it does not read Beta yet").isFalse();

        assertThat(rechecked(rename(acme, from(6, 1), "ACME Holdings"))).as("the re-check").isTrue();

        assertThat(rechecked(rename(beta, from(6, 1), "Beta 3"))).as("it reads Beta now").isTrue();
    }

    @Test
    void aRunOfOnePartOfAVersionReplacesOnlyTheReadsOfThatPart() {
        reading = (version, context) -> {
            if (version.period().from().isBefore(on(4, 1))) {
                context.viewAt(JUNE_1).entity(acme);
            } else {
                context.viewAt(JUNE_1).entity(beta);
            }
        };
        EntityId probe = probe();

        reference.commit(by().patch(probe, from(4, 1), values("label", "b")).build());

        runs.clear();
        reference.commit(rename(acme, from(6, 1), "ACME 2"));
        assertThat(runs).as("only the part before 1 April reads ACME").containsExactly(
                "entity-probe " + probe + " " + from(1, 1).from());
        runs.clear();
        reference.commit(rename(beta, from(6, 1), "Beta 2"));
        assertThat(runs).as("only the part from 1 April reads Beta").containsExactly(
                "entity-probe " + probe + " " + on(4, 1));
    }

    @Test
    void theVersionsThatTheRulesOfTheWritesRanForAreNotRunTwice() {
        reading = (version, context) -> context.viewAt(JUNE_1).entity(acme);
        EntityId probe = probe();

        reference.commit(by().patch(probe, from(4, 1), values("label", "b"))
                .patch(acme, from(6, 1), values("name", "ACME 2")).build());

        assertThat(runs.stream().filter(run -> run.startsWith("entity-probe "))).as("one run for each version")
                .containsExactlyInAnyOrder("entity-probe " + probe + " " + on(4, 1),
                        "entity-probe " + probe + " " + from(1, 1).from());
    }

    // ------------------------------------------------------------------ problems of re-checks

    /** Makes a probe rule report a problem when ACME's name is the given text, as read at 1 June. */
    private static BiConsumer<EntityState, RuleContext> badWhenAcmeIsNamed(String name, EntityId acme) {
        return (version, context) -> {
            if (context.viewAt(JUNE_1).entity(acme).orElseThrow().values().get("name").equals(Value.of(name))) {
                context.problem("bad", "ACME is named " + name + " for the version from " + version.period().from());
            }
        };
    }

    @Test
    void theProblemsOfRechecksComeInTheOrderOfTheIdsThenOfTheRulesThenOfTheVersions() {
        reading = badWhenAcmeIsNamed("Bad", acme);
        readingB = badWhenAcmeIsNamed("Bad", acme);
        EntityId first = probe();
        EntityId second = entity(reference.commit(by().create(ANOTHER, "Probe", from(1, 1), values("label", "a"))
                .build()), ANOTHER);
        reference.commit(by().patch(first, from(4, 1), values("label", "b")).build());

        List<Problem> problems = reference.reject(rename(acme, from(6, 1), "Bad"));

        assertThat(first.value()).isLessThan(second.value());
        assertThat(problems).extracting(Problem::path).containsExactly(first.value(), first.value(), first.value(),
                first.value(), second.value(), second.value());
        assertThat(problems).extracting(problem -> problem.ruleCode().orElseThrow()).containsExactly(
                "entity-probe/bad", "entity-probe/bad", "entity-probe-b/bad", "entity-probe-b/bad",
                "entity-probe/bad", "entity-probe-b/bad");
        assertThat(problems.get(0).message()).contains(from(1, 1).from().toString());
        assertThat(problems.get(1).message()).contains(on(4, 1).toString());
        assertThat(problems.get(2).message()).contains(from(1, 1).from().toString());
        assertThat(problems.get(3).message()).contains(on(4, 1).toString());
    }

    @Test
    void aProblemOfARecheckHasThePathOfWhatItChecksAndTheHint() {
        reading = badWhenAcmeIsNamed("Bad", acme);
        EntityId probe = probe();

        List<Problem> problems = reference.reject(rename(acme, from(6, 1), "Bad"));

        Problem problem = problems.getFirst();
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.path()).isEqualTo(probe.value());
        assertThat(problem.hint()).contains("The transaction changes what the rule read when it last checked "
                + probe.value() + ".");
    }

    @Test
    void aRuleThatFailsInARecheckIsReportedAsFailedWithTheHint() {
        reading = (version, context) -> {
            if (context.viewAt(JUNE_1).entity(acme).orElseThrow().values().get("name").equals(Value.of("Boom"))) {
                throw new IllegalStateException("boom");
            }
        };
        EntityId probe = probe();

        List<Problem> problems = reference.reject(rename(acme, from(6, 1), "Boom"));

        Problem problem = problems.getFirst();
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_FAILED);
        assertThat(problem.path()).isEqualTo(probe.value());
        assertThat(problem.found()).contains("boom");
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).endsWith("The transaction changes "
                + "what the rule read when it last checked " + probe.value() + "."));
    }

    @Test
    void aRuleThatReportedAProblemAboutWhatTheTransactionWritesIsNotRunAgainForOthers() {
        reading = (version, context) -> {
            context.viewAt(JUNE_1).entity(acme);
            if (version.values().get("label").equals(Value.of("wrong"))) {
                context.problem("bad", "The label is wrong.");
            }
        };
        EntityId probe = probe();
        reference.commit(by().create(ANOTHER, "Probe", from(1, 1), values("label", "a")).build());

        List<Problem> problems = reference.reject(by()
                .patch(probe, from(1, 1), values("label", "wrong"))
                .patch(acme, from(6, 1), values("name", "Renamed")).build());

        assertThat(problems).as("the probe that is written, and no re-check of the other").singleElement()
                .satisfies(problem -> {
                    assertThat(problem.path()).isEqualTo(probe.value());
                    assertThat(problem.hint()).as("it ran for what the transaction writes").isEmpty();
                });
    }

    @Test
    void aRuleThatReportedAProblemAboutAVersionThatTheTransactionWritesIsNotRunAgainForTheOtherVersionsOfThatEntity() {
        reading = (version, context) -> {
            boolean renamed = context.viewAt(JUNE_1).entity(acme).orElseThrow().values().get("name")
                    .equals(Value.of("Renamed"));
            if (renamed || version.values().get("label").equals(Value.of("wrong"))) {
                context.problem("bad", "Wrong for the version from " + version.period().from());
            }
        };
        EntityId probe = probe();
        reference.commit(by().patch(probe, from(4, 1), values("label", "b")).build());
        assertThat(reference.reject(rename(acme, from(6, 1), "Renamed"))).as("both versions are run again alone")
                .extracting(Problem::message).containsExactly("Wrong for the version from " + from(1, 1).from(),
                        "Wrong for the version from " + on(4, 1));

        List<Problem> problems = reference.reject(by()
                .patch(probe, from(4, 1), values("label", "wrong"))
                .patch(acme, from(6, 1), values("name", "Renamed")).build());

        assertThat(problems).as("the written version only: the version before 1 April is not run again")
                .singleElement().satisfies(problem -> {
                    assertThat(problem.path()).isEqualTo(probe.value());
                    assertThat(problem.message()).isEqualTo("Wrong for the version from " + on(4, 1));
                    assertThat(problem.hint()).as("it ran for what the transaction writes").isEmpty();
                });
    }

    @Test
    void aRuleThatReportedForARecheckIsStillRunAgainForOtherEntitiesAndVersions() {
        reading = badWhenAcmeIsNamed("Bad", acme);
        EntityId first = probe();
        EntityId second = entity(reference.commit(by().create(ANOTHER, "Probe", from(1, 1), values("label", "a"))
                .build()), ANOTHER);

        List<Problem> problems = reference.reject(rename(acme, from(6, 1), "Bad"));

        assertThat(problems).extracting(Problem::path).containsExactly(first.value(), second.value());
    }

    @Test
    void aDryRunReportsTheProblemsOfRechecksAndRecordsNothing() {
        reading = badWhenAcmeIsNamed("Bad", acme);
        probe();
        runs.clear();

        DryRunResult result = reference.store.dryRun(rename(acme, from(6, 1), "Bad"));

        assertThat(result).isInstanceOf(DryRunResult.Rejected.class);
        assertThat(((DryRunResult.Rejected) result).problems()).singleElement().satisfies(problem ->
                assertThat(problem.ruleCode()).contains("entity-probe/bad"));
        assertThat(reference.view(JUNE_1).entity(acme).orElseThrow().values()).containsEntry("name", Value.of("ACME"));
    }

    @Test
    void noRechecksRunForATransactionWithAProblemOfAnotherKind() {
        reading = badWhenAcmeIsNamed("Bad", acme);
        probe();
        runs.clear();

        CommitResult result = reference.store.commit(by()
                .patch(acme, from(6, 1), values("name", "Bad"))
                .create("Organization", from(1, 1), values()).build());

        assertThat(result).isInstanceOf(CommitResult.Rejected.class);
        assertThat(((CommitResult.Rejected) result).problems()).singleElement().satisfies(problem ->
                assertThat(problem.code()).isEqualTo(ProblemCode.MISSING_REQUIRED_VALUE));
        assertThat(runs).isEmpty();
    }

    // ------------------------------------------------------------------ rules on relationships

    @Test
    void aRuleOnARelationshipIsRunAgainWhenWhatItReadChanges() {
        EntityId one = probe();
        EntityId two = entity(reference.commit(by().create(ANOTHER, "Probe", from(1, 1), values("label", "a"))
                .build()), ANOTHER);
        readingRelationship = (version, context) -> context.viewAt(JUNE_1).entity(acme);
        RelationshipId watches = relationship(reference.commit(by().relate(NEW, "Watches", one, two, from(1, 1),
                values()).build()), NEW);
        runs.clear();

        assertThat(rechecked(rename(acme, from(7, 1), "After"))).isFalse();
        assertThat(rechecked(rename(acme, from(6, 1), "From"))).isTrue();
        assertThat(runs).containsExactly("relationship-probe " + watches + " " + from(1, 1).from());
    }

    @Test
    void aProbeIsNotRunAgainByTheTransactionThatCreatesIt() {
        reading = (version, context) -> context.viewAt(JUNE_1).entity(acme);

        reference.commit(by().create(PROBE, "Probe", from(1, 1), values("label", "a"))
                .patch(acme, from(6, 1), values("name", "Renamed")).build());

        assertThat(runs.stream().filter(run -> run.startsWith("entity-probe "))).as("once, by the rules of its write")
                .hasSize(1);
    }
}
