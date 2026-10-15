package io.github.enricoag1982.cairn.agents;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A request of an agent that waits for a person under the {@link AgentPolicy}, or has been decided (ADR-0017,
 * commitment 8, design D4 of {@code add-agent-tools}, capability {@code agent-tools}). It is a snapshot: deciding it
 * in the {@link ApprovalQueue} produces a new value.
 *
 * @param id          the ID of the request, a UUIDv7
 * @param kind        what the request asks for
 * @param principal   the principal of the adapter that submitted it, which approving records on the transaction
 * @param onBehalfOf  the party the principal acts for, if any
 * @param reason      why the agent made the request, if it said: the reason given with a schema registration, or the
 *                    {@code reason} of a transaction payload
 * @param submittedAt when the request was submitted
 * @param content     the request as submitted: the text of the schema document or of the transaction payload
 * @param status      whether the request waits, was approved or was rejected
 * @param outcome     once decided, the result as JSON text: for an approved request, what registering or committing
 *                    it then did, such as {@code {"outcome": "committed", "receipt": ...}}, or the problems of a
 *                    request that was no longer valid; for a rejected one, the reason of the person; empty while the
 *                    request is pending
 * @param decidedBy   who approved or rejected the request; empty while it is pending
 * @param decidedAt   when it was decided; empty while it is pending
 */
public record ApprovalRequest(
        String id,
        Kind kind,
        String principal,
        Optional<String> onBehalfOf,
        Optional<String> reason,
        Instant submittedAt,
        String content,
        Status status,
        Optional<String> outcome,
        Optional<String> decidedBy,
        Optional<Instant> decidedAt) {

    /** What a request asks for. */
    public enum Kind {
        /** Registering a schema document. */
        SCHEMA_REGISTRATION,
        /** Committing a transaction payload. */
        DATA_COMMIT
    }

    /** Where a request stands. */
    public enum Status {
        /** The request waits for a person. */
        PENDING,
        /** A person approved the request, and it was applied then; the outcome says what happened. */
        APPROVED,
        /** A person rejected the request, and nothing was applied. */
        REJECTED
    }

    /**
     * Rejects null components, and a request that is pending but has a decision or is decided without one.
     *
     * @throws IllegalArgumentException if {@code decidedBy} and {@code decidedAt} are not both present exactly when
     *                                  the status is not {@link Status#PENDING}, or if {@code outcome} is present while
     *                                  the request is pending
     */
    public ApprovalRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(onBehalfOf, "onBehalfOf");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(submittedAt, "submittedAt");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedAt, "decidedAt");
        boolean decided = status != Status.PENDING;
        if (decidedBy.isPresent() != decided || decidedAt.isPresent() != decided || (!decided && outcome.isPresent())) {
            throw new IllegalArgumentException("A request is decided, with who and when, exactly when it is not "
                    + "pending: " + status);
        }
    }
}
