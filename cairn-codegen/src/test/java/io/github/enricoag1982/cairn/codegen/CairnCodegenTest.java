package io.github.enricoag1982.cairn.codegen;

import static io.github.enricoag1982.cairn.codegen.GeneratedSource.PACKAGE;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.document;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command line of the generator (design D5 of {@code add-java-type-generation}). */
class CairnCodegenTest {

    @TempDir
    Path directory;

    private record Run(int status, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = CairnCodegen.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static List<String> files(Path directory) throws IOException {
        try (Stream<Path> tree = Files.walk(directory)) {
            return tree.filter(Files::isRegularFile).map(path -> directory.relativize(path).toString()).sorted()
                    .toList();
        }
    }

    private Path model(String name, String types) throws IOException {
        return document(directory, name, "cairn: 1\norigin: { name: " + name.substring(0, name.indexOf('.'))
                + ", version: \"1.0.0\" }\nentityTypes:\n" + types);
    }

    @Test
    void itWritesTheGeneratedFilesAndExitsWithZero() throws IOException {
        Path output = directory.resolve("generated");

        Run run = run("--package", PACKAGE, "--output", output.toString(), ReferenceDomain.DOCUMENT.toString());

        assertThat(run.status()).isZero();
        assertThat(run.err()).isEmpty();
        assertThat(run.out()).contains("Generated 14 files");
        Generation.Generated generated = (Generation.Generated) JavaTypes.generate(List.of(ReferenceDomain.DOCUMENT),
                PACKAGE);
        assertThat(files(output)).containsExactlyElementsOf(generated.files().stream().map(GeneratedFile::path).toList());
        for (GeneratedFile file : generated.files()) {
            assertThat(Files.readString(output.resolve(file.path()))).isEqualTo(file.text());
        }
    }

    @Test
    void optionsAndDocumentsMayComeInAnyOrder() throws IOException {
        Path first = model("first.cairn.yaml", "  One: {}\n");
        Path second = model("second.cairn.yaml", "  Two: {}\n");
        Path output = directory.resolve("generated");

        Run run = run(first.toString(), "--output", output.toString(), second.toString(), "--package", "a.b");

        assertThat(run.status()).isZero();
        assertThat(files(output)).containsExactly("a/b/One.java", "a/b/Two.java");
    }

    @Test
    void itReplacesTheContentOfTheOutputDirectory() throws IOException {
        Path output = directory.resolve("generated");
        Files.createDirectories(output.resolve("old/package"));
        Files.writeString(output.resolve("old/package/Stale.java"), "class Stale {}");
        Files.writeString(output.resolve("Other.java"), "class Other {}");
        Path file = model("model.cairn.yaml", "  Thing: {}\n");

        Run run = run("--package", "renewed.types", "--output", output.toString(), file.toString());

        assertThat(run.status()).isZero();
        assertThat(files(output)).containsExactly("renewed/types/Thing.java");
        assertThat(output.resolve("old")).doesNotExist();
    }

    @Test
    void itDoesNotDeleteFilesThatAreNotJavaSource() throws IOException {
        Path output = directory.resolve("generated");
        Files.createDirectories(output);
        Files.writeString(output.resolve("notes.txt"), "keep me");
        Files.writeString(output.resolve("Stale.java"), "class Stale {}");
        Path file = model("model.cairn.yaml", "  Thing: {}\n");

        Run run = run("--package", "renewed.types", "--output", output.toString(), file.toString());

        assertThat(run.status()).isEqualTo(2);
        assertThat(run.err()).contains("holds files other than Java source");
        assertThat(files(output)).containsExactly("Stale.java", "notes.txt");
    }

    @Test
    void problemsAreWrittenToStandardErrorOnePerLineAndNothingIsWritten() throws IOException {
        Path broken = document(directory, "broken.cairn.yaml", "cairn: 1\norigin: { name: broken, version: \"1.0.0\" }\n"
                + "entityTypes:\n  Thing:\n    supertype: Missing\n    attributes:\n      size: { type: number }\n");
        Path output = directory.resolve("generated");

        Run run = run("--package", PACKAGE, "--output", output.toString(), broken.toString());

        assertThat(run.status()).isEqualTo(1);
        assertThat(run.out()).isEmpty();
        assertThat(run.err().lines().toList()).isNotEmpty().allSatisfy(line ->
                assertThat(line).matches(Pattern.quote(broken.toString()) + ":\\d+:\\d+: [A-Z_]+ \\S+: .+"));
        assertThat(output).doesNotExist();
    }

    @Test
    void theProblemsOfAllDocumentsAreReported() throws IOException {
        Path syntax = document(directory, "syntax.cairn.yaml", "cairn: 1\norigin: [\n");
        Path missing = model("missing.cairn.yaml", "  Thing:\n    supertype: Missing\n");
        Path fine = model("fine.cairn.yaml", "  Fine: {}\n");

        Generation generation = JavaTypes.generate(List.of(syntax, missing, fine), PACKAGE);

        assertThat(generation).isInstanceOf(Generation.Failed.class);
        assertThat(((Generation.Failed) generation).problems()).extracting(DocumentProblem::document)
                .containsExactly(syntax, missing);
    }

    @Test
    void aDocumentThatCannotBeReadExitsWithOne() {
        Path output = directory.resolve("generated");

        Run run = run("--package", PACKAGE, "--output", output.toString(), directory.resolve("nothing.yaml").toString());

        assertThat(run.status()).isEqualTo(1);
        assertThat(run.err()).contains("nothing.yaml").contains("cannot be read");
        assertThat(output).doesNotExist();
    }

    @Test
    void wrongOptionsExitWithTwoAndPrintTheUsage() throws IOException {
        Path file = model("model.cairn.yaml", "  Thing: {}\n");
        String output = directory.resolve("generated").toString();

        List<Run> runs = List.of(
                run("--output", output, file.toString()),
                run("--package", PACKAGE, file.toString()),
                run("--package", PACKAGE, "--output", output),
                run("--package", PACKAGE, "--output"),
                run("--package", PACKAGE, "--output", output, "--unknown", file.toString()),
                run("--package", "not a package", "--output", output, file.toString()),
                run("--package", "com.class.x", "--output", output, file.toString()),
                run());

        assertThat(runs).allSatisfy(run -> {
            assertThat(run.status()).isEqualTo(2);
            assertThat(run.err()).contains("Usage: CairnCodegen");
        });
        assertThat(Path.of(output)).doesNotExist();
    }

    @Test
    void helpPrintsTheUsageToStandardOutput() {
        Run run = run("--help");

        assertThat(run.status()).isZero();
        assertThat(run.out()).contains("Usage: CairnCodegen --package <name> --output <directory> <document>...");
        assertThat(run.err()).isEmpty();
    }
}
