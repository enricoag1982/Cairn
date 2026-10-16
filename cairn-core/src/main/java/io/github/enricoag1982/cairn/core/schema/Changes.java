package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.schema.SchemaChange;
import java.util.Optional;

/** Creates classified differences between two versions of an origin's types (design D7). */
final class Changes {

    /** The hint for a change that makes existing data or users face something stricter. */
    static final String KEEP_UNCONSTRAINED = "Keep it optional or unconstrained until migrations exist.";

    /** The hint for a change that would leave versions that are stored already without the values it derives. */
    static final String DERIVE_WITH_THE_TYPE =
            "Declare a derived attribute together with its type: versions that are stored are not derived again until "
                    + "migrations exist.";

    /** The hint for a type that no longer lets its relationships outlive their targets. */
    static final String KEEP_OUTLIVING_TARGETS =
            "Keep outlivesTarget while stored relationships may continue after their targets have ended.";

    /** The hint for the removal of a type or attribute. */
    static final String DEPRECATE_INSTEAD =
            "Mark it deprecated instead of removing it, or, if it was renamed, keep the old name as an alias.";

    private Changes() {
    }

    static SchemaChange compatible(String path, String description) {
        return new SchemaChange(path, description, true, Optional.empty());
    }

    static SchemaChange breaking(String path, String description) {
        return new SchemaChange(path, description, false, Optional.empty());
    }

    static SchemaChange breaking(String path, String description, String hint) {
        return new SchemaChange(path, description, false, Optional.of(hint));
    }
}
