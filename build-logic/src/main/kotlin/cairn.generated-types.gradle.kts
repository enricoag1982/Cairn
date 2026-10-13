// For modules that generate Java types from their own schema documents (design D1 of add-hr-example): the types of the
// documents in src/main/cairn go into the main source set, as the Gradle plugin of Cairn does for an application. That
// plugin cannot be applied to another project of this build, so the task runs the generator's main class, which the
// module names as a dependency (cairnCodegen), with the Java 25 launcher. A change to the generator, a document or the
// package generates the types again. The documents are resources of the module too, to register at start-up.
//
//     plugins { id("cairn.java-library"); id("cairn.generated-types") }
//     generatedTypes { packageName = "com.example.model" }
//     dependencies { cairnCodegen(project(":cairn-codegen")) }

plugins {
    `java-library`
}

val generatedTypes = extensions.create<GeneratedTypesExtension>("generatedTypes")

// The dependencies are declared on one configuration and resolved through another, as Gradle asks.
val declaredGenerator = configurations.dependencyScope("cairnCodegen") {
    description = "The generator of Java types from Cairn schema documents."
}
val generatorClasspath = configurations.resolvable("cairnCodegenClasspath") {
    description = "The class path of the generator of Java types, with what it needs to run."
    extendsFrom(declaredGenerator.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
}

val documents = fileTree("src/main/cairn") {
    include("**/*.cairn.yaml", "**/*.cairn.json")
}
val generatedSources = layout.buildDirectory.dir("generated/sources/cairn/java/main")
val javaToolchains = the<JavaToolchainService>()
val javaExtension = the<JavaPluginExtension>()

val generateCairnTypes = tasks.register<JavaExec>("generateCairnTypes") {
    description = "Generates the Java types of the module's schema documents."
    group = "build"
    classpath = files(generatorClasspath)
    mainClass = "io.github.enricoag1982.cairn.codegen.CairnCodegen"
    javaLauncher = javaToolchains.launcherFor(javaExtension.toolchain)
    outputs.dir(generatedSources).withPropertyName("generatedTypes")
    argumentProviders.add(
        GenerateTypesArguments(generatedTypes.packageName, generatedSources.map { it.asFile }, documents)
    )
}

sourceSets.main {
    java.srcDir(generateCairnTypes)
    resources.srcDir("src/main/cairn")
}
