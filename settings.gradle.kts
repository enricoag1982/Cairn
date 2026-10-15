pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "cairn"

include(
    "cairn-api", "cairn-core", "cairn-documents", "cairn-agent-tools", "cairn-codegen", "cairn-gradle-plugin",
    "cairn-example-hr",
)
