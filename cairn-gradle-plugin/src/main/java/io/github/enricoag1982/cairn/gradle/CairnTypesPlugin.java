package io.github.enricoag1982.cairn.gradle;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.artifacts.ResolvableConfiguration;
import org.gradle.api.attributes.Usage;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;

/**
 * The plugin {@code io.github.enricoag1982.cairn.types} (ADR-0021, capability {@code java-types}). In a project with
 * the {@code java} plugin it generates the Java types of the project's schema documents into
 * {@code build/generated/sources/cairn/java/main}, a source directory of the main source set, before the Java code is
 * compiled.
 *
 * <p>The plugin adds:
 *
 * <ul>
 * <li>the extension {@code cairn}, see {@link CairnExtension}: {@code schemaDocuments} and {@code packageName};
 * <li>the configuration {@code cairnCodegen}, to which the project adds the generator and what it needs, until it is
 * published;
 * <li>the task {@code generateCairnTypes}, see {@link GenerateCairnTypes}.
 * </ul>
 *
 * <p>The generated code depends on {@code cairn-api}, which the project adds to its own dependencies. The plugin works
 * with Gradle's configuration cache.
 */
public class CairnTypesPlugin implements Plugin<Project> {

    /** The Java that the generator needs; the plugin runs it with a launcher of this version. */
    static final int GENERATOR_JAVA_VERSION = 25;

    /** The name of the extension. */
    public static final String EXTENSION_NAME = "cairn";

    /** The name of the configuration that holds the generator. */
    public static final String CONFIGURATION_NAME = "cairnCodegen";

    /** The name of the task that generates the types. */
    public static final String TASK_NAME = "generateCairnTypes";

    /**
     * Creates the plugin; Gradle does when a build applies it.
     */
    public CairnTypesPlugin() {
    }

    @Override
    public void apply(Project project) {
        CairnExtension extension = project.getExtensions().create(EXTENSION_NAME, CairnExtension.class);
        extension.getSchemaDocuments().convention(project.fileTree("src/main/cairn", tree ->
                tree.include("**/*.cairn.yaml", "**/*.cairn.json")));

        // The dependencies are declared on one configuration and resolved through another, as Gradle asks.
        NamedDomainObjectProvider<DependencyScopeConfiguration> declared = project.getConfigurations()
                .dependencyScope(CONFIGURATION_NAME, configuration -> configuration.setDescription(
                        "The generator of Java types from Cairn schema documents, and what it needs."));
        NamedDomainObjectProvider<ResolvableConfiguration> generator = project.getConfigurations()
                .resolvable(CONFIGURATION_NAME + "Classpath", configuration -> {
                    configuration.setDescription("The class path of the generator of Java types.");
                    configuration.extendsFrom(declared.get());
                    configuration.getAttributes().attribute(Usage.USAGE_ATTRIBUTE,
                            project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME));
                });

        project.getPluginManager().withPlugin("java", applied -> {
            JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
            TaskProvider<GenerateCairnTypes> generate = project.getTasks().register(TASK_NAME,
                    GenerateCairnTypes.class, task -> {
                        task.setDescription("Generates Java types from the Cairn schema documents.");
                        task.setGroup("build");
                        task.getSchemaDocuments().from(extension.getSchemaDocuments());
                        task.getPackageName().convention(extension.getPackageName());
                        task.getGeneratorClasspath().from(generator);
                        task.getLauncher().convention(toolchains.launcherFor(spec ->
                                spec.getLanguageVersion().set(JavaLanguageVersion.of(GENERATOR_JAVA_VERSION))));
                        task.getOutputDirectory().convention(
                                project.getLayout().getBuildDirectory().dir("generated/sources/cairn/java/main"));
                    });
            project.getExtensions().getByType(SourceSetContainer.class).named("main", main ->
                    main.getJava().srcDir(generate.flatMap(GenerateCairnTypes::getOutputDirectory)));
        });
    }
}
