package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.id;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.rd5BalanceCorrection;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.rd5BalancePatch;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.rd7Rename;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.rd9Gamma;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The scenarios of the {@code views} spec, one test each. */
class ViewsScenariosTest {

    private final ReferenceStore reference = new ReferenceStore();

    // ------------------------------------------------------------------ views are bound to coordinates

    @Test
    @DisplayName("Scenario: Repeatable reads")
    void repeatableReads() {
        EntityId acme = (EntityId) reference.createParties().temporaryIds().get(ACME);
        View before = reference.view(on(2, 1));

        reference.commit(Transaction.by("importer").patch(acme, from(1, 1), values("name", "ACME Holdings")).build());

        assertThat(before.entity(acme).orElseThrow().values()).containsEntry("name", Value.of("ACME"));
        assertThat(before.entities("Organization")).extracting(state -> state.values().get("name"))
                .contains(Value.of("ACME"));
        assertThat(reference.view(on(2, 1)).entity(acme).orElseThrow().values())
                .containsEntry("name", Value.of("ACME Holdings"));
    }

    @Test
    @DisplayName("Scenario: Reading as of a transaction time")
    void readingAsOfATransactionTime() {
        EntityId a1 = (EntityId) reference.createAccounts().temporaryIds().get(A1);
        Basis rd5Jan = reference.store.latestBasis();
        Basis rd5March = reference.replay(rd5BalancePatch(a1)).basis();
        Basis rd5April = reference.replay(rd5BalanceCorrection(a1)).basis();

        View known = reference.store.view(Coordinates.effectiveAt(on(3, 31)).knownAt(on(4, 5)));

        assertThat(known.basis()).isEqualTo(rd5March);
        assertThat(known.effectiveAt()).isEqualTo(on(3, 31));
        assertThat(reference.store.view(Coordinates.effectiveAt(on(3, 31)).knownAt(on(4, 20))).basis())
                .as("a transaction recorded at that very time is known").isEqualTo(rd5April);
        assertThat(reference.store.view(Coordinates.effectiveAt(on(3, 31)).knownAt(on(1, 6))).basis())
                .isEqualTo(rd5Jan);
        assertThat(reference.view(on(3, 31)).basis()).isEqualTo(rd5April);
    }

    // ------------------------------------------------------------------ reading an entity

    @Test
    @DisplayName("Scenario: Not yet recorded")
    void notYetRecorded() {
        EntityId acme = (EntityId) reference.createParties().temporaryIds().get(ACME);

        View view = reference.store.view(Coordinates.effectiveAt(on(2, 1)).knownAt(on(1, 4)));

        assertThat(view.entity(acme)).isEmpty();
        assertThat(view.timeline(acme)).isEmpty();
        assertThat(view.basis()).isEqualTo(Basis.NONE);
    }

    @Test
    @DisplayName("Scenario: Reading after a rename")
    void readingAfterARename() {
        EntityId acme = (EntityId) reference.commit(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME", "lei", LEI)).build())
                .temporaryIds().get(ACME);
        var renamed = Definitions.changeEntityType(ReferenceModel.definition(), "Organization", organization ->
                Definitions.changeAttribute(organization, "lei", lei ->
                        Definitions.renamed(lei, "legalEntityId", "lei")));
        assertThat(reference.store.schema().register(ReferenceModel.origin("1.1.0"), renamed))
                .isInstanceOf(RegistrationResult.Applied.class);

        EntityState state = reference.view(on(2, 1)).entity(acme).orElseThrow();

        assertThat(state.values()).containsEntry("legalEntityId", Value.of(LEI)).doesNotContainKey("lei");
        assertThat(state.values().keySet()).containsExactly("name", "legalEntityId");
    }

    // ------------------------------------------------------------------ listing entities of a type

    @Test
    @DisplayName("Scenario: Polymorphic listing")
    void polymorphicListing() {
        Receipt receipt = reference.createParties();
        EntityId acme = (EntityId) receipt.temporaryIds().get(ACME);
        EntityId beta = (EntityId) receipt.temporaryIds().get(BETA);
        EntityId alice = (EntityId) receipt.temporaryIds().get(ALICE);
        View view = reference.view(on(3, 31));

        assertThat(view.entities("Party")).extracting(EntityState::id).containsExactly(acme, beta, alice);
        assertThat(view.entities("Organization")).extracting(EntityState::id).containsExactly(acme, beta);
        assertThat(view.entities("Party")).extracting(EntityState::entityType)
                .containsExactly("Organization", "Organization", "Person");
        assertThat(acme).isEqualTo(id(1));
    }

    @Test
    @DisplayName("Scenario: Listing a type the view does not know")
    void listingATypeTheViewDoesNotKnow() {
        reference.createParties();
        View view = reference.view(on(3, 31));

        assertThatThrownBy(() -> view.entities("Organisation")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Organisation");
    }

    // ------------------------------------------------------------------ reading an entity by a unique key

    @Test
    @DisplayName("Scenario: Finding an account by its number")
    void findingAnAccountByItsNumber() {
        EntityId a1 = (EntityId) reference.createAccounts().temporaryIds().get(A1);
        reference.replay(rd5BalancePatch(a1));
        reference.replay(rd5BalanceCorrection(a1));

        Optional<EntityState> found = reference.view(on(3, 31)).entity(new UniqueKey("Account", "number",
                Value.of("A-1")));

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(a1);
        assertThat(found.get().values()).containsEntry("balance", Value.of(new BigDecimal("1200")));
        assertThat(found.get()).as("as the entity read by its ID").isEqualTo(reference.view(on(3, 31)).entity(a1)
                .orElseThrow());
    }

    @Test
    @DisplayName("Scenario: Finding by a value that moved")
    void findingByAValueThatMoved() {
        EntityId acme = (EntityId) reference.createParties().temporaryIds().get(ACME);
        reference.commit(Transaction.by("importer")
                .patch(acme, EffectivePeriod.between(on(1, 1), on(3, 1)), values("lei", LEI)).build());
        reference.replay(rd9Gamma());
        UniqueKey key = new UniqueKey("Organization", "lei", Value.of(LEI));

        Optional<EntityState> february = reference.view(on(2, 15)).entity(key);
        Optional<EntityState> march = reference.view(on(3, 15)).entity(key);

        assertThat(february).hasValueSatisfying(state -> assertThat(state.id()).isEqualTo(acme));
        assertThat(march).hasValueSatisfying(state -> {
            assertThat(state.id()).isNotEqualTo(acme);
            assertThat(state.values()).containsEntry("name", Value.of("Gamma"));
        });
        assertThat(reference.view(on(2, 28)).entity(key)).as("the last day of ACME's period").isPresent()
                .hasValueSatisfying(state -> assertThat(state.id()).isEqualTo(acme));
        assertThat(reference.view(on(3, 1)).entity(key)).as("the period of ACME is half-open")
                .hasValueSatisfying(state -> assertThat(state.values()).containsEntry("name", Value.of("Gamma")));
        assertThat(reference.view(on(12, 31)).entity(key)).as("Gamma is not ended").isPresent();
        assertThat(reference.view(on(12, 31)).entity(new UniqueKey("Organization", "lei", Value.of("none"))))
                .isEmpty();
    }

    @Test
    @DisplayName("Scenario: Finding by an attribute that is not unique")
    void findingByAnAttributeThatIsNotUnique() {
        reference.createAccounts();
        View view = reference.view(on(3, 31));

        assertThatThrownBy(() -> view.entity(new UniqueKey("Account", "currency", Value.of("EUR"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currency")
                .hasMessageContaining("not unique");
    }

    // ------------------------------------------------------------------ timeline of an entity

    @Test
    @DisplayName("Scenario: Timeline with a future-dated change")
    void timelineWithAFutureDatedChange() {
        EntityId acme = (EntityId) reference.createParties().temporaryIds().get(ACME);
        reference.replay(rd7Rename(acme));

        var timeline = reference.view(on(3, 31)).timeline(acme);

        assertThat(timeline).hasSize(2);
        assertThat(timeline.get(0).values()).isEqualTo(Map.of("name", Value.of("ACME")));
        assertThat(timeline.get(0).period()).isEqualTo(EffectivePeriod.between(on(1, 1), on(2027, 1, 1)));
        assertThat(timeline.get(1).values()).isEqualTo(Map.of("name", Value.of("ACME Holdings")));
        assertThat(timeline.get(1).period()).isEqualTo(EffectivePeriod.from(on(2027, 1, 1)));
        assertThat(timeline.get(0).recordedAt()).isBetween(on(1, 5), on(1, 6));
        assertThat(timeline.get(1).recordedAt()).isBetween(on(10, 12), on(10, 13));
        assertThat(timeline.get(0).recordedBasis()).isLessThan(timeline.get(1).recordedBasis());
    }
}
