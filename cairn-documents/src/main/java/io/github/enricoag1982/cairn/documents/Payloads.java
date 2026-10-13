package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.Schema;
import java.util.List;
import java.util.Objects;

/**
 * Reads transaction payloads and writes results as JSON (capability {@code json-payloads}).
 *
 * <p>A transaction payload is YAML 1.2, or JSON, which is valid YAML 1.2: a mapping with the key {@code cairn}, whose
 * value is the format version 1, the key {@code principal}, the optional keys {@code onBehalfOf}, {@code reason} and
 * {@code idempotencyKey}, the key {@code writes}, a list of writes, and {@code $schema}, which names a JSON Schema for
 * editors and which reading ignores. Each write is a mapping with one key that names its kind, whose value holds the
 * fields of the kind:
 *
 * <ul>
 * <li>{@code create}: {@code temporaryId}, {@code id}, {@code entityType}, {@code from}, {@code until} and
 *     {@code values};
 * <li>{@code relate}: {@code temporaryId}, {@code id}, {@code relationshipType}, {@code source}, {@code target},
 *     {@code from}, {@code until} and {@code values};
 * <li>{@code patch}: {@code target}, {@code from}, {@code until}, {@code set}, {@code clear} and
 *     {@code conflictCheck};
 * <li>{@code replace}: {@code target}, {@code from}, {@code until}, {@code values} and {@code conflictCheck};
 * <li>{@code endDate}: {@code target}, {@code from} and {@code conflictCheck}.
 * </ul>
 *
 * <p>A reference ({@code source}, {@code target}) is written as {@code $} followed by a temporary ID, or as an ID. The
 * {@code temporaryId} of a {@code create} or {@code relate} is written the same way, or as the name alone. Instants are
 * ISO 8601 UTC strings. A conflict check is {@code {perEntity: <basis>}} or {@code {perAttribute: <basis>}}, with the
 * basis as a whole number. Numbers are written as JSON writes them.
 *
 * <p>The {@code toJson} methods and their plural forms write receipts, entity and relationship states, links, steps and
 * problems as JSON, with two-space indentation and a line break at the end. Each form has the field names of the Java
 * record it stands for, in the order of the record's components; a field that is an empty {@code Optional}, an empty map
 * or an empty list is left out. IDs are strings, and a temporary ID has its {@code $}. Instants are ISO 8601 strings in
 * UTC, dates are ISO 8601 dates, and values are written as schema documents write literals: numbers as JSON numbers
 * (a decimal with every digit and its scale), booleans as {@code true} or {@code false}, and strings, dates and instants
 * as strings. The {@code values} of an entity state or relationship state, written this way, can be used as the
 * {@code values} of a {@code replace} write.
 */
public final class Payloads {

    private Payloads() {
    }

    /**
     * Reads a transaction payload into a transaction, without a lookup of IDs: every ID that the payload does not
     * itself create is read as an entity ID, and the values of a {@code patch} or {@code replace} of one are read by
     * their own YAML types. See {@link #readTransaction(String, Schema, IdLookup)}.
     *
     * @param text   the payload, as YAML or JSON
     * @param schema the schema that types the values of the writes
     * @return the transaction with its locations, or the problems
     */
    public static PayloadReadResult readTransaction(String text, Schema schema) {
        return readTransaction(text, schema, IdLookup.none());
    }

    /**
     * Reads a transaction payload into a transaction.
     *
     * <p>Reading is strict, like reading a schema document. It reports every problem it finds, each with its location
     * in the text, and returns no transaction if there is one: an unknown key (with the closest known key as a hint; a
     * write whose key is no kind of write has a hint that lists the kinds, the closest first), a missing key that is
     * required, a value of the wrong kind (a number written in a form that only YAML knows is one), and YAML features
     * outside the supported subset (anchors, aliases, explicit tags, merge keys, duplicate keys and more than one
     * document). A YAML syntax error stops reading and is the only problem reported. A format version other than 1 also
     * ends reading with that problem alone.
     *
     * <p>Values are read by the value type of the attribute they belong to, as schema documents read literals: in the
     * entity type of a {@code create}, the relationship type of a {@code relate}, and for a {@code patch} or
     * {@code replace} the type of its target, which is found from the payload (a temporary ID or an ID that an earlier
     * write creates) or from the lookup. Attribute names are matched by name or alias without regard to case, as the
     * engine matches them, and are kept as written. A value that does not fit the type of its attribute, or whose
     * attribute is unknown, is not a problem of reading: it is read as a value of its own YAML type, and the validation
     * of the transaction reports it.
     *
     * <p>A reference that is not a temporary ID becomes an {@code EntityId} or a {@code RelationshipId}, as the payload
     * or the lookup says, and an {@code EntityId} if neither knows it, so that the transaction's validation reports it.
     *
     * @param text   the payload, as YAML or JSON
     * @param schema the schema that types the values of the writes, normally the schema of the store that commits the
     *               transaction
     * @param lookup what the IDs that the payload does not create name, for example {@link IdLookup#from the latest view
     *               of the store}
     * @return the transaction with its locations, or the problems
     */
    public static PayloadReadResult readTransaction(String text, Schema schema, IdLookup lookup) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(lookup, "lookup");
        return PayloadReader.read(text, schema, lookup);
    }

    // ------------------------------------------------------------------ results as JSON

    /**
     * Writes a receipt as JSON: {@code basis}, {@code transactionTime}, {@code temporaryIds}, {@code created},
     * {@code ended}, {@code principal}, {@code onBehalfOf}, {@code reason} and {@code idempotencyKey}.
     *
     * <p>{@code temporaryIds} maps each temporary ID, with its {@code $}, to the ID that it names, as
     * {@code {"entity": "<id>"}} or {@code {"relationship": "<id>"}}, as do the items of {@code created} and
     * {@code ended}, because a receipt holds IDs of both kinds.
     *
     * @param receipt the receipt
     * @return the JSON text
     */
    public static String toJson(Receipt receipt) {
        return JsonText.render(JsonResults.receipt(Objects.requireNonNull(receipt, "receipt")));
    }

    /**
     * Writes the state of an entity as JSON: {@code id}, {@code entityType}, {@code values}, {@code period} (with
     * {@code from} and {@code until}), {@code recordedAt} and {@code recordedBasis}.
     *
     * @param state the state
     * @return the JSON text
     */
    public static String toJson(EntityState state) {
        return JsonText.render(JsonResults.entityState(Objects.requireNonNull(state, "state")));
    }

    /**
     * Writes the state of a relationship as JSON: {@code id}, {@code relationshipType}, {@code source}, {@code target},
     * {@code values}, {@code period} (with {@code from} and {@code until}), {@code recordedAt} and
     * {@code recordedBasis}.
     *
     * @param state the state
     * @return the JSON text
     */
    public static String toJson(RelationshipState state) {
        return JsonText.render(JsonResults.relationshipState(Objects.requireNonNull(state, "state")));
    }

    /**
     * Writes a link of a navigation as JSON: the {@code relationship} state and the {@code entity} state at its other
     * end.
     *
     * @param link the link
     * @return the JSON text
     */
    public static String toJson(Link link) {
        return JsonText.render(JsonResults.link(Objects.requireNonNull(link, "link")));
    }

    /**
     * Writes a step of a traversal as JSON: the {@code entity} state that it reaches, its {@code depth}, and the
     * relationship state it came {@code via}.
     *
     * @param step the step
     * @return the JSON text
     */
    public static String toJson(Step step) {
        return JsonText.render(JsonResults.step(Objects.requireNonNull(step, "step")));
    }

    /**
     * Writes a problem as JSON: {@code code}, {@code path}, {@code message}, {@code expected}, {@code found},
     * {@code hint}, {@code ruleCode} and {@code location} (with {@code pointer}, {@code line} and {@code column}). The
     * details that the problem does not have are left out.
     *
     * @param problem the problem
     * @return the JSON text
     */
    public static String toJson(Problem problem) {
        return JsonText.render(JsonResults.problem(Objects.requireNonNull(problem, "problem")));
    }

    /**
     * Writes entity states, for example the timeline of an entity, as a JSON list in the order given. The list is
     * {@code []} if there are none.
     *
     * @param states the states
     * @return the JSON text
     * @see #toJson(EntityState)
     */
    public static String entityStatesToJson(List<EntityState> states) {
        return JsonText.render(JsonResults.list(Objects.requireNonNull(states, "states"), JsonResults::entityState));
    }

    /**
     * Writes relationship states, for example the timeline of a relationship, as a JSON list in the order given. The
     * list is {@code []} if there are none.
     *
     * @param states the states
     * @return the JSON text
     * @see #toJson(RelationshipState)
     */
    public static String relationshipStatesToJson(List<RelationshipState> states) {
        return JsonText.render(JsonResults.list(Objects.requireNonNull(states, "states"),
                JsonResults::relationshipState));
    }

    /**
     * Writes the links of a navigation as a JSON list in the order given. The list is {@code []} if there are none.
     *
     * @param links the links
     * @return the JSON text
     * @see #toJson(Link)
     */
    public static String linksToJson(List<Link> links) {
        return JsonText.render(JsonResults.list(Objects.requireNonNull(links, "links"), JsonResults::link));
    }

    /**
     * Writes the steps of a traversal as a JSON list in the order given. The list is {@code []} if there are none.
     *
     * @param steps the steps
     * @return the JSON text
     * @see #toJson(Step)
     */
    public static String stepsToJson(List<Step> steps) {
        return JsonText.render(JsonResults.list(Objects.requireNonNull(steps, "steps"), JsonResults::step));
    }

    /**
     * Writes problems, for example those of a rejected commit, as a JSON list in the order given. The list is
     * {@code []} if there are none.
     *
     * @param problems the problems
     * @return the JSON text
     * @see #toJson(Problem)
     */
    public static String problemsToJson(List<Problem> problems) {
        return JsonText.render(JsonResults.list(Objects.requireNonNull(problems, "problems"), JsonResults::problem));
    }
}
