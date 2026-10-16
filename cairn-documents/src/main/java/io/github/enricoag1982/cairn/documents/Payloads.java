package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.data.Change;
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
 * <p>A reference ({@code source}, {@code target}) is written as {@code $} followed by a temporary ID, as an ID, or, for
 * an existing entity, as a unique key: {@code {entityType: Account, key: {number: A-1}}}, whose {@code key} has exactly one
 * entry, the name of a unique attribute and its value (ADR-0026). The
 * {@code temporaryId} of a {@code create} or {@code relate} is written the same way, or as the name alone. Instants are
 * ISO 8601 strings, such as {@code 2026-01-01T00:00:00Z}; an offset other than UTC, such as {@code +01:00}, is accepted
 * and converted to UTC. A conflict check is {@code {perEntity: <basis>}} or {@code {perAttribute: <basis>}}, with the
 * basis as a whole number. Numbers are written as JSON writes them.
 *
 * <p>The {@code toJson} methods and their plural forms write receipts, entity and relationship states, links, steps and
 * problems as JSON, with two-space indentation and a line break at the end. Each form has the field names of the Java
 * record it stands for, in the order of the record's components; a field that is an empty {@code Optional}, an empty map
 * or an empty list is left out, except the {@code values} of a state, which are always written, as {@code {}} when the
 * state has none. IDs are strings, and a temporary ID has its {@code $}. Instants are ISO 8601 strings in UTC, dates are
 * ISO 8601 dates, and values are written as schema documents write literals: numbers as JSON numbers (a decimal with
 * every digit and its scale), booleans as {@code true} or {@code false}, and strings, dates and instants as strings. The
 * {@code values} of an entity state or relationship state, written this way, can be used as the {@code values} of a
 * {@code replace} write, whatever the state holds.
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
     * <p>An instant, in the period of a write and in the value of an instant attribute, is an ISO 8601 string, such as
     * {@code 2026-01-01T00:00:00Z}. One with an offset other than UTC, such as {@code 2026-01-01T01:00:00+01:00}, is
     * accepted and converted to UTC.
     *
     * <p>A reference that is not a temporary ID or a unique key becomes an {@code EntityId} or a {@code RelationshipId},
     * as the payload or the lookup says, and an {@code EntityId} if neither knows it, so that the transaction's validation
     * reports it.
     *
     * <p>A reference that is a unique key becomes a {@link io.github.enricoag1982.cairn.data.UniqueKey}, which the store
     * resolves when it commits. Its value is read by the value type of the attribute in the named type, as the values of
     * writes are, or by its own YAML type if the schema does not have the type or the attribute. A mapping that has other
     * keys than {@code entityType} and {@code key}, lacks one of them, has a {@code key} that is no mapping or does not
     * have exactly one entry, or whose value is no literal, is a problem of reading, located at the reference or at its
     * {@code key}, as the shape that is wrong. Whether the type, the attribute and the value are valid, and whether an
     * entity has the value, is for the store to say. If the lookup knows the entity that the key names at the start of the
     * write's period, the values of a {@code patch} or {@code replace} are read by the type of that entity, and problems
     * about that entity, which name its ID, are located at the writes that name it by the key.
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

    /**
     * Reads a unique key that is written alone, in the form that payloads write it in a reference, such as
     * {@code {entityType: Account, key: {number: A-1}}}, for example from the arguments of an agent tool. It reads as a
     * reference of a payload does, and does not check the key against the schema: the type, the attribute and the value
     * are for a store or a view to check.
     *
     * @param text   the key, as YAML or JSON
     * @param schema the schema that types the value by the attribute it names
     * @param path   the path that every problem carries, such as {@code @arguments.key}
     * @return the key, or the problems, each located in the text
     */
    public static UniqueKeyReadResult readUniqueKey(String text, Schema schema, String path) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(path, "path");
        return PayloadReader.readKey(text, schema, path);
    }

    // ------------------------------------------------------------------ results as JSON

    /**
     * Writes a receipt as JSON: {@code basis}, {@code transactionTime}, {@code temporaryIds}, {@code created},
     * {@code ended}, {@code keys}, {@code principal}, {@code onBehalfOf}, {@code reason} and {@code idempotencyKey}.
     *
     * <p>{@code keys} lists what each unique key that a write names resolved to, in the order of the writes: the
     * position of the write ({@code write}), the {@code key} in the form that payloads write it, such as
     * {@code {"entityType": "Account", "key": {"number": "A-1"}}}, and the ID of the {@code entity} that it named.
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
     * Writes the state of an entity as JSON: {@code id}, {@code entityType}, {@code values} (always, as {@code {}} when
     * the state has none), {@code period} (with {@code from} and {@code until}), {@code recordedAt} and
     * {@code recordedBasis}, and, if a view restricted to a clearance withheld attributes, {@code withheld}: their names
     * (ADR-0036).
     *
     * @param state the state
     * @return the JSON text
     */
    public static String toJson(EntityState state) {
        return JsonText.render(JsonResults.entityState(Objects.requireNonNull(state, "state")));
    }

    /**
     * Writes the state of a relationship as JSON: {@code id}, {@code relationshipType}, {@code source}, {@code target},
     * {@code values} (always, as {@code {}} when the state has none, as for a relationship type without attributes),
     * {@code period} (with {@code from} and {@code until}), {@code recordedAt} and {@code recordedBasis}, and, if a view
     * restricted to a clearance withheld attributes, {@code withheld}: their names (ADR-0036).
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
     * {@code hint}, {@code location} (with {@code pointer}, {@code line} and {@code column}) and {@code ruleCode}, in
     * the order of the components of {@link Problem}. The details that the problem does not have are left out.
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
     * Writes a change of the change feed as JSON (ADR-0029, capability {@code json-payloads}): {@code basis},
     * {@code transactionTime} and {@code kind}, which is {@code transaction}, {@code schema} or {@code purge}; then the
     * {@code principal}, {@code onBehalfOf} and {@code reason} of the change's record. A schema registration has its
     * {@code origin}, with {@code name} and {@code version}; a purge has the numbers of {@code entities} and
     * {@code relationships} it removed; a data transaction has {@code changed}, a list of
     * {@code {entity | relationship, type, created, periods}} with the ID under {@code entity} or {@code relationship}, the
     * name of the type at the change's basis, and each period as {@code {from, until}}, {@code until} left out for a
     * period without an end. {@code created} is always written. Details without a value and empty lists, such as the
     * {@code changed} of a transaction that purges have left empty or one that changed nothing, are left out.
     *
     * @param change the change
     * @return the JSON text
     */
    public static String toJson(Change change) {
        return JsonText.render(JsonResults.change(Objects.requireNonNull(change, "change")));
    }

    /**
     * Writes changes of the change feed as a JSON list in the order given. The list is {@code []} if there are none.
     *
     * @param changes the changes
     * @return the JSON text
     * @see #toJson(Change)
     */
    public static String changesToJson(List<Change> changes) {
        return JsonText.render(JsonResults.list(Objects.requireNonNull(changes, "changes"), JsonResults::change));
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
