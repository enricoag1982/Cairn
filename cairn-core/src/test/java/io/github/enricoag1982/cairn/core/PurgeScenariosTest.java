package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.QueryRows.names;
import static io.github.enricoag1982.cairn.core.QueryRows.rows;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A2;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceQueries;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The scenarios of the {@code purge} spec that an in-memory store shows, one test each. */
class PurgeScenariosTest {

    private static final TemporaryId HOME = new TemporaryId("home");
    private static final TemporaryId HAS_ADDRESS = new TemporaryId("hasAddress");

    /** Effective times before the stories, at their dates, and after them. */
    private static final List<Instant> EFFECTIVE_TIMES = List.of(on(2025, 12, 1), on(1, 1), on(1, 31), on(3, 1),
            on(3, 31), on(10, 1), on(12, 31), on(2027, 1, 1));

    private final ReferenceStore reference = new ReferenceStore();

    private static PurgeResult.Purged purged(PurgeResult result) {
        assertThat(result).as("purge result").isInstanceOf(PurgeResult.Purged.class);
        return (PurgeResult.Purged) result;
    }

    private static List<Problem> rejected(PurgeResult result) {
        assertThat(result).as("purge result").isInstanceOf(PurgeResult.Rejected.class);
        return ((PurgeResult.Rejected) result).problems();
    }

    /** The entities of a type that a view reads; none at a basis whose schema does not have the type yet. */
    private static List<EntityState> entitiesOf(View view, String type) {
        return view.schema().entityType(type).isPresent() ? view.entities(type) : List.of();
    }

    /** Every view of the store: at every basis from none to the latest, and every effective time. */
    private List<View> everyView() {
        List<View> views = new ArrayList<>();
        for (long basis = 0; basis <= reference.store.latestBasis().value(); basis++) {
            for (Instant effective : EFFECTIVE_TIMES) {
                views.add(reference.store.view(Coordinates.effectiveAt(effective).knownAt(new Basis(basis))));
            }
        }
        return views;
    }

    /** What the views of a store say about an entity: its state and its timeline, at every basis up to one. */
    private Map<String, Object> timelinesOf(EntityId entity, Basis upTo) {
        Map<String, Object> timelines = new LinkedHashMap<>();
        for (long basis = 0; basis <= upTo.value(); basis++) {
            View view = reference.store.view(Coordinates.effectiveAt(on(3, 31)).knownAt(new Basis(basis)));
            timelines.put("entity at " + basis, view.entity(entity));
            timelines.put("timeline at " + basis, view.timeline(entity));
        }
        return timelines;
    }

    // ------------------------------------------------------------------ purging entities

    @Test
    @DisplayName("Scenario: Purging Alice")
    void purgingAlice() {
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        Receipt employment = reference.replay(ReferenceStories.rd3Employment(beta, alice));
        RelationshipId employs = relationship(employment, BETA_EMPLOYS_ALICE);
        assertThat(reference.view(on(3, 31)).entity(alice)).as("before the purge").isPresent();
        assertThat(reference.view(on(3, 31)).relationship(employs)).as("before the purge").isPresent();
        Basis beforePurge = reference.store.latestBasis();
        Map<String, Object> acmeBefore = timelinesOf(acme, beforePurge);
        Map<String, Object> betaBefore = timelinesOf(beta, beforePurge);

        PurgeResult.Purged result = purged(reference.store.purge(ReferenceStories.rd11PurgeAlice(alice)));

        assertThat(result.entities()).containsExactly(alice);
        assertThat(result.relationships()).containsExactly(employs);
        assertThat(everyView()).as("the views of every basis and effective time").hasSize(5 * EFFECTIVE_TIMES.size())
                .allSatisfy(view -> {
                    assertThat(view.entity(alice)).isEmpty();
                    assertThat(view.timeline(alice)).isEmpty();
                    assertThat(view.relationship(employs)).isEmpty();
                    assertThat(view.relationshipTimeline(employs)).isEmpty();
                    assertThat(entitiesOf(view, "Person")).isEmpty();
                    assertThat(entitiesOf(view, "Party")).extracting(EntityState::id).doesNotContain(alice);
                    if (view.schema().relationshipType("Employs").isPresent()) {
                        assertThat(view.navigate(beta, "Employs")).isEmpty();
                        assertThat(view.navigate(beta, "employedBy")).isEmpty();
                        assertThat(view.navigateTimeline(beta, "Employs")).isEmpty();
                    }
                });
        assertThat(names(rows(reference.view(on(3, 31)).query(ReferenceQueries.parties())), "p"))
                .as("the query of RD-10, without Alice").containsExactly("ACME", "Beta");
        assertThat(timelinesOf(acme, beforePurge)).as("ACME").isEqualTo(acmeBefore);
        assertThat(timelinesOf(beta, beforePurge)).as("Beta").isEqualTo(betaBefore);
        assertThat(reference.view(on(3, 31)).entity(acme)).isPresent();
        assertThat(reference.view(on(3, 31)).entity(beta)).isPresent();
    }

    @Test
    @DisplayName("Scenario: The targets of compositions go too")
    void theTargetsOfCompositionsGoToo() {
        Receipt parties = reference.createParties();
        EntityId alice = entity(parties, ALICE);
        Receipt withAddress = reference.commit(Transaction.by("importer")
                .create(HOME, "Address", from(1, 1),
                        values("street", "1 Main St", "city", "Springfield", "country", "US"))
                .relate(HAS_ADDRESS, "HasAddress", alice, HOME, from(1, 1), values()).build());
        EntityId home = entity(withAddress, HOME);
        RelationshipId hasAddress = relationship(withAddress, HAS_ADDRESS);
        assertThat(reference.view(on(3, 31)).entity(home)).as("before the purge").isPresent();
        assertThat(reference.view(on(3, 31)).relationship(hasAddress)).as("before the purge").isPresent();

        PurgeResult.Purged result = purged(reference.store.purge(ReferenceStories.rd11PurgeAlice(alice)));

        assertThat(result.entities()).containsExactly(alice, home);
        assertThat(result.relationships()).containsExactly(hasAddress);
        assertThat(everyView()).allSatisfy(view -> {
            assertThat(view.entity(home)).isEmpty();
            assertThat(view.timeline(home)).isEmpty();
            assertThat(view.relationship(hasAddress)).isEmpty();
            assertThat(view.relationshipTimeline(hasAddress)).isEmpty();
            assertThat(entitiesOf(view, "Address")).isEmpty();
        });
    }

    // ------------------------------------------------------------------ refused purges

    @Test
    @DisplayName("Scenario: A target that another party composed too")
    void aTargetThatAnotherPartyComposedToo() {
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId alice = entity(parties, ALICE);
        Receipt withAddress = reference.commit(Transaction.by("importer")
                .create(HOME, "Address", from(1, 1),
                        values("street", "1 Main St", "city", "Springfield", "country", "US"))
                .relate(HAS_ADDRESS, "HasAddress", alice, HOME, from(1, 1), values()).build());
        EntityId home = entity(withAddress, HOME);
        // On 1 March the Address passes from Alice to ACME, in one transaction, so that it always has one source.
        reference.commit(Transaction.by("importer")
                .endDate(relationship(withAddress, HAS_ADDRESS), on(3, 1))
                .relate("HasAddress", acme, home, from(3, 1), values()).build());
        assertThat(reference.view(on(2, 1)).navigate(alice, "HasAddress")).hasSize(1);
        assertThat(reference.view(on(4, 1)).navigate(acme, "HasAddress")).hasSize(1);
        Basis before = reference.store.latestBasis();

        List<Problem> problems = rejected(reference.store.purge(ReferenceStories.rd11PurgeAlice(alice)));

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.TOO_FEW_RELATIONSHIPS);
            assertThat(problem.path()).isEqualTo(home.value());
            assertThat(problem.message()).contains(home.value());
            assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Purge " + home.value()));
        });
        assertThat(reference.view(on(2, 1)).entity(alice)).as("Alice still exists").isPresent();
        assertThat(reference.view(on(2, 1)).entity(home)).as("and so does the Address").isPresent();
        assertThat(reference.store.latestBasis()).as("no basis is used").isEqualTo(before);
        assertThat(reference.store.purges()).isEmpty();
    }

    @Test
    @DisplayName("Scenario: An account left without a holder")
    void anAccountLeftWithoutAHolder() {
        Receipt accounts = reference.createAccounts();
        EntityId beta = entity(reference.parties, BETA);
        EntityId a1 = entity(accounts, A1);
        Basis before = reference.store.latestBasis();

        List<Problem> problems = rejected(reference.store.purge(
                Purge.by("dpo").reason("Erasure request").entity(beta).build()));

        assertThat(problems).hasSize(1);
        Problem problem = problems.getFirst();
        assertThat(problem.code()).isEqualTo(ProblemCode.TOO_FEW_RELATIONSHIPS);
        assertThat(problem.path()).isEqualTo(a1.value());
        assertThat(problem.message()).contains(a1.value());
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Purge " + a1.value()));
        assertThat(reference.view(on(3, 31)).entity(beta)).as("Beta still exists").isPresent();
        assertThat(reference.view(on(3, 31)).entity(a1)).as("A-1 still exists").isPresent();
        assertThat(reference.store.latestBasis()).as("no basis is used").isEqualTo(before);
        assertThat(reference.store.purges()).isEmpty();
    }

    @Test
    @DisplayName("Scenario: Purging a holder with its account")
    void purgingAHolderWithItsAccount() {
        Receipt accounts = reference.createAccounts();
        EntityId acme = entity(reference.parties, ACME);
        EntityId beta = entity(reference.parties, BETA);
        EntityId a1 = entity(accounts, A1);
        EntityId a2 = entity(accounts, A2);
        RelationshipId heldByA1 = relationship(accounts, HELD_BY_A1);
        Basis beforePurge = reference.store.latestBasis();
        Map<String, Object> acmeBefore = timelinesOf(acme, beforePurge);
        Map<String, Object> a2Before = timelinesOf(a2, beforePurge);

        PurgeResult.Purged result = purged(reference.store.purge(
                Purge.by("dpo").reason("Erasure request").entity(beta).entity(a1).build()));

        assertThat(result.entities()).containsExactly(beta, a1);
        assertThat(result.relationships()).containsExactly(heldByA1);
        assertThat(everyView()).allSatisfy(view -> {
            assertThat(view.entity(beta)).isEmpty();
            assertThat(view.entity(a1)).isEmpty();
        });
        assertThat(timelinesOf(a2, beforePurge)).as("A-2").isEqualTo(a2Before);
        assertThat(timelinesOf(acme, beforePurge)).as("ACME").isEqualTo(acmeBefore);
        assertThat(reference.view(on(3, 31)).navigate(a2, "HeldBy")).singleElement()
                .satisfies(link -> assertThat(link.entity().id()).isEqualTo(acme));
    }

    // ------------------------------------------------------------------ the record of a purge

    @Test
    @DisplayName("Scenario: The record of Alice's purge")
    void theRecordOfAlicesPurge() {
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        RelationshipId employs = relationship(reference.replay(ReferenceStories.rd3Employment(beta, alice)),
                BETA_EMPLOYS_ALICE);
        Basis afterRd3 = reference.store.latestBasis();
        reference.recordedOn(on(3, 2));

        PurgeResult.Purged result = purged(reference.store.purge(Purge.by("dpo").reason("Erasure request")
                .entity(alice).build()));

        assertThat(result.entities()).as("the purge returns the IDs it removed").containsExactly(alice);
        assertThat(result.relationships()).containsExactly(employs);
        PurgeRecord expected = new PurgeRecord(new Basis(afterRd3.value() + 1), on(3, 2), "dpo", Optional.empty(),
                "Erasure request", 1, 1);
        assertThat(result.record()).isEqualTo(expected);
        assertThat(reference.store.purges()).as("the store lists a record with counts, not IDs")
                .containsExactly(expected);
        assertThat(reference.store.wasPurged(alice)).as("the store tells that Alice's ID was purged").isTrue();
        assertThat(reference.store.wasPurged(employs)).isTrue();
        assertThat(reference.store.wasPurged(beta)).isFalse();
        assertThat(reference.store.latestBasis()).isEqualTo(expected.basis());
        assertThat(reference.store.transactions()).extracting(TransactionRecord::basis)
                .as("the data transactions of RD-1 and RD-3 are still listed")
                .containsExactly(new Basis(2), afterRd3);
        assertThat(reference.store.transactions()).extracting(TransactionRecord::principal)
                .containsExactly("importer", "importer");
    }

    // ------------------------------------------------------------------ what a purge leaves unusable

    @Test
    @DisplayName("Scenario: A purged ID supplied again")
    void aPurgedIdSuppliedAgain() {
        Receipt parties = reference.createParties();
        EntityId alice = entity(parties, ALICE);
        purged(reference.store.purge(ReferenceStories.rd11PurgeAlice(alice)));
        Basis before = reference.store.latestBasis();
        Transaction again = Transaction.by("importer")
                .create(alice, "Person", from(1, 1), values("name", "Alice")).build();

        List<Problem> problems = reference.reject(again);

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.ENTITY_EXISTS);
            assertThat(problem.path()).isEqualTo(alice.value());
            assertThat(problem.message()).contains("purged");
        });
        assertThat(reference.store.dryRun(again)).isInstanceOf(DryRunResult.Rejected.class);
        assertThat(reference.store.latestBasis()).as("nothing was recorded").isEqualTo(before);
        assertThat(reference.view(on(3, 31)).entity(alice)).isEmpty();
    }

    @Test
    @DisplayName("Scenario: A retry after a purge")
    void aRetryAfterAPurge() {
        ReferenceStories.Recording rd1 = ReferenceStories.rd1Parties();
        Transaction keyed = new Transaction(rd1.transaction().principal(), Optional.empty(), Optional.empty(),
                Optional.of("import-rd1"), rd1.transaction().writes());
        Receipt parties = reference.replay(new ReferenceStories.Recording(rd1.recordedAt(), keyed));
        EntityId alice = entity(parties, ALICE);
        purged(reference.store.purge(ReferenceStories.rd11PurgeAlice(alice)));
        Basis before = reference.store.latestBasis();

        CommitResult result = reference.store.commit(keyed);

        assertThat(result).isInstanceOf(CommitResult.Rejected.class);
        List<Problem> problems = ((CommitResult.Rejected) result).problems();
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_PURGED);
            assertThat(problem.path()).isEqualTo("@transaction");
            assertThat(problem.message()).contains("import-rd1");
        });
        assertThat(reference.store.latestBasis()).as("nothing was recorded").isEqualTo(before);
        assertThat(reference.view(on(3, 31)).entity(alice)).as("Alice does not exist").isEmpty();
        assertThat(reference.view(on(3, 31)).entities("Person")).isEmpty();
        assertThat(reference.view(on(3, 31)).entities("Organization")).as("ACME and Beta stay").hasSize(2);
    }
}
