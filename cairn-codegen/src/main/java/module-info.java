/**
 * The generator of Java types from schema documents, and its command line.
 */
// The name follows the io.github.<user> convention; the user name ends in digits by necessity.
@SuppressWarnings("module")
module io.github.enricoag1982.cairn.codegen {
    requires transitive io.github.enricoag1982.cairn;
    requires io.github.enricoag1982.cairn.documents;
    requires io.github.enricoag1982.cairn.core;

    exports io.github.enricoag1982.cairn.codegen;
}
