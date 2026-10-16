package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.data.Change;
import io.github.enricoag1982.cairn.data.Changed;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.KeyResolution;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.PurgeChange;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.SchemaChange;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.data.TransactionChange;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.documents.WrittenNode.Mapping;
import io.github.enricoag1982.cairn.documents.WrittenNode.Plain;
import io.github.enricoag1982.cairn.documents.WrittenNode.Sequence;
import io.github.enricoag1982.cairn.documents.WrittenNode.Text;
import io.github.enricoag1982.cairn.query.Cell;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.query.Row;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.SchemaTransaction;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds the {@linkplain WrittenNode tree} of the JSON forms of results (capability {@code json-payloads}, design D4):
 * receipts, entity and relationship states, links, steps, problems, query results and the changes of the change feed.
 *
 * <p>The keys of a mapping are the names of the components of the Java record it stands for, in the order of the
 * components. A change is the exception: it has the field {@code kind} besides, and the {@code principal},
 * {@code onBehalfOf}, {@code reason} and {@code origin} of its record are at its own level. A component that is an empty {@code Optional}, an empty map or an empty list is left out, except the
 * {@code values} of a state, which are always written; so a state lists the {@code withheld} attributes only when a view
 * restricted to a clearance withheld some (ADR-0036). IDs are strings,
 * a temporary ID has its {@code $}, and where the kind of an ID is not fixed by the field, in a receipt, it is written as
 * {@code {"entity": "<id>"}} or {@code {"relationship": "<id>"}}. Instants are ISO 8601 strings in UTC, dates are ISO 8601
 * dates, and values are written as {@link DocumentWriter#literal} writes them.
 */
final class JsonResults {

    private JsonResults() {
    }

    // ------------------------------------------------------------------ results

    static Mapping receipt(Receipt receipt) {
        Mapping mapping = new Mapping()
                .put("basis", plain(receipt.basis().value()))
                .put("transactionTime", receipt.transactionTime().toString());
        if (!receipt.temporaryIds().isEmpty()) {
            Mapping temporaryIds = new Mapping();
            receipt.temporaryIds().forEach((temporaryId, id) -> temporaryIds.put(temporaryId.toString(), id(id)));
            mapping.put("temporaryIds", temporaryIds);
        }
        ids(mapping, "created", receipt.created());
        ids(mapping, "ended", receipt.ended());
        if (!receipt.keys().isEmpty()) {
            mapping.put("keys", list(receipt.keys(), JsonResults::keyResolution));
        }
        mapping.put("principal", receipt.principal());
        receipt.onBehalfOf().ifPresent(party -> mapping.put("onBehalfOf", party));
        receipt.reason().ifPresent(reason -> mapping.put("reason", reason));
        receipt.idempotencyKey().ifPresent(key -> mapping.put("idempotencyKey", key));
        return mapping;
    }

    /**
     * What a unique key resolved to: {@code {write, key, entity}}, the key in the form that payloads write it, such as
     * {@code {"entityType": "Account", "key": {"number": "A-1"}}}.
     */
    private static Mapping keyResolution(KeyResolution resolution) {
        UniqueKey key = resolution.key();
        return new Mapping()
                .put("write", plain(resolution.write()))
                .put("key", new Mapping()
                        .put("entityType", key.entityType())
                        .put("key", new Mapping().put(key.attribute(), DocumentWriter.literal(key.value()))))
                .put("entity", resolution.entity().value());
    }

    static Mapping entityState(EntityState state) {
        Mapping mapping = new Mapping()
                .put("id", state.id().value())
                .put("entityType", state.entityType());
        values(mapping, state.values());
        mapping
                .put("period", period(state.period()))
                .put("recordedAt", state.recordedAt().toString())
                .put("recordedBasis", plain(state.recordedBasis().value()));
        return withheld(mapping, state.withheld());
    }

    static Mapping relationshipState(RelationshipState state) {
        Mapping mapping = new Mapping()
                .put("id", state.id().value())
                .put("relationshipType", state.relationshipType())
                .put("source", state.source().value())
                .put("target", state.target().value());
        values(mapping, state.values());
        mapping
                .put("period", period(state.period()))
                .put("recordedAt", state.recordedAt().toString())
                .put("recordedBasis", plain(state.recordedBasis().value()));
        return withheld(mapping, state.withheld());
    }

    static Mapping link(Link link) {
        return new Mapping()
                .put("relationship", relationshipState(link.relationship()))
                .put("entity", entityState(link.entity()));
    }

    static Mapping step(Step step) {
        return new Mapping()
                .put("entity", entityState(step.entity()))
                .put("depth", plain(step.depth()))
                .put("via", relationshipState(step.via()));
    }

    /**
     * A change of the change feed (ADR-0029, design D7 of {@code add-change-feed}): {@code basis}, {@code transactionTime}
     * and {@code kind} ({@code transaction}, {@code schema} or {@code purge}), then the {@code principal},
     * {@code onBehalfOf} and {@code reason} of its record; for a schema registration its {@code origin} with {@code name}
     * and {@code version}; for a purge the numbers of {@code entities} and {@code relationships} it removed; for a data
     * transaction {@code changed}, a list of {@code {entity | relationship, type, created, periods}} with each period as
     * {@code {from, until}}. {@code created} is always written, as {@code false} too.
     */
    static Mapping change(Change change) {
        Mapping mapping = new Mapping()
                .put("basis", plain(change.basis().value()))
                .put("transactionTime", change.transactionTime().toString());
        switch (change) {
            case TransactionChange transaction -> {
                TransactionRecord record = transaction.transaction();
                mapping.put("kind", "transaction");
                record(mapping, record.principal(), record.onBehalfOf(), record.reason());
                if (!transaction.changed().isEmpty()) {
                    mapping.put("changed", list(transaction.changed(), JsonResults::changed));
                }
            }
            case SchemaChange schema -> {
                SchemaTransaction registration = schema.registration();
                mapping.put("kind", "schema");
                record(mapping, registration.principal(), registration.onBehalfOf(), registration.reason());
                mapping.put("origin", new Mapping()
                        .put("name", registration.origin().name())
                        .put("version", registration.origin().version()));
            }
            case PurgeChange purge -> {
                PurgeRecord record = purge.purge();
                mapping.put("kind", "purge");
                record(mapping, record.principal(), record.onBehalfOf(), Optional.of(record.reason()));
                mapping.put("entities", plain(record.entities()));
                mapping.put("relationships", plain(record.relationships()));
            }
        }
        return mapping;
    }

    private static void record(Mapping owner, String principal, Optional<String> onBehalfOf,
            Optional<String> reason) {
        owner.put("principal", principal);
        onBehalfOf.ifPresent(party -> owner.put("onBehalfOf", party));
        reason.ifPresent(text -> owner.put("reason", text));
    }

    /** An entity or relationship that a transaction changed: {@code {entity | relationship, type, created, periods}}. */
    private static Mapping changed(Changed changed) {
        Mapping mapping = new Mapping();
        switch (changed.id()) {
            case EntityId entity -> mapping.put("entity", entity.value());
            case RelationshipId relationship -> mapping.put("relationship", relationship.value());
        }
        return mapping
                .put("type", changed.type())
                .put("created", new Plain(Boolean.toString(changed.created())))
                .put("periods", list(changed.periods(), JsonResults::period));
    }

    static Mapping problem(Problem problem) {
        Mapping mapping = new Mapping()
                .put("code", problem.code().name())
                .put("path", problem.path())
                .put("message", problem.message());
        problem.expected().ifPresent(expected -> mapping.put("expected", expected));
        problem.found().ifPresent(found -> mapping.put("found", found));
        problem.hint().ifPresent(hint -> mapping.put("hint", hint));
        problem.location().ifPresent(location -> mapping.put("location", location(location)));
        problem.ruleCode().ifPresent(ruleCode -> mapping.put("ruleCode", ruleCode));
        return mapping;
    }

    /**
     * The result of a query: {@code {effectiveAt, basis, columns, rows}} for rows, each row a list of cells in the order
     * of the columns; the list of problems for a query that does not fit the schema.
     */
    static WrittenNode queryResult(QueryResult result) {
        return switch (result) {
            case QueryResult.Rows rows -> {
                Sequence columns = new Sequence(rows.columns().stream().<WrittenNode>map(Text::new).toList());
                List<WrittenNode> lines = new ArrayList<>();
                for (Row row : rows.rows()) {
                    List<WrittenNode> cells = new ArrayList<>();
                    for (String column : rows.columns()) {
                        cells.add(cell(row.cell(column)));
                    }
                    lines.add(new Sequence(cells));
                }
                yield new Mapping()
                        .put("effectiveAt", rows.effectiveAt().toString())
                        .put("basis", plain(rows.basis().value()))
                        .put("columns", columns)
                        .put("rows", new Sequence(lines));
            }
            case QueryResult.Invalid invalid -> list(invalid.problems(), JsonResults::problem);
        };
    }

    /** A cell: the state of an entity or a relationship, a value as literals are written, or {@code null}. */
    private static WrittenNode cell(Cell cell) {
        return switch (cell) {
            case Cell.EntityCell entity -> entityState(entity.state());
            case Cell.RelationshipCell relationship -> relationshipState(relationship.state());
            case Cell.ValueCell value -> DocumentWriter.literal(value.value());
            case Cell.Empty ignored -> new Plain("null");
        };
    }

    /** The items as a sequence of mappings, in order. */
    static <T> Sequence list(List<T> items, Function<T, Mapping> item) {
        return new Sequence(items.stream().<WrittenNode>map(item).toList());
    }

    // ------------------------------------------------------------------ parts

    private static Mapping location(DocumentLocation location) {
        return new Mapping()
                .put("pointer", location.pointer())
                .put("line", plain(location.line()))
                .put("column", plain(location.column()));
    }

    private static Mapping period(EffectivePeriod period) {
        Mapping mapping = new Mapping().put("from", period.from().toString());
        period.until().ifPresent(until -> mapping.put("until", until.toString()));
        return mapping;
    }

    /**
     * The names of the attributes whose values a view restricted to a clearance withheld (ADR-0036), as a list of strings
     * in the order of the schema, so that a reader tells a withheld value from no value; left out when nothing was
     * withheld.
     */
    private static Mapping withheld(Mapping owner, Set<String> withheld) {
        if (!withheld.isEmpty()) {
            owner.put("withheld", new Sequence(withheld.stream().<WrittenNode>map(Text::new).toList()));
        }
        return owner;
    }

    /**
     * The values in the order of the map, always: as {@code {}} when there are none, so that the {@code values} of any
     * state can be used as the {@code values} of a {@code replace} write, which needs them.
     */
    private static void values(Mapping owner, Map<String, Value> values) {
        Mapping mapping = new Mapping();
        values.forEach((name, value) -> mapping.put(name, DocumentWriter.literal(value)));
        owner.put("values", mapping);
    }

    private static void ids(Mapping owner, String key, List<Id> ids) {
        if (!ids.isEmpty()) {
            owner.put(key, new Sequence(ids.stream().<WrittenNode>map(JsonResults::id).toList()));
        }
    }

    /** An ID whose kind the field does not fix: {@code {"entity": "<id>"}} or {@code {"relationship": "<id>"}}. */
    private static Mapping id(Id id) {
        return switch (id) {
            case EntityId entity -> new Mapping().put("entity", entity.value());
            case RelationshipId relationship -> new Mapping().put("relationship", relationship.value());
        };
    }

    private static Plain plain(long number) {
        return new Plain(Long.toString(number));
    }
}
