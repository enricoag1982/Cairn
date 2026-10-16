package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Design D1 and D2 of {@code add-relationships}: how a view reads relationships by ID, by their timeline, by navigation
 * from an entity and by traversal, at its effective time and basis.
 *
 * <p>The world is the reference domain with some types it does not have: a symmetric type, a relationship type with a
 * subtype, and one with an attribute that a later schema widens. ACME, Beta and Alice exist from 1 January (RD-1).
 */
class RelationshipViewsTest {

    private static final TemporaryId NEW = new TemporaryId("new");
    private static final Instant JUST_BEFORE_OCTOBER = on(10, 1).minusNanos(1000);

    /** The reference domain with types that it does not have. */
    private static SchemaDefinition model() {
        return Definitions.plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .relationshipType("RelatedParty", r -> r.from("Party").to("Party").symmetric()
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("relatedParties"))
                .relationshipType("WorksFor", r -> r.from("Person").to("Organization")
                        .targetsPerSource(Multiplicity.ZERO_OR_ONE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("workers"))
                .relationshipType("Contracts", r -> r.supertype("WorksFor").from("Person").to("Organization")
                        .targetsPerSource(Multiplicity.ZERO_OR_ONE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("contractors"))
                .relationshipType("Rates", r -> r.from("Organization").to("Organization")
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("ratedBy").attribute("level", ValueType.INT))
                .build());
    }

    private final ReferenceStore reference = ReferenceStore.with(model());
    private EntityId acme;
    private EntityId beta;
    private EntityId alice;

    @BeforeEach
    void theWorldOfRd1() {
        Receipt parties = reference.createParties();
        acme = entity(parties, ACME);
        beta = entity(parties, BETA);
        alice = entity(parties, ALICE);
    }

    private EntityId organization(String name) {
        return entity(reference.commit(Transaction.by("tester")
                .create(NEW, "Organization", from(1, 1), values("name", name)).build()), NEW);
    }

    private EntityId person(String name) {
        return entity(reference.commit(Transaction.by("tester")
                .create(NEW, "Person", from(1, 1), values("name", name)).build()), NEW);
    }

    private RelationshipId relate(String type, EntityId source, EntityId target, EffectivePeriod period,
            Object... namesAndValues) {
        return relationship(reference.commit(Transaction.by("tester")
                .relate(NEW, type, source, target, period, values(namesAndValues)).build()), NEW);
    }

    private RelationshipId owns(EntityId owner, EntityId owned, int share) {
        return relate("Owns", owner, owned, from(2, 1), "share", share);
    }

    private static Predicate<RelationshipState> any() {
        return relationship -> true;
    }

    private static List<EntityId> entities(List<Link> links) {
        return links.stream().map(link -> link.entity().id()).toList();
    }

    private static List<RelationshipId> relationships(List<Link> links) {
        return links.stream().map(link -> link.relationship().id()).toList();
    }

    private static BigDecimal share(RelationshipState state) {
        return ((Value.DecimalValue) state.values().get("share")).value();
    }

    // ------------------------------------------------------------------ names

    @Test
    void aTypesNameLeadsFromItsSourcesToTheirTargetsAndItsInverseNameFromTargetsToSources() {
        RelationshipId acmeOwnsBeta = owns(acme, beta, 60);

        List<Link> down = reference.view(on(3, 1)).navigate(acme, "Owns");
        List<Link> up = reference.view(on(3, 1)).navigate(beta, "ownedBy");

        assertThat(entities(down)).containsExactly(beta);
        assertThat(down.getFirst().relationship().id()).isEqualTo(acmeOwnsBeta);
        assertThat(entities(up)).containsExactly(acme);
        assertThat(up.getFirst().relationship()).isEqualTo(down.getFirst().relationship());
        assertThat(reference.view(on(3, 1)).navigate(beta, "Owns")).as("Beta owns nothing").isEmpty();
        assertThat(reference.view(on(3, 1)).navigate(acme, "ownedBy")).as("nothing owns ACME").isEmpty();
    }

    @Test
    void namesAreComparedWithoutRegardToCaseAndInverseAliasesAreNames() {
        owns(acme, beta, 60);
        SchemaDefinition renamed = Definitions.changeRelationshipType(model(), "Owns", owns ->
                new RelationshipTypeDefinition(owns.name(), owns.source(), owns.target(), owns.targetsPerSource(),
                        owns.sourcesPerTarget(), "owners", List.of("ownedBy"), owns.kind(), owns.symmetric(),
                        owns.supertype(), owns.attributes(), owns.aliases(), owns.deprecated(), owns.description(),
                        owns.rules()));
        reference.clock.advance(Duration.ofMinutes(1));
        assertThat(reference.store.schema().register(ReferenceModel.origin("1.1.0"), renamed))
                .isInstanceOf(RegistrationResult.Applied.class);
        View view = reference.view(on(3, 1));

        assertThat(entities(view.navigate(acme, "OWNS"))).containsExactly(beta);
        assertThat(entities(view.navigate(beta, "owners"))).containsExactly(acme);
        assertThat(entities(view.navigate(beta, "OWNERS"))).containsExactly(acme);
        assertThat(entities(view.navigate(beta, "ownedby"))).as("an inverse alias").containsExactly(acme);
    }

    @Test
    void unknownNamesFailWithAnIllegalArgumentExceptionThatNamesThem() {
        View view = reference.view(on(3, 1));

        assertThatThrownBy(() -> view.navigate(acme, "Owners")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Owners");
        assertThatThrownBy(() -> view.navigate(acme, "Organization")).as("an entity type is not a relationship")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Organization");
        assertThatThrownBy(() -> view.navigateTimeline(acme, "Owners")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> view.traverse(acme, "Owners", any(), 3)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Owners");
        assertThatThrownBy(() -> view.traverse(acme, "Owners", any(), 0)).as("also when it would follow nothing")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theMessageNamesTheSchemaOfTheViewsBasis() {
        View view = reference.store.view(Coordinates.effectiveAt(on(3, 1)).knownAt(new Basis(1)));

        assertThatThrownBy(() -> view.navigate(acme, "Owners")).hasMessageContaining("basis 1");
    }

    @Test
    void navigationRejectsNulls() {
        View view = reference.view(on(3, 1));

        assertThatThrownBy(() -> view.navigate(null, "Owns")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> view.navigate(acme, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> view.navigateTimeline(null, "Owns")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> view.traverse(null, "Owns", any(), 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> view.traverse(acme, null, any(), 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> view.traverse(acme, "Owns", null, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> view.relationship(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> view.relationshipTimeline(null)).isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------ subtypes and symmetric types

    @Test
    void navigationIncludesTheRelationshipsOfSubtypes() {
        EntityId company = organization("Company");
        EntityId agency = organization("Agency");
        EntityId employee = person("Employee");
        EntityId contractor = person("Contractor");
        RelationshipId worksFor = relate("WorksFor", employee, company, from(2, 1));
        RelationshipId contracts = relate("Contracts", contractor, agency, from(2, 1));
        View view = reference.view(on(3, 1));

        assertThat(relationships(view.navigate(company, "workers"))).containsExactly(worksFor);
        assertThat(relationships(view.navigate(agency, "workers"))).as("a subtype, by the supertype's inverse name")
                .containsExactly(contracts);
        assertThat(view.navigate(agency, "workers").getFirst().relationship().relationshipType())
                .isEqualTo("Contracts");
        assertThat(relationships(view.navigate(contractor, "WorksFor"))).containsExactly(contracts);
        assertThat(relationships(view.navigate(agency, "contractors"))).containsExactly(contracts);
        assertThat(view.navigate(company, "contractors")).as("a supertype is not a subtype").isEmpty();
        assertThat(view.navigate(employee, "Contracts")).isEmpty();
    }

    @Test
    void aSymmetricTypeIsFoundFromBothEndsByItsNameAndByItsInverseName() {
        RelationshipId related = relate("RelatedParty", acme, beta, from(2, 1));
        View view = reference.view(on(3, 1));

        for (String name : List.of("RelatedParty", "relatedParties")) {
            assertThat(entities(view.navigate(acme, name))).as("from the source by %s", name).containsExactly(beta);
            assertThat(entities(view.navigate(beta, name))).as("from the target by %s", name).containsExactly(acme);
            assertThat(relationships(view.navigate(beta, name))).containsExactly(related);
        }
        assertThat(view.navigate(beta, "RelatedParty").getFirst().relationship().source())
                .as("the relationship keeps the source it was created with").isEqualTo(acme);
        assertThat(view.navigate(alice, "RelatedParty")).isEmpty();
    }

    @Test
    void aSymmetricRelationshipRelatedInTheOtherOrderIsTheSameForNavigation() {
        relate("RelatedParty", beta, acme, from(2, 1));
        View view = reference.view(on(3, 1));

        assertThat(entities(view.navigate(acme, "RelatedParty"))).containsExactly(beta);
        assertThat(entities(view.navigate(beta, "RelatedParty"))).containsExactly(acme);
    }

    @Test
    void anEntityRelatedToItselfIsFoundOnceFromItsOwnEnd() {
        RelationshipId self = owns(acme, acme, 10);
        View view = reference.view(on(3, 1));

        assertThat(relationships(view.navigate(acme, "Owns"))).containsExactly(self);
        assertThat(relationships(view.navigate(acme, "ownedBy"))).containsExactly(self);
        assertThat(entities(view.navigate(acme, "Owns"))).containsExactly(acme);
    }

    // ------------------------------------------------------------------ order, ended relationships and coordinates

    @Test
    void resultsAreOrderedByTheIdOfTheRelationship() {
        EntityId first = organization("First");
        EntityId second = organization("Second");
        EntityId third = organization("Third");
        RelationshipId byThird = owns(third, beta, 10);
        RelationshipId bySecond = owns(second, beta, 10);
        RelationshipId byFirst = owns(first, beta, 10);
        RelationshipId byAcme = owns(acme, beta, 10);

        List<Link> owners = reference.view(on(3, 1)).navigate(beta, "ownedBy");

        assertThat(relationships(owners)).containsExactly(byThird, bySecond, byFirst, byAcme);
        assertThat(entities(owners)).containsExactly(third, second, first, acme);
    }

    @Test
    void aRelationshipIsFoundOnlyWhileItExists() {
        relate("Owns", acme, beta, EffectivePeriod.between(on(2, 1), on(10, 1)), "share", 60);

        assertThat(reference.view(on(2, 1).minusNanos(1000)).navigate(beta, "ownedBy")).isEmpty();
        assertThat(reference.view(on(2, 1)).navigate(beta, "ownedBy")).hasSize(1);
        assertThat(reference.view(JUST_BEFORE_OCTOBER).navigate(beta, "ownedBy")).hasSize(1);
        assertThat(reference.view(on(10, 1)).navigate(beta, "ownedBy")).as("the end is excluded").isEmpty();
        assertThat(reference.view(on(2027, 1, 1)).navigate(beta, "ownedBy")).isEmpty();
    }

    @Test
    void aRelationshipThatWasEndedLaterIsStillFoundBeforeTheEnd() {
        RelationshipId owns = owns(acme, beta, 60);
        reference.recordedOn(on(5, 20));
        reference.commit(Transaction.by("tester").endDate(owns, on(6, 1)).build());

        assertThat(reference.view(on(5, 31)).navigate(beta, "ownedBy")).hasSize(1);
        assertThat(reference.view(on(6, 1)).navigate(beta, "ownedBy")).isEmpty();
        assertThat(reference.view(on(5, 31)).relationship(owns)).isPresent();
        assertThat(reference.view(on(6, 1)).relationship(owns)).isEmpty();
        assertThat(reference.view(on(6, 1)).relationshipTimeline(owns)).singleElement()
                .satisfies(version -> assertThat(version.period()).isEqualTo(EffectivePeriod.between(on(2, 1), on(6, 1))));
    }

    @Test
    void aViewKnownEarlierReadsTheRelationshipAsItWasThen() {
        RelationshipId owns = owns(acme, beta, 60);
        Basis recorded = reference.store.latestBasis();
        reference.recordedOn(on(5, 2));
        reference.replay(ReferenceStories.rd2ShareCorrection(owns));
        View before = reference.store.view(Coordinates.effectiveAt(on(3, 1)).knownAt(recorded));
        View now = reference.view(on(3, 1));
        View beforeItWasRecorded = reference.store.view(Coordinates.effectiveAt(on(3, 1)).knownAt(new Basis(2)));

        assertThat(share(before.navigate(beta, "ownedBy").getFirst().relationship())).isEqualByComparingTo("60");
        assertThat(share(now.navigate(beta, "ownedBy").getFirst().relationship())).isEqualByComparingTo("55");
        assertThat(share(before.relationship(owns).orElseThrow())).isEqualByComparingTo("60");
        assertThat(before.relationshipTimeline(owns)).singleElement()
                .satisfies(version -> assertThat(share(version)).isEqualByComparingTo("60"));
        assertThat(beforeItWasRecorded.navigate(beta, "ownedBy")).isEmpty();
        assertThat(beforeItWasRecorded.relationship(owns)).isEmpty();
        assertThat(beforeItWasRecorded.relationshipTimeline(owns)).isEmpty();
        assertThat(beforeItWasRecorded.navigateTimeline(beta, "ownedBy")).isEmpty();
    }

    @Test
    void theEntityAtTheOtherEndIsReadAtTheCoordinatesOfTheView() {
        owns(acme, beta, 60);
        reference.replay(ReferenceStories.rd7Rename(acme));

        Link in2026 = reference.view(on(12, 31)).navigate(beta, "ownedBy").getFirst();
        Link in2027 = reference.view(on(2027, 1, 1)).navigate(beta, "ownedBy").getFirst();

        assertThat(in2026.entity().values()).containsEntry("name", Value.of("ACME"));
        assertThat(in2027.entity().values()).containsEntry("name", Value.of("ACME Holdings"));
        assertThat(in2027.entity().period().from()).isEqualTo(on(2027, 1, 1));
        assertThat(in2026.relationship().values()).isEqualTo(in2027.relationship().values());
    }

    @Test
    void anEntityWithoutRelationshipsOrThatDoesNotExistHasNoLinks() {
        owns(acme, beta, 60);
        View view = reference.view(on(3, 1));

        assertThat(view.navigate(alice, "ownedBy")).isEmpty();
        assertThat(view.navigate(alice, "Owns")).isEmpty();
        assertThat(view.navigate(new EntityId("unknown"), "ownedBy")).isEmpty();
        assertThat(reference.view(on(2025, 1, 1)).navigate(beta, "ownedBy")).as("before Beta exists").isEmpty();
    }

    // ------------------------------------------------------------------ reading by ID and timelines

    @Test
    void aRelationshipIsReadByItsIdAndOnlyByAnIdOfARelationship() {
        RelationshipId owns = owns(acme, beta, 60);
        View view = reference.view(on(3, 1));

        RelationshipState state = view.relationship(owns).orElseThrow();

        assertThat(state.id()).isEqualTo(owns);
        assertThat(state.relationshipType()).isEqualTo("Owns");
        assertThat(state.source()).isEqualTo(acme);
        assertThat(state.target()).isEqualTo(beta);
        assertThat(state.period()).isEqualTo(from(2, 1));
        assertThat(state.recordedBasis()).isEqualTo(reference.store.latestBasis());
        assertThat(view.relationship(new RelationshipId("unknown"))).isEmpty();
        assertThat(view.relationship(new RelationshipId(acme.value())))
                .as("an entity's ID is not a relationship's").isEmpty();
        assertThat(view.entity(new EntityId(owns.value()))).as("a relationship's ID is not an entity's").isEmpty();
        assertThat(view.relationshipTimeline(new RelationshipId(acme.value()))).isEmpty();
        assertThat(view.relationshipTimeline(new RelationshipId("unknown"))).isEmpty();
    }

    @Test
    void theTimelineOfARelationshipListsEveryVersionInOrderWhateverTheViewsEffectiveTime() {
        RelationshipId owns = owns(acme, beta, 60);
        reference.commit(Transaction.by("tester").patch(owns, from(4, 1), values("share", 50)).build());

        for (Instant at : List.of(on(1, 1), on(3, 1), on(5, 1))) {
            List<RelationshipState> timeline = reference.view(at).relationshipTimeline(owns);

            assertThat(timeline).extracting(version -> version.period().from(), RelationshipViewsTest::share)
                    .containsExactly(tuple(on(2, 1), new BigDecimal("60")), tuple(on(4, 1), new BigDecimal("50")));
            assertThat(timeline.getFirst().period()).isEqualTo(EffectivePeriod.between(on(2, 1), on(4, 1)));
            assertThat(timeline.getLast().period()).isEqualTo(from(4, 1));
            assertThat(timeline.getFirst().recordedBasis()).isLessThan(timeline.getLast().recordedBasis());
        }
    }

    @Test
    void navigatingTheTimelineListsEveryVersionOfEveryRelationshipOfTheEntityByIdThenPeriod() {
        EntityId other = organization("Other");
        RelationshipId byAcme = owns(acme, beta, 60);
        RelationshipId byOther = relate("Owns", other, beta, EffectivePeriod.between(on(6, 1), on(9, 1)), "share", 10);
        reference.commit(Transaction.by("tester").patch(byAcme, from(4, 1), values("share", 50)).build());
        owns(beta, alice, 1);

        List<RelationshipState> early = reference.view(on(1, 1)).navigateTimeline(beta, "ownedBy");
        List<RelationshipState> late = reference.view(on(12, 1)).navigateTimeline(beta, "ownedBy");

        assertThat(early).isEqualTo(late);
        assertThat(early).extracting(RelationshipState::id, version -> version.period().from())
                .containsExactly(tuple(byAcme, on(2, 1)), tuple(byAcme, on(4, 1)), tuple(byOther, on(6, 1)));
        assertThat(reference.view(on(1, 1)).navigateTimeline(beta, "Owns")).as("only what Beta owns")
                .extracting(RelationshipState::target).containsExactly(alice);
        assertThat(reference.view(on(1, 1)).navigateTimeline(acme, "Owns")).extracting(RelationshipState::id)
                .containsExactly(byAcme, byAcme);
    }

    @Test
    void navigatingTheTimelineHonoursSymmetricTypesAndSubtypes() {
        EntityId employee = person("Employee");
        EntityId company = organization("Company");
        RelationshipId related = relate("RelatedParty", acme, beta, from(2, 1));
        RelationshipId contracts = relate("Contracts", employee, company, from(3, 1));

        assertThat(reference.view(on(1, 1)).navigateTimeline(beta, "RelatedParty")).extracting(RelationshipState::id)
                .containsExactly(related);
        assertThat(reference.view(on(1, 1)).navigateTimeline(company, "workers")).extracting(RelationshipState::id)
                .containsExactly(contracts);
    }

    // ------------------------------------------------------------------ values in the names and types of the schema

    @Test
    void valuesAreReportedUnderTheAttributeNamesOfTheViewsSchema() {
        EntityId analyst = person("Analyst");
        RelationshipId employs = relate("Employs", beta, analyst, from(3, 1), "title", "Analyst");
        SchemaDefinition renamed = Definitions.changeRelationshipType(model(), "Employs", type ->
                new RelationshipTypeDefinition(type.name(), type.source(), type.target(), type.targetsPerSource(),
                        type.sourcesPerTarget(), type.inverseName(), type.inverseAliases(), type.kind(),
                        type.symmetric(), type.supertype(), List.of(Definitions.renamed(type.attributes().getFirst(),
                                "position", "title")), type.aliases(), type.deprecated(), type.description(),
                        type.rules()));
        View old = reference.view(on(4, 1));
        reference.clock.advance(Duration.ofMinutes(1));
        assertThat(reference.store.schema().register(ReferenceModel.origin("1.1.0"), renamed))
                .isInstanceOf(RegistrationResult.Applied.class);

        View renamedView = reference.view(on(4, 1));

        assertThat(old.relationship(employs).orElseThrow().values()).containsOnlyKeys("title");
        assertThat(renamedView.relationship(employs).orElseThrow().values())
                .containsEntry("position", Value.of("Analyst")).doesNotContainKey("title");
        assertThat(renamedView.relationshipTimeline(employs)).singleElement()
                .satisfies(version -> assertThat(version.values()).containsOnlyKeys("position"));
        assertThat(renamedView.navigate(analyst, "employedBy").getFirst().relationship().values())
                .containsOnlyKeys("position");
        assertThat(renamedView.navigateTimeline(beta, "Employs").getFirst().values()).containsOnlyKeys("position");
    }

    @Test
    void valuesAreReportedInTheValueTypeOfTheViewsSchema() {
        EntityId other = organization("Other");
        RelationshipId rates = relate("Rates", acme, other, from(2, 1), "level", 3);
        View narrow = reference.view(on(3, 1));
        SchemaDefinition widened = Definitions.changeRelationshipType(model(), "Rates", type ->
                new RelationshipTypeDefinition(type.name(), type.source(), type.target(), type.targetsPerSource(),
                        type.sourcesPerTarget(), type.inverseName(), type.inverseAliases(), type.kind(),
                        type.symmetric(), type.supertype(), List.of(AttributeDefinition.builder("level",
                                ValueType.LONG).build()), type.aliases(), type.deprecated(), type.description(),
                        type.rules()));
        reference.clock.advance(Duration.ofMinutes(1));
        assertThat(reference.store.schema().register(ReferenceModel.origin("1.1.0"), widened))
                .isInstanceOf(RegistrationResult.Applied.class);

        View wide = reference.view(on(3, 1));

        assertThat(narrow.relationship(rates).orElseThrow().values()).containsEntry("level", Value.of(3));
        assertThat(wide.relationship(rates).orElseThrow().values()).containsEntry("level", Value.of(3L));
        assertThat(wide.relationshipTimeline(rates).getFirst().values()).containsEntry("level", Value.of(3L));
        assertThat(wide.navigate(acme, "Rates").getFirst().relationship().values()).containsEntry("level", Value.of(3L));
        assertThat(wide.navigateTimeline(other, "ratedBy").getFirst().values()).containsEntry("level", Value.of(3L));
    }

    // ------------------------------------------------------------------ traversal

    /**
     * Owners and the owned, with A as ACME and B as Beta: A owns B and C, B and C own D, and D owns E. The relationships
     * are created in that order, so their IDs are in that order too.
     */
    private final class Pyramid {
        final EntityId a = acme;
        final EntityId b = beta;
        final EntityId c = organization("C");
        final EntityId d = organization("D");
        final EntityId e = organization("E");
        final RelationshipId ab = owns(a, b, 60);
        final RelationshipId ac = owns(a, c, 60);
        final RelationshipId bd = owns(b, d, 40);
        final RelationshipId cd = owns(c, d, 40);
        final RelationshipId de = owns(d, e, 70);
    }

    @Test
    void aTraversalVisitsEntitiesBreadthFirstEachOnceWithoutTheStart() {
        Pyramid p = new Pyramid();

        List<Step> up = reference.view(on(3, 1)).traverse(p.e, "ownedBy", any(), 10);

        assertThat(up).extracting(step -> step.entity().id(), Step::depth, step -> step.via().id())
                .containsExactly(tuple(p.d, 1, p.de), tuple(p.b, 2, p.bd), tuple(p.c, 2, p.cd), tuple(p.a, 3, p.ab));
        assertThat(up).extracting(step -> step.entity().id()).doesNotContain(p.e);
    }

    @Test
    void aTraversalFollowsTheInverseNameAndTheNameAlike() {
        Pyramid p = new Pyramid();

        List<Step> down = reference.view(on(3, 1)).traverse(p.a, "Owns", any(), 10);

        assertThat(down).extracting(step -> step.entity().id(), Step::depth, step -> step.via().id())
                .containsExactly(tuple(p.b, 1, p.ab), tuple(p.c, 1, p.ac), tuple(p.d, 2, p.bd), tuple(p.e, 3, p.de));
    }

    @Test
    void aTraversalReportsEntitiesAndRelationshipsAsTheViewReadsThem() {
        Pyramid p = new Pyramid();
        View view = reference.view(on(3, 1));

        Step step = view.traverse(p.e, "ownedBy", any(), 1).getFirst();

        assertThat(step.entity()).isEqualTo(view.entity(p.d).orElseThrow());
        assertThat(step.via()).isEqualTo(view.relationship(p.de).orElseThrow());
        assertThat(share(step.via())).isEqualByComparingTo("70");
    }

    @Test
    void onlyRelationshipsThatSatisfyTheConditionAreFollowed() {
        Pyramid p = new Pyramid();
        reference.commit(Transaction.by("tester").patch(p.bd, from(2, 1), values("share", 45)).build());
        Predicate<RelationshipState> above40 = relationship -> share(relationship).compareTo(new BigDecimal("40")) > 0;

        List<Step> steps = reference.view(on(3, 1)).traverse(p.e, "ownedBy", above40, 10);

        assertThat(steps).as("C owns exactly 40% of D, which is not above 40")
                .extracting(step -> step.entity().id(), Step::depth)
                .containsExactly(tuple(p.d, 1), tuple(p.b, 2), tuple(p.a, 3));
    }

    @Test
    void anEntityThatOneRelationshipDoesNotLeadToCanBeReachedByAnother() {
        Pyramid p = new Pyramid();
        Predicate<RelationshipState> notFromB = relationship -> !relationship.source().equals(p.b);

        List<Step> steps = reference.view(on(3, 1)).traverse(p.e, "ownedBy", notFromB, 10);

        assertThat(steps).extracting(step -> step.entity().id(), Step::depth, step -> step.via().id())
                .containsExactly(tuple(p.d, 1, p.de), tuple(p.c, 2, p.cd), tuple(p.a, 3, p.ac));
    }

    @Test
    void theDepthLimitsHowFarATraversalGoes() {
        Pyramid p = new Pyramid();
        View view = reference.view(on(3, 1));

        assertThat(view.traverse(p.e, "ownedBy", any(), 0)).isEmpty();
        assertThat(view.traverse(p.e, "ownedBy", any(), 1)).extracting(step -> step.entity().id())
                .containsExactly(p.d);
        assertThat(view.traverse(p.e, "ownedBy", any(), 2)).extracting(step -> step.entity().id())
                .containsExactly(p.d, p.b, p.c);
        assertThat(view.traverse(p.e, "ownedBy", any(), Integer.MAX_VALUE)).hasSize(4);
        assertThatThrownBy(() -> view.traverse(p.e, "ownedBy", any(), -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("-1");
    }

    @Test
    void aTraversalWithoutAConditionOrADepthFollowsEveryRelationshipToTheEnd() {
        Pyramid p = new Pyramid();
        View view = reference.view(on(3, 1));

        List<Step> toTheEnd = view.traverse(p.e, "ownedBy");

        assertThat(toTheEnd).isEqualTo(view.traverse(p.e, "ownedBy", any(), Integer.MAX_VALUE));
        assertThat(toTheEnd).extracting(step -> step.entity().id(), Step::depth, step -> step.via().id())
                .containsExactly(tuple(p.d, 1, p.de), tuple(p.b, 2, p.bd), tuple(p.c, 2, p.cd), tuple(p.a, 3, p.ab));
        assertThat(view.traverse(p.a, "Owns")).as("by the name, from the other end").extracting(
                step -> step.entity().id(), Step::depth).containsExactly(tuple(p.b, 1), tuple(p.c, 1), tuple(p.d, 2),
                tuple(p.e, 3));
        assertThat(view.traverse(p.a, "owns")).as("a name is compared without regard to case").hasSize(4);
        assertThat(view.traverse(p.e, "Owns")).as("nothing leads away from the last of the chain").isEmpty();
    }

    @Test
    void aTraversalWithoutAConditionFollowsEveryRelationshipUpToTheDepth() {
        Pyramid p = new Pyramid();
        View view = reference.view(on(3, 1));

        assertThat(view.traverse(p.e, "ownedBy", 0)).isEmpty();
        assertThat(view.traverse(p.e, "ownedBy", 1)).isEqualTo(view.traverse(p.e, "ownedBy", any(), 1))
                .extracting(step -> step.entity().id()).containsExactly(p.d);
        assertThat(view.traverse(p.e, "ownedBy", 2)).isEqualTo(view.traverse(p.e, "ownedBy", any(), 2))
                .extracting(step -> step.entity().id()).containsExactly(p.d, p.b, p.c);
        assertThat(view.traverse(p.e, "ownedBy", Integer.MAX_VALUE)).isEqualTo(view.traverse(p.e, "ownedBy"))
                .hasSize(4);
        assertThatThrownBy(() -> view.traverse(p.e, "ownedBy", -1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
    }

    @Test
    void aTraversalWithoutAConditionFailsForANameThatIsNoRelationshipTypeOrInverseName() {
        View view = reference.view(on(3, 1));

        assertThatThrownBy(() -> view.traverse(acme, "Organization")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Organization");
        assertThatThrownBy(() -> view.traverse(acme, "Organization", 3)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Organization");
    }

    @Test
    void cyclesEndATraversalToTheEndToo() {
        EntityId c = organization("C");
        owns(acme, beta, 60);
        owns(beta, c, 60);
        owns(c, acme, 60);
        owns(c, c, 10);

        List<Step> steps = reference.view(on(3, 1)).traverse(acme, "Owns");

        assertThat(steps).extracting(step -> step.entity().id(), Step::depth).containsExactly(tuple(beta, 1),
                tuple(c, 2));
    }

    @Test
    void cyclesEndTheTraversalAndTheStartIsNeverReported() {
        EntityId c = organization("C");
        owns(acme, beta, 60);
        owns(beta, c, 60);
        owns(c, acme, 60);
        owns(c, c, 10);

        List<Step> steps = reference.view(on(3, 1)).traverse(acme, "Owns", any(), 100);

        assertThat(steps).extracting(step -> step.entity().id(), Step::depth).containsExactly(tuple(beta, 1),
                tuple(c, 2));
    }

    @Test
    void aTraversalIsBoundToTheCoordinatesOfTheView() {
        Pyramid p = new Pyramid();
        reference.recordedOn(on(5, 20));
        reference.commit(Transaction.by("tester").endDate(p.bd, on(6, 1)).endDate(p.ab, on(6, 1)).build());

        assertThat(reference.view(on(5, 31)).traverse(p.e, "ownedBy", any(), 10)).extracting(
                step -> step.entity().id()).containsExactly(p.d, p.b, p.c, p.a);
        assertThat(reference.view(on(6, 1)).traverse(p.e, "ownedBy", any(), 10)).extracting(
                step -> step.entity().id()).containsExactly(p.d, p.c, p.a);
        assertThat(reference.store.view(Coordinates.effectiveAt(on(6, 1)).knownAt(new Basis(2)))
                .traverse(p.e, "ownedBy", any(), 10)).isEmpty();
    }

    @Test
    void aTraversalOverASymmetricTypeReachesEveryRelatedEntity() {
        EntityId c = organization("C");
        relate("RelatedParty", acme, beta, from(2, 1));
        relate("RelatedParty", c, beta, from(2, 1));

        List<Step> steps = reference.view(on(3, 1)).traverse(acme, "RelatedParty", any(), 5);

        assertThat(steps).extracting(step -> step.entity().id(), Step::depth).containsExactly(tuple(beta, 1),
                tuple(c, 2));
    }
}
