package io.github.enricoag1982.cairn.schema;

/**
 * The number of a committed transaction (capability {@code schema-evolution}). It identifies the snapshot the transaction produced.
 *
 * @param value the number; 0 is the empty store before any transaction
 */
public record Basis(long value) implements Comparable<Basis> {

    /** The empty store, before any transaction. */
    public static final Basis NONE = new Basis(0);

    @Override
    public int compareTo(Basis other) {
        return Long.compare(value, other.value);
    }
}
