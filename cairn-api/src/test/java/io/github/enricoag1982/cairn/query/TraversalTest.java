package io.github.enricoag1982.cairn.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * The options of a traversal step (design D2 of {@code add-query-and-traversal-conveniences}): {@link Traversal} with
 * its withers, the step it builds, and the maximum depth that a step may leave out.
 */
class TraversalTest {

    private static final Condition LARGE = Condition.gt(Operand.attribute("ownership", "share"),
            Operand.literal(50));

    private static QueryStep.Traverse onlyStep(Query query) {
        assertThat(query.steps()).singleElement().isInstanceOf(QueryStep.Traverse.class);
        return (QueryStep.Traverse) query.steps().getFirst();
    }

    @Test
    void aTraversalStartsFromDepth0ToTheEndWithoutBindingAnythingElse() {
        Traversal traversal = Traversal.of("holder", "ownedBy", "party");

        assertThat(traversal.from()).isEqualTo("holder");
        assertThat(traversal.name()).isEqualTo("ownedBy");
        assertThat(traversal.binding()).isEqualTo("party");
        assertThat(traversal.minDepth()).as("as in JSON").isEqualTo(0);
        assertThat(traversal.maxDepth()).isEmpty();
        assertThat(traversal.relationship()).isEmpty();
        assertThat(traversal.depth()).isEmpty();
        assertThat(traversal.along()).isEmpty();
    }

    @Test
    void theWithersReturnAChangedCopyAndKeepTheOtherOptions() {
        Traversal start = Traversal.of("holder", "ownedBy", "party");

        Traversal all = start.minDepth(1).maxDepth(5).depth("level").relationship("ownership").along(LARGE);

        assertThat(all).isEqualTo(new Traversal("holder", "ownedBy", "party", 1, OptionalInt.of(5),
                Optional.of("ownership"), Optional.of("level"), Optional.of(LARGE)));
        assertThat(start).as("the traversal it started from is unchanged").isEqualTo(Traversal.of("holder", "ownedBy",
                "party"));
        assertThat(all.minDepth(2)).isEqualTo(new Traversal("holder", "ownedBy", "party", 2, OptionalInt.of(5),
                Optional.of("ownership"), Optional.of("level"), Optional.of(LARGE)));
        assertThat(start.along(LARGE).relationship("ownership").depth("level").maxDepth(5).minDepth(1))
                .as("in any order").isEqualTo(all);
    }

    @Test
    void theBuilderAddsAStepWithTheOptionsOfATraversal() {
        Query query = Query.from("Account", "a")
                .traverse(Traversal.of("a", "ownedBy", "party").minDepth(1).maxDepth(5).depth("level")
                        .relationship("ownership").along(LARGE))
                .build();

        assertThat(onlyStep(query)).isEqualTo(new QueryStep.Traverse("a", "ownedBy", "party", Optional.of("ownership"),
                Optional.of("level"), Optional.of(LARGE), 1, OptionalInt.of(5)));
    }

    @Test
    void aTraversalWithoutAMaximumDepthIsAStepWithoutOne() {
        Query query = Query.from("Account", "a").traverse(Traversal.of("a", "ownedBy", "party")).build();

        QueryStep.Traverse step = onlyStep(query);

        assertThat(step.maxDepth()).isEmpty();
        assertThat(step.minDepth()).isEqualTo(0);
        assertThat(step).isEqualTo(new QueryStep.Traverse("a", "ownedBy", "party", Optional.empty(), Optional.empty(),
                Optional.empty(), 0, OptionalInt.empty()));
        assertThat(onlyStep(Query.from("Account", "a").traverse(Traversal.of("a", "ownedBy", "party").minDepth(1))
                .build()).minDepth()).isEqualTo(1);
    }

    @Test
    void aStepWithAnIntMaximumDepthIsAStepWithThatMaximumDepth() {
        QueryStep.Traverse viaInt = new QueryStep.Traverse("a", "n", "b", Optional.empty(), Optional.empty(),
                Optional.empty(), 1, 3);

        assertThat(viaInt.maxDepth()).hasValue(3);
        assertThat(viaInt).isEqualTo(new QueryStep.Traverse("a", "n", "b", Optional.empty(), Optional.empty(),
                Optional.empty(), 1, OptionalInt.of(3)));
        assertThat(onlyStep(Query.from("Account", "a").traverse("a", "n", 1, 3, "b").build())).isEqualTo(viaInt);
    }

    @Test
    void aMinimumDepthAboveTheMaximumDepthIsRejectedWhenTheStepIsMade() {
        Traversal upsideDown = Traversal.of("a", "n", "b").maxDepth(2).minDepth(3);

        assertThat(upsideDown.minDepth()).as("a traversal can be held while its options are set").isEqualTo(3);
        assertThatThrownBy(() -> Query.from("Account", "a").traverse(upsideDown))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("3");
        assertThatThrownBy(() -> new QueryStep.Traverse("a", "n", "b", Optional.empty(), Optional.empty(),
                Optional.empty(), 3, OptionalInt.of(2))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum depth");
    }

    @Test
    void aMinimumDepthWithoutAMaximumDepthIsNeverAboveIt() {
        QueryStep.Traverse step = new QueryStep.Traverse("a", "n", "b", Optional.empty(), Optional.empty(),
                Optional.empty(), 1_000_000, OptionalInt.empty());

        assertThat(step.maxDepth()).isEmpty();
    }

    @Test
    void aNegativeDepthIsRejected() {
        Traversal start = Traversal.of("a", "n", "b");

        assertThatThrownBy(() -> start.minDepth(-1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
        assertThatThrownBy(() -> start.maxDepth(-1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
        assertThatThrownBy(() -> new QueryStep.Traverse("a", "n", "b", Optional.empty(), Optional.empty(),
                Optional.empty(), -1, OptionalInt.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueryStep.Traverse("a", "n", "b", Optional.empty(), Optional.empty(),
                Optional.empty(), 0, OptionalInt.of(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Query.from("Account", "a").traverse("a", "n", 0, -1, "b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTraversalRejectsNulls() {
        Traversal start = Traversal.of("a", "n", "b");

        assertThatThrownBy(() -> Traversal.of(null, "n", "b")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Traversal.of("a", null, "b")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Traversal.of("a", "n", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> start.depth(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> start.relationship(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> start.along(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Traversal("a", "n", "b", 1, null, Optional.empty(), Optional.empty(),
                Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Query.from("Account", "a").traverse((Traversal) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryStep.Traverse("a", "n", "b", Optional.empty(), Optional.empty(),
                Optional.empty(), 0, (OptionalInt) null)).isInstanceOf(NullPointerException.class);
    }
}
