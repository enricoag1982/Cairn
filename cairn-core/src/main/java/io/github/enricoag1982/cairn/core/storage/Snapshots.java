package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.schema.Basis;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Snapshot files (design D2 and D6 of {@code add-durable-commit-log}): the whole state of a store at one basis, in a
 * file of the directory {@code snapshots} named after that basis. After the header, the file is a sequence of frames, as
 * in the log, each holding one item: the basis and the time of the latest transaction, then the schema transactions as
 * their log records, the transaction records, the transactions with an idempotency key and their receipts, and the
 * history of each entity and relationship; an end frame closes the file and counts the frames before it. A file is
 * <em>complete</em> only if every frame matches its checksum and the end frame is last.
 *
 * <p>A snapshot is written to a temporary file in the same directory, forced to disk and renamed, and the directory is
 * forced, so that a crash leaves a whole file or none.
 */
public final class Snapshots {

    private static final int META = 16;
    private static final int TRANSACTION = 17;
    private static final int KEYED = 18;
    private static final int HISTORY = 19;
    private static final int END = 31;

    /** The number of valid snapshot files to keep. */
    private static final int KEPT = 2;

    private static final int BUFFER = 1 << 16;

    private Snapshots() {
    }

    /**
     * Receives what a snapshot file holds, item by item, in the order of the file.
     */
    public interface Receiver {

        /**
         * A schema transaction, in the order of the schema's history.
         *
         * @param record the registration that was applied
         */
        void schema(SchemaRecord record);

        /**
         * An entry of the transaction history.
         *
         * @param record the data transaction that was committed
         */
        void transaction(TransactionRecord record);

        /**
         * A transaction with an idempotency key.
         *
         * @param keyed the transaction and its receipt
         */
        void keyed(KeyedTransaction keyed);

        /**
         * An entity or a relationship with its timelines.
         *
         * @param history the entity or relationship
         */
        void history(History history);
    }

    /**
     * What a snapshot file is made of.
     *
     * @param basis               the basis the snapshot is at
     * @param lastTransactionTime the transaction time of the latest transaction at or below the basis; empty if there is
     *                            none
     * @param schema              the schema transactions at or below the basis, in order
     * @param transactions        the data transactions at or below the basis, in order
     * @param keyed               the transactions with an idempotency key at or below the basis
     * @param histories           the entities and relationships, each with the timelines recorded at or below the basis
     */
    public record Content(
            Basis basis,
            Optional<Instant> lastTransactionTime,
            List<SchemaRecord> schema,
            List<TransactionRecord> transactions,
            List<KeyedTransaction> keyed,
            Iterable<History> histories) {

        /**
         * Rejects null components and copies the lists.
         */
        public Content {
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(lastTransactionTime, "lastTransactionTime");
            schema = List.copyOf(schema);
            transactions = List.copyOf(transactions);
            keyed = List.copyOf(keyed);
            Objects.requireNonNull(histories, "histories");
        }
    }

    /**
     * The state a snapshot file stood for when it was read.
     *
     * @param basis               the basis of the snapshot
     * @param lastTransactionTime the transaction time of the latest transaction it holds; empty if there is none
     */
    public record Loaded(Basis basis, Optional<Instant> lastTransactionTime) {
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes a snapshot file: to a temporary file, forced to disk, renamed to its name, and the directory forced. A file
     * of the same basis is replaced.
     *
     * @param storeDirectory the directory of the store
     * @param content        what to write
     * @throws IOException if writing fails; the temporary file is removed
     */
    public static void write(Path storeDirectory, Content content) throws IOException {
        Path directory = storeDirectory.resolve(FileFormat.SNAPSHOT_DIRECTORY);
        boolean created = !Files.isDirectory(directory);
        Files.createDirectories(directory);
        if (created) {
            FileFormat.forceDirectory(storeDirectory);
        }
        long basis = content.basis().value();
        Path target = directory.resolve(FileFormat.name(basis, FileFormat.SNAPSHOT_SUFFIX));
        Path temporary = directory.resolve(FileFormat.name(basis, FileFormat.SNAPSHOT_SUFFIX
                + FileFormat.TEMPORARY_SUFFIX));
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                OutputStream out = new BufferedOutputStream(Channels.newOutputStream(channel), BUFFER);
                writeFrames(out, content);
                out.flush();
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            FileFormat.forceDirectory(directory);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    private static void writeFrames(OutputStream out, Content content) throws IOException {
        out.write(FileFormat.header(FileFormat.SNAPSHOT_MAGIC, RecordCodec.VERSION));
        long frames = 0;
        Encoder meta = Encoder.framed();
        meta.writeByte(META);
        meta.writeLong(content.basis().value());
        meta.writeOptionalInstant(content.lastTransactionTime());
        out.write(meta.finishFrame());
        frames++;
        for (SchemaRecord record : content.schema()) {
            out.write(RecordCodec.encode(record));
            frames++;
        }
        for (TransactionRecord record : content.transactions()) {
            Encoder frame = Encoder.framed();
            frame.writeByte(TRANSACTION);
            TransactionCodec.writeEntry(frame, record);
            out.write(frame.finishFrame());
            frames++;
        }
        for (KeyedTransaction keyed : content.keyed()) {
            Encoder frame = Encoder.framed();
            frame.writeByte(KEYED);
            TransactionCodec.write(frame, keyed.transaction());
            frame.writeLong(keyed.receipt().basis().value());
            frame.writeInstant(keyed.receipt().transactionTime());
            TransactionCodec.writeReceiptIds(frame, keyed.receipt());
            out.write(frame.finishFrame());
            frames++;
        }
        for (History history : content.histories()) {
            Encoder frame = Encoder.framed();
            frame.writeByte(HISTORY);
            EffectCodec.writeHistory(frame, history);
            out.write(frame.finishFrame());
            frames++;
        }
        Encoder end = Encoder.framed();
        end.writeByte(END);
        end.writeLong(frames);
        out.write(end.finishFrame());
    }

    // ------------------------------------------------------------------ keeping

    /**
     * Deletes what the two newest <em>valid</em> snapshot files make unnecessary: snapshot files older than the older of
     * the two, and the log segments that hold only records at or below its basis. A snapshot file that is damaged does not
     * count as one of the two, so that recovery can still use the older ones if it is the newest; it stays until it is
     * older than the older of the two valid ones. With fewer than two valid snapshot files, nothing is deleted. The
     * snapshot files go first, so that a crash in between never leaves a snapshot without the log it needs.
     *
     * @param storeDirectory the directory of the store
     * @throws IOException if reading or deleting fails
     */
    public static void prune(Path storeDirectory) throws IOException {
        prune(storeDirectory, -1);
    }

    /**
     * Deletes what the two newest valid snapshot files make unnecessary, as {@link #prune(Path)} does, without reading
     * the snapshot file that was just written, which is known to be valid.
     *
     * @param storeDirectory the directory of the store
     * @param writtenBasis   the basis of the snapshot file that was just written, or a negative number for none
     * @throws IOException if reading or deleting fails
     */
    public static void prune(Path storeDirectory, long writtenBasis) throws IOException {
        Path snapshotDirectory = storeDirectory.resolve(FileFormat.SNAPSHOT_DIRECTORY);
        Path logDirectory = storeDirectory.resolve(FileFormat.LOG_DIRECTORY);
        List<FileFormat.Numbered> snapshots = FileFormat.list(snapshotDirectory, FileFormat.SNAPSHOT_SUFFIX);
        List<FileFormat.Numbered> valid = new ArrayList<>();
        for (int i = snapshots.size() - 1; i >= 0 && valid.size() < KEPT; i--) {
            FileFormat.Numbered file = snapshots.get(i);
            if (file.basis() == writtenBasis || isValid(file)) {
                valid.add(file);
            }
        }
        if (valid.size() < KEPT) {
            return;
        }
        long older = valid.get(KEPT - 1).basis();
        boolean deletedSnapshots = false;
        for (FileFormat.Numbered file : snapshots) {
            if (file.basis() < older) {
                deletedSnapshots |= Files.deleteIfExists(file.path());
            }
        }
        if (deletedSnapshots) {
            FileFormat.forceDirectory(snapshotDirectory);
        }
        List<FileFormat.Numbered> segments = FileFormat.list(logDirectory, FileFormat.LOG_SUFFIX);
        boolean deletedSegments = false;
        for (int i = 0; i + 1 < segments.size(); i++) {
            if (segments.get(i + 1).basis() <= older + 1) {
                deletedSegments |= Files.deleteIfExists(segments.get(i).path());
            }
        }
        if (deletedSegments) {
            FileFormat.forceDirectory(logDirectory);
        }
    }

    /** Tells whether a snapshot file is complete and undamaged, and of a format version that is known. */
    private static boolean isValid(FileFormat.Numbered file) throws IOException {
        try {
            return verify(file).isPresent();
        } catch (IllegalStateException e) {
            return false; // A format version that is not known: recovery refuses it, pruning leaves it alone.
        }
    }

    /**
     * Deletes the temporary files that a crash during {@link #write} left behind.
     *
     * @param storeDirectory the directory of the store
     * @throws IOException if deleting fails
     */
    public static void deleteTemporaryFiles(Path storeDirectory) throws IOException {
        Path directory = storeDirectory.resolve(FileFormat.SNAPSHOT_DIRECTORY);
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*" + FileFormat.TEMPORARY_SUFFIX)) {
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        }
    }

    // ------------------------------------------------------------------ reading

    /**
     * Reads the newest snapshot file that is complete and undamaged, and hands what it holds to a receiver. A file whose
     * header, length or checksums are not valid is skipped, and the next older one is used.
     *
     * @param storeDirectory the directory of the store
     * @param receiver       receives the items of the file
     * @return the snapshot that was read; empty if there is no valid snapshot file
     * @throws IOException           if reading fails
     * @throws IllegalStateException if a snapshot file was written in a format version that is not known, or decodes
     *                               wrongly though its checksums are right
     */
    public static Optional<Loaded> loadNewest(Path storeDirectory, Receiver receiver) throws IOException {
        List<FileFormat.Numbered> files = FileFormat.list(storeDirectory.resolve(FileFormat.SNAPSHOT_DIRECTORY),
                FileFormat.SNAPSHOT_SUFFIX);
        for (int i = files.size() - 1; i >= 0; i--) {
            FileFormat.Numbered file = files.get(i);
            Optional<Loaded> checked = verify(file);
            if (checked.isPresent()) {
                read(file.path(), receiver);
                return checked;
            }
        }
        return Optional.empty();
    }

    /**
     * Checks a snapshot file without keeping what it holds: the header, every frame's checksum, the first frame, the end
     * frame and its count.
     */
    private static Optional<Loaded> verify(FileFormat.Numbered file) throws IOException {
        long length = Files.size(file.path());
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file.path()), BUFFER)) {
            FileFormat.Header header = FileFormat.readHeader(in, FileFormat.SNAPSHOT_MAGIC);
            switch (header) {
                case FileFormat.Header.Short ignored -> {
                    return Optional.empty();
                }
                case FileFormat.Header.WrongMagic ignored -> {
                    return Optional.empty();
                }
                case FileFormat.Header.Version version -> {
                    if (version.version() != RecordCodec.VERSION) {
                        throw FileFormat.unknownVersion(file.path(), version.version());
                    }
                }
            }
            FrameReader frames = new FrameReader(in, FileFormat.HEADER_LENGTH, length);
            Loaded loaded = null;
            long count = 0;
            while (true) {
                FrameReader.Result result = frames.next();
                if (!(result instanceof FrameReader.Frame frame)) {
                    return Optional.empty();
                }
                int kind = frame.payload()[0] & 0xFF;
                if (count == 0) {
                    if (kind != META) {
                        return Optional.empty();
                    }
                    try {
                        Decoder meta = new Decoder(frame.payload(), 1);
                        loaded = new Loaded(new Basis(meta.readLong()), meta.readOptionalInstant());
                        meta.requireEnd();
                    } catch (RuntimeException e) {
                        return Optional.empty();
                    }
                    if (loaded.basis().value() != file.basis()) {
                        return Optional.empty();
                    }
                } else if (kind == END) {
                    try {
                        Decoder end = new Decoder(frame.payload(), 1);
                        boolean counted = end.readLong() == count;
                        end.requireEnd();
                        return counted && in.read() < 0 ? Optional.of(loaded) : Optional.empty();
                    } catch (RuntimeException e) {
                        return Optional.empty();
                    }
                }
                count++;
            }
        }
    }

    private static void read(Path file, Receiver receiver) throws IOException {
        long length = Files.size(file);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), BUFFER)) {
            in.skipNBytes(FileFormat.HEADER_LENGTH);
            FrameReader frames = new FrameReader(in, FileFormat.HEADER_LENGTH, length);
            while (frames.next() instanceof FrameReader.Frame frame) {
                int kind = frame.payload()[0] & 0xFF;
                try {
                    switch (kind) {
                        case META -> { }
                        case RecordCodec.SCHEMA -> {
                            if (!(RecordCodec.decode(RecordCodec.VERSION, frame.payload()) instanceof SchemaRecord schema)) {
                                throw new CorruptRecordException("A snapshot holds a data transaction as a schema "
                                        + "transaction.");
                            }
                            receiver.schema(schema);
                        }
                        case TRANSACTION -> receiver.transaction(decode(frame.payload(), TransactionCodec::readEntry));
                        case KEYED -> receiver.keyed(decode(frame.payload(), Snapshots::readKeyed));
                        case HISTORY -> receiver.history(decode(frame.payload(), EffectCodec::readHistory));
                        case END -> {
                            return;
                        }
                        default -> throw new CorruptRecordException("The snapshot frame kind " + kind
                                + " is not known.");
                    }
                } catch (RuntimeException e) {
                    throw FileFormat.damaged(file, frame.start(), e.getMessage());
                }
            }
        }
    }

    private static KeyedTransaction readKeyed(Decoder in) {
        Transaction transaction = TransactionCodec.read(in);
        Basis basis = new Basis(in.readLong());
        Instant transactionTime = in.readInstant();
        Receipt receipt = TransactionCodec.readReceipt(in, basis, transactionTime, transaction);
        return new KeyedTransaction(transaction, receipt);
    }

    /** Decodes the body of a frame after its kind byte, and checks that all of it was read. */
    private static <T> T decode(byte[] payload, Function<Decoder, T> reader) {
        try {
            Decoder in = new Decoder(payload, 1);
            T value = reader.apply(in);
            in.requireEnd();
            return value;
        } catch (CorruptRecordException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CorruptRecordException(e.toString(), e);
        }
    }
}
