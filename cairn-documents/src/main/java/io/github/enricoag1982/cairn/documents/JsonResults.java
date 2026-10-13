package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.documents.WrittenNode.Mapping;
import io.github.enricoag1982.cairn.documents.WrittenNode.Plain;
import io.github.enricoag1982.cairn.documents.WrittenNode.Sequence;
import io.github.enricoag1982.cairn.documents.WrittenNode.Text;
import io.github.enricoag1982.cairn.query.Cell;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.query.Row;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the {@linkplain WrittenNode tree} of the JSON forms of results (capability {@code json-payloads}, design D4):
 * receipts, entity and relationship states, links, steps and problems.
 *
 * <p>The keys of a mapping are the names of the components of the Java record it stands for, in the order of the
 * components. A component that is an empty {@code Optional}, an empty map or an empty list is left out, except the
 * {@code values} of a state, which are always written. IDs are strings,
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
        mapping.put("principal", receipt.principal());
        receipt.onBehalfOf().ifPresent(party -> mapping.put("onBehalfOf", party));
        receipt.reason().ifPresent(reason -> mapping.put("reason", reason));
        receipt.idempotencyKey().ifPresent(key -> mapping.put("idempotencyKey", key));
        return mapping;
    }

    static Mapping entityState(EntityState state) {
        Mapping mapping = new Mapping()
                .put("id", state.id().value())
                .put("entityType", state.entityType());
        values(mapping, state.values());
        return mapping
                .put("period", period(state.period()))
                .put("recordedAt", state.recordedAt().toString())
                .put("recordedBasis", plain(state.recordedBasis().value()));
    }

    static Mapping relationshipState(RelationshipState state) {
        Mapping mapping = new Mapping()
                .put("id", state.id().value())
                .put("relationshipType", state.relationshipType())
                .put("source", state.source().value())
                .put("target", state.target().value());
        values(mapping, state.values());
        return mapping
                .put("period", period(state.period()))
                .put("recordedAt", state.recordedAt().toString())
                .put("recordedBasis", plain(state.recordedBasis().value()));
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
