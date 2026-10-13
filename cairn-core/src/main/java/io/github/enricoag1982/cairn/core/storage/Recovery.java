package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.schema.Basis;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reads a store's directory back (design D4 of {@code add-durable-commit-log}). It hands to a {@link Replay} what the
 * newest valid snapshot file holds, and then each record of the log after it, in the order of the bases. It checks the
 * files; it never validates what the records say. It also tells whether a purge is left to complete (ADR-0024, design D4
 * of {@code add-purge}): a purge record in the log is newer than every valid snapshot file, so the files that precede
 * it still hold the purged data.
 *
 * <ul>
 *   <li>A snapshot file that is not valid is skipped, and the next older one is used; with none, recovery starts empty.
 *   <li>Log segments are read in the order of their first basis. Records at or below the snapshot's basis are skipped.
 *       The first record after it must have the next basis, and every record the next one after its predecessor.
 *   <li>A bad record at position {@code p} of the last segment is where a crash cut the log, and the segment is
 *       truncated at {@code p}, in five cases only:
 *       <ul>
 *         <li>fewer bytes remain than the header of a record;
 *         <li>the header is right (the checksum of its length matches), but the payload runs past the end of the file;
 *         <li>the header is right and the payload fits, but the payload's checksum fails and nothing but zero bytes
 *             follows it, as when a file system extended the file before the data reached the disk;
 *         <li>the checksum of the header fails, as when a header was written in part, and every byte after the
 *             12 bytes of the header, up to the end of the file, is zero;
 *         <li>every byte from {@code p} to the end of the file is zero (a header whose checksum fails, which is zero
 *             itself, is of the fourth kind).
 *       </ul>
 *   <li>A last segment that is shorter than its header, or whose header and everything after it are zero bytes, as when
 *       a crash cut its creation, holds no record and is deleted.
 *   <li>Anything else fails with an {@code IllegalStateException} that names the file and the position, and nothing on
 *       disk changes: a record whose length does not match its checksum and that other data follows, a payload whose
 *       checksum fails with a byte other than zero after it, a bad record in a segment before the last, an unknown
 *       format version, or a gap in the bases.
 * </ul>
 */
public final class Recovery {

    private Recovery() {
    }

    /** Receives the state that recovery reads. */
    public interface Replay extends Snapshots.Receiver {

        /**
         * A record of the log, after those of the snapshot, in the order of the bases.
         *
         * @param record the record
         */
        void record(LogRecord record);
    }

    /**
     * Where recovery ended.
     *
     * @param latest              the basis of the latest transaction that was recovered; {@link Basis#NONE} if there is
     *                            none
     * @param lastTransactionTime the transaction time of that transaction; empty if there is none
     * @param lastPurge           the basis of the latest purge that the snapshot file or the log holds; empty if the
     *                            store never purged
     * @param purgePending        whether a purge record was read from the log, which means that no snapshot file holds
     *                            the purge yet, and the files that precede it still hold the purged data
     */
    public record Result(Basis latest, Optional<Instant> lastTransactionTime, Optional<Basis> lastPurge,
            boolean purgePending) {
    }

    /** Hands everything to a replay, and notes the purges that pass by. */
    private static final class Watching implements Replay {
        private final Replay replay;
        private long lastPurge = -1;
        private boolean pending;

        Watching(Replay replay) {
            this.replay = replay;
        }

        @Override
        public void schema(SchemaRecord record) {
            replay.schema(record);
        }

        @Override
        public void transaction(TransactionRecord record) {
            replay.transaction(record);
        }

        @Override
        public void keyed(KeyedTransaction keyed) {
            replay.keyed(keyed);
        }

        @Override
        public void purge(PurgeRecord record) {
            replay.purge(record);
            lastPurge = Math.max(lastPurge, record.basis().value());
        }

        @Override
        public void purgedKey(String key) {
            replay.purgedKey(key);
        }

        @Override
        public void history(History history) {
            replay.history(history);
        }

        @Override
        public void record(LogRecord record) {
            replay.record(record);
            if (record instanceof PurgeLogRecord purge) {
                lastPurge = Math.max(lastPurge, purge.basis().value());
                pending = true;
            }
        }
    }

    /**
     * Recovers the state in a directory.
     *
     * @param storeDirectory the directory of the store, whose lock the caller holds
     * @param target         receives the state
     * @return the latest basis and transaction time recovered, and the purges that it saw
     * @throws UncheckedIOException  if reading or truncating a file fails
     * @throws IllegalStateException        if a file is damaged anywhere but at the end of the last segment, was written
     *                                      in a format version that is not known, or the log has a gap
     */
    public static Result recover(Path storeDirectory, Replay target) {
        Watching replay = new Watching(target);
        try {
            Optional<Snapshots.Loaded> snapshot = Snapshots.loadNewest(storeDirectory, replay);
            long latest = snapshot.map(loaded -> loaded.basis().value()).orElse(0L);
            Optional<Instant> lastTime = snapshot.flatMap(Snapshots.Loaded::lastTransactionTime);
            long snapshotBasis = latest;
            List<FileFormat.Numbered> segments = FileFormat.list(storeDirectory.resolve(FileFormat.LOG_DIRECTORY),
                    FileFormat.LOG_SUFFIX);
            Cursor cursor = new Cursor(snapshotBasis, latest, lastTime);
            for (int i = 0; i < segments.size(); i++) {
                boolean last = i == segments.size() - 1;
                if (!last && segments.get(i + 1).basis() <= snapshotBasis + 1) {
                    continue; // Every record of this segment is in the snapshot.
                }
                readSegment(segments.get(i), last, replay, cursor);
            }
            Snapshots.deleteTemporaryFiles(storeDirectory);
            return new Result(new Basis(cursor.latest), cursor.lastTime,
                    replay.lastPurge < 0 ? Optional.empty() : Optional.of(new Basis(replay.lastPurge)),
                    replay.pending);
        } catch (IOException e) {
            throw new UncheckedIOException("Reading the directory " + storeDirectory + " failed.", e);
        }
    }

    /** Where the replay stands. */
    private static final class Cursor {
        final long snapshotBasis;
        long latest;
        Optional<Instant> lastTime;

        Cursor(long snapshotBasis, long latest, Optional<Instant> lastTime) {
            this.snapshotBasis = snapshotBasis;
            this.latest = latest;
            this.lastTime = lastTime;
        }
    }

    private static void readSegment(FileFormat.Numbered segment, boolean last, Replay replay, Cursor cursor)
            throws IOException {
        Path file = segment.path();
        long length = Files.size(file);
        if (length < FileFormat.HEADER_LENGTH) {
            if (last) {
                Files.delete(file); // A crash while the segment was created: it holds no record.
                FileFormat.forceDirectory(file.getParent());
                return;
            }
            throw FileFormat.damaged(file, length, "the file is shorter than its header");
        }
        long truncateAt = -1;
        boolean zeros = false;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            FileFormat.Header header = FileFormat.readHeader(in, FileFormat.LOG_MAGIC);
            if (last && header instanceof FileFormat.Header.WrongMagic wrong && wrong.zero()
                    && FrameReader.zeroToTheEnd(in)) {
                zeros = true; // A crash while the segment was created: it holds no record.
            }
            int version = zeros ? RecordCodec.VERSION : switch (header) {
                case FileFormat.Header.Version found -> found.version();
                case FileFormat.Header.WrongMagic ignored -> throw FileFormat.damaged(file, 0,
                        "the file does not start with CAIRNLOG, so it is not a commit log segment");
                case FileFormat.Header.Short ignored -> throw FileFormat.damaged(file, length,
                        "the file is shorter than its header");
            };
            if (version != RecordCodec.VERSION) {
                throw FileFormat.unknownVersion(file, version);
            }
            if (zeros) {
                Files.delete(file);
                FileFormat.forceDirectory(file.getParent());
                return;
            }
            FrameReader frames = new FrameReader(in, FileFormat.HEADER_LENGTH, length);
            boolean first = true;
            while (true) {
                FrameReader.Result result = frames.next();
                if (result instanceof FrameReader.End) {
                    break;
                }
                if (result instanceof FrameReader.Bad bad) {
                    if (last && endsTheLog(bad, frames)) {
                        truncateAt = bad.start();
                        break;
                    }
                    throw FileFormat.damaged(file, bad.start(), bad.reason());
                }
                FrameReader.Frame frame = (FrameReader.Frame) result;
                long basis = basisOf(file, frame);
                if (first && basis != segment.basis()) {
                    throw FileFormat.damaged(file, frame.start(), "the segment is named after the basis "
                            + segment.basis() + " but its first record has the basis " + basis);
                }
                first = false;
                if (basis <= cursor.snapshotBasis) {
                    continue;
                }
                if (basis != cursor.latest + 1) {
                    throw FileFormat.damaged(file, frame.start(), "the record has the basis " + basis + " but the "
                            + "basis " + (cursor.latest + 1) + " should follow; records are missing");
                }
                LogRecord record;
                try {
                    record = RecordCodec.decode(version, frame.payload());
                } catch (CorruptRecordException e) {
                    throw FileFormat.damaged(file, frame.start(), e.getMessage());
                }
                try {
                    replay.record(record);
                } catch (RuntimeException e) {
                    throw FileFormat.damaged(file, frame.start(), "the record cannot be applied: " + e.getMessage());
                }
                cursor.latest = basis;
                cursor.lastTime = Optional.of(record.transactionTime());
            }
        }
        if (truncateAt >= 0) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.truncate(truncateAt);
                channel.force(true);
            }
        }
    }

    /**
     * Tells whether a bad frame in the last segment is where a crash ended the log, and so is dropped. Only these cases
     * are:
     *
     * <ul>
     *   <li>fewer bytes than a header remain;
     *   <li>the header is right, with a checksum of the length that matches, but the payload runs past the end of the
     *       file;
     *   <li>the header is right and the payload fits in the file, but its checksum fails, and nothing but zero bytes
     *       follows it: some file systems extend a file before its data reaches the disk, so a crash can leave a
     *       payload that is partly zeros or garbage, and that commit was never acknowledged;
     *   <li>the checksum of the header fails, and every byte after the header, up to the end of the file, is zero: a
     *       header that was written in part, across a page boundary, or not at all.
     * </ul>
     *
     * <p>Everything else is damage, including a frame whose length does not match its checksum with any byte other than
     * zero after the header, and a payload whose checksum fails with any byte other than zero after it. The bytes after
     * the frame are consumed.
     */
    private static boolean endsTheLog(FrameReader.Bad bad, FrameReader frames) throws IOException {
        return switch (bad.kind()) {
            case SHORT_HEADER, PAYLOAD_PAST_END -> true;
            case PAYLOAD_CHECKSUM, HEADER_CHECKSUM -> frames.restIsZero();
            case LENGTH -> false;
        };
    }

    private static long basisOf(Path file, FrameReader.Frame frame) {
        try {
            return RecordCodec.peekBasis(frame.payload());
        } catch (CorruptRecordException e) {
            throw FileFormat.damaged(file, frame.start(), e.getMessage());
        }
    }
}
