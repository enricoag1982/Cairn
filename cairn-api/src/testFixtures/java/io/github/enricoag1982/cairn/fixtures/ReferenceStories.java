package io.github.enricoag1982.cairn.fixtures;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Ref;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The stories of the reference domain ({@code docs/reference/reference-domain.md}) as the transactions that tell them,
 * with the dates at which they are recorded. It builds every story except RD-6, RD-10 and RD-11: the query with
 * aggregation, the listing and the purge, which need features that do not exist yet.
 *
 * <p>Stories are in 2026 unless they say otherwise. "Recorded" is the transaction time: a {@link Recording} is
 * replayed on a store with a {@link TestClock} moved to its recording date, at midnight UTC. Effective dates are at
 * midnight UTC too. A store's transaction times are strictly increasing, so a story recorded at or before the time of
 * the previous transaction, such as the one on the day the schema was registered, gets the next microsecond after
 * it. Replay the transactions of a store in the order of their recording dates.
 *
 * <p>A story that changes an entity another story created takes its {@link Ref}: the entity's ID from the receipt of
 * that story, or its temporary ID within the same transaction. Where a story gives no recording date, the date is
 * chosen here and said so.
 *
 * <p>The class also holds the shortcuts that build the data of these stories: the temporary IDs, dates, periods and
 * values.
 */
public final class ReferenceStories {

    /** The principal that records the stories. */
    public static final String PRINCIPAL = "importer";

    /** The temporary ID of ACME, an organization. */
    public static final TemporaryId ACME = new TemporaryId("acme");

    /** The temporary ID of Beta, an organization. */
    public static final TemporaryId BETA = new TemporaryId("beta");

    /** The temporary ID of Alice, a person. */
    public static final TemporaryId ALICE = new TemporaryId("alice");

    /** The temporary ID of Gamma, an organization. */
    public static final TemporaryId GAMMA = new TemporaryId("gamma");

    /** The temporary ID of account A-1. */
    public static final TemporaryId A1 = new TemporaryId("a1");

    /** The temporary ID of account A-2. */
    public static final TemporaryId A2 = new TemporaryId("a2");

    /** The temporary ID of the {@code HeldBy} relationship between account A-1 and its holder, Beta, in the story RD-5. */
    public static final TemporaryId HELD_BY_A1 = new TemporaryId("heldByA1");

    /** The temporary ID of the {@code HeldBy} relationship between account A-2 and its holder, ACME, in the story RD-5. */
    public static final TemporaryId HELD_BY_A2 = new TemporaryId("heldByA2");

    /** The temporary ID of the {@code Owns} relationship between ACME and Beta in the story RD-2. */
    public static final TemporaryId ACME_OWNS_BETA = new TemporaryId("acmeOwnsBeta");

    /** The temporary ID of the {@code Employs} relationship between Beta and Alice in the story RD-3. */
    public static final TemporaryId BETA_EMPLOYS_ALICE = new TemporaryId("betaEmploysAlice");

    /** The temporary ID of Beta's customer role (a {@code CustomerRole}) in the story RD-4. */
    public static final TemporaryId CUSTOMER_ROLE = new TemporaryId("customerRole");

    /** The temporary ID of the {@code PlayedBy} relationship between Beta's customer role and Beta in RD-4. */
    public static final TemporaryId PLAYED_BY = new TemporaryId("playedBy");

    /** The temporary ID of the {@code HeldBy} relationship that moves account A-1 to ACME in the story RD-8. */
    public static final TemporaryId HELD_BY_A1_MOVED = new TemporaryId("heldByA1Moved");

    /** The temporary ID of the {@code Owns} relationship between Gamma and Beta in the story RD-13. */
    public static final TemporaryId GAMMA_OWNS_BETA = new TemporaryId("gammaOwnsBeta");

    /** ACME's Legal Entity Identifier in the story RD-9. */
    public static final String LEI = "5493001KJTIIGC8Y1R12";

    /** Gamma's Legal Entity Identifier in the story RD-12, whose check digits are wrong. */
    public static final String LEI_WRONG_CHECK_DIGITS = "5493001KJTIIGC8Y1R13";

    private ReferenceStories() {
    }

    /**
     * A transaction of a story and the instant at which the story records it.
     *
     * @param recordedAt  the instant to which the clock is moved before the transaction is committed
     * @param transaction the transaction
     */
    public record Recording(Instant recordedAt, Transaction transaction) {

        /**
         * Rejects nulls.
         */
        public Recording {
            Objects.requireNonNull(recordedAt, "recordedAt");
            Objects.requireNonNull(transaction, "transaction");
        }
    }

    // ------------------------------------------------------------------ the stories

    /**
     * RD-1. Recorded 5 January: ACME and Beta (organizations) and Alice (a person) exist from 1 January. They are
     * named by {@link #ACME}, {@link #BETA} and {@link #ALICE}.
     *
     * @return the recording
     */
    public static Recording rd1Parties() {
        return new Recording(on(1, 5), Transaction.by(PRINCIPAL)
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .create(BETA, "Organization", from(1, 1), values("name", "Beta"))
                .create(ALICE, "Person", from(1, 1), values("name", "Alice"))
                .build());
    }

    /**
     * RD-2, the ownership. Recorded 10 February: ACME owns 60% of Beta from 1 February. The relationship is named by
     * {@link #ACME_OWNS_BETA}.
     *
     * @param acme ACME
     * @param beta Beta
     * @return the recording
     */
    public static Recording rd2Ownership(Ref acme, Ref beta) {
        return new Recording(on(2, 10), Transaction.by(PRINCIPAL)
                .relate(ACME_OWNS_BETA, "Owns", acme, beta, from(2, 1), values("share", 60)).build());
    }

    /**
     * RD-2, the correction. Recorded 2 May: the share was 55% from 1 February.
     *
     * @param owns the {@code Owns} relationship between ACME and Beta
     * @return the recording
     */
    public static Recording rd2ShareCorrection(Ref owns) {
        return new Recording(on(5, 2), Transaction.by(PRINCIPAL)
                .patch(owns, from(2, 1), values("share", 55)).build());
    }

    /**
     * RD-3. Recorded 1 March: Beta employs Alice as "Analyst" from 1 March. The relationship is named by
     * {@link #BETA_EMPLOYS_ALICE}.
     *
     * @param beta  Beta
     * @param alice Alice
     * @return the recording
     */
    public static Recording rd3Employment(Ref beta, Ref alice) {
        return new Recording(on(3, 1), Transaction.by(PRINCIPAL)
                .relate(BETA_EMPLOYS_ALICE, "Employs", beta, alice, from(3, 1), values("title", "Analyst")).build());
    }

    /**
     * RD-4. Recorded 1 March: Beta is a customer, segment "Corporate", from 1 March until 1 October. The role is a
     * {@code CustomerRole}, named by {@link #CUSTOMER_ROLE}, which Beta plays through a {@code PlayedBy} relationship
     * named by {@link #PLAYED_BY}, for the same period.
     *
     * @param beta Beta
     * @return the recording
     */
    public static Recording rd4CustomerRole(Ref beta) {
        EffectivePeriod period = EffectivePeriod.between(on(3, 1), on(10, 1));
        return new Recording(on(3, 1), Transaction.by(PRINCIPAL)
                .create(CUSTOMER_ROLE, "CustomerRole", period, values("segment", "Corporate"))
                .relate(PLAYED_BY, "PlayedBy", CUSTOMER_ROLE, beta, period, values())
                .build());
    }

    /**
     * RD-5, the accounts. Recorded 5 January: account A-1 is held by Beta and A-2 by ACME, both from 1 January, with
     * balances 1,000 and 2,000, both in euros. The accounts are named by {@link #A1} and {@link #A2}, and their
     * {@code HeldBy} relationships by {@link #HELD_BY_A1} and {@link #HELD_BY_A2}. An account has exactly one holder
     * at every point in time, so the accounts are created together with the relationships.
     *
     * @param beta the holder of A-1, for example {@link #BETA} in the transaction that creates it, or its ID
     * @param acme the holder of A-2, for example {@link #ACME} in the transaction that creates it, or its ID
     * @return the recording
     */
    public static Recording rd5Accounts(Ref beta, Ref acme) {
        return new Recording(on(1, 5), Transaction.by(PRINCIPAL)
                .create(A1, "Account", from(1, 1), values("number", "A-1", "currency", "EUR", "balance", money("1000")))
                .create(A2, "Account", from(1, 1), values("number", "A-2", "currency", "EUR", "balance", money("2000")))
                .relate(HELD_BY_A1, "HeldBy", A1, beta, from(1, 1), values())
                .relate(HELD_BY_A2, "HeldBy", A2, acme, from(1, 1), values())
                .build());
    }

    /**
     * RD-5. Recorded 16 March: A-1's balance is 1,500 from 15 March.
     *
     * @param a1 account A-1
     * @return the recording
     */
    public static Recording rd5BalancePatch(Ref a1) {
        return new Recording(on(3, 16), Transaction.by(PRINCIPAL)
                .patch(a1, from(3, 15), values("balance", money("1500"))).build());
    }

    /**
     * RD-5. Recorded 20 April: correction, A-1's balance was 1,200 from 15 March.
     *
     * @param a1 account A-1
     * @return the recording
     */
    public static Recording rd5BalanceCorrection(Ref a1) {
        return new Recording(on(4, 20), Transaction.by(PRINCIPAL)
                .patch(a1, from(3, 15), values("balance", money("1200"))).build());
    }

    /**
     * RD-7. Recorded 12 October: ACME is renamed "ACME Holdings" from 1 January 2027.
     *
     * @param acme ACME
     * @return the recording
     */
    public static Recording rd7Rename(Ref acme) {
        return new Recording(on(10, 12), Transaction.by(PRINCIPAL)
                .patch(acme, EffectivePeriod.from(on(2027, 1, 1)), values("name", "ACME Holdings")).build());
    }

    /**
     * RD-8, the rejected end. Ending Beta from 1 June, while relationships to Beta continue after that date, such as
     * A-1's {@code HeldBy}. The story gives no recording date, so it is recorded on 20 May, after the stories that
     * precede it.
     *
     * @param beta Beta
     * @return the recording
     */
    public static Recording rd8EndBetaAlone(Ref beta) {
        return new Recording(on(5, 20), Transaction.by(PRINCIPAL).endDate(beta, on(6, 1)).build());
    }

    /**
     * RD-8, the successful end. Ending Beta from 1 June succeeds when the same transaction, from 1 June, moves A-1 to
     * ACME (ends {@code HeldBy} to Beta and starts {@code HeldBy} to ACME, named by {@link #HELD_BY_A1_MOVED}, keeping
     * exactly one holder), ends {@code Owns} and {@code Employs} involving Beta, and ends Beta's customer role
     * together with its {@code PlayedBy}. The story gives no recording date, so it is recorded on 20 May, like
     * {@link #rd8EndBetaAlone}, after the stories that precede it.
     *
     * @param beta         Beta
     * @param acme         ACME
     * @param a1           account A-1
     * @param heldByA1     the {@code HeldBy} relationship between A-1 and Beta (RD-5)
     * @param owns         the {@code Owns} relationship between ACME and Beta (RD-2)
     * @param employs      the {@code Employs} relationship between Beta and Alice (RD-3)
     * @param customerRole Beta's customer role (RD-4)
     * @param playedBy     the {@code PlayedBy} relationship between the role and Beta (RD-4)
     * @return the recording
     */
    public static Recording rd8EndBeta(Ref beta, Ref acme, Ref a1, Ref heldByA1, Ref owns, Ref employs,
            Ref customerRole, Ref playedBy) {
        return new Recording(on(5, 20), Transaction.by(PRINCIPAL)
                .endDate(beta, on(6, 1))
                .endDate(heldByA1, on(6, 1))
                .relate(HELD_BY_A1_MOVED, "HeldBy", a1, acme, from(6, 1), values())
                .endDate(owns, on(6, 1))
                .endDate(employs, on(6, 1))
                .endDate(customerRole, on(6, 1))
                .endDate(playedBy, on(6, 1))
                .build());
    }

    /**
     * RD-9, the first part: ACME has the LEI {@link #LEI} from 1 January. The story gives no date, so it is recorded
     * on 5 January, the day ACME is.
     *
     * @param acme ACME
     * @return the recording
     */
    public static Recording rd9AcmeLei(Ref acme) {
        return new Recording(on(1, 5), Transaction.by(PRINCIPAL).patch(acme, from(1, 1), values("lei", LEI)).build());
    }

    /**
     * RD-9, the second part: another organization, Gamma, named by {@link #GAMMA}, with the same LEI from 1 March.
     * The story gives no date, so it is recorded on 1 February. It is rejected while ACME's LEI is effective.
     *
     * @return the recording
     */
    public static Recording rd9Gamma() {
        return new Recording(on(2, 1), Transaction.by(PRINCIPAL)
                .create(GAMMA, "Organization", from(3, 1), values("name", "Gamma", "lei", LEI)).build());
    }

    /**
     * RD-12. Recorded 1 March: creating Gamma, an organization with the LEI {@link #LEI_WRONG_CHECK_DIGITS}, is
     * rejected, because its check digits are wrong. Gamma is named by {@link #GAMMA}. The story gives no effective
     * date, so Gamma is effective from the day it is recorded.
     *
     * @return the recording
     */
    public static Recording rd12GammaWrongCheckDigits() {
        return new Recording(on(3, 1), Transaction.by(PRINCIPAL)
                .create(GAMMA, "Organization", from(3, 1), values("name", "Gamma", "lei", LEI_WRONG_CHECK_DIGITS))
                .build());
    }

    /**
     * RD-13. Recorded 1 April: Gamma, an organization named by {@link #GAMMA}, is created and owns 50% of Beta from
     * 1 April, through a relationship named by {@link #GAMMA_OWNS_BETA}. It is rejected, because of the share of ACME
     * as known on 1 April, which the page of the reference domain states together with the total that results.
     *
     * @param beta Beta
     * @return the recording
     */
    public static Recording rd13GammaOwnsBeta(Ref beta) {
        return new Recording(on(4, 1), Transaction.by(PRINCIPAL)
                .create(GAMMA, "Organization", from(4, 1), values("name", "Gamma"))
                .relate(GAMMA_OWNS_BETA, "Owns", GAMMA, beta, from(4, 1), values("share", 50))
                .build());
    }

    // ------------------------------------------------------------------ replaying

    /**
     * Moves the clock to the recording date of a story and commits its transaction.
     *
     * @param store     the store whose clock is {@code clock}
     * @param clock     the store's clock
     * @param recording the transaction to commit
     * @return the result, committed or rejected
     */
    public static CommitResult attempt(Store store, TestClock clock, Recording recording) {
        clock.moveTo(recording.recordedAt());
        return store.commit(recording.transaction());
    }

    /**
     * Moves the clock to the recording date of a story and commits its transaction, which must be accepted.
     *
     * @param store     the store whose clock is {@code clock}
     * @param clock     the store's clock
     * @param recording the transaction to commit
     * @return the receipt
     * @throws IllegalStateException if the store rejects the transaction
     */
    public static Receipt replay(Store store, TestClock clock, Recording recording) {
        return switch (attempt(store, clock, recording)) {
            case CommitResult.Committed committed -> committed.receipt();
            case CommitResult.Rejected rejected -> throw new IllegalStateException(
                    "The store rejected the story: " + rejected.problems());
        };
    }

    // ------------------------------------------------------------------ data shortcuts

    /**
     * Midnight UTC on a day of 2026.
     *
     * @param month the month, from 1
     * @param day   the day of the month, from 1
     * @return the instant
     */
    public static Instant on(int month, int day) {
        return on(2026, month, day);
    }

    /**
     * Midnight UTC on a day.
     *
     * @param year  the year
     * @param month the month, from 1
     * @param day   the day of the month, from 1
     * @return the instant
     */
    public static Instant on(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * The period that starts at midnight UTC on a day of 2026 and has no end.
     *
     * @param month the month, from 1
     * @param day   the day of the month, from 1
     * @return the period
     */
    public static EffectivePeriod from(int month, int day) {
        return EffectivePeriod.from(on(month, day));
    }

    /**
     * Values from alternating names and values. A value is given as a {@link Value}, or as a string, int, long,
     * {@link BigDecimal} or {@link LocalDate}, which stands for the value of that type.
     *
     * @param namesAndValues the names of attributes, each followed by its value
     * @return the values, in the order given
     * @throws IllegalArgumentException if a value is not of one of these types
     */
    public static Map<String, Value> values(Object... namesAndValues) {
        Map<String, Value> values = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            Object value = namesAndValues[i + 1];
            values.put((String) namesAndValues[i], switch (value) {
                case Value constant -> constant;
                case String text -> Value.of(text);
                case Integer number -> Value.of(number);
                case Long number -> Value.of(number);
                case BigDecimal number -> Value.of(number);
                case LocalDate date -> Value.of(date);
                default -> throw new IllegalArgumentException("Not a value: " + value);
            });
        }
        return values;
    }

    /**
     * An amount of money, which is a decimal.
     *
     * @param amount the amount in plain notation, such as {@code 1500}
     * @return the decimal
     */
    public static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}
