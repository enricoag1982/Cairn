package io.github.enricoag1982.cairn.codegen;

import java.util.Set;
import java.util.TreeSet;

/**
 * The imports of one generated file, and how the file refers to library types. A file refers to a library type by its
 * simple name and imports it, except where a type of the documents has that simple name: the file then writes the
 * library type's qualified name, because types of the documents are always referred to by their simple names (they are
 * in the file's own package).
 */
final class Imports {

    private final Set<String> documentTypes;
    private final Set<String> imported = new TreeSet<>();

    /**
     * Creates the imports of a file.
     *
     * @param documentTypes the Java names of the types in the package of the generated files
     */
    Imports(Set<String> documentTypes) {
        this.documentTypes = documentTypes;
    }

    /**
     * Refers to a library type, and imports it if its simple name can be used.
     *
     * @param qualified the qualified name of the type
     * @return the name to write: the simple name, or the qualified name if a type of the documents has the simple name
     */
    String ref(String qualified) {
        String simple = Library.simpleName(qualified);
        if (documentTypes.contains(simple)) {
            return qualified;
        }
        if (!Library.packageOf(qualified).equals("java.lang")) {
            imported.add(qualified);
        }
        return simple;
    }

    /**
     * The import declarations, one per line, sorted; empty if the file imports nothing.
     *
     * @return the lines
     */
    Set<String> declarations() {
        Set<String> lines = new TreeSet<>();
        imported.forEach(qualified -> lines.add("import " + qualified + ";"));
        return lines;
    }
}
