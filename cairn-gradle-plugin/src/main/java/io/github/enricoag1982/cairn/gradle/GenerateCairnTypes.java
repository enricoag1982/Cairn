package io.github.enricoag1982.cairn.gradle;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

/**
 * Generates the Java types of schema documents (capability {@code java-types}). The task runs the generator in a
 * process of its own, with the Java launcher of a toolchain, because the generator needs a newer Java than the one that
 * may run Gradle.
 *
 * <p>The task is skipped when there are no documents, and up to date when the documents (their names and content), the
 * package and the generator are unchanged. It can be cached. If the documents have problems it fails, and its message
 * lists each problem with the document, line and column.
 */
@CacheableTask
public abstract class GenerateCairnTypes extends DefaultTask {

    /**
     * Creates the task; Gradle does, as {@code generateCairnTypes}.
     */
    public GenerateCairnTypes() {
    }

    /** The class that runs the generator from the command line. */
    static final String MAIN_CLASS = "io.github.enricoag1982.cairn.codegen.CairnCodegen";

    /**
     * The schema documents. Their paths relative to their directory and their content are inputs, because the
     * generated source names the documents.
     *
     * @return the documents
     */
    @InputFiles
    @SkipWhenEmpty
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSchemaDocuments();

    /**
     * The package of the generated types.
     *
     * @return the package name
     */
    @Input
    public abstract Property<String> getPackageName();

    /**
     * The class path of the generator: the jars that hold {@value #MAIN_CLASS} and what it needs.
     *
     * @return the class path
     */
    @Classpath
    public abstract ConfigurableFileCollection getGeneratorClasspath();

    /**
     * The Java launcher that runs the generator.
     *
     * @return the launcher
     */
    @Nested
    public abstract Property<JavaLauncher> getLauncher();

    /**
     * The directory that the generated source is written to. The generator replaces its content.
     *
     * @return the directory
     */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /**
     * Runs processes.
     *
     * @return the service
     */
    @Inject
    protected abstract ExecOperations getExecOperations();

    /**
     * Generates the types, or fails with the problems of the documents.
     */
    @TaskAction
    public void generate() {
        List<String> arguments = new ArrayList<>();
        arguments.add("--package");
        arguments.add(getPackageName().get());
        arguments.add("--output");
        arguments.add(getOutputDirectory().get().getAsFile().getAbsolutePath());
        getSchemaDocuments().getFiles().stream().sorted(Comparator.comparing(File::getAbsolutePath))
                .forEach(document -> arguments.add(document.getAbsolutePath()));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        ExecResult result = getExecOperations().javaexec(spec -> {
            spec.setExecutable(getLauncher().get().getExecutablePath().getAsFile().getAbsolutePath());
            spec.classpath(getGeneratorClasspath());
            spec.getMainClass().set(MAIN_CLASS);
            spec.args(arguments);
            spec.setStandardOutput(output);
            spec.setErrorOutput(errors);
            spec.setIgnoreExitValue(true);
        });

        String message = errors.toString(StandardCharsets.UTF_8).strip();
        if (result.getExitValue() != 0) {
            throw new GradleException("Cairn could not generate the types of the schema documents:\n" + message);
        }
        getLogger().info(output.toString(StandardCharsets.UTF_8).strip());
    }
}
