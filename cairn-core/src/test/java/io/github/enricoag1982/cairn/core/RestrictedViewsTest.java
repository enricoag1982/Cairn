package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.QueryRows.rows;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A2;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.query.Cell;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Views restricted to a clearance, beyond the scenarios of the {@code attribute-access} spec (ADR-0036, design D2 of
 * {@code add-attribute-access-control}): every read that returns a state withholds, in the order of the schema and also
 * where a version has no value, restricting twice intersects, and a clearance that covers everything changes nothing. The
 * world is the reference domain with RD-1, RD-2, RD-5 and the LEI of RD-9, with the classifications of
 * {@link ClassifiedModel}. The tests run against RocksDB stores too (see {@link TestBackend}).
 */
class RestrictedViewsTest {

    private final ReferenceStore reference = ReferenceStore.with(ClassifiedModel.definition());
    private EntityId acme;
    private EntityId beta;
    private EntityId alice;
    private EntityId a1;
    private EntityId a2;
    private RelationshipId owns;

    @BeforeEach
    void theWorldOfRd1Rd2Rd5AndTheLei() {
        Receipt accounts = reference.createAccounts();
        acme = entity(reference.parties, ACME);
        beta = entity(reference.parties, BETA);
        alice = entity(reference.parties, ALICE);
        a1 = entity(accounts, A1);
        a2 = entity(accounts, A2);
        owns = relationship(reference.replay(ReferenceStories.rd2Ownership(acme, beta)), ACME_OWNS_BETA);
        reference.replay(ReferenceStories.rd5BalancePatch(a1));
        reference.replay(ReferenceStories.rd5BalanceCorrection(a1));
        reference.replay(ReferenceStories.rd2ShareCorrection(owns));
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
    }

    private View view() {
        return reference.view(on(4, 1));
    }

    private View none() {
        return view().restrictedTo(Clearance.none());
    }

    // ------------------------------------------------------------------ entities

    @Test
    void anEntityWithholdsTheAttributesInTheOrderOfTheSchemaAndNothingElse() {
        EntityState state = none().entity(a1).orElseThrow();

        assertThat(state.values()).containsOnlyKeys("number");
        assertThat(state.withheld()).as("the schema lists currency before balance").containsExactly("currency", "balance");
        assertThat(state.id()).isEqualTo(a1);
        assertThat(state.entityType()).isEqualTo("Account");
        assertThat(state.period()).isEqualTo(view().entity(a1).orElseThrow().period());
        assertThat(state.recordedAt()).isEqualTo(view().entity(a1).orElseThrow().recordedAt());
        assertThat(state.recordedBasis()).isEqualTo(view().entity(a1).orElseThrow().recordedBasis());
    }

    @Test
    void anEntityWithholdsWhatItsVersionHasNoValueFor() {
        EntityState beta = none().entity(this.beta).orElseThrow();
        EntityState acme = none().entity(this.acme).orElseThrow();

        assertThat(beta.values()).as("Beta has no LEI").doesNotContainKey("lei");
        assertThat(beta.withheld()).as("a missing value is withheld as well").containsExactly("lei");
        assertThat(acme.values()).doesNotContainKey("lei");
        assertThat(acme.withheld()).containsExactly("lei");
        assertThat(acme.values()).containsEntry("name", Value.of("ACME"));
    }

    @Test
    void anEntityWhoseTypeHasNothingToWithholdIsReturnedAsItIs() {
        EntityState alice = none().entity(this.alice).orElseThrow();

        assertThat(alice).isEqualTo(view().entity(this.alice).orElseThrow());
        assertThat(alice.withheld()).isEmpty();
    }

    @Test
    void anEntityThatDoesNotExistIsStillNotFound() {
        assertThat(none().entity(new EntityId("nobody"))).isEmpty();
        assertThat(none().timeline(new EntityId("nobody"))).isEmpty();
    }

    @Test
    void anEntityFoundByAKeyOfAnAttributeThatIsNotWithheldWithholdsTheRest() {
        EntityState state = none().entity(new UniqueKey("Account", "number", Value.of("A-1"))).orElseThrow();

        assertThat(state.id()).isEqualTo(a1);
        assertThat(state.values()).containsOnlyKeys("number");
        assertThat(state.withheld()).containsExactly("currency", "balance");
    }

    @Test
    void anEntityIsNotFoundByAKeyOfAWithheldAttributeEvenWhenNoEntityHasTheValue() {
        UniqueKey absent = new UniqueKey("Organization", "lei", Value.of("5493001KJTIIGC8Y1R13"));
        UniqueKey present = new UniqueKey("Organization", "lei", Value.of(LEI));

        assertThatThrownBy(() -> none().entity(absent)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lei").hasMessageContaining("identity");
        assertThatThrownBy(() -> none().entity(present)).isInstanceOf(IllegalArgumentException.class);
        assertThat(view().restrictedTo(Clearance.of("identity")).entity(absent))
                .as("a clearance that covers the LEI finds nothing for a value that nobody has").isEmpty();
        assertThatThrownBy(() -> view().restrictedTo(Clearance.of("finance")).entity(present))
                .as("a clearance of another classification").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKeyThatTheSchemaDoesNotAllowFailsAsItDoesForAViewThatIsNotRestricted() {
        UniqueKey unknown = new UniqueKey("Account", "nothing", Value.of("x"));

        assertThatThrownBy(() -> view().entity(unknown)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> none().entity(unknown)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing");
    }

    @Test
    void listingEntitiesWithholdsFromEveryStateAndKeepsTheOrder() {
        List<EntityState> accounts = none().entities("Account");
        List<EntityState> parties = none().entities("Party");

        assertThat(accounts).extracting(EntityState::id).containsExactlyElementsOf(
                view().entities("Account").stream().map(EntityState::id).toList());
        assertThat(accounts).allSatisfy(state -> {
            assertThat(state.values()).containsOnlyKeys("number");
            assertThat(state.withheld()).containsExactly("currency", "balance");
        });
        assertThat(parties).extracting(EntityState::entityType).containsExactlyInAnyOrder("Organization", "Organization",
                "Person");
        assertThat(parties).filteredOn(state -> state.entityType().equals("Organization"))
                .allSatisfy(state -> assertThat(state.withheld()).containsExactly("lei"));
        assertThat(parties).filteredOn(state -> state.entityType().equals("Person"))
                .allSatisfy(state -> assertThat(state.withheld()).isEmpty());
    }

    @Test
    void aTimelineWithholdsFromEveryVersion() {
        List<EntityState> versions = none().timeline(a1);

        assertThat(versions).hasSizeGreaterThan(1).extracting(EntityState::period)
                .containsExactlyElementsOf(view().timeline(a1).stream().map(EntityState::period).toList());
        assertThat(versions).allSatisfy(version -> {
            assertThat(version.values()).containsOnlyKeys("number");
            assertThat(version.withheld()).containsExactly("currency", "balance");
        });
        assertThat(view().timeline(a1)).as("the view that was restricted").allSatisfy(version ->
                assertThat(version.values()).containsKeys("balance", "currency"));
    }

    @Test
    void aViewThatIsRestrictedToWhatItReadsWithholdsNothing() {
        assertThat(view().restrictedTo(ClassifiedModel.ALL).entity(a1)).isEqualTo(view().entity(a1));
        assertThat(view().restrictedTo(ClassifiedModel.ALL).entities("Party")).isEqualTo(view().entities("Party"));
        assertThat(view().restrictedTo(ClassifiedModel.ALL).timeline(a1)).isEqualTo(view().timeline(a1));
        assertThat(view().restrictedTo(ClassifiedModel.ALL).relationship(owns)).isEqualTo(view().relationship(owns));
        assertThat(view().restrictedTo(ClassifiedModel.ALL).navigate(acme, "Owns")).isEqualTo(view().navigate(acme, "Owns"));
    }

    // ------------------------------------------------------------------ relationships and navigation

    @Test
    void aRelationshipWithholdsItsAttributes() {
        RelationshipState state = none().relationship(owns).orElseThrow();

        assertThat(state.values()).isEmpty();
        assertThat(state.withheld()).containsExactly("share");
        assertThat(state.source()).isEqualTo(acme);
        assertThat(state.target()).isEqualTo(beta);
        assertThat(view().relationship(owns).orElseThrow().values()).containsEntry("share", Value.of(money("55")));
    }

    @Test
    void aRelationshipTimelineWithholdsFromEveryVersion() {
        List<RelationshipState> versions = none().relationshipTimeline(owns);

        assertThat(versions).isNotEmpty().allSatisfy(version -> {
            assertThat(version.values()).isEmpty();
            assertThat(version.withheld()).containsExactly("share");
        });
        assertThat(versions).extracting(RelationshipState::period).containsExactlyElementsOf(
                view().relationshipTimeline(owns).stream().map(RelationshipState::period).toList());
    }

    @Test
    void navigationWithholdsFromTheRelationshipAndFromTheEntityAtTheOtherEnd() {
        List<Link> links = none().navigate(acme, "Owns");

        assertThat(links).singleElement().satisfies(link -> {
            assertThat(link.relationship().withheld()).containsExactly("share");
            assertThat(link.relationship().values()).isEmpty();
            assertThat(link.entity().id()).isEqualTo(beta);
            assertThat(link.entity().withheld()).containsExactly("lei");
        });
        assertThat(none().navigate(beta, "ownedBy")).singleElement().satisfies(link -> {
            assertThat(link.entity().id()).isEqualTo(acme);
            assertThat(link.entity().withheld()).containsExactly("lei");
        });
    }

    @Test
    void navigationAcrossTimeWithholdsFromEveryVersion() {
        List<RelationshipState> versions = none().navigateTimeline(acme, "Owns");

        assertThat(versions).isNotEmpty().allSatisfy(version -> {
            assertThat(version.withheld()).containsExactly("share");
            assertThat(version.values()).isEmpty();
        });
        assertThat(versions).extracting(RelationshipState::id).containsOnly(owns);
    }

    @Test
    void aTraversalWithholdsFromEveryStepAndItsConditionSeesTheRestrictedStates() {
        List<RelationshipState> seen = new ArrayList<>();

        List<Step> steps = none().traverse(acme, "Owns", relationship -> {
            seen.add(relationship);
            return true;
        }, 3);
        List<Step> followedByShare = none().traverse(acme, "Owns",
                relationship -> relationship.values().containsKey("share"), 3);
        List<Step> unrestricted = view().traverse(acme, "Owns", relationship -> relationship.values().containsKey("share"),
                3);

        assertThat(steps).singleElement().satisfies(step -> {
            assertThat(step.entity().id()).isEqualTo(beta);
            assertThat(step.entity().withheld()).containsExactly("lei");
            assertThat(step.via().withheld()).containsExactly("share");
            assertThat(step.via().values()).isEmpty();
        });
        assertThat(seen).as("the condition was asked about the relationship").isNotEmpty().allSatisfy(relationship -> {
            assertThat(relationship.withheld()).containsExactly("share");
            assertThat(relationship.values()).doesNotContainKey("share");
        });
        assertThat(followedByShare).as("a condition on the share cannot hold when the share is withheld").isEmpty();
        assertThat(unrestricted).as("it holds for a view that is not restricted").hasSize(1);
    }

    @Test
    void theTraversalsWithoutAConditionWithholdToo() {
        assertThat(none().traverse(acme, "Owns")).singleElement()
                .satisfies(step -> assertThat(step.via().withheld()).containsExactly("share"));
        assertThat(none().traverse(acme, "Owns", 1)).singleElement()
                .satisfies(step -> assertThat(step.entity().withheld()).containsExactly("lei"));
    }

    // ------------------------------------------------------------------ queries

    @Test
    void theRowsOfAQueryHoldRestrictedStates() {
        Query query = Query.from("Organization", "o").follow("o", "Owns", "p", "owns").build();

        QueryResult.Rows result = rows(none().query(query));

        assertThat(result.rows()).singleElement().satisfies(row -> {
            EntityState organization = row.entity("o").orElseThrow();
            EntityState owned = row.entity("p").orElseThrow();
            RelationshipState relationship = row.relationship("owns").orElseThrow();
            assertThat(organization.id()).isEqualTo(acme);
            assertThat(organization.withheld()).containsExactly("lei");
            assertThat(owned.withheld()).containsExactly("lei");
            assertThat(relationship.withheld()).containsExactly("share");
            assertThat(row.cell("o")).isInstanceOf(Cell.EntityCell.class);
        });
    }

    @Test
    void aQueryThatNamesNothingWithheldRunsAndCountsTheRowsOfAViewThatIsNotRestricted() {
        Query query = Query.from("Account", "a").build();

        QueryResult.Rows restricted = rows(none().query(query));
        QueryResult.Rows open = rows(view().query(query));

        assertThat(restricted.rows()).hasSameSizeAs(open.rows());
        assertThat(restricted.effectiveAt()).isEqualTo(open.effectiveAt());
        assertThat(restricted.basis()).isEqualTo(open.basis());
        assertThat(restricted.rows()).extracting(row -> row.entity("a").orElseThrow().id())
                .containsExactly(a1, a2);
    }

    // ------------------------------------------------------------------ clearances

    @Test
    void restrictingARestrictedViewRestrictsToTheIntersection() {
        View first = view().restrictedTo(Clearance.of("finance", "identity"));

        View second = first.restrictedTo(Clearance.of("identity", "ownership"));

        assertThat(first.clearance()).hasValue(Clearance.of("finance", "identity"));
        assertThat(second.clearance()).hasValue(Clearance.of("identity"));
        assertThat(second.entity(a1).orElseThrow().withheld()).containsExactly("currency", "balance");
        assertThat(second.entity(acme).orElseThrow().withheld()).as("identity is covered by both").isEmpty();
        assertThat(second.relationship(owns).orElseThrow().withheld()).as("ownership by only one").containsExactly("share");
        assertThat(first.entity(a1).orElseThrow().withheld()).as("the view that was restricted again is not changed")
                .containsExactly("currency");
    }

    @Test
    void restrictingToAnotherClearanceNeverGivesMoreThanTheViewHad() {
        View narrow = view().restrictedTo(Clearance.of("identity"));

        View wider = narrow.restrictedTo(ClassifiedModel.ALL);

        assertThat(wider.clearance()).hasValue(Clearance.of("identity"));
        assertThat(wider.entity(a1).orElseThrow().values()).containsOnlyKeys("number");
    }

    @Test
    void aViewThatIsNotRestrictedHasNoClearanceAndKeepsItsCoordinates() {
        View open = view();
        View restricted = open.restrictedTo(Clearance.of("finance"));

        assertThat(open.clearance()).isEmpty();
        assertThat(restricted.clearance()).hasValue(Clearance.of("finance"));
        assertThat(restricted.effectiveAt()).isEqualTo(open.effectiveAt());
        assertThat(restricted.basis()).isEqualTo(open.basis());
        assertThat(restricted.schema()).isSameAs(open.schema());
        assertThat(restricted.schema().entityType("Account").orElseThrow().attribute("balance").orElseThrow()
                .classification()).hasValue("finance");
        assertThatThrownBy(() -> open.restrictedTo(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> restricted.restrictedTo(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aViewKnownAtAnEarlierBasisWithholdsWhatWasKnownThen() {
        View earlier = reference.store.view(Coordinates.effectiveAt(on(3, 31)).knownAt(on(4, 5)))
                .restrictedTo(Clearance.of("operations"));

        EntityState state = earlier.entity(a1).orElseThrow();

        assertThat(state.values()).containsEntry("currency", Value.of("EUR")).doesNotContainKey("balance");
        assertThat(state.withheld()).containsExactly("balance");
        assertThat(earlier.basis()).isLessThan(view().basis());
    }

    @Test
    void theRestrictedViewReadsThroughWhateverViewItRestricts() {
        CountingView counting = new CountingView(view());

        View restricted = counting.restrictedTo(Clearance.none());
        restricted.entity(a1);
        restricted.entities("Account");

        assertThat(restricted).isInstanceOf(CountingView.class);
        assertThat(((CountingView) restricted).reads()).isEqualTo(2);
        assertThat(restricted.clearance()).hasValue(Clearance.none());
    }
}
