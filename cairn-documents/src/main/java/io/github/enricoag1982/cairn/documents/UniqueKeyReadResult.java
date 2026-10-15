package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.schema.Problem;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of {@link Payloads#readUniqueKey} (capabilities {@code json-payloads} and {@code agent-tools}): the unique
 * key, or every problem found in the text.
 */
public sealed interface UniqueKeyReadResult {

    /**
     * The text is a unique key without problems.
     *
     * @param key the key, with its value typed by the attribute that it names if the schema has it
     */
    record Read(UniqueKey key) implements UniqueKeyReadResult {
        /**
         * Rejects a null key.
         */
        public Read {
            Objects.requireNonNull(key, "key");
        }
    }

    /**
     * The text has problems, so there is no key. Every problem is located in the text.
     *
     * @param problems every problem found, in the order of the text; a syntax error is the only problem, because it
     *                 stops reading
     */
    record Invalid(List<Problem> problems) implements UniqueKeyReadResult {
        /**
         * Copies the problem list.
         */
        public Invalid {
            problems = List.copyOf(problems);
        }
    }
}
