package io.github.enricoag1982.cairn.gradle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scenarios of the {@code java-types} spec about the Gradle plugin, run with Gradle TestKit on a project in a
 * temporary directory. The project gets the generator's class path and the jar of {@code cairn-api} from the system
 * properties that the build of this module sets, as files; no toolchain is downloaded, and nothing is resolved from a
 * repository.
 */
class CairnTypesPluginScenariosTest {

    private static final Path REFERENCE_MODEL = Path.of("").toAbsolutePath().getParent()
            .resolve("docs/reference/reference-model.cairn.yaml");

    private static final String DOCUMENT = "src/main/cairn/reference-model.cairn.yaml";

    @TempDir
    Path project;

    // ------------------------------------------------------------------ the temporary project

    private static String files(String property) {
        String paths = System.getProperty(property);
        assertThat(paths).as("system property " + property + ", set by the build of this module").isNotBlank();
        return List.of(paths.split(File.pathSeparator)).stream()
                .map(path -> "'" + path.replace("\\", "/") + "'").collect(Collectors.joining(", "));
    }

    private void write(String path, String text) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static String path(Path path) {
        return path.toString().replace("\\", "/");
    }

    /**
     * The {@code gradle.properties} of every test project. Toolchains are found on the machine and never downloaded.
     * Gradle looks for them in the Java that runs it and in common places such as {@code /usr/lib/jvm}, but not where
     * a CI runner installs them, so the Java of these tests and the Java 25 that the runner exports as
     * {@code JAVA_HOME_25_X64}, if it does, are listed. The generator needs Java 25 whatever Java runs Gradle.
     *
     * @param extra further lines, such as {@code org.gradle.java.home=...}
     */
    private static String gradleProperties(String... extra) {
        List<String> installations = new ArrayList<>();
        installations.add(path(Path.of(System.getProperty("java.home"))));
        String java25 = System.getenv("JAVA_HOME_25_X64");
        if (java25 != null && !java25.isBlank()) {
            installations.add(path(Path.of(java25)));
        }
        List<String> lines = new ArrayList<>();
        lines.add("org.gradle.java.installations.auto-download=false");
        lines.add("org.gradle.java.installations.paths=" + String.join(",", installations));
        lines.addAll(List.of(extra));
        return String.join("\n", lines) + "\n";
    }

    /** A Java project that applies the plugin, with the reference model as its schema document. */
    private void createProject(String extraSettings) throws IOException {
        write("settings.gradle", "rootProject.name = 'sample'\n" + extraSettings);
        write("gradle.properties", gradleProperties());
        write("build.gradle", """
                plugins {
                    id 'java'
                    id 'io.github.enricoag1982.cairn.types'
                }

                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(25)
                    }
                }

                dependencies {
                    cairnCodegen files(%s)
                    implementation files(%s)
                }

                cairn {
                    packageName = 'com.example.model'
                }

                tasks.register('gradleJava') {
                    doLast {
                        println 'Gradle runs on Java ' + System.getProperty('java.specification.version')
                    }
                }
                """.formatted(files("cairn.generatorClasspath"), files("cairn.apiClasspath")));
        write(DOCUMENT, Files.readString(REFERENCE_MODEL));
        write("src/main/java/com/example/Hiring.java", """
                package com.example;

                import com.example.model.Person;
                import io.github.enricoag1982.cairn.data.Write;
                import java.time.Instant;

                public final class Hiring {
                    public static Write.Create hireAlice() {
                        return Person.create().as("alice").name("Alice").effectiveFrom(Instant.parse("2026-01-01T00:00:00Z"));
                    }
                }
                """);
    }

    private GradleRunner gradle(String... arguments) {
        List<String> all = new ArrayList<>(List.of(arguments));
        all.add("--stacktrace");
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath().withArguments(all);
    }

    private Path generated(String type) {
        return project.resolve("build/generated/sources/cairn/java/main/com/example/model/" + type + ".java");
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    @DisplayName("Scenario: Generating in a Gradle build")
    void generatingInAGradleBuild() throws IOException {
        createProject("");

        BuildResult result = gradle("compileJava", "--configuration-cache").build();

        assertThat(result.task(":generateCairnTypes").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(generated("Person")).exists();
        assertThat(generated("LeiChecksumRuleV1")).exists();
        assertThat(project.resolve("build/classes/java/main/com/example/Hiring.class")).exists();
        assertThat(project.resolve("build/classes/java/main/com/example/model/Person.class")).exists();
    }

    @Test
    @DisplayName("Scenario: Nothing changed")
    void nothingChanged() throws IOException {
        createProject("");
        gradle("compileJava", "--configuration-cache").build();

        BuildResult again = gradle("compileJava", "--configuration-cache").build();

        assertThat(again.task(":generateCairnTypes").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(again.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(again.getOutput()).contains("Reusing configuration cache");
    }

    @Test
    @DisplayName("Scenario: A document with problems fails the build")
    void aDocumentWithProblemsFailsTheBuild() throws IOException {
        createProject("");
        List<String> lines = Files.readAllLines(REFERENCE_MODEL);
        int organization = lines.indexOf("  Organization:");
        assertThat(organization).isNotNegative();
        assertThat(lines.get(organization + 1)).isEqualTo("    supertype: Party");
        lines.set(organization + 1, "    supertype: Prty");
        write(DOCUMENT, String.join("\n", lines) + "\n");

        BuildResult result = gradle("compileJava", "--configuration-cache").buildAndFail();

        assertThat(result.task(":generateCairnTypes").getOutcome()).isEqualTo(TaskOutcome.FAILED);
        assertThat(result.getOutput()).contains("reference-model.cairn.yaml:" + (organization + 1) + ":3: UNKNOWN_REFERENCE "
                + "Organization: ");
        assertThat(result.task(":compileJava")).as("Java is not compiled").isNull();
    }

    @Test
    @DisplayName("Scenario: Gradle on an older Java")
    void gradleOnAnOlderJava() throws IOException {
        Path older = olderJava();
        assumeTrue(older != null, "A Java 17 to 24 under /usr/lib/jvm to run Gradle with");
        createProject("");
        write("gradle.properties", gradleProperties("org.gradle.java.home=" + path(older)));

        BuildResult result = gradle("gradleJava", "compileJava").build();

        assertThat(result.getOutput()).contains("Gradle runs on Java " + olderVersion(older))
                .doesNotContain("Gradle runs on Java 25");
        assertThat(result.task(":generateCairnTypes").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(generated("Person")).exists();
        assertThat(project.resolve("build/classes/java/main/com/example/Hiring.class")).exists();
    }

    // ------------------------------------------------------------------ what else the design promises

    @Test
    void theDocumentsOfTheExtensionAddToTheDefaultOnesOrReplaceThem() throws IOException {
        createProject("");
        write("model/crm.cairn.yaml", "cairn: 1\norigin: { name: crm, version: \"1.0.0\" }\n"
                + "entityTypes:\n  Lead: {}\n");
        Files.delete(project.resolve("src/main/java/com/example/Hiring.java"));
        String script = Files.readString(project.resolve("build.gradle"));

        Files.writeString(project.resolve("build.gradle"), script.replace("packageName = 'com.example.model'",
                "packageName = 'com.example.model'\n    schemaDocuments.from('model/crm.cairn.yaml')"));
        gradle("generateCairnTypes").build();
        assertThat(generated("Lead")).as("a document that the build names").exists();
        assertThat(generated("Person")).as("the document in the default place").exists();

        Files.writeString(project.resolve("build.gradle"), script.replace("packageName = 'com.example.model'",
                "packageName = 'com.example.model'\n    schemaDocuments.setFrom('model/crm.cairn.yaml')"));
        gradle("generateCairnTypes").build();
        assertThat(generated("Lead")).exists();
        assertThat(generated("Person")).as("setFrom replaces the default").doesNotExist();
    }

    @Test
    void theGenerationIsCached() throws IOException {
        createProject("buildCache { local { directory = file('cache') } }\n");

        BuildResult first = gradle("compileJava", "--build-cache").build();
        deleteRecursively(project.resolve("build"));
        BuildResult second = gradle("compileJava", "--build-cache").build();

        assertThat(first.task(":generateCairnTypes").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(second.task(":generateCairnTypes").getOutcome()).isEqualTo(TaskOutcome.FROM_CACHE);
        assertThat(generated("Person")).exists();
    }

    private static String olderVersion(Path jdk) throws IOException {
        Matcher version = Pattern.compile("JAVA_VERSION=\"(\\d+)")
                .matcher(Files.readString(jdk.resolve("release")));
        assertThat(version.find()).isTrue();
        return version.group(1);
    }

    /** A JDK older than 25, of Java 17 or newer, which Gradle can run on, if the machine has one. */
    private static Path olderJava() throws IOException {
        Path jvm = Path.of("/usr/lib/jvm");
        if (!Files.isDirectory(jvm)) {
            return null;
        }
        try (Stream<Path> installations = Files.list(jvm)) {
            return installations.sorted().filter(Files::isDirectory).filter(path -> {
                try {
                    String release = Files.readString(path.resolve("release"));
                    return Files.isExecutable(path.resolve("bin/java")) && release.matches("(?s).*JAVA_VERSION=\"(17|18|19|2[0-4])[.\"].*");
                } catch (IOException e) {
                    return false;
                }
            }).findFirst().orElse(null);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> tree = Files.walk(directory)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
