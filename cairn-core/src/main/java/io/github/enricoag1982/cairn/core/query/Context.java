package io.github.enricoag1982.cairn.core.query;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.data.View;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The reads of one run of a query: the view's own, remembered for the length of the run, so that several rows that lead
 * away from the same entity navigate once. Nothing is kept between runs (design D3).
 */
final class Context {

    private record Navigation(EntityId from, String name) {
    }

    private record Traversal(EntityId from, String name, int maxDepth) {
    }

    private final View view;
    private final Map<Navigation, List<Link>> navigations = new HashMap<>();
    private final Map<Traversal, List<Step>> traversals = new HashMap<>();

    Context(View view) {
        this.view = Objects.requireNonNull(view, "view");
    }

    View view() {
        return view;
    }

    /** The links that lead away from an entity by a name, as the view navigates. */
    List<Link> navigate(EntityId from, String name) {
        return navigations.computeIfAbsent(new Navigation(from, name), key -> view.navigate(from, name));
    }

    /** The entities reached from an entity by a name up to a depth, each at its smallest depth, as the view traverses. */
    List<Step> traverse(EntityId from, String name, int maxDepth) {
        return traversals.computeIfAbsent(new Traversal(from, name, maxDepth),
                key -> view.traverse(from, name, relationship -> true, maxDepth));
    }
}
