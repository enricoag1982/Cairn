plugins {
    id("cairn.java-library")
}

description = "Cairn engine: schema validation, compatibility checks and the in-memory schema registry"

dependencies {
    api(project(":cairn-api"))
    testImplementation(testFixtures(project(":cairn-api")))
}
