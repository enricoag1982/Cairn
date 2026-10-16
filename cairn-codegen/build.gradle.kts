plugins {
    id("cairn.java-library")
    id("cairn.reference-domain-tests")
    id("cairn.reference-types")
}

description = "Cairn type generator: Java interfaces, snapshots, builders of writes and rule base classes generated from schema documents, with a command line"

dependencies {
    api(project(":cairn-api"))
    implementation(project(":cairn-documents"))
    implementation(project(":cairn-core"))
    testImplementation(testFixtures(project(":cairn-api")))
    // The generator checks documents with a registry, which compiles the derived attributes of the tests (ADR-0031): it
    // finds the module through ServiceLoader, as the generator of a build does when cairn-cel is on its class path.
    testRuntimeOnly(project(":cairn-cel"))
}
