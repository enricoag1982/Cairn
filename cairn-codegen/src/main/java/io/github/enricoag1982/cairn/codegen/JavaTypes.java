package io.github.enricoag1982.cairn.codegen;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Generates Java types from schema documents (ADR-0021, capability {@code java-types}).
 *
 * <p>The generator reads the documents in any order, checks them together as a schema registry would register them,
 * and writes the Java source of the types they define, into one package:
 *
 * <ul>
 * <li>an interface per trait, entity type and relationship type, with an accessor per attribute it declares, that
 * extends the interfaces of its supertype and traits and {@code TypedEntity} or {@code TypedRelationship};
 * <li>in each interface of an entity type or a relationship type, {@code from} to read a state as the type, a
 * {@code Snapshot} record, and builders of the writes of the generic API;
 * <li>an abstract base class per rule that the documents name and per version, with a typed check.
 * </ul>
 *
 * <p>The source depends on the Cairn API only, compiles without warnings, and is the same for the same documents: it
 * has no time stamps and no absolute paths, and the files and their members are in a fixed order.
 */
public final class JavaTypes {

    private static final Pattern PACKAGE = Pattern.compile("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
            + "(\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*");

    private JavaTypes() {
    }

    /**
     * Generates the types of schema documents.
     *
     * <p>When any document has problems, the result lists every problem found, each located in its document, and no
     * source is generated. A document that cannot be read as a schema document has the problems of reading it; the
     * others are still registered, so a problem that follows from a missing type may be reported as well.
     *
     * @param documents   the documents, YAML or JSON, in any order; their file names, not their locations, appear in the
     *                    generated files
     * @param packageName the package of the generated source, such as {@code com.example.model}
     * @return the generated files, sorted by path, or every problem
     * @throws IllegalArgumentException if the package name is not a legal package name
     * @throws UncheckedIOException     if a document cannot be read
     */
    public static Generation generate(List<Path> documents, String packageName) {
        Objects.requireNonNull(documents, "documents");
        Objects.requireNonNull(packageName, "packageName");
        if (!PACKAGE.matcher(packageName).matches() || List.of(packageName.split("\\.")).stream()
                .anyMatch(JavaTypes::isKeyword)) {
            throw new IllegalArgumentException("Not a package name: '" + packageName + "'. Write identifiers "
                    + "separated by dots, such as com.example.model; no part can be a Java keyword.");
        }
        Checker.Checked checked = Checker.check(documents);
        if (!checked.problems().isEmpty()) {
            return new Generation.Failed(checked.problems());
        }
        List<String> names = documents.stream().map(document -> document.getFileName().toString()).sorted().toList();
        Model model = Model.of(checked.schema(), checked.rules(), names, packageName);
        String directory = packageName.replace('.', '/') + "/";
        List<GeneratedFile> files = new ArrayList<>();
        model.elements().forEach(element ->
                files.add(new GeneratedFile(directory + element.java() + ".java", TypeSource.of(model, element))));
        model.rules().forEach(rule ->
                files.add(new GeneratedFile(directory + rule.java() + ".java", RuleSource.of(model, rule))));
        files.sort(Comparator.comparing(GeneratedFile::path));
        return new Generation.Generated(files);
    }

    private static boolean isKeyword(String word) {
        return JavaNames.isKeyword(word);
    }
}
