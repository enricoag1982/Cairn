/**
 * Schema documents: reading and writing them, locating problems in them, and the published JSON Schema. Transaction
 * payloads: reading them into transactions, locating problems in them, the JSON forms of results, and the JSON
 * Schemas generated from a schema.
 */
// The name follows the io.github.<user> convention; the user name ends in digits by necessity.
@SuppressWarnings("module")
module io.github.enricoag1982.cairn.documents {
    requires transitive io.github.enricoag1982.cairn;
    requires org.snakeyaml.engine;

    exports io.github.enricoag1982.cairn.documents;
}
