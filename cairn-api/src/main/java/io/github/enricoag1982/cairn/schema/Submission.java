package io.github.enricoag1982.cairn.schema;

import java.util.Objects;
import java.util.Optional;

/**
 * Who submits a schema registration, for whom, and why (ADR-0017, capability {@code schema-evolution}): what a schema
 * transaction records besides the origin, as a data transaction records them.
 *
 * <p>A registration that names no submission is recorded with the principal {@value #UNKNOWN_PRINCIPAL}.
 *
 * @param principal   who submits the registration, for example {@code agent:modeler}; never blank
 * @param onBehalfOf  the party the principal acts for, if any
 * @param reason      why the registration is submitted, if given
 */
public record Submission(String principal, Optional<String> onBehalfOf, Optional<String> reason) {

    /** The principal that a registration without a submission is recorded with. */
    public static final String UNKNOWN_PRINCIPAL = "@unknown";

    /**
     * Rejects null components and a blank principal.
     *
     * @throws IllegalArgumentException if the principal is empty or blank
     */
    public Submission {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(onBehalfOf, "onBehalfOf");
        Objects.requireNonNull(reason, "reason");
        if (principal.isBlank()) {
            throw new IllegalArgumentException("The principal of a submission must not be blank.");
        }
    }

    /**
     * Creates a submission that names a principal only.
     *
     * @param principal who submits the registration
     * @return the submission, without a party acted for and without a reason
     * @throws IllegalArgumentException if the principal is empty or blank
     */
    public static Submission by(String principal) {
        return new Submission(principal, Optional.empty(), Optional.empty());
    }

    /**
     * Creates the submission of a registration that names none: the principal {@value #UNKNOWN_PRINCIPAL}.
     *
     * @return the submission, without a party acted for and without a reason
     */
    public static Submission unknown() {
        return by(UNKNOWN_PRINCIPAL);
    }
}
