package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Where the parts of a query are in its text, and what the paths of the problems about them mean (capability
 * {@code queries}, design D4 of {@code add-queries-and-aggregation}).
 *
 * <p>The paths are those that a view gives the problems of running a query:
 *
 * <ul>
 * <li>{@code @query} is the root of the text.
 * <li>{@code @query.from} is the key {@code from}, and {@code @query.limit} the key {@code limit}.
 * <li>{@code @query.steps.<n>}, {@code @query.groupBy.<n>}, {@code @query.aggregates.<n>}, {@code @query.columns.<n>}
 *     and {@code @query.order.<n>}, with the position {@code n} counted from 0, are the item at that position of the
 *     list under that key: the whole step, group key, aggregate, column or order.
 * </ul>
 *
 * <p>A path that names a part that the text does not have, such as a position past the end of a list, is located at the
 * closest enclosing part that it has: the key of the list, else the root. A problem that is located already, such as one
 * of reading, keeps its location.
 */
public final class QueryLocations {

    private final DocumentLocation root;
    private final Map<String, DocumentLocation> parts;

    QueryLocations(DocumentLocation root, Map<String, DocumentLocation> parts) {
        this.root = Objects.requireNonNull(root, "root");
        this.parts = Map.copyOf(parts);
    }

    /**
     * Returns the root of the text, where a problem about the query as a whole is located.
     *
     * @return the location of the root, with the empty pointer
     */
    public DocumentLocation root() {
        return root;
    }

    /**
     * Finds a part by the path of the problems about it.
     *
     * @param path the path, such as {@code @query.steps.0}
     * @return the location of the part, or empty if the text has no part with that path
     */
    public Optional<DocumentLocation> find(String path) {
        return Optional.ofNullable(parts.get(path));
    }

    /**
     * Locates a problem by its path. A problem about a part that the text does not have is located at the closest
     * enclosing part that it has, and at the root if there is none. A problem that is located already is returned as it
     * is.
     *
     * @param problem the problem
     * @return the located problem
     */
    public Problem locate(Problem problem) {
        if (problem.location().isPresent()) {
            return problem;
        }
        String candidate = problem.path();
        while (true) {
            Optional<DocumentLocation> found = find(candidate);
            if (found.isPresent()) {
                return problem.at(found.get());
            }
            int dot = candidate.lastIndexOf('.');
            if (dot <= 0) {
                return problem.at(root);
            }
            candidate = candidate.substring(0, dot);
        }
    }

    /**
     * Locates problems by their paths, as {@link #locate(Problem)} does.
     *
     * @param problems the problems
     * @return the located problems, in the same order
     */
    public List<Problem> locate(List<Problem> problems) {
        return problems.stream().map(this::locate).toList();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof QueryLocations that && root.equals(that.root) && parts.equals(that.parts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(root, parts);
    }

    @Override
    public String toString() {
        return "QueryLocations" + new TreeMap<>(parts);
    }
}
