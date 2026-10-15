/**
 * The public API of Cairn.
 */
// The name follows the io.github.<user> convention; the user name ends in digits by necessity.
@SuppressWarnings("module")
module io.github.enricoag1982.cairn {
    exports io.github.enricoag1982.cairn.data;
    exports io.github.enricoag1982.cairn.expressions;
    exports io.github.enricoag1982.cairn.query;
    exports io.github.enricoag1982.cairn.schema;
}
