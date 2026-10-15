package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.schema.SchemaChange;
import java.util.Optional;

/** Creates classified differences between two versions of an origin's types (design D7). */
final class Changes {

    /** The hint for a change that makes existing data or users face something stricter. */
    static final String KEEP_UNCONSTRAINED = "Keep it optional or unconstrained until migrations exist.";

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
