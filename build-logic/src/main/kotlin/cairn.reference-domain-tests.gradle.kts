// For modules whose tests read the reference domain: docs/reference/reference-domain.md and the schema document
// docs/reference/reference-model.cairn.yaml. A change to either reruns them.

tasks.withType<Test>().configureEach {
    inputs.file(rootProject.layout.projectDirectory.file("docs/reference/reference-domain.md"))
        .withPropertyName("referenceDomain")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("docs/reference/reference-model.cairn.yaml"))
        .withPropertyName("referenceModelDocument")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
