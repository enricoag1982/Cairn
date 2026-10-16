package io.github.enricoag1982.cairn.core.query;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.schema.Attribute;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a restricted view withholds, as the query checker asks it (ADR-0036, design D3 of
 * {@code add-attribute-access-control}): for an attribute of the view's schema, the classification that makes the view
 * withhold it, if there is one. A view decides this from its clearance, and, since it must withhold what the store has
 * classified since its basis too (design D2), from the store's latest schema as well, so the checker asks the view
 * instead of reading the clearance.
 */
public interface Withholding {

    /**
     * The classification that makes the view withhold an attribute.
     *
     * @param attribute the attribute, as the schema of the view has it
     * @return the classification of the attribute, in the view's schema or in the store's latest schema, that the
     *         clearance does not cover; empty if the view reads the attribute
     */
    Optional<String> blockedBy(Attribute attribute);

    /**
     * The classifications that the view's clearance covers, which a problem reports as what was found.
     *
     * @return the classifications, in the order of the clearance
     */
    Set<String> covered();

    /**
     * What a view withholds that goes by its own schema only: the attributes whose classification a clearance does not
     * cover.
     *
     * @param clearance the clearance of the view
     * @return the withholding
     */
    static Withholding of(Clearance clearance) {
        Objects.requireNonNull(clearance, "clearance");
        return new Withholding() {
            @Override
            public Optional<String> blockedBy(Attribute attribute) {
                return attribute.classification().filter(classification -> !clearance.classifications()
                        .contains(classification));
            }

            @Override
            public Set<String> covered() {
                return clearance.classifications();
            }
        };
    }
}
