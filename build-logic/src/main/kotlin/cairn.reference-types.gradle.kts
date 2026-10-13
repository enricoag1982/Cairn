// For modules that hold the generator of Java types (design D1 of add-java-type-generation): generates the types of the
// reference domain, docs/reference/reference-model.cairn.yaml, into a source directory of the test source set, so that
// typed tests can use them. The generator is the project's own: the task runs its main class on the main runtime class
// path, with the Java 25 launcher, and a change to the generator or to the document generates the types again.

plugins {
    `java-library`
}

val referenceModel = rootProject.layout.projectDirectory.file("docs/reference/reference-model.cairn.yaml")
val generatedTypes = layout.buildDirectory.dir("generated/sources/referenceTypes/java/test")
val javaToolchains = the<JavaToolchainService>()
val javaExtension = the<JavaPluginExtension>()

val generateReferenceTypes = tasks.register<JavaExec>("generateReferenceTypes") {
    description = "Generates the Java types of the reference domain for the tests."
    group = "build"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.enricoag1982.cairn.codegen.CairnCodegen"
    javaLauncher = javaToolchains.launcherFor(javaExtension.toolchain)
    inputs.file(referenceModel).withPropertyName("referenceModel").withPathSensitivity(PathSensitivity.NAME_ONLY)
    outputs.dir(generatedTypes).withPropertyName("generatedTypes")
    args(
        "--package", "io.github.enricoag1982.cairn.reference.types",
        "--output", generatedTypes.get().asFile.absolutePath,
        referenceModel.asFile.absolutePath,
    )
}

sourceSets.test {
    java.srcDir(generateReferenceTypes)
}
