// Shared configuration for Cairn's Gradle plugins (ADR-0015, design D1 of add-java-type-generation).
//
// A Gradle plugin runs on the Java that runs Gradle, and Gradle 9 runs on Java 17 or newer. So the plugin is built with
// the toolchain of every Cairn library (Java 25) but compiled for Java 17: it must not use anything newer, and it has
// no dependency on Cairn's classes, which need Java 25. The lint and Javadoc rules are those of cairn.java-library.

plugins {
    id("cairn.java-library")
    `java-gradle-plugin`
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

// The tests run Gradle builds (TestKit) in a temporary project. The project's builds get the class path of the generator
// and the jar of cairn-api, which the plugin's own class path does not hold, from the configurations pluginTestGenerator
// and pluginTestApi: a module's build file declares what goes into them, and the test task passes their files as the
// system properties cairn.generatorClasspath and cairn.apiClasspath, each a path list. The resolvable configurations do
// not ask for a Java version, because the plugin is compiled for an older Java than the libraries it generates for.
fun testClasspath(name: String, description: String): Provider<out Configuration> {
    val declared = configurations.dependencyScope("pluginTest$name") {
        this.description = description
    }
    return configurations.resolvable("pluginTest${name}Classpath") {
        this.description = "The files of $description"
        extendsFrom(declared.get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        }
    }
}

val generatorClasspath = testClasspath("Generator", "the generator of Java types, with its runtime class path.")
val apiClasspath = testClasspath("Api", "the API that generated code compiles against.")

tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(PathListSystemProperty("cairn.generatorClasspath", files(generatorClasspath)))
    jvmArgumentProviders.add(PathListSystemProperty("cairn.apiClasspath", files(apiClasspath)))
}
