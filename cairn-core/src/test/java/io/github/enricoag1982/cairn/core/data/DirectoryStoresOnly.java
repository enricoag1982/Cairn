package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.TestBackend;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

/**
 * Marks a test that is about stores kept in a directory through a commit log and snapshot files: their segments, snapshot
 * files and the crashes that cut them. The run of the scenario tests against RocksDB stores (see {@link TestBackend}) skips
 * it, because a RocksDB store has no such files; its own scenarios are in {@code cairn-rocksdb}.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@DisabledIfSystemProperty(named = TestBackend.PROPERTY, matches = "rocksdb")
@interface DirectoryStoresOnly {
}
