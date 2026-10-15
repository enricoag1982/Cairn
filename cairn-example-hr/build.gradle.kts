plugins {
    id("cairn.java-library")
    id("cairn.generated-types")
    id("cairn.hr-domain-tests")
}

description = "Cairn example: the HR system of the fictional company Brightwater, built as an application would build it on Cairn's public API, with its end-to-end tests"

generatedTypes {
    packageName = "io.github.enricoag1982.cairn.example.hr.model"
}

dependencies {
    cairnCodegen(project(":cairn-codegen"))
    api(project(":cairn-api"))
    api(project(":cairn-documents"))
    testImplementation(project(":cairn-core"))
    testImplementation(project(":cairn-agent-tools"))
    testImplementation(testFixtures(project(":cairn-api")))
}
