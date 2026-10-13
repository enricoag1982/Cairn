import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

/**
 * The command line of the generator of Java types (cairn-codegen's main class): the package, the output directory and
 * the schema documents. The documents are listed when the task runs, not when the build is configured, so that a
 * document added later is generated too, also from the configuration cache. The documents and the package are inputs of
 * the task; the output directory is declared by the task as its output.
 */
class GenerateTypesArguments(
    @get:Input val packageName: Provider<String>,
    @get:Internal val outputDirectory: Provider<File>,
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY) val documents: FileCollection,
) : CommandLineArgumentProvider {

    override fun asArguments(): Iterable<String> =
        listOf("--package", packageName.get(), "--output", outputDirectory.get().absolutePath) +
            documents.files.sortedBy { it.absolutePath }.map { it.absolutePath }
}
