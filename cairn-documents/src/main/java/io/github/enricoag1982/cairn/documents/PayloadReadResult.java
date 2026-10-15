package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.Problem;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of {@link Payloads#readTransaction(String, io.github.enricoag1982.cairn.schema.Schema)} (capability
 * {@code json-payloads}): the transaction, or every problem found in the text.
 */
public sealed interface PayloadReadResult {

    /**
     * The text is a transaction payload without problems.
     *
     * @param payload the transaction and the locations of its writes
     */
    record Read(TransactionPayload payload) implements PayloadReadResult {
        /**
         * Rejects a null payload.
         */
        public Read {
            Objects.requireNonNull(payload, "payload");
        }
    }

    /**
     * The text has problems, so there is no transaction. Every problem is located in the text.
     *
     * @param problems every problem found, in the order of the text; a syntax error or a format version other than 1 is
     *                 the only problem, because it stops reading
     */
    record Invalid(List<Problem> problems) implements PayloadReadResult {
        /**
         * Copies the problem list.
         */
        public Invalid {
            problems = List.copyOf(problems);
        }
    }
}
