package io.github.enricoag1982.sysrec.core.schema;

/**
 * One difference between two versions of an origin's types.
 *
 * @param path        the element concerned
 * @param description what changed
 * @param breaking    {@code true} if existing data or users could not follow the change
 */
record Change(String path, String description, boolean breaking) {

    static Change compatible(String path, String description) {
        return new Change(path, description, false);
    }

    static Change breaking(String path, String description) {
        return new Change(path, description, true);
    }
}
