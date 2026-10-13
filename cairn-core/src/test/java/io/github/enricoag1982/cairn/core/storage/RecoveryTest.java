package io.github.enricoag1982.cairn.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Submission;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Design D2, D4 and D5 of {@code add-durable-commit-log}, on the files alone: log segments, what recovery does with a
 * record that a crash cut short and with damage, and snapshot files. The records are minimal; the engine's tests read
 * them back into stores.
 */
class RecoveryTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path directory;

    /** What recovery handed over. */
    private static final class Collected implements Recovery.Replay {
        final List<LogRecord> records = new ArrayList<>();
        final List<SchemaRecord> schema = new ArrayList<>();
        final List<TransactionRecord> transactions = new ArrayList<>();
        final List<KeyedTransaction> keyed = new ArrayList<>();
        final List<History> histories = new ArrayList<>();
        final List<PurgeRecord> purges = new ArrayList<>();
        final List<String> purgedKeys = new ArrayList<>();

        @Override
        public void schema(SchemaRecord record) {
            schema.add(record);
        }

        @Override
        public void transaction(TransactionRecord record) {
            transactions.add(record);
        }

        @Override
        public void keyed(KeyedTransaction keyedTransaction) {
            keyed.add(keyedTransaction);
        }

        @Override
        public void purge(PurgeRecord record) {
            purges.add(record);
        }

        @Override
        public void purgedKey(String key) {
            purgedKeys.add(key);
        }

        @Override
        public void history(History history) {
            histories.add(history);
        }

        @Override
        public void record(LogRecord record) {
            records.add(record);
        }

        List<Long> bases() {
            return records.stream().map(record -> record.basis().value()).toList();
        }
    }

    private static DataRecord data(long basis) {
        Instant time = START.plusSeconds(basis);
        Transaction transaction = Transaction.by("tester").reason("record " + basis).build();
        Receipt receipt = new Receipt(new Basis(basis), time, Map.of(), List.of(), List.of(), "tester", Optional.empty(),
                Optional.of("record " + basis), Optional.empty());
        return new DataRecord(transaction, receipt, List.of());
    }

    /** Writes the records of the bases 1 to {@code count} to a log whose segments are complete after {@code limit}. */
    private void writeLog(int count, long limit) {
        CommitLog log = CommitLog.open(directory, 1, limit);
        for (long basis = 1; basis <= count; basis++) {
            log.append(data(basis));
        }
        log.close();
    }

    private Collected recover() {
        Collected collected = new Collected();
        Recovery.recover(directory, collected);
        return collected;
    }

    private List<Path> segments() {
        return files("log");
    }

    private List<Path> snapshots() {
        return files("snapshots");
    }

    private List<Path> files(String name) {
        try (Stream<Path> files = Files.list(directory.resolve(name))) {
            return files.sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Long> starts(Path segment) throws IOException {
        byte[] bytes = Files.readAllBytes(segment);
        List<Long> starts = new ArrayList<>();
        int position = FileFormat.HEADER_LENGTH;
        while (position < bytes.length) {
            starts.add((long) position);
            position += Encoder.FRAME_HEADER + ByteBuffer.wrap(bytes).getInt(position);
        }
        return starts;
    }

    private static void truncate(Path file, long size) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(size);
        }
    }

    private static void flip(Path file, long position) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        bytes[(int) position]++;
        Files.write(file, bytes);
    }

    private Map<String, byte[]> contents() throws IOException {
        Map<String, byte[]> contents = new TreeMap<>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                contents.put(directory.relativize(file).toString(), Files.readAllBytes(file));
            }
        }
        return contents;
    }

    // ------------------------------------------------------------------ the log

    @Test
    void anEmptyDirectoryRecoversAnEmptyStore() {
        Collected collected = new Collected();

        Recovery.Result result = Recovery.recover(directory, collected);

        assertThat(result.latest()).isEqualTo(Basis.NONE);
        assertThat(result.lastTransactionTime()).isEmpty();
        assertThat(collected.records).isEmpty();
    }

    @Test
    void recoveryHandsOverTheRecordsInTheOrderOfTheBases() throws IOException {
        writeLog(5, CommitLog.DEFAULT_SEGMENT_LIMIT);

        Collected collected = new Collected();
        Recovery.Result result = Recovery.recover(directory, collected);

        assertThat(collected.records).isEqualTo(List.of(data(1), data(2), data(3), data(4), data(5)));
        assertThat(result.latest()).isEqualTo(new Basis(5));
        assertThat(result.lastTransactionTime()).contains(START.plusSeconds(5));
        assertThat(segments()).extracting(path -> path.getFileName().toString())
                .containsExactly("00000000000000000001.log");
        byte[] header = Files.readAllBytes(segments().getFirst());
        assertThat(new String(header, 0, 8)).isEqualTo("CAIRNLOG");
        assertThat(ByteBuffer.wrap(header, 8, 4).getInt()).isEqualTo(1);
    }

    @Test
    void aSegmentIsCompleteOnceItHasPassedTheLimitAndTheNextOneIsNamedAfterItsFirstBasis() throws IOException {
        writeLog(9, 150);

        List<Path> segments = segments();

        assertThat(segments).hasSizeGreaterThan(2);
        long expectedFirst = 1;
        for (Path segment : segments) {
            assertThat(segment.getFileName().toString()).isEqualTo(String.format("%020d.log", expectedFirst));
            expectedFirst += starts(segment).size();
        }
        assertThat(expectedFirst).as("every record is in some segment").isEqualTo(10);
        assertThat(recover().bases()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
    }

    @Test
    void anExistingEmptySegmentOfTheNextBasisIsUsedAgain() {
        writeLog(3, CommitLog.DEFAULT_SEGMENT_LIMIT);
        CommitLog log = CommitLog.open(directory, 4, CommitLog.DEFAULT_SEGMENT_LIMIT);
        log.close();

        CommitLog again = CommitLog.open(directory, 4, CommitLog.DEFAULT_SEGMENT_LIMIT);
        again.append(data(4));
        again.close();

        assertThat(segments()).hasSize(2);
        assertThat(recover().bases()).containsExactly(1L, 2L, 3L, 4L);
    }

    // ------------------------------------------------------------------ a record that a crash cut short

    @Test
    void aLastRecordWhosePayloadRunsPastTheEndOfTheFileIsDroppedAndTheSegmentIsTruncatedThere() throws IOException {
        writeLog(4, CommitLog.DEFAULT_SEGMENT_LIMIT);
        Path segment = segments().getFirst();
        List<Long> starts = starts(segment);
        truncate(segment, starts.get(3) + Encoder.FRAME_HEADER + 20);

        Collected collected = recover();

        assertThat(collected.bases()).containsExactly(1L, 2L, 3L);
        assertThat(Files.size(segment)).isEqualTo(starts.get(3));
    }

    @Test
    void aLastRecordWhoseHeaderIsCompleteAndWhosePayloadIsMissingIsDropped() throws IOException {
        writeLog(4, CommitLog.DEFAULT_SEGMENT_LIMIT);
        Path segment = segments().getFirst();
        List<Long> starts = starts(segment);
        truncate(segment, starts.get(3) + Encoder.FRAME_HEADER);

        assertThat(recover().bases()).containsExactly(1L, 2L, 3L);
        assertThat(Files.size(segment)).isEqualTo(starts.get(3));
    }

    @Test
    void aLastRecordWhoseHeaderIsCutInTheMiddleIsDroppedWhereverItIsCut() throws IOException {
        for (int kept : new int[] {1, 3, 4, 5, 7, 8, 11}) {
            writeLog(4, CommitLog.DEFAULT_SEGMENT_LIMIT);
            Path segment = segments().getFirst();
            List<Long> starts = starts(segment);
            truncate(segment, starts.get(3) + kept);

            assertThat(recover().bases()).as("%d bytes of the header are left", kept).containsExactly(1L, 2L, 3L);
            assertThat(Files.size(segment)).isEqualTo(starts.get(3));
            Files.delete(segment);
        }
    }

    @Test
    void aTailOfZeroBytesAfterTheLastRecordIsTruncated() throws IOException {
        for (int zeros : new int[] {1, 5, 11, 12, 13, 100, 4096}) {
            writeLog(3, CommitLog.DEFAULT_SEGMENT_LIMIT);
            Path segment = segments().getFirst();
            long end = Files.size(segment);
            Files.write(segment, new byte[zeros], StandardOpenOption.APPEND);

            assertThat(recover().bases()).as("%d zero bytes", zeros).containsExactly(1L, 2L, 3L);
            assertThat(Files.size(segment)).isEqualTo(end);
            Files.delete(segment);
        }
    }

    @Test
    void aTailOfZeroBytesThatReplacesTheLastRecordIsTruncatedAtItsStart() throws IOException {
        writeLog(4, CommitLog.DEFAULT_SEGMENT_LIMIT);
        Path segment = segments().getFirst();
        List<Long> starts = starts(segment);
        long size = Files.size(segment);
        truncate(segment, starts.get(3));
        Files.write(segment, new byte[(int) (size - starts.get(3))], StandardOpenOption.APPEND);

        assertThat(recover().bases()).containsExactly(1L, 2L, 3L);
        assertThat(Files.size(segment)).isEqualTo(starts.get(3));
    }

    @Test
    void aTailOfZeroBytesThatEndsInOtherDataIsDamage() throws IOException {
        writeLog(3, CommitLog.DEFAULT_SEGMENT_LIMIT);
        Path segment = segments().getFirst();
        long end = Files.size(segment);
        Files.write(segment, new byte[40], StandardOpenOption.APPEND);
        Files.write(segment, new byte[] {7}, StandardOpenOption.APPEND);

        assertThatThrownBy(this::recover).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(segment.toString()).hasMessageContaining("byte " + end);
    }

    @Test
    void aFlippedBitInTheLengthOfAMiddleRecordOfTheLastSegmentIsDamageAndNothingIsTruncated() throws IOException {
        for (int offset : new int[] {0, 1, 2, 3}) {
            writeLog(5, CommitLog.DEFAULT_SEGMENT_LIMIT);
            Path segment = segments().getFirst();
            List<Long> starts = starts(segment);
            flip(segment, starts.get(2) + offset);
            Map<String, byte[]> before = contents();

            assertThatThrownBy(this::recover).as("byte %d of the length", offset)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(segment.toString())
                    .hasMessageContaining("byte " + starts.get(2));

            assertThat(contents()).containsOnlyKeys(before.keySet())
                    .allSatisfy((name, bytes) -> assertThat(bytes).isEqualTo(before.get(name)));
            Files.delete(segment);
        }
    }

    @Test
    void aDamagedLengthOfTheLastRecordIsDamageNotACutRecord() throws IOException {
        for (int offset : new int[] {0, 1, 2, 3, 4, 5, 6, 7}) {
            writeLog(4, CommitLog.DEFAULT_SEGMENT_LIMIT);
            Path segment = segments().getFirst();
            List<Long> starts = starts(segment);
            flip(segment, starts.get(3) + offset);
            Map<String, byte[]> before = contents();

            assertThatThrownBy(this::recover).as("byte %d of the header", offset)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("byte " + starts.get(3));

            assertThat(contents()).containsOnlyKeys(before.keySet())
                    .allSatisfy((name, bytes) -> assertThat(bytes).isEqualTo(before.get(name)));
            Files.delete(segment);
        }
    }

    @Test
    void aPayloadChecksumThatFailsIsDroppedAtTheTailOrBeforeZeroBytesAndDamageBeforeOtherData() throws IOException {
        for (String tail : new String[] {"nothing", "zero bytes", "other data", "zero bytes and other data"}) {
            writeLog(4, CommitLog.DEFAULT_SEGMENT_LIMIT);
            Path segment = segments().getFirst();
            List<Long> starts = starts(segment);
            flip(segment, starts.get(3) + Encoder.FRAME_HEADER + 3);
            switch (tail) {
                case "zero bytes" -> Files.write(segment, new byte[4096], StandardOpenOption.APPEND);
                case "other data" -> Files.write(segment, new byte[] {0, 0, 7}, StandardOpenOption.APPEND);
                case "zero bytes and other data" -> {
                    Files.write(segment, new byte[4096], StandardOpenOption.APPEND);
                    Files.write(segment, new byte[] {1}, StandardOpenOption.APPEND);
                }
                default -> { }
            }
            Map<String, byte[]> before = contents();

            if (tail.equals("nothing") || tail.equals("zero bytes")) {
                assertThat(recover().bases()).as(tail).containsExactly(1L, 2L, 3L);
                assertThat(Files.size(segment)).as(tail).isEqualTo(starts.get(3));
            } else {
                assertThatThrownBy(this::recover).as(tail).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(segment.toString()).hasMessageContaining("byte " + starts.get(3));
                assertThat(contents()).as(tail).containsOnlyKeys(before.keySet())
                        .allSatisfy((name, bytes) -> assertThat(bytes).isEqualTo(before.get(name)));
            }
            Files.delete(segment);
        }
    }

    @Test
    void aLastRecordThatIsFullLengthButPartlyZerosOrGarbageIsDropped() throws IOException {
        for (boolean zeros : new boolean[] {true, false}) {
            writeLog(4, CommitLog.DEFAULT_SEGMENT_LIMIT);
            Path segment = segments().getFirst();
            List<Long> starts = starts(segment);
            byte[] bytes = Files.readAllBytes(segment);
            for (int i = (int) (starts.get(3) + Encoder.FRAME_HEADER + 10); i < bytes.length; i++) {
                bytes[i] = zeros ? 0 : (byte) (i * 31 + 5);
            }
            Files.write(segment, bytes);

            assertThat(recover().bases()).as(zeros ? "zeros" : "garbage").containsExactly(1L, 2L, 3L);
            assertThat(Files.size(segment)).isEqualTo(starts.get(3));
            Files.delete(segment);
        }
    }

    @Test
    void aHeaderThatWasWrittenInPartAndIsFollowedOnlyByZeroBytesIsDropped() throws IOException {
        for (int written = 1; written <= 11; written++) {
            writeLog(4, CommitLog.DEFAULT_SEGMENT_LIMIT);
            Path segment = segments().getFirst();
            List<Long> starts = starts(segment);
            byte[] bytes = Files.readAllBytes(segment);
            Arrays.fill(bytes, (int) (starts.get(3) + written), bytes.length, (byte) 0);
            Files.write(segment, bytes);

            assertThat(recover().bases()).as("%d bytes of the header are written", written)
                    .containsExactly(1L, 2L, 3L);
            assertThat(Files.size(segment)).isEqualTo(starts.get(3));
            Files.delete(segment);
        }
    }

    @Test
    void aHeaderThatFailsItsChecksumIsDamageIfAnyByteAfterItIsNotZero() throws IOException {
        for (int written : new int[] {1, 4, 7}) {
            writeLog(4, CommitLog.DEFAULT_SEGMENT_LIMIT);
            Path segment = segments().getFirst();
            List<Long> starts = starts(segment);
            byte[] bytes = Files.readAllBytes(segment);
            Arrays.fill(bytes, (int) (starts.get(3) + written), bytes.length, (byte) 0);
            bytes[bytes.length - 1] = 1;
            Files.write(segment, bytes);
            Map<String, byte[]> before = contents();

            assertThatThrownBy(this::recover).as("%d bytes of the header are written", written)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(segment.toString())
                    .hasMessageContaining("byte " + starts.get(3));

            assertThat(contents()).containsOnlyKeys(before.keySet())
                    .allSatisfy((name, content) -> assertThat(content).isEqualTo(before.get(name)));
            Files.delete(segment);
        }
    }

    @Test
    void aLastSegmentWhoseHeaderAndEverythingAfterAreZeroBytesIsDeleted() throws IOException {
        for (int zeros : new int[] {12, 13, 100, 4096}) {
            writeLog(3, CommitLog.DEFAULT_SEGMENT_LIMIT);
            Path next = directory.resolve("log").resolve(String.format("%020d.log", 4));
            Files.write(next, new byte[zeros]);

            assertThat(recover().bases()).as("%d zero bytes", zeros).containsExactly(1L, 2L, 3L);
            assertThat(next).doesNotExist();
            Files.delete(segments().getFirst());
        }
    }

    @Test
    void aLastSegmentThatIsTheOnlyOneAndIsAllZeroBytesIsDeletedAndTheStoreIsEmpty() throws IOException {
        Files.createDirectories(directory.resolve("log"));
        Path only = directory.resolve("log").resolve(String.format("%020d.log", 1));
        Files.write(only, new byte[64]);

        Recovery.Result result = Recovery.recover(directory, new Collected());

        assertThat(result.latest()).isEqualTo(Basis.NONE);
        assertThat(only).doesNotExist();
    }

    @Test
    void aZeroHeaderFollowedByOtherDataOrBeforeTheLastSegmentIsDamage() throws IOException {
        writeLog(9, 150);
        List<Path> segments = segments();
        Path last = segments.getLast();
        Path zeroed = segments.get(1);
        Files.write(zeroed, new byte[64]);
        assertThatThrownBy(this::recover).as("a zero segment before the last").isInstanceOf(
                IllegalStateException.class).hasMessageContaining(zeroed.toString());
        Files.delete(zeroed);
        Files.delete(segments.getFirst());
        for (int i = 2; i < segments.size() - 1; i++) {
            Files.delete(segments.get(i));
        }
        byte[] bytes = new byte[64];
        bytes[40] = 3;
        Files.write(last, bytes);

        assertThatThrownBy(this::recover).as("a zero header and other data after it")
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(last.toString())
                .hasMessageContaining("CAIRNLOG");
        assertThat(last).exists();
    }

    @Test
    void aPayloadChecksumThatFailsInASegmentBeforeTheLastIsDamageEvenAtItsEnd() throws IOException {
        writeLog(9, 150);
        Path first = segments().getFirst();
        List<Long> starts = starts(first);
        flip(first, starts.getLast() + Encoder.FRAME_HEADER + 3);

        assertThatThrownBy(this::recover).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(first.toString()).hasMessageContaining("byte " + starts.getLast());
    }

    @Test
    void aBadRecordFollowedByOtherDataIsDamageAndNothingIsTruncated() throws IOException {
        writeLog(4, CommitLog.DEFAULT_SEGMENT_LIMIT);
        Path segment = segments().getFirst();
        List<Long> starts = starts(segment);
        flip(segment, starts.get(2) + Encoder.FRAME_HEADER + 3);
        Map<String, byte[]> before = contents();

        assertThatThrownBy(this::recover).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(segment.toString()).hasMessageContaining("byte " + starts.get(2));

        assertThat(contents()).containsOnlyKeys(before.keySet())
                .allSatisfy((name, bytes) -> assertThat(bytes).isEqualTo(before.get(name)));
    }

    @Test
    void aRecordThatIsCutShortInASegmentBeforeTheLastIsDamage() throws IOException {
        writeLog(9, 150);
        List<Path> segments = segments();
        Path first = segments.getFirst();
        List<Long> starts = starts(first);
        truncate(first, starts.getLast() + 5);

        assertThatThrownBy(this::recover).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(first.toString());
    }

    @Test
    void aLastSegmentThatIsShorterThanItsHeaderIsDeleted() throws IOException {
        writeLog(3, CommitLog.DEFAULT_SEGMENT_LIMIT);
        Path next = directory.resolve("log").resolve(String.format("%020d.log", 4));
        Files.write(next, new byte[] {'C', 'A', 'I', 'R', 'N'});

        assertThat(recover().bases()).containsExactly(1L, 2L, 3L);
        assertThat(next).doesNotExist();
    }

    @Test
    void aSegmentThatIsShorterThanItsHeaderBeforeTheLastIsDamage() throws IOException {
        writeLog(9, 150);
        truncate(segments().getFirst(), 5);

        assertThatThrownBy(this::recover).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(segments().getFirst().toString());
    }

    // ------------------------------------------------------------------ damage that is refused

    @Test
    void aFileThatDoesNotStartWithTheMagicBytesIsDamage() throws IOException {
        writeLog(3, CommitLog.DEFAULT_SEGMENT_LIMIT);
        flip(segments().getFirst(), 0);

        assertThatThrownBy(this::recover).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(segments().getFirst().toString()).hasMessageContaining("CAIRNLOG");
    }

    @Test
    void aVersionThatIsNotKnownNamesTheFileAndTheVersion() throws IOException {
        writeLog(3, CommitLog.DEFAULT_SEGMENT_LIMIT);
        Path segment = segments().getFirst();
        flip(segment, 11);
        Map<String, byte[]> before = contents();

        assertThatThrownBy(this::recover).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(segment.toString()).hasMessageContaining("version 2");

        assertThat(contents()).containsOnlyKeys(before.keySet())
                .allSatisfy((name, bytes) -> assertThat(bytes).isEqualTo(before.get(name)));
    }

    @Test
    void aSegmentWhoseFirstRecordIsNotTheBasisInItsNameIsDamage() throws IOException {
        writeLog(3, CommitLog.DEFAULT_SEGMENT_LIMIT);
        Path segment = segments().getFirst();
        Files.move(segment, segment.resolveSibling(String.format("%020d.log", 2)));

        assertThatThrownBy(this::recover).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("named after the basis 2");
    }

    @Test
    void aMissingSegmentIsAGapInTheBasesAndIsDamage() throws IOException {
        writeLog(9, 150);
        List<Path> segments = segments();
        Files.delete(segments.get(1));

        assertThatThrownBy(this::recover).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(segments.get(2).toString()).hasMessageContaining("records are missing");
    }

    // ------------------------------------------------------------------ snapshot files

    private static SchemaRecord schema(long basis) {
        return new SchemaRecord(new Basis(basis), START.plusSeconds(basis), Submission.unknown(), new Origin("o", "1"),
                SchemaDefinition.EMPTY, new SchemaIdentifiers(List.of(), List.of(), 1));
    }

    private void writeSnapshot(long basis) throws IOException {
        TransactionRecord entry = new TransactionRecord(new Basis(basis), START.plusSeconds(basis), "tester",
                Optional.empty(), Optional.empty());
        Snapshots.write(directory, new Snapshots.Content(new Basis(basis), Optional.of(START.plusSeconds(basis)),
                List.of(schema(1)), List.of(entry), List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void aSnapshotFileHoldsWhatItWasWrittenWith() throws IOException {
        TransactionRecord entry = new TransactionRecord(new Basis(2), START.plusSeconds(2), "tester",
                Optional.of("sales"), Optional.of("why"));
        Snapshots.write(directory, new Snapshots.Content(new Basis(2), Optional.of(START.plusSeconds(2)),
                List.of(schema(1)), List.of(entry), List.of(), List.of(), List.of(), List.of()));

        Collected collected = new Collected();
        Optional<Snapshots.Loaded> loaded = Snapshots.loadNewest(directory, collected);

        assertThat(loaded).contains(new Snapshots.Loaded(new Basis(2), Optional.of(START.plusSeconds(2))));
        assertThat(collected.schema).containsExactly(schema(1));
        assertThat(collected.transactions).containsExactly(entry);
        assertThat(snapshots()).extracting(path -> path.getFileName().toString())
                .containsExactly("00000000000000000002.snapshot");
        byte[] bytes = Files.readAllBytes(snapshots().getFirst());
        assertThat(new String(bytes, 0, 8)).isEqualTo("CAIRNSNP");
        assertThat(ByteBuffer.wrap(bytes, 8, 4).getInt()).isEqualTo(1);
    }

    @Test
    void recoveryStartsFromTheSnapshotAndSkipsTheRecordsAtOrBelowItsBasis() throws IOException {
        writeLog(6, 150);
        writeSnapshot(4);

        Collected collected = recover();

        assertThat(collected.bases()).containsExactly(5L, 6L);
        assertThat(collected.transactions).hasSize(1);
    }

    @Test
    void aSnapshotThatIsNotValidIsSkippedForTheNextOlderOne() throws IOException {
        writeLog(6, CommitLog.DEFAULT_SEGMENT_LIMIT);
        writeSnapshot(2);
        writeSnapshot(4);
        Path newest = snapshots().getLast();

        List<Consumer<Path>> damages = List.of(
                file -> uncheckedTruncate(file, 5),
                file -> uncheckedTruncate(file, Encoder.FRAME_HEADER + FileFormat.HEADER_LENGTH + 3),
                file -> uncheckedTruncate(file, uncheckedSize(file) - 3),
                file -> uncheckedFlip(file, 0),
                file -> uncheckedFlip(file, FileFormat.HEADER_LENGTH + Encoder.FRAME_HEADER + 2),
                file -> uncheckedFlip(file, uncheckedSize(file) - 2),
                file -> uncheckedAppend(file, new byte[] {1}));
        byte[] original = Files.readAllBytes(newest);
        for (Consumer<Path> damage : damages) {
            Files.write(newest, original);
            damage.accept(newest);

            Collected collected = recover();

            assertThat(collected.transactions).as("the older snapshot file was used").extracting(
                    record -> record.basis().value()).containsExactly(2L);
            assertThat(collected.bases()).containsExactly(3L, 4L, 5L, 6L);
        }
    }

    private static void uncheckedTruncate(Path file, long size) {
        try {
            truncate(file, size);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long uncheckedSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void uncheckedFlip(Path file, long position) {
        try {
            flip(file, position);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void uncheckedAppend(Path file, byte[] bytes) {
        try {
            Files.write(file, bytes, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aSnapshotFileOfAnUnknownVersionIsRefusedNotSkipped() throws IOException {
        writeLog(3, CommitLog.DEFAULT_SEGMENT_LIMIT);
        writeSnapshot(2);
        Path snapshot = snapshots().getFirst();
        flip(snapshot, 11);

        assertThatThrownBy(this::recover).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(snapshot.toString()).hasMessageContaining("version 2");
    }

    @Test
    void aSnapshotThatIsNewerThanTheLogIsStillTheLatestBasis() throws IOException {
        writeSnapshot(7);

        Collected collected = new Collected();
        Recovery.Result result = Recovery.recover(directory, collected);

        assertThat(result.latest()).isEqualTo(new Basis(7));
        assertThat(result.lastTransactionTime()).contains(START.plusSeconds(7));
        assertThat(collected.records).isEmpty();
    }

    @Test
    void withoutTheLogAfterTheOlderSnapshotRecoveryFindsTheGap() throws IOException {
        writeLog(9, 150);
        writeSnapshot(2);
        writeSnapshot(4);
        Path newest = snapshots().getLast();
        flip(newest, 20);
        for (Path segment : segments().subList(0, segments().size() - 1)) {
            Files.delete(segment);
        }

        assertThatThrownBy(this::recover).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("records are missing");
    }

    @Test
    void aTemporaryFileThatACrashLeftIsDeletedWhenRecoverySucceeds() throws IOException {
        writeLog(2, CommitLog.DEFAULT_SEGMENT_LIMIT);
        Files.createDirectories(directory.resolve("snapshots"));
        Path temporary = directory.resolve("snapshots").resolve("00000000000000000009.snapshot.tmp");
        Files.write(temporary, new byte[] {1, 2, 3});

        assertThat(recover().bases()).containsExactly(1L, 2L);
        assertThat(temporary).doesNotExist();
    }

    // ------------------------------------------------------------------ purges

    private static PurgeRecord purge(long basis) {
        return new PurgeRecord(new Basis(basis), START.plusSeconds(basis), "dpo", Optional.of("alice"),
                "Erasure request", List.of(new EntityId("e-1"), new EntityId("e-2")),
                List.of(new RelationshipId("r-1")));
    }

    private void writeSnapshotWithPurge(long basis, PurgeRecord purge, List<String> purgedKeys) throws IOException {
        TransactionRecord entry = new TransactionRecord(new Basis(basis), START.plusSeconds(basis), "tester",
                Optional.empty(), Optional.empty());
        Snapshots.write(directory, new Snapshots.Content(new Basis(basis), Optional.of(START.plusSeconds(basis)),
                List.of(schema(1)), List.of(entry), List.of(), List.of(purge), purgedKeys, List.of()));
    }

    @Test
    void aSnapshotFileHoldsThePurgesAndThePurgedKeysAndRecoveryTellsThatTheyAreInIt() throws IOException {
        writeSnapshotWithPurge(5, purge(4), List.of("key-b", "key-a"));

        Collected collected = new Collected();
        Recovery.Result result = Recovery.recover(directory, collected);

        assertThat(collected.purges).containsExactly(purge(4));
        assertThat(collected.purgedKeys).containsExactly("key-b", "key-a");
        assertThat(result.lastPurge()).contains(new Basis(4));
        assertThat(result.purgePending()).as("the snapshot file holds the purge").isFalse();
    }

    @Test
    void aPurgeRecordInTheLogIsHandedOverAndIsPendingUntilASnapshotFileHoldsIt() throws IOException {
        CommitLog log = CommitLog.open(directory, 1, CommitLog.DEFAULT_SEGMENT_LIMIT);
        log.append(data(1));
        log.append(data(2));
        log.append(new PurgeLogRecord(purge(3)));
        log.close();

        Collected collected = new Collected();
        Recovery.Result result = Recovery.recover(directory, collected);

        assertThat(collected.records).isEqualTo(List.of(data(1), data(2), new PurgeLogRecord(purge(3))));
        assertThat(result.latest()).isEqualTo(new Basis(3));
        assertThat(result.lastTransactionTime()).contains(START.plusSeconds(3));
        assertThat(result.lastPurge()).contains(new Basis(3));
        assertThat(result.purgePending()).isTrue();

        writeSnapshotWithPurge(3, purge(3), List.of());
        Collected afterwards = new Collected();
        Recovery.Result later = Recovery.recover(directory, afterwards);

        assertThat(afterwards.records).as("the record is in the snapshot file").isEmpty();
        assertThat(later.lastPurge()).contains(new Basis(3));
        assertThat(later.purgePending()).isFalse();
    }

    @Test
    void aLogWithoutPurgesHasNoPurgeToComplete() throws IOException {
        writeLog(3, CommitLog.DEFAULT_SEGMENT_LIMIT);
        writeSnapshot(2);

        Recovery.Result result = Recovery.recover(directory, new Collected());

        assertThat(result.lastPurge()).isEmpty();
        assertThat(result.purgePending()).isFalse();
    }

    @Test
    void purgeFilesDeleteTheOlderSnapshotFilesAndTheSegmentsThatStartAtOrBelowTheBasis() throws IOException {
        writeLog(12, 150);
        writeSnapshot(3);
        writeSnapshot(6);
        writeSnapshot(9);
        List<Path> before = segments();

        PurgeFiles.deleteOlder(directory, 6);

        assertThat(snapshots()).extracting(path -> path.getFileName().toString())
                .containsExactly("00000000000000000006.snapshot", "00000000000000000009.snapshot");
        assertThat(segments()).as("only the segments that start above 6 are left")
                .allSatisfy(segment -> assertThat(Long.parseLong(segment.getFileName().toString()
                        .replace(".log", ""))).isGreaterThan(6));
        assertThat(segments()).isNotEmpty().isSubsetOf(before);
        assertThat(before.size()).isGreaterThan(segments().size());

        PurgeFiles.deleteOlder(directory, 6);

        assertThat(snapshots()).as("repeating it changes nothing").hasSize(2);
    }

    @Test
    void purgeFilesDoNothingToADirectoryWithoutFiles() throws IOException {
        PurgeFiles.deleteOlder(directory, 5);

        assertThat(contents()).isEmpty();
    }

    @Test
    void theLogCanDiscardEverySegmentAndStartANewOneAfterAPurge() throws IOException {
        CommitLog log = CommitLog.open(directory, 1, 150);
        for (long basis = 1; basis <= 6; basis++) {
            log.append(data(basis));
        }
        assertThat(segments()).hasSizeGreaterThan(1);

        log.discardAndRestart(7);

        assertThat(segments()).extracting(path -> path.getFileName().toString())
                .containsExactly("00000000000000000007.log");
        assertThat(starts(segments().getFirst())).as("the new segment has a header only").isEmpty();
        log.append(data(7));
        log.close();
        writeSnapshot(6);
        assertThat(recover().bases()).containsExactly(7L);
    }

    @Test
    void aLogThatFailedOrIsClosedRefusesToDiscardItsSegments() {
        CommitLog failed = CommitLog.open(directory, 1, CommitLog.DEFAULT_SEGMENT_LIMIT);
        failed.fail();

        assertThatThrownBy(() -> failed.discardAndRestart(2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.append(data(1))).isInstanceOf(IllegalStateException.class);
        failed.close();
        CommitLog closed = CommitLog.open(directory, 1, CommitLog.DEFAULT_SEGMENT_LIMIT);
        closed.close();
        assertThatThrownBy(() -> closed.discardAndRestart(2)).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ keeping the two newest

    @Test
    void afterAThirdSnapshotTheOldestSnapshotAndTheSegmentsOfTheOlderOfTheTwoAreDeleted() throws IOException {
        writeLog(12, 150);
        List<Path> before = segments();
        writeSnapshot(3);
        writeSnapshot(6);
        Snapshots.prune(directory);
        assertThat(snapshots()).as("two snapshot files are kept").hasSize(2);
        assertThat(segments()).as("the older of the two, 3, needs the log after it: only the segment of 1 and 2 goes")
                .isEqualTo(before.subList(1, before.size()));

        writeSnapshot(9);
        Snapshots.prune(directory);

        assertThat(snapshots()).extracting(path -> path.getFileName().toString())
                .containsExactly("00000000000000000006.snapshot", "00000000000000000009.snapshot");
        List<Path> kept = segments();
        assertThat(kept).as("segments with records at or below 6 only are gone").doesNotContainAnyElementsOf(
                before.stream().filter(segment -> lastBasisOf(before, segment) <= 6).toList());
        assertThat(kept).as("every segment with a record above 6 is kept").containsAll(
                before.stream().filter(segment -> lastBasisOf(before, segment) > 6).toList());
        assertThat(kept.getFirst().getFileName().toString()).isEqualTo(String.format("%020d.log", 7));
    }

    /** The basis of the last record of a segment, from the name of the next one; a segment that is last is kept. */
    private long lastBasisOf(List<Path> segments, Path segment) {
        int index = segments.indexOf(segment);
        if (index == segments.size() - 1) {
            return Long.MAX_VALUE;
        }
        return Long.parseLong(segments.get(index + 1).getFileName().toString().substring(0, 20)) - 1;
    }

    @Test
    void aDamagedNewestSnapshotDoesNotCountAmongTheTwoThatAreKept() throws IOException {
        writeLog(12, 150);
        List<Path> before = segments();
        writeSnapshot(3);
        writeSnapshot(6);
        writeSnapshot(9);
        flip(snapshots().getLast(), 20);

        Snapshots.prune(directory);

        assertThat(snapshots()).as("the valid ones are 6 and 3, so all three files stay").hasSize(3);
        assertThat(segments()).as("only the segment of the records at or below 3 goes")
                .isEqualTo(before.subList(1, before.size()));
        flip(snapshots().get(1), 20);
        Collected collected = recover();
        assertThat(collected.transactions).as("with 9 and 6 damaged, recovery starts from 3").extracting(
                record -> record.basis().value()).containsExactly(3L);
        assertThat(collected.bases()).containsExactly(4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
    }

    @Test
    void withAValidNewestSnapshotTheOlderOnesAreDeletedAsBefore() throws IOException {
        writeLog(12, 150);
        writeSnapshot(3);
        writeSnapshot(6);
        writeSnapshot(9);
        writeSnapshot(11);
        flip(snapshots().get(1), 20);

        Snapshots.prune(directory);

        assertThat(snapshots()).extracting(path -> path.getFileName().toString())
                .as("the valid ones are 11 and 9: what is older than 9 goes, damaged or not")
                .containsExactly("00000000000000000009.snapshot", "00000000000000000011.snapshot");
    }

    @Test
    void withoutTwoValidSnapshotsNothingIsDeleted() throws IOException {
        writeLog(12, 150);
        List<Path> before = segments();
        writeSnapshot(3);
        writeSnapshot(6);
        flip(snapshots().getFirst(), 20);

        Snapshots.prune(directory);

        assertThat(snapshots()).hasSize(2);
        assertThat(segments()).isEqualTo(before);
    }

    @Test
    void withOneSnapshotNothingIsDeleted() throws IOException {
        writeLog(9, 150);
        List<Path> before = segments();

        writeSnapshot(8);
        Snapshots.prune(directory);

        assertThat(segments()).isEqualTo(before);
        assertThat(snapshots()).hasSize(1);
    }

    @Test
    void theNewestSegmentIsNeverDeleted() throws IOException {
        writeLog(9, 150);
        List<Path> before = segments();
        writeSnapshot(3);
        writeSnapshot(9);
        Snapshots.prune(directory);

        assertThat(segments()).contains(before.getLast());
        assertThat(recover().bases()).isEmpty();
    }

    // ------------------------------------------------------------------ a log that cannot be written

    @Test
    void afterAWriteFailsTheLogRefusesEveryRecordAndTheRecordIsNotWhole() throws IOException {
        CommitLog log = CommitLog.open(directory, 1, CommitLog.DEFAULT_SEGMENT_LIMIT);
        log.append(data(1));
        log.breakChannel();

        assertThatThrownBy(() -> log.append(data(2))).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> log.append(data(2))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(log::requireWritable).isInstanceOf(IllegalStateException.class);
        log.close();

        assertThat(recover().bases()).containsExactly(1L);
    }

    @Test
    void aClosedLogRefusesRecords() {
        CommitLog log = CommitLog.open(directory, 1, CommitLog.DEFAULT_SEGMENT_LIMIT);
        log.close();
        log.close();

        assertThatThrownBy(() -> log.append(data(1))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    // ------------------------------------------------------------------ the lock

    @Test
    void aDirectoryIsHeldByOneLockAtATime() throws IOException {
        DirectoryLock first = DirectoryLock.acquire(directory);

        assertThatThrownBy(() -> DirectoryLock.acquire(directory)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(directory.toString());
        assertThatThrownBy(() -> DirectoryLock.acquire(directory.resolve("..").resolve(directory.getFileName())))
                .as("the same directory under another spelling").isInstanceOf(IllegalStateException.class);
        first.close();
        first.close();

        DirectoryLock second = DirectoryLock.acquire(directory);
        second.close();
        assertThat(directory.resolve("lock")).exists();
    }

    @Test
    void aLockThatAnotherChannelOfThisJvmHoldsIsRefusedWithAnIllegalStateException() throws IOException {
        try (FileChannel foreign = FileChannel.open(directory.resolve("lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock held = foreign.tryLock()) {
            assertThat(held).isNotNull();

            assertThatThrownBy(() -> DirectoryLock.acquire(directory)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(directory.toString());
        }
    }
}
