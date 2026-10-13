package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import java.util.Optional;

/** Creates problems with the details of design D5. */
final class Problems {

    private Problems() {
    }

    /** Creates a problem; a detail that is {@code null} is left out. */
    static Problem of(ProblemCode code, String path, String message, String expected, String found, String hint) {
        return new Problem(code, path, message, Optional.ofNullable(expected), Optional.ofNullable(found),
                Optional.ofNullable(hint));
    }
}
