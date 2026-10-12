plugins {
    id("sysrec.java-library")
}

description = "sysRec engine: schema validation, compatibility checks and the in-memory schema registry"

dependencies {
    api(project(":sysrec-api"))
    testImplementation(testFixtures(project(":sysrec-api")))
}
