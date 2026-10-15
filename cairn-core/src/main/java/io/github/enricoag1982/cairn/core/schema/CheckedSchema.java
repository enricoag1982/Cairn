package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.List;

/**
 * A {@link Schema} that keeps the checks of its types compiled (design D2 of {@code add-cel-expressions}). The schemas
 * of the registries of this module are checked schemas; a store runs the checks of the types it writes through this
 * interface.
 */
public interface CheckedSchema extends Schema {

    /**
     * The checks that apply to an entity type or relationship type, in the order of the type's effective checks.
     *
     * @param type the identifier of the type
     * @return the checks, none if the type has none or the schema has no such type
     */
    List<AppliedCheck> checks(TypeId type);
}
