package io.github.enricoag1982.cairn.codegen;

import java.util.ArrayList;
import java.util.List;

/** Java source being written: lines with indentation, and comments. */
final class Source {

    private static final String INDENT = "    ";

    /** The width that comments are wrapped to, counting the indentation. */
    private static final int WIDTH = 120;

    private final StringBuilder text = new StringBuilder();
    private int depth;

    Source line(String line) {
        if (!line.isEmpty()) {
            text.append(INDENT.repeat(depth)).append(line);
        }
        text.append('\n');
        return this;
    }

    Source blank() {
        return line("");
    }

    /** Indents what follows, without writing a line. */
    Source indent() {
        depth++;
        return this;
    }

    /** Undoes {@link #indent()}. */
    Source outdent() {
        depth--;
        return this;
    }

    /** Writes a line that opens a block, and indents what follows. */
    Source open(String line) {
        line(line);
        depth++;
        return this;
    }

    /** Unindents, and writes the line that closes a block. */
    Source close(String line) {
        depth--;
        return line(line);
    }

    /**
     * Writes a documentation comment. Its lines are Javadoc, already escaped; a line that is empty separates
     * paragraphs and tags. Long lines are wrapped.
     */
    Source javadoc(List<String> lines) {
        int width = WIDTH - INDENT.length() * depth;
        List<String> wrapped = new ArrayList<>();
        for (String line : lines) {
            wrapped.addAll(wrap(line, width - 3));
        }
        if (wrapped.size() == 1 && wrapped.getFirst().length() + 7 <= width) {
            return line("/** " + wrapped.getFirst() + " */");
        }
        line("/**");
        for (String line : wrapped) {
            line(line.isEmpty() ? " *" : " * " + line);
        }
        return line(" */");
    }

    /** Breaks a line at spaces so that no line is longer than the width, unless a single word is. */
    private static List<String> wrap(String line, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : line.split(" ", -1)) {
            if (current.length() > 0 && current.length() + 1 + word.length() > width) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0 || (!lines.isEmpty() && word.isEmpty())) {
                current.append(' ');
            }
            current.append(word);
        }
        lines.add(current.toString());
        return lines;
    }

    @Override
    public String toString() {
        return text.toString();
    }
}
