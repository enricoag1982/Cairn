package io.github.enricoag1982.cairn.core.storage;

/**
 * Where a store writes what each commit did before it makes the commit visible (design D5 of
 * {@code add-durable-commit-log}). Both the store and its schema registry call it, only while holding the writer lock.
 */
public interface Journal {

    /**
     * Checks that the journal accepts records.
     *
     * @throws IllegalStateException if an earlier write failed, or the journal is closed
     */
    void requireWritable();

    /**
     * Appends a record and forces it to disk. When it returns, the record survives a crash.
     *
     * @param record what a commit did
     * @throws java.io.UncheckedIOException if encoding, writing or forcing fails; the journal refuses further records
     * @throws IllegalStateException        if an earlier write failed, or the journal is closed
     */
    void append(LogRecord record);
}
