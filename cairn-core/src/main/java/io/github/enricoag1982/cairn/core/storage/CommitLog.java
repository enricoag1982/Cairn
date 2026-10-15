package io.github.enricoag1982.cairn.core.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The commit log of a durable store (design D2 and D5 of {@code add-durable-commit-log}): a sequence of segment files in
 * the directory {@code log}, named after the basis of their first record. It appends each record to the current segment
 * and forces the segment to disk before it returns. A new segment starts when the current one has passed a limit, and
 * when the log is opened.
 *
 * <p>If encoding, writing or forcing a record fails, the log refuses every further record: the partial record that may
 * be left on disk is what recovery drops.
 *
 * <p>The writer lock of the store serializes the calls to {@link #append(LogRecord)}.
 */
public final class CommitLog implements Journal, AutoCloseable {

    /** The size after which a segment is complete and the next record starts a new one: 64 MiB. */
    public static final long DEFAULT_SEGMENT_LIMIT = 64L * 1024 * 1024;

    private final Path directory;
    private final long segmentLimit;
    private FileChannel channel;
    private Path segment;
    private long size;
    private volatile boolean failed;
    private volatile boolean closed;

    private CommitLog(Path directory, long segmentLimit, FileChannel channel, Path segment, long size) {
        this.directory = directory;
        this.segmentLimit = segmentLimit;
        this.channel = channel;
        this.segment = segment;
        this.size = size;
    }

    /**
     * Opens the log for the records that follow those that recovery read. The log starts a new segment named after the
     * basis of the first record to come; if recovery left an empty segment of that name, because the store was opened
     * and closed or abandoned without a commit, that segment is used.
     *
     * @param storeDirectory the directory of the store
     * @param firstBasis     the basis of the first record that will be appended, one above the latest basis
     * @param segmentLimit   the size after which a segment is complete
     * @return the log
     * @throws UncheckedIOException if the segment cannot be created or opened
     */
    public static CommitLog open(Path storeDirectory, long firstBasis, long segmentLimit) {
        Path directory = storeDirectory.resolve(FileFormat.LOG_DIRECTORY);
        try {
            boolean created = !Files.isDirectory(directory);
            Files.createDirectories(directory);
            if (created) {
                FileFormat.forceDirectory(storeDirectory);
            }
            Path segment = directory.resolve(FileFormat.name(firstBasis, FileFormat.LOG_SUFFIX));
            if (Files.exists(segment)) {
                FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE);
                long size = channel.size();
                channel.position(size);
                return new CommitLog(directory, segmentLimit, channel, segment, size);
            }
            CommitLog log = new CommitLog(directory, segmentLimit, null, null, 0);
            log.startSegment(firstBasis);
            return log;
        } catch (IOException e) {
            throw new UncheckedIOException("Opening the commit log in " + directory + " failed.", e);
        }
    }

    /** Creates a segment with its header, forces the file and its directory, and makes it the current one. */
    private void startSegment(long firstBasis) throws IOException {
        Path next = directory.resolve(FileFormat.name(firstBasis, FileFormat.LOG_SUFFIX));
        FileChannel created = FileChannel.open(next, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            writeFully(created, ByteBuffer.wrap(FileFormat.header(FileFormat.LOG_MAGIC, RecordCodec.VERSION)));
            created.force(true);
            FileFormat.forceDirectory(directory);
        } catch (IOException | RuntimeException e) {
            created.close();
            throw e;
        }
        FileChannel previous = channel;
        channel = created;
        segment = next;
        size = FileFormat.HEADER_LENGTH;
        if (previous != null) {
            previous.close();
        }
    }

    private static void writeFully(FileChannel target, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            target.write(bytes);
        }
    }

    @Override
    public void requireWritable() {
        if (closed) {
            throw new IllegalStateException("The store is closed.");
        }
        if (failed) {
            throw new IllegalStateException("A commit could not be written to the log, so the store refuses further "
                    + "commits and schema registrations until it is opened again.");
        }
    }

    @Override
    public synchronized void append(LogRecord record) {
        requireWritable();
        try {
            byte[] frame = RecordCodec.encode(record);
            if (size > FileFormat.HEADER_LENGTH && size >= segmentLimit) {
                startSegment(record.basis().value());
            }
            writeFully(channel, ByteBuffer.wrap(frame));
            channel.force(false);
            size += frame.length;
        } catch (IOException e) {
            failed = true;
            throw new UncheckedIOException("Writing the commit to " + segment + " failed.", e);
        } catch (RuntimeException e) {
            failed = true;
            throw new UncheckedIOException("Writing the commit to " + segment + " failed.", new IOException(e));
        }
    }

    /**
     * Deletes every segment of the log, the current one included, and starts a new one named after the basis of the first
     * record to come. A purge calls it after it wrote a snapshot file that holds the whole state, so that no segment
     * keeps the purged data (ADR-0024, design D4 of {@code add-purge}). If it fails, the log refuses further records.
     *
     * @param firstBasis the basis of the first record that will be appended, one above the latest basis
     * @throws UncheckedIOException  if deleting the segments or creating the new one fails
     * @throws IllegalStateException if an earlier write failed, or the log is closed
     */
    public synchronized void discardAndRestart(long firstBasis) {
        requireWritable();
        try {
            channel.close();
            for (FileFormat.Numbered old : FileFormat.list(directory, FileFormat.LOG_SUFFIX)) {
                Files.deleteIfExists(old.path());
            }
            FileFormat.forceDirectory(directory);
            startSegment(firstBasis);
        } catch (IOException e) {
            failed = true;
            throw new UncheckedIOException("Replacing the segments of the commit log in " + directory + " failed.", e);
        }
    }

    /**
     * Makes the log refuse further records, as a failed write does. A store calls it when something that has to follow a
     * logged record failed, so that nothing is written to segments that it could not clean up.
     */
    public void fail() {
        failed = true;
    }

    /**
     * Closes the channel under the log, so that the next write fails as a failing disk would make it fail. For tests of
     * the requirement "A commit that cannot be written".
     */
    public synchronized void breakChannel() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Closes the current segment. Whatever was appended is on disk already; nothing is written. The log refuses
     * further records.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Closing " + segment + " failed.", e);
        }
    }
}
