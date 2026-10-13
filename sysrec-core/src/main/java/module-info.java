/**
 * The sysRec engine. Only its entry points are exported.
 */
// The name follows the io.github.<user> convention; the user name ends in digits by necessity.
@SuppressWarnings("module")
module io.github.enricoag1982.sysrec.core {
    requires transitive io.github.enricoag1982.sysrec;

    exports io.github.enricoag1982.sysrec.core;
}
