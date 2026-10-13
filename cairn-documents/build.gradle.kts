plugins {
    id("cairn.java-library")
    id("cairn.reference-domain-tests")
}

description = "Cairn schema documents: reading and writing YAML and JSON documents, with locations for problems, and the published JSON Schema"

dependencies {
    api(project(":cairn-api"))
    implementation(libs.snakeyaml.engine)
    testImplementation(project(":cairn-core"))
    testImplementation(testFixtures(project(":cairn-api")))
    testImplementation(libs.json.schema.validator)
}
