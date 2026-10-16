package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A set of writes that a store commits atomically, with who submits it, for whom, and why (ADR-0017, capabilities
 * {@code transactions}, {@code entity-versions} and {@code relationships}). Either every write is recorded or none is.
 *
 * <p>Build one with {@link #by(String)}. Two transactions are equal if their principal, party acted for, reason,
 * idempotency key, writes and clearance are, which is how a store recognizes a retry of a transaction with an
 * idempotency key.
 *
 * <p>A transaction may carry a {@linkplain Clearance clearance} (ADR-0036, capability {@code attribute-access}): a write
 * in it that sets or clears an attribute whose classification the clearance does not cover is rejected with a problem
 * {@code ACCESS_DENIED}, and so is a write that names an entity by a unique key of such an attribute. A transaction that
 * is rejected so gets those problems only: nothing is validated, derived, checked or run for it, so that no outcome can
 * be used to probe a value that it may not read. The problems that a transaction with a clearance gets about such
 * attributes, or from a check or derivation that reads them, carry no value of them. Rules, checks and derivations still
 * see every value. A transaction without a clearance writes as before; a caller that acts for a user or an agent must
 * give it one.
 *
 * <p>The record only holds what the caller wrote. A store checks it when it commits or runs it dry, and reports every
 * problem found; a transaction without a principal is a problem ({@code MISSING_PRINCIPAL}), not an exception.
 *
 * @param principal      who submits the transaction, for example {@code agent:onboarding}; empty if none was given
 * @param onBehalfOf     the party the principal acts for, if any
 * @param reason         why the transaction is submitted, if given
 * @param idempotencyKey a key that makes a retry of the transaction safe, if given
 * @param writes         the writes, applied in order
 * @param clearance      the classifications whose attributes the writes may set or clear; empty if the transaction has
 *                       no clearance and may write every attribute
 */
public record Transaction(
        String principal,
        Optional<String> onBehalfOf,
        Optional<String> reason,
        Optional<String> idempotencyKey,
        List<Write> writes,
        Optional<Clearance> clearance) {

    /**
     * Rejects null components, except that a null principal is the same as an empty one, which a store reports as a
     * missing principal, and copies the writes.
     */
    public Transaction {
        principal = principal == null ? "" : principal;
        Objects.requireNonNull(onBehalfOf, "onBehalfOf");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        writes = List.copyOf(writes);
        Objects.requireNonNull(clearance, "clearance");
    }

    /**
     * Creates a transaction without a clearance.
     *
     * @param principal      who submits the transaction; empty or null if none was given
     * @param onBehalfOf     the party the principal acts for, if any
     * @param reason         why the transaction is submitted, if given
     * @param idempotencyKey a key that makes a retry of the transaction safe, if given
     * @param writes         the writes, applied in order
     */
    public Transaction(String principal, Optional<String> onBehalfOf, Optional<String> reason,
            Optional<String> idempotencyKey, List<Write> writes) {
        this(principal, onBehalfOf, reason, idempotencyKey, writes, Optional.empty());
    }

    /**
     * Starts building a transaction.
     *
     * @param principal who submits the transaction; a null, empty or blank principal is rejected by the store
     * @return the builder
     */
    public static Builder by(String principal) {
        return new Builder(principal);
    }

    /**
     * Builds a {@link Transaction}. A builder is not safe for use by several threads. Every method returns the builder.
     */
    public static final class Builder {

        private final String principal;
        private Optional<String> onBehalfOf = Optional.empty();
        private Optional<String> reason = Optional.empty();
        private Optional<String> idempotencyKey = Optional.empty();
        private Optional<Clearance> clearance = Optional.empty();
        private final List<Write> writes = new ArrayList<>();

        private Builder(String principal) {
            this.principal = principal;
        }

        /**
         * Names the party the principal acts for.
         *
         * @param party the party, for example a person's identifier
         * @return this builder
         */
        public Builder onBehalfOf(String party) {
            this.onBehalfOf = Optional.of(Objects.requireNonNull(party, "party"));
            return this;
        }

        /**
         * Gives the reason for the transaction.
         *
         * @param reason the reason, in words
         * @return this builder
         */
        public Builder reason(String reason) {
            this.reason = Optional.of(Objects.requireNonNull(reason, "reason"));
            return this;
        }

        /**
         * Gives the transaction an idempotency key, so that submitting it again returns the first receipt instead of
         * recording it twice.
         *
         * @param key the key, chosen by the caller
         * @return this builder
         */
        public Builder idempotencyKey(String key) {
            this.idempotencyKey = Optional.of(Objects.requireNonNull(key, "key"));
            return this;
        }

        /**
         * Gives the transaction a clearance: a write in it that sets or clears an attribute whose classification the
         * clearance does not cover is rejected with {@code ACCESS_DENIED}, in a commit and in a dry run, with no other
         * problem. Rules, checks and derivations see every value whatever the clearance is.
         *
         * @param clearance the classifications whose attributes the writes may set or clear
         * @return this builder
         */
        public Builder clearance(Clearance clearance) {
            this.clearance = Optional.of(Objects.requireNonNull(clearance, "clearance"));
            return this;
        }

        /**
         * Adds a write of any kind.
         *
         * @param write the write
         * @return this builder
         */
        public Builder write(Write write) {
            writes.add(Objects.requireNonNull(write, "write"));
            return this;
        }

        /**
         * Adds a write that creates an entity with a generated ID.
         *
         * @param entityType the name or an alias of the entity type
         * @param period     when the entity is effective
         * @param values     the values of its attributes
         * @return this builder
         */
        public Builder create(String entityType, EffectivePeriod period, Map<String, Value> values) {
            return write(new Write.Create(Optional.empty(), Optional.empty(), entityType, period, values));
        }

        /**
         * Adds a write that creates an entity with a generated ID and a temporary ID, which other writes of this
         * transaction use to refer to it.
         *
         * @param temporaryId the temporary ID
         * @param entityType  the name or an alias of the entity type
         * @param period      when the entity is effective
         * @param values      the values of its attributes
         * @return this builder
         */
        public Builder create(TemporaryId temporaryId, String entityType, EffectivePeriod period,
                Map<String, Value> values) {
            return write(new Write.Create(Optional.of(Objects.requireNonNull(temporaryId, "temporaryId")),
                    Optional.empty(), entityType, period, values));
        }

        /**
         * Adds a write that creates an entity with an ID supplied by the caller.
         *
         * @param id         the ID, in the format of the store's generator
         * @param entityType the name or an alias of the entity type
         * @param period     when the entity is effective
         * @param values     the values of its attributes
         * @return this builder
         */
        public Builder create(EntityId id, String entityType, EffectivePeriod period, Map<String, Value> values) {
            return write(new Write.Create(Optional.empty(), Optional.of(Objects.requireNonNull(id, "id")),
                    entityType, period, values));
        }

        /**
         * Adds a write that creates an entity with an ID supplied by the caller and a temporary ID.
         *
         * @param temporaryId the temporary ID
         * @param id          the ID, in the format of the store's generator
         * @param entityType  the name or an alias of the entity type
         * @param period      when the entity is effective
         * @param values      the values of its attributes
         * @return this builder
         */
        public Builder create(TemporaryId temporaryId, EntityId id, String entityType, EffectivePeriod period,
                Map<String, Value> values) {
            return write(new Write.Create(Optional.of(Objects.requireNonNull(temporaryId, "temporaryId")),
                    Optional.of(Objects.requireNonNull(id, "id")), entityType, period, values));
        }

        /**
         * Adds a write that relates two entities, with a generated ID.
         *
         * @param relationshipType the name or an alias of the relationship type
         * @param source           the source entity, by its ID, temporary ID or unique key
         * @param target           the target entity, by its ID, temporary ID or unique key
         * @param period           when the relationship is effective
         * @param values           the values of its attributes
         * @return this builder
         */
        public Builder relate(String relationshipType, Ref source, Ref target, EffectivePeriod period,
                Map<String, Value> values) {
            return write(new Write.Relate(Optional.empty(), Optional.empty(), relationshipType, source, target,
                    period, values));
        }

        /**
         * Adds a write that relates two entities, with a generated ID and a temporary ID, which other writes of this
         * transaction use to refer to the relationship.
         *
         * @param temporaryId      the temporary ID
         * @param relationshipType the name or an alias of the relationship type
         * @param source           the source entity, by its ID, temporary ID or unique key
         * @param target           the target entity, by its ID, temporary ID or unique key
         * @param period           when the relationship is effective
         * @param values           the values of its attributes
         * @return this builder
         */
        public Builder relate(TemporaryId temporaryId, String relationshipType, Ref source, Ref target,
                EffectivePeriod period, Map<String, Value> values) {
            return write(new Write.Relate(Optional.of(Objects.requireNonNull(temporaryId, "temporaryId")),
                    Optional.empty(), relationshipType, source, target, period, values));
        }

        /**
         * Adds a write that relates two entities, with an ID supplied by the caller.
         *
         * @param id               the ID, in the format of the store's generator
         * @param relationshipType the name or an alias of the relationship type
         * @param source           the source entity, by its ID, temporary ID or unique key
         * @param target           the target entity, by its ID, temporary ID or unique key
         * @param period           when the relationship is effective
         * @param values           the values of its attributes
         * @return this builder
         */
        public Builder relate(RelationshipId id, String relationshipType, Ref source, Ref target,
                EffectivePeriod period, Map<String, Value> values) {
            return write(new Write.Relate(Optional.empty(), Optional.of(Objects.requireNonNull(id, "id")),
                    relationshipType, source, target, period, values));
        }

        /**
         * Adds a write that relates two entities, with an ID supplied by the caller and a temporary ID.
         *
         * @param temporaryId      the temporary ID
         * @param id               the ID, in the format of the store's generator
         * @param relationshipType the name or an alias of the relationship type
         * @param source           the source entity, by its ID, temporary ID or unique key
         * @param target           the target entity, by its ID, temporary ID or unique key
         * @param period           when the relationship is effective
         * @param values           the values of its attributes
         * @return this builder
         */
        public Builder relate(TemporaryId temporaryId, RelationshipId id, String relationshipType, Ref source,
                Ref target, EffectivePeriod period, Map<String, Value> values) {
            return write(new Write.Relate(Optional.of(Objects.requireNonNull(temporaryId, "temporaryId")),
                    Optional.of(Objects.requireNonNull(id, "id")), relationshipType, source, target, period, values));
        }

        /**
         * Adds a patch without a conflict check that sets attributes for a period.
         *
         * @param target the entity or relationship, by its ID or temporary ID, or an entity by its unique key
         * @param period the period the patch applies to
         * @param set    the attributes to set, with their new values
         * @return this builder
         */
        public Builder patch(Ref target, EffectivePeriod period, Map<String, Value> set) {
            return patch(target, period, set, Set.of(), ConflictCheck.none());
        }

        /**
         * Adds a patch with a conflict check that sets attributes for a period.
         *
         * @param target the entity or relationship, by its ID or temporary ID, or an entity by its unique key
         * @param period the period the patch applies to
         * @param set    the attributes to set, with their new values
         * @param check  the conflict check
         * @return this builder
         */
        public Builder patch(Ref target, EffectivePeriod period, Map<String, Value> set, ConflictCheck check) {
            return patch(target, period, set, Set.of(), check);
        }

        /**
         * Adds a patch that sets some attributes and clears others for a period.
         *
         * @param target the entity or relationship, by its ID or temporary ID, or an entity by its unique key
         * @param period the period the patch applies to
         * @param set    the attributes to set, with their new values
         * @param clear  the optional attributes to clear
         * @param check  the conflict check
         * @return this builder
         */
        public Builder patch(Ref target, EffectivePeriod period, Map<String, Value> set, Set<String> clear,
                ConflictCheck check) {
            return write(new Write.Patch(target, period, set, clear, check));
        }

        /**
         * Adds a replacement without a conflict check.
         *
         * @param target the entity or relationship, by its ID or temporary ID, or an entity by its unique key
         * @param period the period the replacement applies to
         * @param values the complete values of the entity's attributes
         * @return this builder
         */
        public Builder replace(Ref target, EffectivePeriod period, Map<String, Value> values) {
            return replace(target, period, values, ConflictCheck.none());
        }

        /**
         * Adds a replacement with a conflict check.
         *
         * @param target the entity or relationship, by its ID or temporary ID, or an entity by its unique key
         * @param period the period the replacement applies to
         * @param values the complete values of the entity's attributes
         * @param check  the conflict check
         * @return this builder
         */
        public Builder replace(Ref target, EffectivePeriod period, Map<String, Value> values, ConflictCheck check) {
            return write(new Write.Replace(target, period, values, check));
        }

        /**
         * Adds an end-date without a conflict check.
         *
         * @param target the entity or relationship, by its ID or temporary ID, or an entity by its unique key
         * @param from   the first instant at which the entity or relationship no longer exists
         * @return this builder
         */
        public Builder endDate(Ref target, Instant from) {
            return endDate(target, from, ConflictCheck.none());
        }

        /**
         * Adds an end-date with a conflict check.
         *
         * @param target the entity or relationship, by its ID or temporary ID, or an entity by its unique key
         * @param from   the first instant at which the entity or relationship no longer exists
         * @param check  the conflict check
         * @return this builder
         */
        public Builder endDate(Ref target, Instant from, ConflictCheck check) {
            return write(new Write.EndDate(target, from, check));
        }

        /**
         * Builds the transaction from what has been added so far.
         *
         * @return the transaction
         */
        public Transaction build() {
            return new Transaction(principal, onBehalfOf, reason, idempotencyKey, writes, clearance);
        }
    }
}
