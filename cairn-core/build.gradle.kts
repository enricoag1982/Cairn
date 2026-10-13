plugins {
    id("cairn.java-library")
}

description = "Cairn engine: schema validation, compatibility checks, the in-memory schema registry and the in-memory store"

dependencies {
    api(project(":cairn-api"))
    implementation(libs.re2j)
    testImplementation(testFixtures(project(":cairn-api")))
}
