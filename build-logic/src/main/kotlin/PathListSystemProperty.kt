import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

/**
 * Passes the files of a file collection to a forked JVM as a system property: a list of absolute paths, separated as
 * class paths are. The files, not their locations, are the input of the task: a task is up to date when the content of
 * the jars is, wherever they are.
 */
class PathListSystemProperty(
    @get:Input val name: String,
    @get:Classpath val files: FileCollection,
) : CommandLineArgumentProvider {

    override fun asArguments(): Iterable<String> =
        listOf("-D$name=" + files.files.joinToString(File.pathSeparator) { it.absolutePath })
}
