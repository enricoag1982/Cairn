package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.schema.Problem;
import java.util.List;
import java.util.Objects;

/**
 * A transaction payload that was read: the transaction and where in the text each of its writes is (capability
 * {@code json-payloads}).
 *
 * @param transaction the transaction, with values typed by the schema that read it
 * @param locations   where the writes and their values are in the text
 */
public record TransactionPayload(Transaction transaction, PayloadLocations locations) {

    /**
     * Rejects null components.
     */
    public TransactionPayload {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(locations, "locations");
    }

    /**
     * Locates problems in this payload: the problems that a dry run or a commit of its transaction reports. A problem
     * about an entity, relationship or attribute is located at the last write of the payload that concerns it, at the
     * attribute's key where there is one; a problem about the transaction as a whole at the root of the payload. See
     * {@link PayloadLocations} for the rules. A problem that is located already keeps its location.
     *
     * @param problems the problems, for example those of {@code Store.dryRun} or of a rejected commit
     * @return the same problems, in the same order, each with its location
     */
    public List<Problem> locate(List<Problem> problems) {
        return locations.locate(problems);
    }
}
