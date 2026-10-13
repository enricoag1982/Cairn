// For modules whose tests read docs/reference/reference-domain.md: a change to the page reruns them.

tasks.withType<Test>().configureEach {
    inputs.file(rootProject.layout.projectDirectory.file("docs/reference/reference-domain.md"))
        .withPropertyName("referenceDomain")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
