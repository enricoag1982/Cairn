plugins {
    id("cairn.java-library")
    id("cairn.reference-domain-tests")
}

description = "Cairn schema documents and transaction payloads: reading YAML and JSON with locations for problems, writing schema documents and the JSON forms of results, and the published and generated JSON Schemas"

dependencies {
    api(project(":cairn-api"))
    implementation(libs.snakeyaml.engine)
    testImplementation(project(":cairn-core"))
    testImplementation(testFixtures(project(":cairn-api")))
    testImplementation(libs.json.schema.validator)
}
