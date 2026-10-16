package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.PayloadFixtures.payload;
import static io.github.enricoag1982.cairn.documents.PayloadFixtures.read;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.documents.PayloadFixtures.ReferenceDomainStore;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What the IDs of a store name, found through the timelines of its latest view (design D2 of {@code add-json-payloads}). */
class IdLookupTest {

    private final ReferenceDomainStore reference = new ReferenceDomainStore();

    private IdLookup.Known entity(String type) {
        return new IdLookup.Known(IdLookup.Kind.ENTITY, type);
    }

    private IdLookup.Known relationship(String type) {
        return new IdLookup.Known(IdLookup.Kind.RELATIONSHIP, type);
    }

    @Test
    void anEntityAndARelationshipAreFoundWithTheNamesOfTheirTypes() {
        Receipt parties = reference.replay(ReferenceStories.rd1Parties());
        Receipt ownership = reference.replay(ReferenceStories.rd2Ownership(
                parties.temporaryIds().get(ACME), parties.temporaryIds().get(BETA)));
        IdLookup lookup = reference.lookup();

        assertThat(lookup.find(parties.temporaryIds().get(ACME).value())).contains(entity("Organization"));
        assertThat(lookup.find(parties.temporaryIds().get(ALICE).value())).contains(entity("Person"));
        assertThat(lookup.find(ownership.temporaryIds().get(ACME_OWNS_BETA).value())).contains(relationship("Owns"));
    }

    @Test
    void anIdThatNothingHoldsIsUnknown() {
        reference.replay(ReferenceStories.rd1Parties());

        assertThat(reference.lookup().find("no-such-id")).isEmpty();
    }

    @Test
    void anEntityThatHasEndedIsFoundThoughTheViewDoesNotHaveItAtItsEffectiveTime() {
        Receipt parties = reference.replay(ReferenceStories.rd1Parties());
        EntityId alice = (EntityId) parties.temporaryIds().get(ALICE);
        reference.commit(Transaction.by(PRINCIPAL).endDate(alice, on(2, 1)).build());

        assertThat(reference.latest().entity(alice)).isEmpty();
        assertThat(reference.lookup().find(alice.value())).contains(entity("Person"));
    }

    @Test
    void anEntityThatIsNotYetEffectiveIsFound() {
        reference.replay(ReferenceStories.rd1Parties());
        Receipt gamma = reference.commit(Transaction.by(PRINCIPAL)
                .create(GAMMA, "Organization", EffectivePeriod.from(on(2027, 1, 1)), values("name", "Gamma")).build());
        EntityId id = (EntityId) gamma.temporaryIds().get(GAMMA);

        assertThat(reference.latest().entity(id)).isEmpty();
        assertThat(reference.lookup().find(id.value())).contains(entity("Organization"));
    }

    @Test
    void aRelationshipThatHasEndedIsFoundViaItsTimeline() {
        Receipt parties = reference.replay(ReferenceStories.rd1Parties());
        Receipt ownership = reference.replay(ReferenceStories.rd2Ownership(
                parties.temporaryIds().get(ACME), parties.temporaryIds().get(BETA)));
        RelationshipId owns = (RelationshipId) ownership.temporaryIds().get(ACME_OWNS_BETA);
        reference.commit(Transaction.by(PRINCIPAL).endDate(owns, on(3, 1)).build());

        assertThat(reference.latest().relationship(owns)).isEmpty();
        assertThat(reference.latest().relationshipTimeline(owns)).isNotEmpty();
        assertThat(reference.lookup().find(owns.value())).contains(relationship("Owns"));
    }

    @Test
    void thePayloadOfAnEndedRelationshipIsReadWithTheTypeOfItsAttributes() {
        Receipt parties = reference.replay(ReferenceStories.rd1Parties());
        Receipt ownership = reference.replay(ReferenceStories.rd2Ownership(
                parties.temporaryIds().get(ACME), parties.temporaryIds().get(BETA)));
        RelationshipId owns = (RelationshipId) ownership.temporaryIds().get(ACME_OWNS_BETA);
        EntityId alice = (EntityId) parties.temporaryIds().get(ALICE);
        reference.commit(Transaction.by(PRINCIPAL).endDate(owns, on(3, 1)).endDate(alice, on(3, 1)).build());

        TransactionPayload payload = read(payload(
                "{ \"patch\": { \"target\": \"" + owns.value() + "\", \"from\": \"2026-02-01T00:00:00Z\", "
                        + "\"until\": \"2026-03-01T00:00:00Z\", \"set\": { \"share\": 55 } } }",
                "{ \"patch\": { \"target\": \"" + alice.value() + "\", \"from\": \"2026-01-01T00:00:00Z\", "
                        + "\"until\": \"2026-03-01T00:00:00Z\", \"set\": { \"birthDate\": \"1990-05-17\" } } }"),
                reference.schema(), reference.lookup());

        Write.Patch ownsPatch = (Write.Patch) payload.transaction().writes().get(0);
        Write.Patch alicePatch = (Write.Patch) payload.transaction().writes().get(1);
        assertThat(ownsPatch.target()).isEqualTo(owns);
        assertThat(ownsPatch.set()).containsEntry("share", Value.of(new BigDecimal("55")));
        assertThat(alicePatch.target()).isEqualTo(alice);
        assertThat(alicePatch.set()).containsEntry("birthDate", Value.of(LocalDate.of(1990, 5, 17)));
    }

    @Test
    void noLookupKnowsNoId() {
        assertThat(IdLookup.none().find("anything")).isEqualTo(Optional.empty());
    }

    @Test
    void whatAStoreHoldsHasAKindAndATypeNameThatAreNotNull() {
        assertThatNullPointerException().isThrownBy(() -> new IdLookup.Known(null, "Person"));
        assertThatNullPointerException().isThrownBy(() -> new IdLookup.Known(IdLookup.Kind.ENTITY, null));
        assertThatNullPointerException().isThrownBy(() -> IdLookup.from(null));
    }

    @Test
    void aLookupWithAClearanceFindsNoEntityByAKeyOfAnAttributeItDoesNotCoverWhetherOrNotOneHasTheValue() {
        ReferenceDomainStore classified = new ReferenceDomainStore(Definitions.withClassification(
                ReferenceModel.definition(), "Organization", "lei", "identity"));
        Receipt parties = classified.replay(ReferenceStories.rd1Parties());
        classified.replay(ReferenceStories.rd9AcmeLei(parties.temporaryIds().get(ACME)));
        UniqueKey held = new UniqueKey("Organization", "lei", Value.of(ReferenceStories.LEI));
        UniqueKey absent = new UniqueKey("Organization", "lei", Value.of("5493001KJTIIGC8Y1R13"));
        UniqueKey byName = new UniqueKey("Organization", "name", Value.of("ACME"));

        IdLookup none = IdLookup.fromStore(classified.store, Clearance.none());
        IdLookup identity = IdLookup.fromStore(classified.store, Clearance.of("identity"));

        assertThat(IdLookup.fromStore(classified.store).find(held, on(3, 1))).as("without a clearance").isPresent();
        assertThat(none.find(held, on(3, 1))).isEmpty();
        assertThat(none.find(absent, on(3, 1))).isEmpty();
        assertThat(identity.find(held, on(3, 1))).map(IdLookup.Named::id)
                .contains(parties.temporaryIds().get(ACME).value());
        assertThat(identity.find(absent, on(3, 1))).isEmpty();
        assertThat(none.find(parties.temporaryIds().get(ACME).value())).as("IDs are not withheld")
                .contains(entity("Organization"));
        assertThat(none.find(byName, on(3, 1))).as("name is not a unique attribute, so the key is invalid").isEmpty();
        assertThatNullPointerException().isThrownBy(() -> IdLookup.fromStore(classified.store, null));
    }
}
