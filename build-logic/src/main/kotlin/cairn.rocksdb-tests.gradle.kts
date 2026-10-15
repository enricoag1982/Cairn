// For modules whose tests open RocksDB stores (ADR-0028, design D5 of add-rocksdb-store). The RocksDB library loads a
// native library, which Java 24 and later warn about unless native access is enabled for the code that loads it: the
// tests run on the class path, so for the unnamed module. A test that stops a process without closing its store, to show
// that nothing committed is lost, forks a JVM with the class path of the tests, which the task passes as the system
// property cairn.testRuntimeClasspath, a path list.

plugins {
    `java-library`
}

val testSourceSet = the<SourceSetContainer>().named("test")

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgumentProviders.add(PathListSystemProperty("cairn.testRuntimeClasspath", testSourceSet.get().runtimeClasspath))
}
