package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.DocumentLocation;
import java.util.Optional;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.nodes.Node;

/** Turns the marks of YAML nodes into document locations: lines and columns counted from 1. */
final class Marks {

    private Marks() {
    }

    /** The location of a node, which is where it starts, including its anchor and tag. */
    static DocumentLocation location(String pointer, Node node) {
        return location(pointer, node.getStartMark());
    }

    /** The location of a mark. */
    static DocumentLocation location(String pointer, Mark mark) {
        return location(pointer, Optional.of(mark));
    }

    private static DocumentLocation location(String pointer, Optional<Mark> mark) {
        return new DocumentLocation(
                pointer, mark.map(m -> m.getLine() + 1).orElse(1), mark.map(m -> m.getColumn() + 1).orElse(1));
    }
}
