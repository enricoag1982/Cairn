package io.github.enricoag1982.cairn.core.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.core.kv.KeyValueStores;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A store kept in a directory through a commit log and snapshot files refuses a directory that a RocksDB store wrote, which
 * has a subdirectory {@code rocksdb} (capability {@code durability}, ADR-0028), without a RocksDB library: it creates
 * nothing in it, and releases it. The other direction is tested in {@code cairn-rocksdb}.
 */
class DirectoryStoreOtherKindTest {

    @TempDir
    Path directory;

    private List<String> names() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aDirectoryWithASubdirectoryRocksdbIsRefusedNamingItAndNothingIsCreatedInIt() throws IOException {
        Files.createDirectory(directory.resolve("rocksdb"));
        Files.writeString(directory.resolve("rocksdb").resolve("CURRENT"), "MANIFEST-000005\n");

        assertThatThrownBy(() -> Stores.durable(directory)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(directory.toString()).hasMessageContaining("RocksDB store")
                .hasMessageContaining("RocksDbStores.open");

        assertThat(names()).as("no log, no snapshots, and the database untouched").containsExactly("lock", "rocksdb");
        assertThat(Files.readString(directory.resolve("rocksdb").resolve("CURRENT"))).isEqualTo("MANIFEST-000005\n");
        KeyValueStores.lockDirectory(directory).run();
    }

    @Test
    void aDirectoryThatIsInUseIsReportedAsInUseBeforeItsKindIsChecked() throws IOException {
        Files.createDirectory(directory.resolve("rocksdb"));
        Runnable release = KeyValueStores.lockDirectory(directory);
        try {
            assertThatThrownBy(() -> Stores.durable(directory)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("held by another open store");
        } finally {
            release.run();
        }
    }

    @Test
    void aFileNamedRocksdbIsNotAStoreAndTheDirectoryOpensAsADirectoryStore() throws IOException {
        Files.writeString(directory.resolve("rocksdb"), "a file");

        Stores.durable(directory).close();

        assertThat(names()).contains("lock", "log", "snapshots", "rocksdb");
    }
}
