plugins {
    id("cairn.java-library")
}

description = "Cairn engine: schema validation, compatibility checks and the in-memory schema registry"

dependencies {
    api(project(":cairn-api"))
    implementation(libs.re2j)
    testImplementation(testFixtures(project(":cairn-api")))
}
