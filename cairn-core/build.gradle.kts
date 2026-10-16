plugins {
    id("cairn.java-library")
    id("cairn.rocksdb-conformance")
}

description = "Cairn engine: schema validation, compatibility checks, the in-memory schema registry, the in-memory store and the engine of stores over a persistent key-value store"

// The scenario tests of these capabilities must hold for a RocksDB store too (ADR-0028): transactions, entity-versions,
// views, relationships, entity-rules, checks, derived-attributes, queries, purge, change-feed, attribute-access, and the
// scenarios of durability that do not concern the files of directory stores.
rocksdbConformance {
    val core = "io.github.enricoag1982.cairn.core"
    testClasses = listOf(
        "$core.TransactionsScenariosTest", "$core.EntityVersionsScenariosTest", "$core.ViewsScenariosTest",
        "$core.RelationshipsScenariosTest", "$core.EntityRulesScenariosTest", "$core.QueriesScenariosTest",
        "$core.PurgeScenariosTest", "$core.ChangeFeedScenariosTest", "$core.data.DurabilityScenariosTest",
        "$core.ChecksScenariosTest", "$core.DerivedAttributesScenariosTest",
        "$core.DerivedAttributesDurabilityTest", "$core.OutlivingTargetsTest",
        "$core.AttributeAccessScenariosTest", "$core.RestrictedViewsTest", "$core.RestrictedQueriesTest",
        "$core.CommitClearancesTest", "$core.AttributeAccessDurabilityTest", "$core.LaterClassificationsTest",
        "$core.RefusedTransactionsTest", "$core.RedactedProblemsTest", "$core.RegistrationClearanceTest",
    )
}

dependencies {
    api(project(":cairn-api"))
    implementation(libs.re2j)
    testImplementation(testFixtures(project(":cairn-api")))
    // The tests of checks need an expression language (ADR-0030): the engine finds this one through ServiceLoader. It is
    // on the run-time class path only, so that the tests do not compile against it, and it depends on the API, not on this
    // module, so there is no cycle.
    testRuntimeOnly(project(":cairn-cel"))
    rocksdbBackend(project(":cairn-rocksdb"))
}
