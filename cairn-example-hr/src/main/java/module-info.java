/**
 * The HR system of the fictional company Brightwater, built on Cairn's public API as an application would build it: its
 * schema document and the Java types generated from it, its rules, and the company's data as transactions.
 */
// The name follows the io.github.<user> convention; the user name ends in digits by necessity.
@SuppressWarnings("module")
module io.github.enricoag1982.cairn.example.hr {
    requires transitive io.github.enricoag1982.cairn;
    requires transitive io.github.enricoag1982.cairn.documents;

    exports io.github.enricoag1982.cairn.example.hr;
    exports io.github.enricoag1982.cairn.example.hr.model;
    exports io.github.enricoag1982.cairn.example.hr.rules;

    // The same rules are listed in META-INF/services for the class path, where the tests run.
    provides io.github.enricoag1982.cairn.data.Rule with
            io.github.enricoag1982.cairn.example.hr.rules.DecidedByManager,
            io.github.enricoag1982.cairn.example.hr.rules.LeaveDatesInOrder,
            io.github.enricoag1982.cairn.example.hr.rules.NoUnitCycles,
            io.github.enricoag1982.cairn.example.hr.rules.VacationWithinEntitlement;
}
