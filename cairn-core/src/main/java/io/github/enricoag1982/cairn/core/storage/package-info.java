/**
 * The files of a durable store (ADR-0011, ADR-0022, design of {@code add-durable-commit-log}): the codec of log and
 * snapshot records in format version 1, the commit log in segment files, snapshot files, the lock of the directory, and
 * recovery. It knows the types of the API and nothing of the engine's internals, which convert between their own
 * structures and the records of this package. Not exported.
 */
package io.github.enricoag1982.cairn.core.storage;
