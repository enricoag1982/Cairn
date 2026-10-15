package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import java.util.Optional;

/** Creates problems with the details of design D5. */
public final class Problems {

    private Problems() {
    }

    /**
     * Creates a problem; a detail that is {@code null} is left out.
     *
     * @param code     the code
     * @param path     the element concerned
     * @param message  a readable explanation
     * @param expected what would have been accepted, or {@code null}
     * @param found    what was given instead, or {@code null}
     * @param hint     how to fix the problem, or {@code null}
     * @return the problem
     */
    public static Problem of(ProblemCode code, String path, String message, String expected, String found,
            String hint) {
        return new Problem(code, path, message, Optional.ofNullable(expected), Optional.ofNullable(found),
                Optional.ofNullable(hint));
    }
}
