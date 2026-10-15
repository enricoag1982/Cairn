package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.assertProblem;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.EmployerHoldsAccountRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Design D1, D2 and D3 of {@code change-composition-cascades}: a composition cascade also ends the other relationships
 * of the targets it ends, at either end, and what it leaves alone. The scenarios of the {@code relationships} spec are
 * in {@link RelationshipsScenariosTest}; these are the edge cases.
 *
 * <p>The world is the reference domain with some types it does not have. Two regions have sites, which are the targets
 * of compositions: North has the sites Depot and Dock, South has Annex and Yard. Depot has a room, which is the target
 * of a composition too, and relationships of other types at both of its ends: Alice inspects it, it has Annex and Yard
 * and Dock as neighbours, in a symmetric type, and it is tagged. Alice cleans the room. Everything exists from 1 January
 * and goes on, so ending North from 1 June cascades.
 */
class CompositionCascadesTest {

    private static final TemporaryId NORTH = new TemporaryId("north");
    private static final TemporaryId SOUTH = new TemporaryId("south");
    private static final TemporaryId DEPOT = new TemporaryId("depot");
    private static final TemporaryId DOCK = new TemporaryId("dock");
    private static final TemporaryId ANNEX = new TemporaryId("annex");
    private static final TemporaryId YARD = new TemporaryId("yard");
    private static final TemporaryId ROOM = new TemporaryId("room");
    private static final TemporaryId TAG = new TemporaryId("tag");
    private static final TemporaryId OPENS_A1 = new TemporaryId("opensA1");

    /** The reference domain plus the regions, sites, rooms and tags, and the relationship types between them. */
    private static SchemaDefinition model() {
        return model(Multiplicity.ZERO_OR_MORE);
    }

    /** The same, in which each tag has the given number of sites that it is tagged on. */
    private static SchemaDefinition model(Multiplicity sitesPerTag) {
        return Definitions.plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .entityType("Region", e -> e.attribute("name", ValueType.STRING))
                .entityType("Site", e -> e.attribute("name", ValueType.STRING))
                .entityType("Room", e -> e.attribute("name", ValueType.STRING))
                .entityType("Tag", e -> e.attribute("name", ValueType.STRING))
                .relationshipType("HasSite", r -> r.from("Region").to("Site").composition()
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.EXACTLY_ONE)
                        .inverse("region"))
                .relationshipType("HasRoom", r -> r.from("Site").to("Room").composition()
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.EXACTLY_ONE)
                        .inverse("site"))
                .relationshipType("Inspects", r -> r.from("Person").to("Site")
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("inspectedBy"))
                .relationshipType("Cleans", r -> r.from("Person").to("Room")
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("cleanedBy"))
                .relationshipType("Tagged", r -> r.from("Site").to("Tag")
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(sitesPerTag)
                        .inverse("tags"))
                .relationshipType("Neighbours", r -> r.from("Site").to("Site").symmetric()
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("neighbouringSites"))
                .build());
    }

    private static Transaction.Builder transaction() {
        return Transaction.by("importer");
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    /** The world described above, with the IDs of what it created. */
    private static final class World {
        final ReferenceStore store = ReferenceStore.with(model());
        final Receipt parties = store.createParties();
        final EntityId alice = entity(parties, ALICE);
        final Receipt estate = store.commit(transaction()
                .create(NORTH, "Region", from(1, 1), values("name", "North"))
                .create(SOUTH, "Region", from(1, 1), values("name", "South"))
                .create(DEPOT, "Site", from(1, 1), values("name", "Depot"))
                .create(DOCK, "Site", from(1, 1), values("name", "Dock"))
                .create(ANNEX, "Site", from(1, 1), values("name", "Annex"))
                .create(YARD, "Site", from(1, 1), values("name", "Yard"))
                .create(ROOM, "Room", from(1, 1), values("name", "Room 1"))
                .create(TAG, "Tag", from(1, 1), values("name", "cold"))
                .relate(new TemporaryId("hasDepot"), "HasSite", NORTH, DEPOT, from(1, 1), values())
                .relate(new TemporaryId("hasDock"), "HasSite", NORTH, DOCK, from(1, 1), values())
                .relate(new TemporaryId("hasAnnex"), "HasSite", SOUTH, ANNEX, from(1, 1), values())
                .relate(new TemporaryId("hasYard"), "HasSite", SOUTH, YARD, from(1, 1), values())
                .relate(new TemporaryId("hasRoom"), "HasRoom", DEPOT, ROOM, from(1, 1), values())
                .relate(new TemporaryId("inspects"), "Inspects", alice, DEPOT, from(1, 1), values())
                .relate(new TemporaryId("withAnnex"), "Neighbours", ANNEX, DEPOT, from(1, 1), values())
                .relate(new TemporaryId("withYard"), "Neighbours", DEPOT, YARD, from(1, 1), values())
                .relate(new TemporaryId("withDock"), "Neighbours", DEPOT, DOCK, from(1, 1), values())
                .relate(new TemporaryId("cleans"), "Cleans", alice, ROOM, from(1, 1), values())
                .relate(new TemporaryId("tagged"), "Tagged", DEPOT, TAG, from(1, 1), values())
                .build());
        final EntityId north = entity(estate, NORTH);

        EntityId id(TemporaryId name) {
            return entity(estate, name);
        }

        RelationshipId rel(String name) {
            return relationship(estate, new TemporaryId(name));
        }

        boolean existsOn(RelationshipId relationship, int month, int day) {
            return store.view(on(month, day)).relationship(relationship).isPresent();
        }
    }

    // ------------------------------------------------------------------ what a cascade ends, and in which order

    @Test
    void theReceiptListsTheOtherRelationshipsOfEachTargetAfterItsOwnCascadeInTheOrderOfTheirIds() {
        World world = new World();

        Receipt receipt = world.store.commit(transaction().endDate(world.north, on(6, 1)).build());

        assertThat(receipt.ended()).containsExactly(
                world.rel("hasDepot"), world.id(DEPOT),
                world.rel("hasRoom"), world.id(ROOM),
                world.rel("cleans"),
                world.rel("inspects"), world.rel("withAnnex"), world.rel("withYard"),
                world.rel("withDock"), world.rel("tagged"),
                world.rel("hasDock"), world.id(DOCK));
        assertThat(receipt.created()).isEmpty();
    }

    @Test
    void aTargetEndsItsIncomingAndItsOutgoingRelationshipsAndTheEntitiesAtTheirOtherEndsStay() {
        World world = new World();
        RelationshipId inspects = world.rel("inspects");
        RelationshipId tagged = world.rel("tagged");

        world.store.commit(transaction().endDate(world.north, on(6, 1)).build());

        assertThat(world.existsOn(inspects, 5, 31)).isTrue();
        assertThat(world.existsOn(inspects, 6, 1)).as("incoming: Alice inspects Depot").isFalse();
        assertThat(world.existsOn(tagged, 5, 31)).isTrue();
        assertThat(world.existsOn(tagged, 6, 1)).as("outgoing: Depot is tagged").isFalse();
        assertThat(world.store.view(on(6, 1)).entity(world.alice)).isPresent();
        assertThat(world.store.view(on(6, 1)).entity(world.id(TAG))).isPresent();
        assertThat(world.store.view(on(6, 1)).entity(world.id(DEPOT))).as("the target ended").isEmpty();
    }

    @Test
    void aTargetOfTheCascadeEndsTheRelationshipsOfItsOwnTargetsToo() {
        World world = new World();
        RelationshipId cleans = world.rel("cleans");

        world.store.commit(transaction().endDate(world.north, on(6, 1)).build());

        assertThat(world.store.view(on(6, 1)).entity(world.id(ROOM))).as("the target of its target").isEmpty();
        assertThat(world.existsOn(cleans, 5, 31)).isTrue();
        assertThat(world.existsOn(cleans, 6, 1)).as("a relationship of the inner target").isFalse();
    }

    @Test
    void aSymmetricRelationshipEndsWhicheverEndTheTargetIs() {
        World world = new World();
        RelationshipId withAnnex = world.rel("withAnnex");
        RelationshipId withYard = world.rel("withYard");
        RelationshipId withDock = world.rel("withDock");

        world.store.commit(transaction().endDate(world.north, on(6, 1)).build());

        assertThat(world.store.view(on(5, 31)).navigate(world.id(ANNEX), "Neighbours")).as("the target end")
                .hasSize(1);
        assertThat(world.store.view(on(6, 1)).navigate(world.id(ANNEX), "Neighbours")).isEmpty();
        assertThat(world.store.view(on(5, 31)).navigate(world.id(YARD), "Neighbours")).as("the source end")
                .hasSize(1);
        assertThat(world.store.view(on(6, 1)).navigate(world.id(YARD), "Neighbours")).isEmpty();
        assertThat(world.existsOn(withAnnex, 6, 1)).isFalse();
        assertThat(world.existsOn(withYard, 6, 1)).isFalse();
        assertThat(world.existsOn(withDock, 6, 1)).as("between two targets of the cascade").isFalse();
        assertThat(world.store.view(on(6, 1)).entity(world.id(ANNEX))).as("the other end stays").isPresent();
    }

    @Test
    void aRelationshipBetweenTwoTargetsOfTheCascadeIsListedOnceWhereItIsFirstReached() {
        World world = new World();

        Receipt receipt = world.store.commit(transaction().endDate(world.north, on(6, 1)).build());

        assertThat(receipt.ended()).containsOnlyOnce(world.rel("withDock"));
        assertThat(receipt.ended().indexOf(world.rel("withDock")))
                .isLessThan(receipt.ended().indexOf(world.id(DOCK)));
    }

    @Test
    void aRelationshipThatTheTransactionCreatesEndsToo() {
        World world = new World();
        TemporaryId cool = new TemporaryId("cool");

        Receipt receipt = world.store.commit(transaction()
                .create(cool, "Tag", from(1, 1), values("name", "cool"))
                .relate(new TemporaryId("alsoTagged"), "Tagged", world.id(DEPOT), cool, from(1, 1), values())
                .endDate(world.north, on(6, 1)).build());

        RelationshipId alsoTagged = relationship(receipt, new TemporaryId("alsoTagged"));
        assertThat(receipt.ended()).contains(alsoTagged);
        assertThat(receipt.ended().indexOf(alsoTagged)).as("after the relationships that already existed")
                .isGreaterThan(receipt.ended().indexOf(world.rel("tagged")));
        assertThat(world.existsOn(alsoTagged, 5, 31)).isTrue();
        assertThat(world.existsOn(alsoTagged, 6, 1)).isFalse();
    }

    // ------------------------------------------------------------------ what a cascade leaves alone

    @Test
    void aRelationshipThatTheTransactionEndsFromTheInstantItselfIsNotListed() {
        World world = new World();
        RelationshipId tagged = world.rel("tagged");

        Receipt receipt = world.store.commit(transaction().endDate(world.north, on(6, 1))
                .endDate(tagged, on(6, 1)).build());

        assertThat(receipt.ended()).doesNotContain(tagged).contains(world.rel("inspects"),
                world.rel("withAnnex"));
        assertThat(world.existsOn(tagged, 5, 31)).isTrue();
        assertThat(world.existsOn(tagged, 6, 1)).isFalse();
    }

    @Test
    void aRelationshipThatTheTransactionEndsEarlierKeepsItsEnd() {
        World world = new World();
        RelationshipId tagged = world.rel("tagged");

        Receipt receipt = world.store.commit(transaction().endDate(world.north, on(6, 1))
                .endDate(tagged, on(5, 1)).build());

        assertThat(receipt.ended()).doesNotContain(tagged);
        assertThat(world.existsOn(tagged, 4, 30)).isTrue();
        assertThat(world.existsOn(tagged, 5, 1)).isFalse();
    }

    @Test
    void aRelationshipThatTheTransactionEndsLaterIsShortenedAndListed() {
        World world = new World();
        RelationshipId tagged = world.rel("tagged");

        Receipt receipt = world.store.commit(transaction().endDate(tagged, on(9, 1))
                .endDate(world.north, on(6, 1)).build());

        assertThat(receipt.ended()).contains(tagged);
        assertThat(world.existsOn(tagged, 5, 31)).isTrue();
        assertThat(world.existsOn(tagged, 6, 1)).as("not until 1 September").isFalse();
    }

    @Test
    void aRelationshipThatAnEarlierTransactionEndedIsLeftAlone() {
        World world = new World();
        RelationshipId tagged = world.rel("tagged");
        world.store.commit(transaction().endDate(tagged, on(4, 1)).build());

        Receipt receipt = world.store.commit(transaction().endDate(world.north, on(6, 1)).build());

        assertThat(receipt.ended()).doesNotContain(tagged);
        assertThat(world.existsOn(tagged, 3, 31)).isTrue();
        assertThat(world.existsOn(tagged, 4, 1)).isFalse();
    }

    @Test
    void aRelationshipThatStartsAtTheInstantOrAfterItStaysAProblem() {
        World world = new World();
        TemporaryId june = new TemporaryId("june");
        TemporaryId august = new TemporaryId("august");
        Receipt later = world.store.commit(transaction()
                .create(june, "Tag", from(1, 1), values("name", "june"))
                .create(august, "Tag", from(1, 1), values("name", "august"))
                .relate(new TemporaryId("taggedInJune"), "Tagged", world.id(DEPOT), june, from(6, 1), values())
                .relate(new TemporaryId("taggedInAugust"), "Tagged", world.id(DEPOT), august, from(8, 1),
                        values())
                .build());

        List<Problem> problems = world.store.reject(transaction().endDate(world.north, on(6, 1)).build());

        assertThat(problems).extracting(Problem::code).containsOnly(ProblemCode.RELATIONSHIP_OUTLIVES_END);
        assertThat(problems).extracting(Problem::path).containsExactlyInAnyOrder(
                relationship(later, new TemporaryId("taggedInJune")).value(),
                relationship(later, new TemporaryId("taggedInAugust")).value());
    }

    @Test
    void anEntityThatAWriteEndsKeepsItsContinuingRelationshipsAsProblems() {
        World world = new World();

        List<Problem> problems = world.store.reject(transaction().endDate(world.id(DEPOT), on(6, 1)).build());

        assertThat(problems).extracting(Problem::code).containsOnly(ProblemCode.RELATIONSHIP_OUTLIVES_END);
        assertThat(problems).extracting(Problem::path).as("what the writer must end, not what the cascade does")
                .containsExactlyInAnyOrder(world.rel("hasDepot").value(),
                        world.rel("inspects").value(), world.rel("withAnnex").value(),
                        world.rel("withYard").value(), world.rel("withDock").value(),
                        world.rel("tagged").value());
    }

    @Test
    void aWriterThatEndsTheRelationshipsOfItsEntityItselfStillCommits() {
        World world = new World();

        Receipt receipt = world.store.commit(transaction().endDate(world.id(DEPOT), on(6, 1))
                .endDate(world.rel("hasDepot"), on(6, 1)).endDate(world.rel("inspects"), on(6, 1))
                .endDate(world.rel("withAnnex"), on(6, 1)).endDate(world.rel("withYard"), on(6, 1))
                .endDate(world.rel("withDock"), on(6, 1)).endDate(world.rel("tagged"), on(6, 1))
                .build());

        assertThat(receipt.ended()).as("only the composition and the relationships of its target")
                .containsExactly(world.rel("hasRoom"), world.id(ROOM), world.rel("cleans"));
    }

    // ------------------------------------------------------------------ checks and re-checks

    @Test
    void theEntityAtTheOtherEndOfAnEndedRelationshipHasItsMultiplicitiesCheckedAndIsNamed() {
        ReferenceStore store = ReferenceStore.with(model(Multiplicity.EXACTLY_ONE));
        TemporaryId lone = new TemporaryId("lone");
        Receipt estate = store.commit(transaction()
                .create(NORTH, "Region", from(1, 1), values("name", "North"))
                .create(DEPOT, "Site", from(1, 1), values("name", "Depot"))
                .create(lone, "Tag", from(1, 1), values("name", "lone"))
                .relate("HasSite", NORTH, DEPOT, from(1, 1), values())
                .relate("Tagged", DEPOT, lone, from(1, 1), values()).build());

        List<Problem> problems = store.reject(transaction().endDate(entity(estate, NORTH), on(6, 1)).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.TOO_FEW_RELATIONSHIPS, entity(estate, lone).value() + "[tags]");
        assertThat(store.view(on(6, 1)).entity(entity(estate, NORTH))).as("nothing was ended").isPresent();
    }

    /** A rule that reads the accounts of an employer: the reference domain with Opens, a composition to an account. */
    private static final class Opening {
        final ReferenceStore store = ReferenceStore.withRules(
                Definitions.withRule(Definitions.plus(ReferenceModel.definition(), SchemaDefinition.builder()
                        .relationshipType("Opens", r -> r.from("Person").to("Account").composition()
                                .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.EXACTLY_ONE)
                                .inverse("openedBy"))
                        .build()), "Employs", EmployerHoldsAccountRule.DESCRIPTOR),
                ReferenceStore.rulesWith(new EmployerHoldsAccountRule()), ReferenceModel.declaredRules());
        final Receipt parties = store.createParties();
        final TemporaryId carol = new TemporaryId("carol");
        final TemporaryId dave = new TemporaryId("dave");
        final Receipt accounts;
        final RelationshipId employs;

        /**
         * Carol opened account A-1, which Beta holds; Beta employs Alice. If {@code another} is true, Dave opened A-3,
         * which Beta holds too.
         */
        Opening(boolean another) {
            EntityId beta = entity(parties, BETA);
            Transaction.Builder builder = transaction()
                    .create(carol, "Person", from(1, 1), values("name", "Carol"))
                    .create(A1, "Account", from(1, 1),
                            values("number", "A-1", "currency", "EUR", "balance", money("1000")))
                    .relate(HELD_BY_A1, "HeldBy", A1, beta, from(1, 1), values())
                    .relate(OPENS_A1, "Opens", carol, A1, from(1, 1), values());
            if (another) {
                builder.create(dave, "Person", from(1, 1), values("name", "Dave"))
                        .create(new TemporaryId("a3"), "Account", from(1, 1),
                                values("number", "A-3", "currency", "EUR", "balance", money("10")))
                        .relate("HeldBy", new TemporaryId("a3"), beta, from(1, 1), values())
                        .relate("Opens", dave, new TemporaryId("a3"), from(1, 1), values());
            }
            accounts = store.commit(builder.build());
            employs = relationship(store.replay(ReferenceStories.rd3Employment(beta, entity(parties, ALICE))),
                    BETA_EMPLOYS_ALICE);
            store.recordedOn(on(5, 20));
        }
    }

    @Test
    void aRuleThatReadTheRelationshipsACascadeEndsIsCheckedAgain() {
        Opening opening = new Opening(false);

        List<Problem> problems = opening.store.reject(transaction()
                .endDate(entity(opening.accounts, opening.carol), on(6, 1)).build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).contains("employer-holds-account/no-account");
        assertThat(problem.path()).as("Beta's Employs of Alice, which the transaction does not name")
                .isEqualTo(opening.employs.value());
    }

    @Test
    void aRuleThatReadTheRelationshipsACascadeEndsIsSatisfiedIfWhatItNeedsRemains() {
        Opening opening = new Opening(true);

        Receipt receipt = opening.store.commit(transaction()
                .endDate(entity(opening.accounts, opening.carol), on(6, 1)).build());

        assertThat(receipt.ended()).containsExactly(relationship(opening.accounts, OPENS_A1),
                entity(opening.accounts, A1), relationship(opening.accounts, HELD_BY_A1));
    }
}
