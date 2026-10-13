/**
 * The Cairn engine. Only its entry points are exported.
 */
// The name follows the io.github.<user> convention; the user name ends in digits by necessity.
// re2j is an automatic module: its jar has no module descriptor, so its name comes from the file name (re2j-1.8.jar).
@SuppressWarnings({"module", "requires-automatic"})
module io.github.enricoag1982.cairn.core {
    requires transitive io.github.enricoag1982.cairn;
    requires re2j;

    exports io.github.enricoag1982.cairn.core;

    // StoreSettings.Builder.discoverRules() loads the rules that other modules or jars provide.
    uses io.github.enricoag1982.cairn.data.Rule;
}
