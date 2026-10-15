package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the indexes of the ledger list, and what the views make of it (ADR-0027, design D4 of
 * {@code refactor-storage-onto-key-value-store}): the entities of a type and its subtypes, the relationships of a type
 * and its subtypes at an end in the direction asked, both directions of a symmetric type, and the entities that ever held
 * a value of a unique attribute, each only from the basis that added it, in the order of the IDs, and then read at the
 * basis, because the indexes give candidates and the timelines decide. The world is the reference domain with a
 * symmetric type and a relationship type with a subtype.
 */
class LedgerIndexesTest {

    private static final TemporaryId NEW = new TemporaryId("new");

    private final TestClock clock = new TestClock();
    private InMemoryStore store;
    private Schema schema;

    private static SchemaDefinition model() {
        return Definitions.plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .relationshipType("RelatedParty", r -> r.from("Party").to("Party").symmetric()
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("relatedParties"))
                .relationshipType("WorksFor", r -> r.from("Person").to("Organization")
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("workers"))
                .relationshipType("Contracts", r -> r.supertype("WorksFor").from("Person").to("Organization")
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("contractors"))
                .build());
    }

    @BeforeEach
    void aStoreWithTheReferenceDomainAndTwoMoreTypes() {
        store = new InMemoryStore(clock, IdGenerators.deterministic(), ReferenceModel.executableRules(),
                ReferenceModel.declaredRules());
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), model()))
                .isInstanceOf(RegistrationResult.Applied.class);
        schema = store.schema().current();
    }

    private Receipt commit(Instant recordedAt, Transaction transaction) {
        clock.moveTo(recordedAt);
        CommitResult result = store.commit(transaction);
        assertThat(result).as("the transaction is accepted").isInstanceOf(CommitResult.Committed.class);
        return ((CommitResult.Committed) result).receipt();
    }

    private EntityId create(Instant recordedAt, String type, String name) {
        return (EntityId) commit(recordedAt, Transaction.by("tester")
                .create(NEW, type, from(1, 1), values("name", name)).build()).temporaryIds().get(NEW);
    }

    private RelationshipId relate(Instant recordedAt, String type, EntityId source, EntityId target) {
        return (RelationshipId) commit(recordedAt, Transaction.by("tester")
                .relate(NEW, type, source, target, from(2, 1), values()).build()).temporaryIds().get(NEW);
    }

    private TypeId entityType(String name) {
        return schema.entityType(name).orElseThrow().id();
    }

    private TypeId relationshipType(String name) {
        return schema.relationshipType(name).orElseThrow().id();
    }

    private View view(Basis basis) {
        return store.view(Coordinates.effectiveAt(on(3, 1)).knownAt(basis));
    }

    private static List<EntityId> ids(List<EntityState> states) {
        return states.stream().map(EntityState::id).toList();
    }

    private static List<EntityId> entities(List<Link> links) {
        return links.stream().map(link -> link.entity().id()).toList();
    }

    @Test
    void theEntitiesOfATypeAndItsSubtypesAreListedFromTheBasisThatCreatedThemInTheOrderOfTheirIds() {
        EntityId zed = create(on(1, 5), "Organization", "Zed");
        Basis afterZed = store.latestBasis();
        EntityId bob = create(on(1, 6), "Person", "Bob");
        Basis afterBob = store.latestBasis();
        EntityId amy = create(on(1, 7), "Organization", "Amy");
        Ledger ledger = store.ledger();
        Set<TypeId> parties = Set.of(entityType("Person"), entityType("Organization"));

        assertThat(ledger.entities(Set.of(entityType("Organization")), afterZed)).extracting(Held::id)
                .containsExactly(zed);
        assertThat(ledger.entities(parties, afterZed)).extracting(Held::id).containsExactly(zed);
        assertThat(ledger.entities(parties, afterBob)).extracting(Held::id).containsExactlyInAnyOrder(zed, bob)
                .isSorted();
        assertThat(ledger.entities(parties, Ledger.LATEST)).extracting(Held::id)
                .containsExactlyInAnyOrder(zed, bob, amy).isSorted();
        assertThat(ledger.entities(Set.of(entityType("Person")), Ledger.LATEST)).extracting(Held::id)
                .containsExactly(bob);
        assertThat(ledger.entities(Set.of(entityType("Account")), Ledger.LATEST)).isEmpty();
        assertThat(ledger.entities(parties, Basis.NONE)).isEmpty();
        assertThat(ids(view(afterBob).entities("Party"))).as("a view lists the subtypes of an abstract type")
                .containsExactlyInAnyOrder(zed, bob).isSorted();
        assertThat(ids(view(afterBob).entities("Organization"))).containsExactly(zed);
        assertThat(ids(store.view(Coordinates.effectiveAt(on(3, 1))).entities("Party")))
                .containsExactlyInAnyOrder(zed, bob, amy).isSorted();
    }

    @Test
    void theRelationshipsOfAnEntityAreListedByTypeSubtypesAndDirection() {
        EntityId alice = create(on(1, 5), "Person", "Alice");
        EntityId acme = create(on(1, 6), "Organization", "ACME");
        EntityId beta = create(on(1, 7), "Organization", "Beta");
        RelationshipId works = relate(on(2, 1), "WorksFor", alice, acme);
        Basis afterWorks = store.latestBasis();
        RelationshipId contracts = relate(on(2, 2), "Contracts", alice, beta);
        Ledger ledger = store.ledger();
        TypeId worksFor = relationshipType("WorksFor");
        TypeId contractsType = relationshipType("Contracts");

        assertThat(ledger.relationships(alice, Set.of(worksFor), Ledger.Direction.FORWARD, Ledger.LATEST))
                .extracting(Held::id).as("a supertype alone").containsExactly(works);
        assertThat(ledger.relationships(alice, Set.of(worksFor, contractsType), Ledger.Direction.FORWARD,
                Ledger.LATEST)).extracting(Held::id).as("with its subtype").containsExactlyInAnyOrder(works, contracts)
                .isSorted();
        assertThat(ledger.relationships(alice, Set.of(worksFor, contractsType), Ledger.Direction.BACKWARD,
                Ledger.LATEST)).as("Alice is nobody's target").isEmpty();
        assertThat(ledger.relationships(acme, Set.of(worksFor, contractsType), Ledger.Direction.BACKWARD,
                Ledger.LATEST)).extracting(Held::id).containsExactly(works);
        assertThat(ledger.relationships(acme, Set.of(worksFor, contractsType), Ledger.Direction.FORWARD,
                Ledger.LATEST)).as("ACME is nobody's source").isEmpty();
        assertThat(ledger.relationships(alice, Set.of(worksFor, contractsType), Ledger.Direction.BOTH,
                Ledger.LATEST)).extracting(Held::id).containsExactlyInAnyOrder(works, contracts);
        assertThat(ledger.relationships(alice, Set.of(worksFor, contractsType), Ledger.Direction.BOTH, afterWorks))
                .extracting(Held::id).as("the later relationship is not listed at an earlier basis")
                .containsExactly(works);
        assertThat(ledger.ends(alice, Ledger.LATEST)).extracting(Ledger.End::relationship, Ledger.End::type,
                Ledger.End::forward).containsExactlyInAnyOrder(
                        tuple(works, worksFor, true), tuple(contracts, contractsType, true));

        assertThat(entities(view(store.latestBasis()).navigate(alice, "WorksFor"))).as("the name of the supertype")
                .containsExactlyInAnyOrder(acme, beta);
        assertThat(entities(view(store.latestBasis()).navigate(alice, "Contracts"))).containsExactly(beta);
        assertThat(entities(view(store.latestBasis()).navigate(beta, "workers"))).as("the inverse name of the supertype")
                .containsExactly(alice);
        assertThat(entities(view(store.latestBasis()).navigate(acme, "contractors"))).isEmpty();
        assertThat(entities(view(afterWorks).navigate(alice, "WorksFor"))).containsExactly(acme);
    }

    @Test
    void aSymmetricTypeLeadsFromBothEndsByEitherNameAndASelfRelationshipLeadsOnce() {
        EntityId acme = create(on(1, 5), "Organization", "ACME");
        EntityId beta = create(on(1, 6), "Organization", "Beta");
        RelationshipId related = relate(on(2, 1), "RelatedParty", acme, beta);
        RelationshipId self = relate(on(2, 2), "RelatedParty", acme, acme);
        Ledger ledger = store.ledger();
        TypeId type = relationshipType("RelatedParty");
        View view = view(store.latestBasis());

        assertThat(ledger.relationships(beta, Set.of(type), Ledger.Direction.BOTH, Ledger.LATEST))
                .extracting(Held::id).containsExactly(related);
        assertThat(ledger.relationships(acme, Set.of(type), Ledger.Direction.BOTH, Ledger.LATEST))
                .extracting(Held::id).as("a relationship with both ends at an entity is listed once")
                .containsExactlyInAnyOrder(related, self).isSorted();
        assertThat(ledger.ends(acme, Ledger.LATEST)).as("but it has an entry for each of its ends")
                .extracting(Ledger.End::relationship).containsExactlyInAnyOrder(related, self, self);
        assertThat(entities(view.navigate(acme, "RelatedParty"))).containsExactlyInAnyOrder(beta, acme);
        assertThat(entities(view.navigate(beta, "RelatedParty"))).as("from the target by the type's name")
                .containsExactly(acme);
        assertThat(entities(view.navigate(beta, "relatedParties"))).as("and by the inverse name").containsExactly(acme);
        assertThat(entities(view.navigate(acme, "relatedParties"))).containsExactlyInAnyOrder(beta, acme);
        assertThat(view.navigateTimeline(acme, "RelatedParty")).extracting(state -> state.id())
                .as("each version of a relationship once").containsExactlyInAnyOrder(related, self).isSorted();
    }

    @Test
    void aUniqueValueIsACandidateForEveryEntityThatEverHeldItAndTheTimelinesDecideWhoHoldsIt() {
        Receipt parties = commit(on(1, 5), Transaction.by("tester")
                .create(new TemporaryId("first"), "Organization", from(1, 1), values("name", "First", "lei", LEI))
                .create(new TemporaryId("second"), "Organization", from(1, 1), values("name", "Second")).build());
        EntityId first = (EntityId) parties.temporaryIds().get(new TemporaryId("first"));
        EntityId second = (EntityId) parties.temporaryIds().get(new TemporaryId("second"));
        Basis created = store.latestBasis();
        commit(on(3, 1), Transaction.by("tester").patch(first, from(4, 1), Map.of(), Set.of("lei"),
                ConflictCheck.none()).build());
        Basis cleared = store.latestBasis();
        commit(on(3, 2), Transaction.by("tester").patch(second, from(4, 1), values("lei", LEI)).build());
        Basis taken = store.latestBasis();
        AttributeId lei = schema.entityType("Organization").orElseThrow().attribute("lei").orElseThrow().id();
        UniqueKey key = new UniqueKey("Organization", "lei", Value.of(LEI));
        Ledger ledger = store.ledger();

        assertThat(ledger.uniqueIds(lei, Value.of(LEI), Basis.NONE)).isEmpty();
        assertThat(ledger.uniqueIds(lei, Value.of(LEI), created)).containsExactly(first.value());
        assertThat(ledger.uniqueIds(lei, Value.of(LEI), cleared)).as("it no longer holds it, but it did")
                .containsExactly(first.value());
        assertThat(ledger.uniqueIds(lei, Value.of(LEI), taken)).containsExactly(first.value(), second.value());
        assertThat(ledger.uniqueHolders(lei, Value.of(LEI), false, taken)).extracting(Held::id)
                .containsExactly(first, second);
        assertThat(ledger.uniqueHolders(lei, Value.of(LEI), true, taken)).as("no relationship has it").isEmpty();
        assertThat(ledger.uniqueHolders(lei, Value.of(LEI), false, created)).extracting(Held::id)
                .containsExactly(first);
        assertThat(store.view(Coordinates.effectiveAt(on(2, 1)).knownAt(taken)).entity(key))
                .hasValueSatisfying(state -> assertThat(state.id()).isEqualTo(first));
        assertThat(store.view(Coordinates.effectiveAt(on(5, 1)).knownAt(taken)).entity(key))
                .hasValueSatisfying(state -> assertThat(state.id()).isEqualTo(second));
        assertThat(store.view(Coordinates.effectiveAt(on(5, 1)).knownAt(cleared)).entity(key))
                .as("a candidate that does not hold it then").isEmpty();
        assertThat(store.view(Coordinates.effectiveAt(on(5, 1)).knownAt(created)).entity(key))
                .hasValueSatisfying(state -> assertThat(state.id()).isEqualTo(first));
        assertThat(store.view(Coordinates.effectiveAt(on(5, 1))).entity(key))
                .hasValueSatisfying(state -> assertThat(state.id()).isEqualTo(second));
    }
}
