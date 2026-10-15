// For the module whose scenario tests must hold for a RocksDB store too (ADR-0028, design D6 of add-rocksdb-store): a
// second test task runs the named test classes again, with the system property cairn.test.backend set to rocksdb, which
// only the test fixtures read, to build their stores as RocksDB stores in temporary directories, closed after each test.
// The module that holds the RocksDB store, which depends on this one, is on the runtime class path of that task only. The
// task is part of check, and so of build.
//
//     plugins { id("cairn.java-library"); id("cairn.rocksdb-conformance") }
//     rocksdbConformance { testClasses = listOf("com.example.SomeScenariosTest") }
//     dependencies { rocksdbBackend(project(":cairn-rocksdb")) }

plugins {
    `java-library`
}

val conformance = extensions.create<RocksdbConformanceExtension>("rocksdbConformance")

// The dependencies are declared on one configuration and resolved through another, as Gradle asks.
val declaredBackend = configurations.dependencyScope("rocksdbBackend") {
    description = "The module that holds the RocksDB store, with what it needs to run."
}
val backendClasspath = configurations.resolvable("rocksdbBackendClasspath") {
    description = "The class path of the RocksDB store, with what it needs to run."
    extendsFrom(declaredBackend.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
    // The classes of this module are on the class path already, as its main source set's, and not to be there twice as a jar.
    exclude(group = project.group.toString(), module = project.name)
}

val testSourceSet = the<SourceSetContainer>().named("test")

val rocksdbConformanceTest = tasks.register<Test>("rocksdbConformanceTest") {
    description = "Runs the scenario tests of the engine's capabilities against a RocksDB store."
    group = "verification"
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath + files(backendClasspath)
    systemProperty("cairn.test.backend", "rocksdb")
    // The fixtures close the stores they open after each test, with an extension that JUnit finds in META-INF/services.
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    filter {
        isFailOnNoMatchingTests = true
        conformance.testClasses.get().forEach { includeTestsMatching(it) }
    }
}

tasks.named("check") {
    dependsOn(rocksdbConformanceTest)
}
