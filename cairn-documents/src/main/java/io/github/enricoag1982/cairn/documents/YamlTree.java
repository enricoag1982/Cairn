package io.github.enricoag1982.cairn.documents;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.comments.CommentLine;
import org.snakeyaml.engine.v2.common.Anchor;
import org.snakeyaml.engine.v2.composer.Composer;
import org.snakeyaml.engine.v2.events.CollectionStartEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.ScalarEvent;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.parser.Parser;
import org.snakeyaml.engine.v2.parser.ParserImpl;
import org.snakeyaml.engine.v2.scanner.StreamReader;
import org.snakeyaml.engine.v2.schema.CoreSchema;

/**
 * Composes the YAML node tree of a schema document with SnakeYAML Engine (design D2).
 *
 * <p>The tree keeps the start mark of every node, which is where a node, including its anchor and tag, starts in the
 * text, and for every scalar its text and whether it was quoted. Scalars are resolved with the YAML 1.2 core schema.
 * Composing does not check for duplicate keys, and an alias shows in the tree only as the same node in two places;
 * the reader looks for both. An explicit tag does not show in the tree if it names the type the scalar would have
 * had anyway, and a merge key is replaced by the entries it merges, so the composer used here remembers every node
 * that had a tag and every mapping that had a merge key.
 */
final class YamlTree {

    /** The outcome of composing a text. */
    sealed interface Outcome {
    }

    /**
     * The text is YAML.
     *
     * @param root          the root of the first document, if the text has a document
     * @param explicitTags  the nodes that were written with an explicit tag, with the tag as written
     * @param mergeKeys     the mappings that were written with a merge key ({@code <<}), with the key's node
     * @param extraDocument where the second document starts, if the text has more than one
     */
    record Composed(
            Optional<Node> root, Map<Node, String> explicitTags, Map<Node, Node> mergeKeys, Optional<Mark> extraDocument)
            implements Outcome {
    }

    /**
     * The text is not YAML.
     *
     * @param message what is wrong
     * @param line    the line of the error, from 1
     * @param column  the column of the error, from 1
     */
    record Failed(String message, int line, int column) implements Outcome {
    }

    private YamlTree() {
    }

    /** Composes the first document of a text, and finds out whether there is a second. */
    static Outcome compose(String text) {
        // Non-scalar keys are the reader's to report, with their place in the document, not the composer's.
        LoadSettings settings = LoadSettings.builder().setSchema(new CoreSchema()).setAllowNonScalarKeys(true).build();
        try {
            TaggingComposer composer =
                    new TaggingComposer(settings, new ParserImpl(settings, new StreamReader(settings, text)));
            Optional<Node> root = composer.hasNext() ? Optional.of(composer.next()) : Optional.empty();
            Optional<Mark> extra = composer.hasNext() ? composer.nextDocumentStart() : Optional.empty();
            return new Composed(root, composer.explicitTags(), composer.mergeKeys(), extra);
        } catch (MarkedYamlEngineException e) {
            return failed(e, text);
        } catch (YamlEngineException e) {
            return new Failed(e.getMessage() == null ? "The text is not valid YAML." : e.getMessage(), 1, 1);
        } catch (StackOverflowError e) {
            return new Failed("The document is nested too deeply.", 1, 1);
        }
    }

    /**
     * Describes a syntax error. It is located at the mark where the parser stopped, unless that is the end of the
     * text: then something was not closed, and the place where it started is more useful.
     */
    private static Failed failed(MarkedYamlEngineException e, String text) {
        Optional<Mark> problem = e.getProblemMark();
        Optional<Mark> context = e.getContextMark();
        boolean atEnd = problem.isPresent() && problem.get().getIndex() >= text.codePointCount(0, text.length());
        Optional<Mark> at = atEnd && context.isPresent() ? context : problem.or(() -> context);
        String what = e.getProblem() == null || e.getProblem().isBlank() ? "The text is not valid YAML." : e.getProblem();
        String message = what + (e.getContext() == null || e.getContext().isBlank() ? "" : " (" + e.getContext() + ")");
        return new Failed(
                message, at.map(mark -> mark.getLine() + 1).orElse(1), at.map(mark -> mark.getColumn() + 1).orElse(1));
    }

    /** A composer that remembers which nodes were written with an explicit tag, and which mappings with a merge key. */
    private static final class TaggingComposer extends Composer {

        private final Map<Node, String> explicitTags = new IdentityHashMap<>();
        private final Map<Node, Node> mergeKeys = new IdentityHashMap<>();

        TaggingComposer(LoadSettings settings, Parser parser) {
            super(settings, parser);
        }

        Map<Node, String> explicitTags() {
            return explicitTags;
        }

        Map<Node, Node> mergeKeys() {
            return mergeKeys;
        }

        /** Where the next document starts: the next event is its start. */
        Optional<Mark> nextDocumentStart() {
            return parser.peekEvent().getStartMark();
        }

        @Override
        protected Node composeScalarNode(Optional<Anchor> anchor, List<CommentLine> blockComments) {
            Optional<String> tag = tagOf(parser.peekEvent());
            Node node = super.composeScalarNode(anchor, blockComments);
            tag.ifPresent(written -> explicitTags.put(node, written));
            return node;
        }

        @Override
        protected SequenceNode composeSequenceNode(Optional<Anchor> anchor) {
            Optional<String> tag = tagOf(parser.peekEvent());
            SequenceNode node = super.composeSequenceNode(anchor);
            tag.ifPresent(written -> explicitTags.put(node, written));
            return node;
        }

        @Override
        protected Node composeMappingNode(Optional<Anchor> anchor) {
            Optional<String> tag = tagOf(parser.peekEvent());
            Node node = super.composeMappingNode(anchor);
            tag.ifPresent(written -> explicitTags.put(node, written));
            return node;
        }

        @Override
        protected Node composeKeyNode(MappingNode node) {
            Node key = super.composeKeyNode(node);
            if (Tag.MERGE.equals(key.getTag())) {
                mergeKeys.putIfAbsent(node, key);
            }
            return key;
        }

        private static Optional<String> tagOf(Event event) {
            return switch (event) {
                case ScalarEvent scalar -> scalar.getTag();
                case CollectionStartEvent collection -> collection.getTag();
                default -> Optional.empty();
            };
        }
    }
}
