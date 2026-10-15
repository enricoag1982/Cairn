package io.github.enricoag1982.cairn.core.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The lock that lets one open store hold a directory (design D2 of {@code add-durable-commit-log}): a {@link FileLock}
 * on the file {@code lock}, held while the store is open. It keeps other processes out. Inside this JVM the JVM-wide
 * table of file locks refuses a second lock on the same file with an {@link OverlappingFileLockException}; this class
 * also keeps its own set of the directories it holds, and consults it first, because closing a second channel on a locked
 * file can release the lock of the first channel on some platforms, and so no second channel is opened.
 */
public final class DirectoryLock implements AutoCloseable {

    private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();

    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean released;

    private DirectoryLock(Path directory, FileChannel channel, FileLock lock) {
        this.directory = directory;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Takes the lock of a directory, which must exist.
     *
     * @param directory the directory of the store
     * @return the lock, which the caller releases with {@link #close()}
     * @throws IllegalStateException if another store, in this JVM or another process, holds the directory
     * @throws UncheckedIOException  if the lock file cannot be opened or locked
     */
    public static DirectoryLock acquire(Path directory) {
        Path key;
        try {
            key = directory.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("Opening the directory " + directory + " failed.", e);
        }
        if (!HELD.add(key)) {
            throw heldElsewhere(directory);
        }
        boolean acquired = false;
        FileChannel channel = null;
        try {
            channel = FileChannel.open(key.resolve(FileFormat.LOCK_FILE), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                throw heldElsewhere(directory);
            }
            if (lock == null) {
                throw heldElsewhere(directory);
            }
            acquired = true;
            return new DirectoryLock(key, channel, lock);
        } catch (IOException e) {
            throw new UncheckedIOException("Locking the directory " + directory + " failed.", e);
        } finally {
            if (!acquired) {
                HELD.remove(key);
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        // The failure that led here is the one to report.
                    }
                }
            }
        }
    }

    private static IllegalStateException heldElsewhere(Path directory) {
        return new IllegalStateException("The directory " + directory + " is held by another open store; close it "
                + "before opening another store on the same directory.");
    }

    /** Releases the lock, so that another store can be opened on the directory. Releasing it again does nothing. */
    @Override
    public synchronized void close() {
        if (released) {
            return;
        }
        released = true;
        try {
            lock.release();
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Releasing the lock of " + directory + " failed.", e);
        } finally {
            HELD.remove(directory);
        }
    }
}
