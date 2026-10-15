import org.gradle.api.provider.ListProperty

/**
 * The settings of the convention plugin cairn.rocksdb-conformance: which test classes run again against a RocksDB store.
 */
abstract class RocksdbConformanceExtension {

    /** The fully qualified names of the test classes, whose tests build their stores through the test backend switch. */
    abstract val testClasses: ListProperty<String>
}
