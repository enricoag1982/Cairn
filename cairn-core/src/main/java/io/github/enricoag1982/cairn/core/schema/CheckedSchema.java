package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.List;

/**
 * A {@link Schema} that keeps the checks and the derived attributes of its types compiled (design D2 of
 * {@code add-cel-expressions} and D3 of {@code add-derived-attributes}). The schemas of the registries of this module
 * are checked schemas; a store runs the checks and the derivations of the types it writes through this interface.
 */
public interface CheckedSchema extends Schema {

    /**
     * The checks that apply to an entity type or relationship type, in the order of the type's effective checks.
     *
     * @param type the identifier of the type
     * @return the checks, none if the type has none or the schema has no such type
     */
    List<AppliedCheck> checks(TypeId type);

    /**
     * The derived attributes of an entity type or relationship type, compiled, in the order of the type's attributes.
     *
     * @param type the identifier of the type
     * @return the derivations, none if the type has no derived attribute or the schema has no such type
     */
    List<AppliedDerivation> derivations(TypeId type);
}
