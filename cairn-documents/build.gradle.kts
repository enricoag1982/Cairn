plugins {
    id("cairn.java-library")
    id("cairn.reference-domain-tests")
}

description = "Cairn schema documents: reading YAML and JSON documents into schema definitions, with locations for problems"

dependencies {
    api(project(":cairn-api"))
    implementation(libs.snakeyaml.engine)
    testImplementation(project(":cairn-core"))
    testImplementation(testFixtures(project(":cairn-api")))
    testImplementation(libs.json.schema.validator)
}
