package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Where the elements of a schema document are in its text (capability {@code schema-documents}, design D4).
 *
 * <p>Elements are identified by the paths that problems carry: a type name; a type name and an attribute, inverse
 * name or rule name joined by a dot; and {@code @origin}. A type is located at its key under {@code traits},
 * {@code entityTypes} or {@code relationshipTypes}, an attribute at its key under {@code attributes}, an inverse name
 * at the key {@code inverseName}, a rule at its entry in {@code rules} and the origin at the key {@code origin}.
 * Paths match without regard to case, as names do. A path that matches no element is located at the root of the
 * document.
 */
public final class DocumentLocations {

    private final DocumentLocation root;
    private final Map<String, DocumentLocation> elements;

    DocumentLocations(DocumentLocation root, Map<String, DocumentLocation> elements) {
        this.root = Objects.requireNonNull(root, "root");
        this.elements = Map.copyOf(elements);
    }

    /**
     * Returns the root of the document, where a problem about no particular element is located.
     *
     * @return the location of the root, with the empty pointer
     */
    public DocumentLocation root() {
        return root;
    }

    /**
     * Finds an element.
     *
     * @param path the path of the element, compared without regard to case
     * @return the location of the element, or empty if the document has no element with that path
     */
    public Optional<DocumentLocation> find(String path) {
        return Optional.ofNullable(elements.get(key(path)));
    }

    /**
     * Locates a path.
     *
     * @param path the path of an element, compared without regard to case
     * @return the location of the element, or the root of the document if there is no such element
     */
    public DocumentLocation of(String path) {
        return find(path).orElse(root);
    }

    /**
     * Locates a problem by its path. A problem that is located already is returned as it is.
     *
     * @param problem the problem
     * @return the located problem
     */
    public Problem locate(Problem problem) {
        return problem.location().isPresent() ? problem : problem.at(of(problem.path()));
    }

    /**
     * Locates problems by their paths. Problems that are located already are returned as they are.
     *
     * @param problems the problems
     * @return the located problems, in the same order
     */
    public List<Problem> locate(List<Problem> problems) {
        return problems.stream().map(this::locate).toList();
    }

    static String key(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DocumentLocations that && root.equals(that.root) && elements.equals(that.elements);
    }

    @Override
    public int hashCode() {
        return Objects.hash(root, elements);
    }

    @Override
    public String toString() {
        return "DocumentLocations" + new TreeMap<>(elements);
    }
}
