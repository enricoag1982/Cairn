package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.CUSTOMER_ROLE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PLAYED_BY;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.documents.PayloadFixtures.ReferenceDomainStore;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories.Recording;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The stories of the reference domain as payloads (capability {@code json-payloads}, requirement "Generated JSON
 * Schemas"): each transaction of RD-1, RD-2, RD-3, RD-4, RD-5, RD-7, RD-8, RD-9, RD-12 and RD-13 is rendered as a JSON
 * payload, which must be valid against the generated JSON Schema for transactions and must be read back as the
 * transaction it was rendered from. The stories are replayed on a store in the order of their recording dates, as the
 * stories that change an entity need its ID, and the payload of each is read with the lookup of the store as it is
 * before the story is recorded. The stories that the store rejects (RD-8 alone, the second part of RD-9, RD-12 and
 * RD-13) have payloads like the others: they break rules of the engine, not the JSON Schema.
 */
class ReferenceStoryPayloadsTest {

    private final ReferenceDomainStore reference = new ReferenceDomainStore();
    private final String schema = JsonSchemas.transactions(reference.schema());
    private final Set<String> checked = new LinkedHashSet<>();

    /** Checks the payload of a story's transaction against the JSON Schema, and reads it back. */
    private void checkPayload(String story, Recording recording) {
        Transaction transaction = recording.transaction();
        String payload = TransactionPayloads.render(transaction);

        assertThat(GeneratedSchemaCheck.errors(schema, payload)).as("%s against the JSON Schema:%n%s", story, payload)
                .isEmpty();
        TransactionPayload read = PayloadFixtures.read(payload, reference.schema(), reference.lookup());
        assertThat(read.transaction()).as("%s read back from:%n%s", story, payload)
                .isEqualTo(TransactionPayloads.readBack(transaction, reference.schema(), reference.lookup()));
        checked.add(story);
    }

    /** Checks the payload of a story that the store accepts, and records the story. */
    private Receipt accepted(String story, Recording recording) {
        checkPayload(story, recording);
        return reference.replay(recording);
    }

    /** Checks the payload of a story that the store rejects, and tries to record it. */
    private void rejected(String story, Recording recording) {
        checkPayload(story, recording);
        assertThat(ReferenceStories.attempt(reference.store, reference.clock, recording)).as("%s is rejected", story)
                .isInstanceOf(CommitResult.Rejected.class);
    }

    private static EntityId entity(Receipt receipt, TemporaryId temporaryId) {
        return (EntityId) receipt.temporaryIds().get(temporaryId);
    }

    private static RelationshipId relationship(Receipt receipt, TemporaryId temporaryId) {
        return (RelationshipId) receipt.temporaryIds().get(temporaryId);
    }

    @Test
    @DisplayName("The payloads of the reference stories are valid against the generated JSON Schema and are read back")
    void thePayloadsOfTheReferenceStoriesAreValidAndAreReadBack() {
        Receipt parties = accepted("RD-1", ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        Receipt accounts = accepted("RD-5", ReferenceStories.rd5Accounts(beta, acme));
        EntityId a1 = entity(accounts, A1);
        accepted("RD-9", ReferenceStories.rd9AcmeLei(acme));
        rejected("RD-9", ReferenceStories.rd9Gamma());
        Receipt ownership = accepted("RD-2", ReferenceStories.rd2Ownership(acme, beta));
        Receipt employment = accepted("RD-3", ReferenceStories.rd3Employment(beta, alice));
        Receipt customer = accepted("RD-4", ReferenceStories.rd4CustomerRole(beta));
        rejected("RD-12", ReferenceStories.rd12GammaWrongCheckDigits());
        accepted("RD-5", ReferenceStories.rd5BalancePatch(a1));
        rejected("RD-13", ReferenceStories.rd13GammaOwnsBeta(beta));
        accepted("RD-5", ReferenceStories.rd5BalanceCorrection(a1));
        accepted("RD-2", ReferenceStories.rd2ShareCorrection(relationship(ownership, ACME_OWNS_BETA)));
        rejected("RD-8", ReferenceStories.rd8EndBetaAlone(beta));
        accepted("RD-8", ReferenceStories.rd8EndBeta(beta, acme, a1, relationship(accounts, HELD_BY_A1),
                relationship(ownership, ACME_OWNS_BETA), relationship(employment, BETA_EMPLOYS_ALICE),
                entity(customer, CUSTOMER_ROLE), relationship(customer, PLAYED_BY)));
        accepted("RD-7", ReferenceStories.rd7Rename(acme));

        assertThat(checked).containsExactlyInAnyOrder("RD-1", "RD-2", "RD-3", "RD-4", "RD-5", "RD-7", "RD-8", "RD-9",
                "RD-12", "RD-13");
    }

    @Test
    @DisplayName("The payloads of stories are read back as transactions that the store commits as the stories do")
    void aPayloadIsReadBackAsATransactionThatTheStoreCommits() {
        Receipt parties = reference.replay(ReferenceStories.rd1Parties());
        Recording accounts = ReferenceStories.rd5Accounts(entity(parties, BETA), entity(parties, ACME));
        String payload = TransactionPayloads.render(accounts.transaction());

        TransactionPayload read = PayloadFixtures.read(payload, reference.schema(), reference.lookup());
        reference.clock.moveTo(accounts.recordedAt());
        Receipt receipt = reference.commit(read.transaction());

        assertThat(receipt.temporaryIds()).containsKeys(ReferenceStories.A1, ReferenceStories.A2,
                ReferenceStories.HELD_BY_A1, ReferenceStories.HELD_BY_A2);
    }
}
