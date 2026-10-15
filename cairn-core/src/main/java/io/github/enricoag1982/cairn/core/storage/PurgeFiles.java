package io.github.enricoag1982.cairn.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Deletes the files that a purge makes unsafe to keep (ADR-0024, design D4 of {@code add-purge}): every snapshot file
 * older than the one that was written after the purge, and every log segment whose records are all at or below the
 * purge's basis, since they hold the purged data. A purge deletes the snapshot files first and the log segments after
 * them, as {@link Snapshots#prune(Path)} does: a crash in between never leaves a snapshot file without the log it needs.
 * Both steps can be repeated, so recovery repeats them to complete a purge that was interrupted.
 */
public final class PurgeFiles {

    private PurgeFiles() {
    }

    /**
     * Deletes the snapshot files whose basis is below a basis, and then the log segments that start at or below it. The
     * caller has written a snapshot file at that basis, or a later one, and the segments that start at or below the
     * basis hold records at or below it only, because the store starts a new segment after a purge.
     *
     * @param storeDirectory the directory of the store
     * @param basis          the basis of the newest snapshot file that holds the purge
     * @throws IOException if listing or deleting fails
     */
    public static void deleteOlder(Path storeDirectory, long basis) throws IOException {
        deleteSnapshotsBefore(storeDirectory, basis);
        deleteSegmentsUpTo(storeDirectory, basis);
    }

    /**
     * Deletes the snapshot files whose basis is below a basis.
     *
     * @param storeDirectory the directory of the store
     * @param basis          the basis of the snapshot file to keep
     * @throws IOException if listing or deleting fails
     */
    public static void deleteSnapshotsBefore(Path storeDirectory, long basis) throws IOException {
        Path directory = storeDirectory.resolve(FileFormat.SNAPSHOT_DIRECTORY);
        boolean deleted = false;
        for (FileFormat.Numbered file : FileFormat.list(directory, FileFormat.SNAPSHOT_SUFFIX)) {
            if (file.basis() < basis) {
                deleted |= Files.deleteIfExists(file.path());
            }
        }
        if (deleted) {
            FileFormat.forceDirectory(directory);
        }
    }

    /**
     * Deletes the log segments whose first record has a basis at or below a basis.
     *
     * @param storeDirectory the directory of the store
     * @param basis          the basis of the purge, or of the snapshot file written after it
     * @throws IOException if listing or deleting fails
     */
    public static void deleteSegmentsUpTo(Path storeDirectory, long basis) throws IOException {
        Path directory = storeDirectory.resolve(FileFormat.LOG_DIRECTORY);
        List<FileFormat.Numbered> segments = FileFormat.list(directory, FileFormat.LOG_SUFFIX);
        boolean deleted = false;
        for (FileFormat.Numbered segment : segments) {
            if (segment.basis() <= basis) {
                deleted |= Files.deleteIfExists(segment.path());
            }
        }
        if (deleted) {
            FileFormat.forceDirectory(directory);
        }
    }
}
