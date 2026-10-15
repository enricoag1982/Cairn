package io.github.enricoag1982.cairn.agents;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.documents.DocumentReadResult;
import io.github.enricoag1982.cairn.documents.IdLookup;
import io.github.enricoag1982.cairn.documents.PayloadReadResult;
import io.github.enricoag1982.cairn.documents.Payloads;
import io.github.enricoag1982.cairn.documents.SchemaDocument;
import io.github.enricoag1982.cairn.documents.SchemaDocuments;
import io.github.enricoag1982.cairn.documents.TransactionPayload;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaChange;
import io.github.enricoag1982.cairn.schema.SchemaPlan;
import io.github.enricoag1982.cairn.schema.Submission;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the tools do to a store, without the approval policy: plan and register a schema document, dry-run and commit a
 * transaction payload (design D2, D4, D5 and D6 of {@code add-agent-tools}). Every method takes the text of a document
 * or payload, never throws for a text that is wrong, and returns the JSON of the outcome. People approving a request
 * run the same code as an agent whose request is allowed, so both get the same outcome for the same state.
 */
final class Operations {

    private final Store store;

    Operations(Store store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    Store store() {
        return store;
    }

    // ------------------------------------------------------------------ schema documents

    /** Plans the registration of a document: {@code {outcome, changes, problems}}. */
    ObjectNode planSchema(String text) {
        return switch (SchemaDocuments.read(text)) {
            case DocumentReadResult.Invalid invalid -> plan("rejected", List.of(), invalid.problems());
            case DocumentReadResult.Read read -> {
                SchemaDocument document = read.document();
                SchemaPlan plan = store.schema().plan(document.origin(), document.definition());
                yield plan(switch (plan.outcome()) {
                    case APPLY -> "wouldApply";
                    case UNCHANGED -> "unchanged";
                    case REJECT -> "rejected";
                }, plan.changes(), document.locate(plan.problems()));
            }
        };
    }

    /** Registers a document for a submission: {@code {outcome: applied | unchanged | rejected, ...}}. */
    ObjectNode registerSchema(String text, Submission submission) {
        return switch (SchemaDocuments.read(text)) {
            case DocumentReadResult.Invalid invalid -> rejected(invalid.problems());
            case DocumentReadResult.Read read -> {
                SchemaDocument document = read.document();
                yield switch (store.schema().register(document.origin(), document.definition(), submission)) {
                    case RegistrationResult.Applied applied -> Json.object()
                            .put("outcome", "applied")
                            .put("basis", applied.basis().value())
                            .put("transactionTime", applied.transactionTime().toString());
                    case RegistrationResult.Unchanged unchanged -> Json.object().put("outcome", "unchanged");
                    case RegistrationResult.Rejected rejected ->
                            rejected(document.locate(rejected.problems()));
                };
            }
        };
    }

    /**
     * The outcome that ends the registration of a document before it is put to a person: rejected, if registering it
     * now would be, or unchanged, if there is nothing to approve. Empty if the registration would be applied.
     */
    Optional<ObjectNode> schemaPreflight(String text) {
        ObjectNode plan = planSchema(text);
        return switch (plan.get("outcome").stringValue()) {
            case "wouldApply" -> Optional.empty();
            case "unchanged" -> Optional.of(Json.object().put("outcome", "unchanged"));
            default -> Optional.of(rejected(plan.get("problems")));
        };
    }

    /** The refusal of a registration that the approval policy denies, located at the root of the document. */
    ObjectNode denySchema(String text) {
        return switch (SchemaDocuments.read(text)) {
            case DocumentReadResult.Invalid invalid -> rejected(invalid.problems());
            case DocumentReadResult.Read read -> rejected(List.of(denied("@document",
                    "registering schema documents").at(read.document().locations().root())));
        };
    }

    // ------------------------------------------------------------------ transaction payloads

    /** Runs a payload dry: {@code {outcome: wouldCommit | rejected, problems?}}. */
    ObjectNode dryRun(String text, String principal, Optional<String> onBehalfOf) {
        return dryRun(prepare(text, principal, onBehalfOf));
    }

    private ObjectNode dryRun(Prepared prepared) {
        return switch (prepared) {
            case Prepared.Failed failed -> rejected(failed.problems());
            case Prepared.Ready ready -> switch (store.dryRun(ready.transaction())) {
                case DryRunResult.WouldCommit wouldCommit -> Json.object().put("outcome", "wouldCommit");
                case DryRunResult.Rejected rejected -> rejected(ready.payload().locate(rejected.problems()));
            };
        };
    }

    /** Commits a payload: {@code {outcome: committed | rejected, receipt?, problems?}}. */
    ObjectNode commit(String text, String principal, Optional<String> onBehalfOf) {
        return switch (prepare(text, principal, onBehalfOf)) {
            case Prepared.Failed failed -> rejected(failed.problems());
            case Prepared.Ready ready -> switch (store.commit(ready.transaction())) {
                case CommitResult.Committed committed -> Json.object()
                        .put("outcome", "committed")
                        .set("receipt", Json.parse(Payloads.toJson(committed.receipt())));
                case CommitResult.Rejected rejected -> rejected(ready.payload().locate(rejected.problems()));
            };
        };
    }

    /**
     * What a look at a payload finds before it is put to a person.
     *
     * @param outcome    the outcome that ends the commit at once: rejected, if a dry run of the payload is; empty if
     *                   the transaction would be committed
     * @param reason     the reason that the payload gives for the transaction, if it gives one
     * @param onBehalfOf the party the transaction is for: the one the payload names, or else the adapter's, if it has
     *                   one; this is what committing the transaction records
     */
    record Preflight(Optional<ObjectNode> outcome, Optional<String> reason, Optional<String> onBehalfOf) {
    }

    /** Runs a payload dry, and reads the reason and the party it gives, to put it to a person if the dry run passes. */
    Preflight commitPreflight(String text, String principal, Optional<String> onBehalfOf) {
        Prepared prepared = prepare(text, principal, onBehalfOf);
        ObjectNode dryRun = dryRun(prepared);
        if ("wouldCommit".equals(dryRun.get("outcome").stringValue())) {
            Transaction transaction = ((Prepared.Ready) prepared).transaction();
            return new Preflight(Optional.empty(), transaction.reason(), transaction.onBehalfOf());
        }
        return new Preflight(Optional.of(dryRun), Optional.empty(), Optional.empty());
    }

    /** The refusal of a commit that the approval policy denies, located at the root of the payload. */
    ObjectNode denyCommit(String text, String principal, Optional<String> onBehalfOf) {
        return switch (prepare(text, principal, onBehalfOf)) {
            case Prepared.Failed failed -> rejected(failed.problems());
            case Prepared.Ready ready -> rejected(ready.payload().locate(List.of(denied("@transaction",
                    "committing transactions"))));
        };
    }

    // ------------------------------------------------------------------ reading a payload

    private sealed interface Prepared {
        /** The payload was read, and names the adapter's principal. */
        record Ready(TransactionPayload payload, Transaction transaction) implements Prepared {
        }

        /** The payload cannot be submitted. */
        record Failed(List<Problem> problems) implements Prepared {
        }
    }

    /**
     * Reads a payload with the store's latest schema, the IDs it holds and the entities that its unique keys name, and
     * checks that it names the principal the adapter acts as. A payload that names no party acted for gets the adapter's, if it has one.
     */
    private Prepared prepare(String text, String principal, Optional<String> onBehalfOf) {
        View view = store.view(Coordinates.effectiveAt(Instant.EPOCH));
        return switch (Payloads.readTransaction(text, view.schema(), IdLookup.fromStore(store))) {
            case PayloadReadResult.Invalid invalid -> new Prepared.Failed(invalid.problems());
            case PayloadReadResult.Read read -> {
                TransactionPayload payload = read.payload();
                Transaction transaction = payload.transaction();
                if (!transaction.principal().equals(principal)) {
                    yield new Prepared.Failed(payload.locate(List.of(mismatch(transaction.principal(), principal))));
                }
                if (transaction.onBehalfOf().isEmpty() && onBehalfOf.isPresent()) {
                    transaction = new Transaction(transaction.principal(), onBehalfOf, transaction.reason(),
                            transaction.idempotencyKey(), transaction.writes());
                }
                yield new Prepared.Ready(payload, transaction);
            }
        };
    }

    // ------------------------------------------------------------------ outcomes and problems

    private static ObjectNode plan(String outcome, List<SchemaChange> changes, List<Problem> problems) {
        ObjectNode plan = Json.object().put("outcome", outcome);
        ArrayNode list = plan.putArray("changes");
        for (SchemaChange change : changes) {
            ObjectNode node = list.addObject()
                    .put("path", change.path())
                    .put("description", change.description())
                    .put("compatible", change.compatible());
            change.hint().ifPresent(hint -> node.put("hint", hint));
        }
        plan.set("problems", Json.problems(problems));
        return plan;
    }

    /** The plan of a call whose arguments are wrong: rejected, with no changes. */
    static ObjectNode rejectedPlan(List<Problem> problems) {
        return plan("rejected", List.of(), problems);
    }

    static ObjectNode rejected(List<Problem> problems) {
        return rejected(Json.problems(problems));
    }

    private static ObjectNode rejected(tools.jackson.databind.JsonNode problems) {
        return Json.object().put("outcome", "rejected").set("problems", problems);
    }

    /** A tool result that holds problems only, for a call that cannot be done. */
    static ObjectNode problemsOnly(List<Problem> problems) {
        return Json.object().set("problems", Json.problems(problems));
    }

    static Problem denied(String path, String operation) {
        return new Problem(ProblemCode.APPROVAL_DENIED, path,
                "The approval policy of this store denies " + operation + " to agents.", Optional.empty(),
                Optional.empty(), Optional.of("Ask a person to change the approval policy, or to make the change."));
    }

    private static Problem mismatch(String found, String expected) {
        return new Problem(ProblemCode.PRINCIPAL_MISMATCH, "@transaction.principal",
                "This adapter acts as '" + expected + "', but the payload names the principal '" + found + "'.",
                Optional.of(expected), Optional.of(found),
                Optional.of("Set principal to '" + expected + "', and name the party you act for in onBehalfOf."));
    }
}
