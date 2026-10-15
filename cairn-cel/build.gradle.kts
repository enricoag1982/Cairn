plugins {
    id("cairn.java-library")
}

description = "Cairn checks in CEL: the expression language that compiles and runs the checks of schema definitions, on Google's CEL for Java"

// No module-info.java: the jars of CEL for Java share packages across jars and have no module names, so they cannot be
// resolved as modules. This project, like its dependencies, runs on the class path, as cairn-agent-tools does.
dependencies {
    api(project(":cairn-api"))
    implementation(libs.cel)
    testImplementation(project(":cairn-core"))
    testImplementation(testFixtures(project(":cairn-api")))
}
