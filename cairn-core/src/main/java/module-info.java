/**
 * The Cairn engine. Only its entry points are exported.
 */
// The name follows the io.github.<user> convention; the user name ends in digits by necessity.
// re2j is an automatic module: its jar has no module descriptor, so its name comes from the file name (re2j-1.8.jar).
// The module that kv is exported to is built after this one, and the compiler does not find it: the "module" lint.
@SuppressWarnings({"module", "requires-automatic"})
module io.github.enricoag1982.cairn.core {
    requires transitive io.github.enricoag1982.cairn;
    requires re2j;

    exports io.github.enricoag1982.cairn.core;

    // The key-value interface and the factory of stores over a persistent one: the RocksDB store is their only user
    // (ADR-0028, design D1 of add-rocksdb-store).
    exports io.github.enricoag1982.cairn.core.kv to io.github.enricoag1982.cairn.rocksdb;

    // StoreSettings.Builder.discoverRules() loads the rules that other modules or jars provide.
    uses io.github.enricoag1982.cairn.data.Rule;

    // The settings and schema registries look for the expression language that runs checks and derivations (ADR-0030,
    // ADR-0031).
    uses io.github.enricoag1982.cairn.expressions.ExpressionLanguage;
}
