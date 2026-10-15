package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.Store;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The backend that the stores of the scenario tests are built on (ADR-0028, design D6 of {@code add-rocksdb-store}). The
 * tests of the capabilities that every kind of store must show build their stores through the fixtures, which ask this
 * class: by default an in-memory store, and when the system property {@value #PROPERTY} is {@code rocksdb}, which only the
 * build's second run of those tests sets, a RocksDB store in a new temporary directory. A test that stays on in-memory
 * stores builds them directly; a test about the files of directory stores builds directory stores.
 *
 * <p>The RocksDB store is in a module that depends on this one, so it is found by name, with reflection, on the class
 * path of that run. {@link Cleanup}, which JUnit loads when auto-detection of extensions is on, as it is in that run,
 * closes every store that was opened and deletes its directory after each test.
 */
public final class TestBackend {

    /** The system property that names the backend: {@code rocksdb}, or anything else for in-memory stores. */
    public static final String PROPERTY = "cairn.test.backend";

    private static final String ROCKSDB_STORES = "io.github.enricoag1982.cairn.rocksdb.RocksDbStores";

    /** A store that was opened, and the directory to delete when the test is over. */
    private record Opened(DurableStore store, Path directory) {
    }

    private static final List<Opened> OPENED = new ArrayList<>();

    private TestBackend() {
    }

    /**
     * Tells whether the tests run against RocksDB stores.
     *
     * @return {@code true} if the system property {@value #PROPERTY} is {@code rocksdb}
     */
    public static boolean isRocksDb() {
        return "rocksdb".equals(System.getProperty(PROPERTY));
    }

    /**
     * Opens an empty store: an in-memory store, or, on the RocksDB backend, a RocksDB store in a new temporary directory,
     * which is closed and deleted after the test.
     *
     * @param settings the settings of the store
     * @return the store
     */
    public static Store open(StoreSettings settings) {
        if (!isRocksDb()) {
            return Stores.inMemory(settings);
        }
        try {
            Path directory = Files.createTempDirectory("cairn-rocksdb-");
            DurableStore store = openRocksDb(directory, settings);
            synchronized (OPENED) {
                OPENED.add(new Opened(store, directory));
            }
            return store;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Opens a RocksDB store in a directory, which the caller closes.
     *
     * @param directory the directory
     * @param settings  the settings of the store
     * @return the store
     * @throws IllegalStateException if the RocksDB store is not on the class path
     */
    public static DurableStore openRocksDb(Path directory, StoreSettings settings) {
        try {
            Method open = Class.forName(ROCKSDB_STORES).getMethod("open", Path.class, StoreSettings.class);
            return (DurableStore) open.invoke(null, directory, settings);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("The RocksDB store is not on the class path of this run.", e);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /** Closes the stores that {@link #open} made, and deletes their directories, after each test. */
    public static final class Cleanup implements AfterEachCallback {

        /** Created by JUnit. */
        public Cleanup() {
        }

        @Override
        public void afterEach(ExtensionContext context) {
            List<Opened> done;
            synchronized (OPENED) {
                done = List.copyOf(OPENED);
                OPENED.clear();
            }
            for (Opened opened : done) {
                try {
                    opened.store().close();
                } finally {
                    delete(opened.directory());
                }
            }
        }

        private static void delete(Path directory) {
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
