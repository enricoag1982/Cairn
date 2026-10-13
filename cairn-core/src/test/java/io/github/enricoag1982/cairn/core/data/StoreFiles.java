package io.github.enricoag1982.cairn.core.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reads and alters the files of a durable store's directory, the way a crash or damage would: the log segments, the
 * offsets of their records, and the snapshot files. The format is the one of design D2 and D3 of
 * {@code add-durable-commit-log}: a header of 12 bytes, then records, each a 4-byte length, a 4-byte checksum of the
 * length, a 4-byte checksum of the payload, and the payload.
 */
final class StoreFiles {

    static final int HEADER = 12;
    static final int FRAME_HEADER = 12;

    private StoreFiles() {
    }

    static List<Path> segments(Path directory) {
        return list(directory.resolve("log"), ".log");
    }

    static List<Path> snapshots(Path directory) {
        return list(directory.resolve("snapshots"), ".snapshot");
    }

    private static List<Path> list(Path directory, String suffix) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(suffix)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Path lastSegment(Path directory) {
        return segments(directory).getLast();
    }

    /** The offsets at which the records of a segment start; the file's length is the end of the last. */
    static List<Long> recordStarts(Path segment) {
        try {
            byte[] bytes = Files.readAllBytes(segment);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            List<Long> starts = new ArrayList<>();
            int position = HEADER;
            while (position + FRAME_HEADER <= bytes.length) {
                int length = buffer.getInt(position);
                starts.add((long) position);
                position += FRAME_HEADER + length;
            }
            return starts;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void truncate(Path file, long size) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(size);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Appends bytes to a file. */
    static void append(Path file, byte[] bytes) {
        try {
            Files.write(file, bytes, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Changes one byte of a file: adds 1 to it. */
    static void damage(Path file, long position) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            channel.read(one, position);
            one.flip();
            byte changed = (byte) (one.get() + 1);
            channel.write(ByteBuffer.wrap(new byte[] {changed}), position);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes a 4-byte integer at a position, as the format version in a header is. */
    static void writeInt(Path file, long position, int value) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.allocate(4).putInt(value).flip(), position);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every file of the directory, by its path relative to it, with its bytes. */
    static Map<String, byte[]> contents(Path directory) {
        Map<String, byte[]> contents = new TreeMap<>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                contents.put(directory.relativize(file).toString(), Files.readAllBytes(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return contents;
    }
}
