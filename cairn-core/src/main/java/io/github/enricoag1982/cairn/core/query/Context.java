package io.github.enricoag1982.cairn.core.query;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.query.Cell;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The reads of one run of a query: the view's own, remembered for the length of the run, so that several rows that lead
 * away from the same entity navigate once. Nothing is kept between runs (design D3).
 */
final class Context {

    private record Navigation(EntityId from, String name) {
    }

    /** A traversal: the test of the relationships it follows is told apart by identity, as a step has one. */
    private record Traversal(EntityId from, String name, Plan.Test along, int maxDepth) {
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

    /**
     * The entities reached from an entity by a name up to a depth, each at its smallest depth, as the view traverses,
     * following only the relationships for which a test holds.
     *
     * @param from  the entity to start from
     * @param name  the relationship name or inverse name
     * @param along the test of a relationship, which sees it in the slot given; null to follow every relationship
     * @param slot  the position of the relationship in a row, which the test reads
     * @param slots the number of cells of a row
     * @param maxDepth the largest depth
     */
    List<Step> traverse(EntityId from, String name, Plan.Test along, int slot, int slots, int maxDepth) {
        return traversals.computeIfAbsent(new Traversal(from, name, along, maxDepth),
                key -> view.traverse(from, name, followed(along, slot, slots), maxDepth));
    }

    /** What the view asks of each relationship: the test of the step, run on a row that holds only the relationship. */
    private Predicate<RelationshipState> followed(Plan.Test along, int slot, int slots) {
        if (along == null) {
            return relationship -> true;
        }
        return relationship -> {
            Cell[] probe = new Cell[slots];
            probe[slot] = new Cell.RelationshipCell(relationship);
            return along.holds(probe, this);
        };
    }
}
