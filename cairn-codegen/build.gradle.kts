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
}
