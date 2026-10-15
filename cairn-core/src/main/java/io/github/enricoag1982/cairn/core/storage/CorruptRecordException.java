package io.github.enricoag1982.cairn.core.storage;

/**
 * A record that cannot be decoded: its bytes end too soon, hold a value that the format does not allow, or are left over
 * after the record. Whoever reads a file turns it into an exception that names the file and the position.
 */
final class CorruptRecordException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    CorruptRecordException(String message) {
        super(message);
    }

    CorruptRecordException(String message, Throwable cause) {
        super(message, cause);
    }
}
