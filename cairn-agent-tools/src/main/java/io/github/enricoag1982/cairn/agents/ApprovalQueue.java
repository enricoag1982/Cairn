package io.github.enricoag1982.cairn.agents;

import io.github.enricoag1982.cairn.agents.ApprovalRequest.Kind;
import io.github.enricoag1982.cairn.agents.ApprovalRequest.Status;
import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.schema.Submission;
import java.security.SecureRandom;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The approval requests of a store served to agents, in memory (ADR-0017, commitment 8, design D4 of
 * {@code add-agent-tools}, capability {@code agent-tools}). A request that the {@link AgentPolicy} sends to a person
 * waits here. People list, approve and reject the requests through this class; agents see them through the tool
 * {@code cairn_approval_status}.
 *
 * <p>Approving a request applies it then, as if the agent had submitted it at that moment: against the store as it is,
 * and with the principal, the party acted for and the reason that the request records. A request that is not valid
 * any more is approved all the same, and its outcome holds the problems. The queue records who approved it. A commit is
 * applied with the clearance of the agent that asked for it (ADR-0036), which the request records.
 *
 * <p>A queue belongs to one store, which it applies approved requests to; give the same queue and the same store to
 * the {@link AgentTools} that submit to it. A queue is safe for use by several threads.
 */
public final class ApprovalQueue {

    private final Operations operations;
    private final InstantSource clock;
    private final IdGenerator ids;
    /** The requests by ID, in the order of submission. */
    private final Map<String, ApprovalRequest> requests = new LinkedHashMap<>();

    /**
     * Creates an empty queue that reads the system clock.
     *
     * @param store the store that approved requests are applied to
     */
    public ApprovalQueue(Store store) {
        this(store, InstantSource.system());
    }

    /**
     * Creates an empty queue.
     *
     * @param store the store that approved requests are applied to
     * @param clock the source of the times at which requests are submitted and decided
     */
    public ApprovalQueue(Store store, InstantSource clock) {
        this.operations = new Operations(store);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = IdGenerators.uuidV7(clock, new SecureRandom());
    }

    /**
     * Lists the requests that wait for a person.
     *
     * @return the pending requests, oldest first
     */
    public synchronized List<ApprovalRequest> pending() {
        return requests.values().stream().filter(request -> request.status() == Status.PENDING).toList();
    }

    /**
     * Lists every request, pending or decided.
     *
     * @return the requests, oldest first
     */
    public synchronized List<ApprovalRequest> all() {
        return List.copyOf(requests.values());
    }

    /**
     * Finds a request.
     *
     * @param id the ID of the request
     * @return the request as it stands, or empty if the queue has none with that ID
     */
    public synchronized Optional<ApprovalRequest> find(String id) {
        return Optional.ofNullable(requests.get(Objects.requireNonNull(id, "id")));
    }

    /**
     * Approves a request and applies it now: the schema document is registered, or the transaction committed, as if
     * the agent had submitted it at this moment. The result, applied or rejected with problems, is the request's
     * {@linkplain ApprovalRequest#outcome() outcome}; the request is approved either way.
     *
     * @param id       the ID of a pending request
     * @param approver who approves it, for example the name of a person; recorded on the request
     * @return the request as approved
     * @throws IllegalArgumentException if the queue has no request with that ID, or the approver is blank
     * @throws IllegalStateException    if the request is not pending any more
     */
    public synchronized ApprovalRequest approve(String id, String approver) {
        ApprovalRequest request = pendingRequest(id, approver);
        ObjectNode outcome = switch (request.kind()) {
            case SCHEMA_REGISTRATION -> operations.registerSchema(request.content(),
                    new Submission(request.principal(), request.onBehalfOf(), request.reason()));
            case DATA_COMMIT -> operations.commit(request.content(), request.principal(), request.onBehalfOf(),
                    request.clearance().orElse(Clearance.none()));
        };
        return decide(request, Status.APPROVED, outcome, approver);
    }

    /**
     * Rejects a request. Nothing is applied. The reason becomes the request's
     * {@linkplain ApprovalRequest#outcome() outcome}, so that the agent can read it.
     *
     * @param id       the ID of a pending request
     * @param approver who rejects it; recorded on the request
     * @param reason   why, in words
     * @return the request as rejected
     * @throws IllegalArgumentException if the queue has no request with that ID, or the approver is blank
     * @throws IllegalStateException    if the request is not pending any more
     */
    public synchronized ApprovalRequest reject(String id, String approver, String reason) {
        Objects.requireNonNull(reason, "reason");
        ApprovalRequest request = pendingRequest(id, approver);
        ObjectNode outcome = Json.object().put("outcome", "declined").put("reason", reason);
        return decide(request, Status.REJECTED, outcome, approver);
    }

    // ------------------------------------------------------------------ submitting, for AgentTools

    /**
     * Puts the registration of a schema document to a person, unless it would be rejected or change nothing now.
     *
     * @return {@code {outcome: pending, request}}, or the outcome that ends the registration at once
     */
    synchronized ObjectNode requestSchemaRegistration(String document, Submission submission) {
        return operations.schemaPreflight(document).orElseGet(() ->
                pending(submit(Kind.SCHEMA_REGISTRATION, submission.principal(), submission.onBehalfOf(), document,
                        submission.reason(), Optional.empty())));
    }

    /**
     * Puts the commit of a transaction payload to a person, unless it would be rejected now. The request records the
     * party that the transaction is for: the one the payload names, or else {@code onBehalfOf}; and the clearance of the
     * agent, with which approving it commits the transaction.
     *
     * @return {@code {outcome: pending, request}}, or the rejection that ends the commit at once
     */
    synchronized ObjectNode requestCommit(String payload, String principal, Optional<String> onBehalfOf,
            Clearance clearance) {
        Operations.Preflight preflight = operations.commitPreflight(payload, principal, onBehalfOf, clearance);
        return preflight.outcome().orElseGet(() -> pending(submit(Kind.DATA_COMMIT, principal,
                preflight.onBehalfOf(), payload, preflight.reason(), Optional.of(clearance))));
    }

    Operations operations() {
        return operations;
    }

    /** Tells whether the queue applies its approved requests to this store. */
    boolean isFor(Store store) {
        return operations.store() == store;
    }

    // ------------------------------------------------------------------ helpers

    private ApprovalRequest submit(Kind kind, String principal, Optional<String> onBehalfOf, String content,
            Optional<String> reason, Optional<Clearance> clearance) {
        ApprovalRequest request = new ApprovalRequest(ids.next(), kind, principal, onBehalfOf, reason,
                clock.instant(), content, Status.PENDING, Optional.empty(), Optional.empty(), Optional.empty(),
                clearance);
        requests.put(request.id(), request);
        return request;
    }

    private ApprovalRequest pendingRequest(String id, String approver) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(approver, "approver");
        if (approver.isBlank()) {
            throw new IllegalArgumentException("The approver must not be blank.");
        }
        ApprovalRequest request = requests.get(id);
        if (request == null) {
            throw new IllegalArgumentException("No approval request has the ID " + id + ".");
        }
        if (request.status() != Status.PENDING) {
            throw new IllegalStateException("The approval request " + id + " has been decided already: it is "
                    + request.status().name().toLowerCase(Locale.ROOT) + ".");
        }
        return request;
    }

    private ApprovalRequest decide(ApprovalRequest before, Status status, ObjectNode outcome, String approver) {
        ApprovalRequest decided = new ApprovalRequest(before.id(), before.kind(), before.principal(),
                before.onBehalfOf(), before.reason(), before.submittedAt(), before.content(), status,
                Optional.of(Json.render(outcome)), Optional.of(approver), Optional.of(clock.instant()),
                before.clearance());
        requests.put(decided.id(), decided);
        return decided;
    }

    private static ObjectNode pending(ApprovalRequest request) {
        return Json.object().put("outcome", "pending").set("request", toJson(request));
    }

    /** The JSON form of a request; the outcome, which is JSON text, is embedded as JSON. */
    static ObjectNode toJson(ApprovalRequest request) {
        ObjectNode node = Json.object()
                .put("id", request.id())
                .put("kind", request.kind() == Kind.SCHEMA_REGISTRATION ? "schemaRegistration" : "dataCommit")
                .put("status", request.status().name().toLowerCase(Locale.ROOT))
                .put("principal", request.principal());
        request.onBehalfOf().ifPresent(party -> node.put("onBehalfOf", party));
        request.reason().ifPresent(reason -> node.put("reason", reason));
        node.put("submittedAt", request.submittedAt().toString());
        request.clearance().ifPresent(clearance -> {
            ArrayNode classifications = node.putArray("clearance");
            clearance.classifications().forEach(classifications::add);
        });
        node.put("content", request.content());
        request.outcome().ifPresent(outcome -> node.set("outcome", Json.parse(outcome)));
        request.decidedBy().ifPresent(who -> node.put("decidedBy", who));
        request.decidedAt().ifPresent(time -> node.put("decidedAt", time.toString()));
        return node;
    }
}
