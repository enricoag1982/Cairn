plugins {
    id("sysrec.java-library")
    `java-test-fixtures`
}

description = "sysRec public API: schema definitions, the resolved schema and the schema registry"

tasks.test {
    // ReferenceModelTest compares the reference model fixture with this page.
    inputs.file(rootProject.layout.projectDirectory.file("docs/reference/reference-domain.md"))
        .withPropertyName("referenceDomain")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
