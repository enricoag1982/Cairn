package io.github.enricoag1982.cairn.documents;

/** Builds JSON Pointers (RFC 6901). */
final class Pointers {

    private Pointers() {
    }

    /** The pointer to a member of an object. */
    static String child(String pointer, String member) {
        return pointer + "/" + escape(member);
    }

    /** The pointer to an item of an array. */
    static String child(String pointer, int index) {
        return pointer + "/" + index;
    }

    /** Escapes a reference token: {@code ~} becomes {@code ~0} and {@code /} becomes {@code ~1}. */
    static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
