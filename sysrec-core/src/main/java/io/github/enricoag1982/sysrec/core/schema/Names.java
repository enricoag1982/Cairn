package io.github.enricoag1982.sysrec.core.schema;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/** Rules for names: syntax, and comparison without regard to case. */
final class Names {

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,127}");

    private Names() {
    }

    static boolean isValid(String name) {
        return NAME.matcher(name).matches();
    }

    /** The key under which names are compared: case is ignored. */
    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    static boolean same(String first, String second) {
        return first.equalsIgnoreCase(second);
    }

    static boolean contains(Collection<String> names, String name) {
        return names.stream().anyMatch(candidate -> same(candidate, name));
    }

    static String path(String type, String member) {
        return type + "." + member;
    }
}
