/**
 * Stores kept in RocksDB (ADR-0028): {@code RocksDbStores.open} gives a durable store whose entities, relationships and
 * metadata are keys in a RocksDB database, and whose commits are synchronous write batches. The module loads the native
 * library of RocksDB, so an application that runs it on the module path enables native access for the library's module,
 * with {@code --enable-native-access=rocksdbjni}.
 */
// The name follows the io.github.<user> convention; the user name ends in digits by necessity.
// rocksdbjni is an automatic module: its jar has no module descriptor and no Automatic-Module-Name, so its name comes from
// the file name (rocksdbjni-11.8.1.jar).
@SuppressWarnings({"module", "requires-automatic"})
module io.github.enricoag1982.cairn.rocksdb {
    requires transitive io.github.enricoag1982.cairn.core;
    requires rocksdbjni;

    exports io.github.enricoag1982.cairn.rocksdb;
}
