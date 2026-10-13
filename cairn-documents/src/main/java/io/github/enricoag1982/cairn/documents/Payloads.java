package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.Schema;
import java.util.Objects;

/**
 * Reads transaction payloads (capability {@code json-payloads}).
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
}
