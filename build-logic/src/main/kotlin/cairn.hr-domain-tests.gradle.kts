// For modules whose tests read the HR domain page, docs/reference/hr-domain.md. A change to it reruns them.

tasks.withType<Test>().configureEach {
    inputs.file(rootProject.layout.projectDirectory.file("docs/reference/hr-domain.md"))
        .withPropertyName("hrDomain")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
