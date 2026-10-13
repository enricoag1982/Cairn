package io.github.enricoag1982.cairn.codegen;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Generates Java types from schema documents on the command line (ADR-0021, design D5 of
 * {@code add-java-type-generation}, capability {@code java-types}):
 *
 * <pre>
 * java -cp ... io.github.enricoag1982.cairn.codegen.CairnCodegen \
 *     --package com.example.model --output build/generated docs/model.cairn.yaml
 * </pre>
 *
 * <p>The options are {@code --package <name>}, the package of the generated types, {@code --output <directory>}, where
 * the source goes, and then one or more schema documents. The generator replaces the content of the output directory;
 * it refuses to do so if the directory holds files other than Java source, so that a wrong option does not delete
 * anything else. When the documents have problems, it prints each as
 * {@code <file>:<line>:<column>: <code> <path>: <message>} to standard error and writes nothing.
 */
public final class CairnCodegen {

    /** The exit status when the documents have problems or cannot be read. */
    static final int PROBLEMS = 1;

    /** The exit status when the options are wrong. */
    static final int USAGE_ERROR = 2;

    static final String USAGE = """
            Usage: CairnCodegen --package <name> --output <directory> <document>...

              --package <name>      the package of the generated Java types
              --output <directory>  where the source goes; its content is replaced
              <document>            a schema document, YAML or JSON; at least one

            Exits with 0 after writing the types, with 1 if the documents have problems (they are printed to
            standard error as <file>:<line>:<column>: <code> <path>: <message>, and nothing is written), and with
            2 if the options are wrong.
            """;

    private CairnCodegen() {
    }

    /**
     * Generates the types and exits with the status that {@link #run} returns.
     *
     * @param args the options and the documents
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Generates the types.
     *
     * @param args the options and the documents
     * @param out  where the summary goes
     * @param err  where problems and messages go
     * @return the exit status: 0 after writing the types, 1 if the documents have problems or cannot be read, 2 if the
     *         options are wrong
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        String packageName = null;
        Path output = null;
        List<Path> documents = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> {
                    out.print(USAGE);
                    return 0;
                }
                case "--package", "--output" -> {
                    if (i + 1 >= args.length) {
                        return usage(err, "The option " + arg + " needs a value.");
                    }
                    String value = args[++i];
                    if (arg.equals("--package")) {
                        packageName = value;
                    } else {
                        output = Path.of(value);
                    }
                }
                default -> {
                    if (arg.startsWith("--")) {
                        return usage(err, "Unknown option " + arg + ".");
                    }
                    documents.add(Path.of(arg));
                }
            }
        }
        if (packageName == null) {
            return usage(err, "The option --package is required.");
        }
        if (output == null) {
            return usage(err, "The option --output is required.");
        }
        if (documents.isEmpty()) {
            return usage(err, "Name at least one schema document.");
        }

        Generation generation;
        try {
            generation = JavaTypes.generate(documents, packageName);
        } catch (IllegalArgumentException e) {
            return usage(err, e.getMessage());
        } catch (UncheckedIOException e) {
            err.println(e.getMessage());
            return PROBLEMS;
        }
        switch (generation) {
            case Generation.Failed failed -> {
                failed.problems().forEach(problem -> err.println(problem.format()));
                return PROBLEMS;
            }
            case Generation.Generated generated -> {
                try {
                    if (!replaceContent(output, generated.files())) {
                        return usage(err, "The output directory " + output + " holds files other than Java source, "
                                + "which the generator will not replace.");
                    }
                } catch (IOException e) {
                    err.println("The types cannot be written to " + output + ": " + e);
                    return PROBLEMS;
                }
                out.println("Generated " + generated.files().size() + " files in " + output + ".");
                return 0;
            }
        }
    }

    private static int usage(PrintStream err, String message) {
        err.println(message);
        err.print(USAGE);
        return USAGE_ERROR;
    }

    /**
     * Empties the directory and writes the files into it.
     *
     * @return {@code false}, with nothing changed, if the directory holds anything else than directories and Java
     *         source
     */
    private static boolean replaceContent(Path directory, List<GeneratedFile> files) throws IOException {
        if (Files.exists(directory)) {
            try (Stream<Path> tree = Files.walk(directory)) {
                if (tree.anyMatch(path -> Files.isRegularFile(path) && !path.getFileName().toString().endsWith(".java")
                        || Files.isSymbolicLink(path))) {
                    return false;
                }
            }
            try (Stream<Path> tree = Files.walk(directory)) {
                for (Path path : tree.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(directory)).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(directory);
        for (GeneratedFile file : files) {
            Path path = directory.resolve(file.path());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.text(), StandardCharsets.UTF_8);
        }
        return true;
    }
}
