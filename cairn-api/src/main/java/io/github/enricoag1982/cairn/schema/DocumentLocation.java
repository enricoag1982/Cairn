package io.github.enricoag1982.cairn.schema;

import java.util.Objects;

/**
 * A place in a schema document: a JSON Pointer (RFC 6901), a line and a column (capability
 * {@code schema-documents}).
 *
 * <p>Lines and columns are counted from 1. The pointer names the element in the document's data, for example
 * {@code /entityTypes/Organization/attributes/lei}; the empty pointer is the whole document. The line and column are
 * where that element, or its key, starts in the text.
 *
 * @param pointer the JSON Pointer of the element
 * @param line    the line, from 1
 * @param column  the column, from 1
 */
public record DocumentLocation(String pointer, int line, int column) {

    /**
     * Rejects a null pointer and a line or column below 1.
     *
     * @throws IllegalArgumentException if the line or the column is below 1
     */
    public DocumentLocation {
        Objects.requireNonNull(pointer, "pointer");
        if (line < 1) {
            throw new IllegalArgumentException("line must be at least 1: " + line);
        }
        if (column < 1) {
            throw new IllegalArgumentException("column must be at least 1: " + column);
        }
    }

    @Override
    public String toString() {
        return "line " + line + ", column " + column + " (" + (pointer.isEmpty() ? "document" : pointer) + ")";
    }
}
