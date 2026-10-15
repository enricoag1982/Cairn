plugins {
    id("cairn.java-library")
    id("cairn.rocksdb-conformance")
}

description = "Cairn engine: schema validation, compatibility checks, the in-memory schema registry, the in-memory store and the engine of stores over a persistent key-value store"

// The scenario tests of these capabilities must hold for a RocksDB store too (ADR-0028): transactions, entity-versions,
// views, relationships, entity-rules, queries, purge, change-feed, and the scenarios of durability that do not concern the
// files of directory stores.
rocksdbConformance {
    val core = "io.github.enricoag1982.cairn.core"
    testClasses = listOf(
        "$core.TransactionsScenariosTest", "$core.EntityVersionsScenariosTest", "$core.ViewsScenariosTest",
        "$core.RelationshipsScenariosTest", "$core.EntityRulesScenariosTest", "$core.QueriesScenariosTest",
        "$core.PurgeScenariosTest", "$core.ChangeFeedScenariosTest", "$core.data.DurabilityScenariosTest",
    )
}

dependencies {
    api(project(":cairn-api"))
    implementation(libs.re2j)
    testImplementation(testFixtures(project(":cairn-api")))
    rocksdbBackend(project(":cairn-rocksdb"))
}
