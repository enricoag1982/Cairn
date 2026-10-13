package io.github.enricoag1982.cairn.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Writing lines and comments. */
class SourceTest {

    @Test
    void blocksAreIndentedByFourSpacesAndBlankLinesHaveNoIndentation() {
        Source source = new Source();

        source.open("class A {").blank().open("void m() {").line("run();").close("}").close("}");

        assertThat(source.toString()).isEqualTo("class A {\n\n    void m() {\n        run();\n    }\n}\n");
    }

    @Test
    void aShortCommentIsOneLineAndALongOneIsWrapped() {
        Source source = new Source();
        String words = "word ".repeat(40).strip();

        source.javadoc(List.of("Short."));
        source.javadoc(List.of(words, "", "@return " + words));

        List<String> lines = source.toString().lines().toList();
        assertThat(lines.getFirst()).isEqualTo("/** Short. */");
        assertThat(lines).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(120));
        assertThat(lines.get(1)).isEqualTo("/**");
        assertThat(lines.getLast()).isEqualTo(" */");
        assertThat(lines).as("the empty line between the paragraphs").contains(" *");
        assertThat(lines).as("the short comment, then two lines for each of the two long ones, and the frame")
                .hasSize(1 + 1 + 2 + 1 + 2 + 1);
    }

    @Test
    void wrappingKeepsEveryWord() {
        Source source = new Source();
        String words = "alpha beta gamma delta ".repeat(20).strip();

        source.javadoc(List.of(words));

        String joined = source.toString().lines().filter(line -> line.startsWith(" * "))
                .map(line -> line.substring(3)).reduce((a, b) -> a + " " + b).orElseThrow();
        assertThat(joined).isEqualTo(words);
    }
}
