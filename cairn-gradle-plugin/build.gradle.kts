plugins {
    id("cairn.gradle-plugin")
    id("cairn.reference-domain-tests")
}

description = "Cairn Gradle plugin: generates Java types from a project's schema documents before its Java code compiles"

gradlePlugin {
    plugins {
        register("cairnTypes") {
            id = "io.github.enricoag1982.cairn.types"
            implementationClass = "io.github.enricoag1982.cairn.gradle.CairnTypesPlugin"
            displayName = "Cairn types"
            description = "Generates Java types from Cairn schema documents"
        }
    }
}

dependencies {
    pluginTestGenerator(project(":cairn-codegen"))
    pluginTestApi(project(":cairn-api"))
}
