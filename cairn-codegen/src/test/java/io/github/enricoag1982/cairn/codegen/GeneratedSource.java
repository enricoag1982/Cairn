package io.github.enricoag1982.cairn.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.Value;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Helpers for the tests that generate types from documents (design D7 of {@code add-java-type-generation}): writing
 * documents, generating, and compiling the result in this process with the Java compiler, against {@code cairn-api}
 * only, with every lint warning and every Javadoc problem an error.
 */
final class GeneratedSource {

    /** The package that tests generate into. */
    static final String PACKAGE = "com.example.generated";

    private GeneratedSource() {
    }

    /** Writes a document into a directory. */
    static Path document(Path directory, String name, String text) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, text);
        return file;
    }

    /** Generates the types of documents that must have no problems. */
    static Generation.Generated generate(Path... documents) {
        Generation generation = JavaTypes.generate(List.of(documents), PACKAGE);
        if (generation instanceof Generation.Failed failed) {
            throw new AssertionError("The documents have problems: " + failed.problems());
        }
        return (Generation.Generated) generation;
    }

    /** Generates the types of a document that must have no problems. */
    static Generation.Generated generate(Path directory, String text) throws IOException {
        return generate(document(directory, "model.cairn.yaml", text));
    }

    /** The text of one generated file, by the simple name of its type. */
    static String text(Generation.Generated generated, String type) {
        return generated.files().stream().filter(file -> file.path().equals(PACKAGE.replace('.', '/') + "/" + type + ".java"))
                .findFirst().orElseThrow(() -> new AssertionError("No file for " + type + " in "
                        + generated.files().stream().map(GeneratedFile::path).toList())).text();
    }

    /** The result of compiling generated source. */
    record Compiled(Path classes, List<String> diagnostics) {

        /** Loads the generated classes, on top of the class path of the tests, which holds {@code cairn-api}. */
        URLClassLoader loader() throws IOException {
            return new URLClassLoader(new URL[] {classes.toUri().toURL()}, GeneratedSource.class.getClassLoader());
        }
    }

    /**
     * Compiles generated files with {@code -Xlint:all -Werror} and all Javadoc checks, against the API of Cairn and
     * nothing else, and asserts that the compiler has nothing to say.
     */
    static Compiled compile(Generation.Generated generated, Path directory) throws IOException {
        Compiled compiled = tryCompile(generated, directory);
        assertThat(compiled.diagnostics()).as("diagnostics of the compiler").isEmpty();
        return compiled;
    }

    static Compiled tryCompile(Generation.Generated generated, Path directory) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the Java compiler of the JDK that runs the tests").isNotNull();
        Path classes = Files.createDirectories(directory.resolve("classes"));
        List<JavaFileObject> units = new ArrayList<>();
        for (GeneratedFile file : generated.files()) {
            units.add(new SimpleJavaFileObject(URI.create("string:///" + file.path()), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return file.text();
                }
            });
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of(apiLocation()));
            StringWriter output = new StringWriter();
            List<String> options = List.of("-Xlint:all", "-Werror", "-Xdoclint:all/protected", "-proc:none", "-encoding", "UTF-8");
            boolean success = compiler.getTask(output, files, diagnostics, options, null, units).call();
            List<String> messages = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                messages.add(diagnostic.getKind() + " " + diagnostic.getSource().getName() + ":"
                        + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(null) + "\n    "
                        + sourceLine(diagnostic));
            }
            if (!success && messages.isEmpty()) {
                messages.add("compilation failed: " + output);
            }
            return new Compiled(classes, messages);
        }
    }

    private static String sourceLine(Diagnostic<? extends JavaFileObject> diagnostic) {
        try {
            return diagnostic.getSource().getCharContent(true).toString().lines()
                    .skip(Math.max(0, diagnostic.getLineNumber() - 1)).findFirst().orElse("").strip();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** The class path entry that holds the API: the generated code compiles against this and nothing else. */
    private static Path apiLocation() {
        try {
            return Path.of(Value.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** All the files as a map from path to text, in path order. */
    static Map<String, String> byPath(Generation.Generated generated) {
        Map<String, String> files = new TreeMap<>();
        generated.files().forEach(file -> files.put(file.path(), file.text()));
        return files;
    }
}
