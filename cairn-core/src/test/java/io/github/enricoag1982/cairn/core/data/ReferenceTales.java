package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.CUSTOMER_ROLE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PLAYED_BY;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Problem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Tells the stories of the reference domain to a store, in the order of their recording dates, and keeps what the store
 * answered: the receipts of the stories that were accepted, and the problems of the ones that were rejected.
 */
final class ReferenceTales {

    /** The effective times at which the stories are read: before them, at their dates, and after. */
    static final List<Instant> EFFECTIVE_TIMES = List.of(on(2025, 12, 1), on(1, 1), on(1, 31), on(2, 1), on(3, 1),
            on(3, 14), on(3, 15), on(4, 1), on(5, 31), on(6, 1), on(10, 1), on(12, 31), on(2027, 1, 1));

    /** What a store answered to the stories told to it. */
    static final class Told {
        final List<Receipt> receipts = new ArrayList<>();
        final List<List<Problem>> rejected = new ArrayList<>();
        Receipt parties;
        Receipt accounts;
        Receipt ownership;
        Receipt employment;
        Receipt customer;

        /** The IDs of every entity and relationship that a receipt names. */
        List<Id> ids() {
            List<Id> ids = new ArrayList<>();
            for (Receipt receipt : receipts) {
                for (Id id : receipt.created()) {
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                }
            }
            return ids;
        }

        Receipt accept(Store store, TestClock clock, ReferenceStories.Recording recording) {
            Receipt receipt = ReferenceStories.replay(store, clock, recording);
            receipts.add(receipt);
            return receipt;
        }

        void reject(Store store, TestClock clock, ReferenceStories.Recording recording) {
            CommitResult result = ReferenceStories.attempt(store, clock, recording);
            if (!(result instanceof CommitResult.Rejected rejection)) {
                throw new AssertionError("The story should be rejected, but " + result);
            }
            rejected.add(rejection.problems());
        }
    }

    private ReferenceTales() {
    }

    static EntityId entity(Receipt receipt, TemporaryId temporaryId) {
        return (EntityId) receipt.temporaryIds().get(temporaryId);
    }

    static RelationshipId relationship(Receipt receipt, TemporaryId temporaryId) {
        return (RelationshipId) receipt.temporaryIds().get(temporaryId);
    }

    /**
     * RD-1 to RD-5: the parties, the accounts, the ownership and its correction, the employment, the customer role, and
     * the balance with its correction. The reference model must be registered.
     */
    static Told rd1ToRd5(Store store, TestClock clock) {
        Told told = new Told();
        told.parties = told.accept(store, clock, ReferenceStories.rd1Parties());
        EntityId acme = entity(told.parties, ACME);
        EntityId beta = entity(told.parties, BETA);
        told.accounts = told.accept(store, clock, ReferenceStories.rd5Accounts(beta, acme));
        told.ownership = told.accept(store, clock, ReferenceStories.rd2Ownership(acme, beta));
        told.employment = told.accept(store, clock,
                ReferenceStories.rd3Employment(beta, entity(told.parties, ALICE)));
        told.customer = told.accept(store, clock, ReferenceStories.rd4CustomerRole(beta));
        EntityId a1 = entity(told.accounts, A1);
        told.accept(store, clock, ReferenceStories.rd5BalancePatch(a1));
        told.accept(store, clock, ReferenceStories.rd5BalanceCorrection(a1));
        told.accept(store, clock, ReferenceStories.rd2ShareCorrection(relationship(told.ownership, ACME_OWNS_BETA)));
        return told;
    }

    /**
     * Every story that the fixtures build, in the order of the dates: those that are accepted, and those that are
     * rejected, RD-9's Gamma, RD-12, RD-13 and RD-8's end of Beta alone. The reference model must be registered.
     */
    static Told everyStory(Store store, TestClock clock) {
        Told told = new Told();
        told.parties = told.accept(store, clock, ReferenceStories.rd1Parties());
        EntityId acme = entity(told.parties, ACME);
        EntityId beta = entity(told.parties, BETA);
        EntityId alice = entity(told.parties, ALICE);
        told.accounts = told.accept(store, clock, ReferenceStories.rd5Accounts(beta, acme));
        EntityId a1 = entity(told.accounts, A1);
        told.accept(store, clock, ReferenceStories.rd9AcmeLei(acme));
        told.reject(store, clock, ReferenceStories.rd9Gamma());
        told.ownership = told.accept(store, clock, ReferenceStories.rd2Ownership(acme, beta));
        told.employment = told.accept(store, clock, ReferenceStories.rd3Employment(beta, alice));
        told.customer = told.accept(store, clock, ReferenceStories.rd4CustomerRole(beta));
        told.reject(store, clock, ReferenceStories.rd12GammaWrongCheckDigits());
        told.accept(store, clock, ReferenceStories.rd5BalancePatch(a1));
        told.reject(store, clock, ReferenceStories.rd13GammaOwnsBeta(beta));
        told.accept(store, clock, ReferenceStories.rd5BalanceCorrection(a1));
        told.accept(store, clock, ReferenceStories.rd2ShareCorrection(relationship(told.ownership, ACME_OWNS_BETA)));
        told.reject(store, clock, ReferenceStories.rd8EndBetaAlone(beta));
        told.accept(store, clock, ReferenceStories.rd8EndBeta(beta, acme, a1, relationship(told.accounts, HELD_BY_A1),
                relationship(told.ownership, ACME_OWNS_BETA), relationship(told.employment, BETA_EMPLOYS_ALICE),
                entity(told.customer, CUSTOMER_ROLE), relationship(told.customer, PLAYED_BY)));
        told.accept(store, clock, ReferenceStories.rd7Rename(acme));
        return told;
    }
}
