plugins {
    id("cairn.java-library")
    id("cairn.reference-domain-tests")
    id("cairn.rocksdb-tests")
}

description = "Cairn RocksDB store: a durable store kept in RocksDB, with its metadata as keys and its write-ahead log as the commit log"

dependencies {
    api(project(":cairn-core"))
    implementation(libs.rocksdbjni)
    testImplementation(testFixtures(project(":cairn-api")))
}
