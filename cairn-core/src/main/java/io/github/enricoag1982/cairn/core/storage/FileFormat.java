package io.github.enricoag1982.cairn.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The layout of a durable store's directory and the header of its files (design D2 of {@code add-durable-commit-log}).
 *
 * <pre>
 * &lt;directory&gt;/lock
 * &lt;directory&gt;/log/&lt;first basis, 20 digits&gt;.log
 * &lt;directory&gt;/snapshots/&lt;basis, 20 digits&gt;.snapshot
 * </pre>
 *
 * <p>Every log and snapshot file starts with 8 magic bytes and the format version as a 4-byte integer.
 */
final class FileFormat {

    /** The magic bytes that start a log segment. */
    static final byte[] LOG_MAGIC = "CAIRNLOG".getBytes(StandardCharsets.US_ASCII);

    /** The magic bytes that start a snapshot file. */
    static final byte[] SNAPSHOT_MAGIC = "CAIRNSNP".getBytes(StandardCharsets.US_ASCII);

    /** The length of the header: the magic bytes and the version. */
    static final int HEADER_LENGTH = 12;

    static final String LOCK_FILE = "lock";
    static final String LOG_DIRECTORY = "log";
    static final String SNAPSHOT_DIRECTORY = "snapshots";
    static final String LOG_SUFFIX = ".log";
    static final String SNAPSHOT_SUFFIX = ".snapshot";
    static final String TEMPORARY_SUFFIX = ".tmp";

    private static final int DIGITS = 20;
    private static final Pattern NUMBERED = Pattern.compile("(\\d{" + DIGITS + "})(\\.[a-z]+)");

    private FileFormat() {
    }

    /** A file named after a basis. */
    record Numbered(long basis, Path path) {
    }

    /** The name of a file for a basis: 20 digits and the suffix. */
    static String name(long basis, String suffix) {
        return String.format(Locale.ROOT, "%0" + DIGITS + "d%s", basis, suffix);
    }

    /**
     * The files of a directory whose names are 20 digits and a suffix, in the order of their numbers. Other files are
     * not the store's and are ignored. A directory that does not exist has no files.
     */
    static List<Numbered> list(Path directory, String suffix) throws IOException {
        List<Numbered> found = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return found;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                Matcher matcher = NUMBERED.matcher(entry.getFileName().toString());
                if (matcher.matches() && matcher.group(2).equals(suffix) && Files.isRegularFile(entry)) {
                    try {
                        found.add(new Numbered(Long.parseLong(matcher.group(1)), entry));
                    } catch (NumberFormatException e) {
                        // More than a long can hold: not a name this store gives.
                    }
                }
            }
        }
        found.sort(Comparator.comparingLong(Numbered::basis));
        return found;
    }

    /** The header of a file: the magic bytes and the version. */
    static byte[] header(byte[] magic, int version) {
        return ByteBuffer.allocate(HEADER_LENGTH).put(magic).putInt(version).array();
    }

    /** What a file's first bytes say. */
    sealed interface Header {

        /** Fewer bytes than a header. */
        record Short(int length) implements Header {
        }

        /** The magic bytes are not the expected ones. */
        record WrongMagic() implements Header {
        }

        /** The magic bytes are right. */
        record Version(int version) implements Header {
        }
    }

    /**
     * Reads the header of a file from its start.
     *
     * @param in    a stream at the start of the file
     * @param magic the magic bytes the file should start with
     */
    static Header readHeader(InputStream in, byte[] magic) throws IOException {
        byte[] bytes = in.readNBytes(HEADER_LENGTH);
        if (bytes.length < HEADER_LENGTH) {
            return new Header.Short(bytes.length);
        }
        if (!Arrays.equals(bytes, 0, magic.length, magic, 0, magic.length)) {
            return new Header.WrongMagic();
        }
        return new Header.Version(ByteBuffer.wrap(bytes, magic.length, 4).getInt());
    }

    /**
     * Forces the entries of a directory to disk, after a file in it was created, renamed or deleted. Where the platform
     * cannot open a directory (Windows), this does nothing.
     */
    static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            if (!System.getProperty("os.name", "").startsWith("Windows")) {
                throw e;
            }
        }
    }

    static IllegalStateException damaged(Path file, long position, String what) {
        return new IllegalStateException("The file " + file + " is damaged at byte " + position + ": " + what + ".");
    }

    static IllegalStateException unknownVersion(Path file, int version) {
        return new IllegalStateException("The file " + file + " was written in format version " + version
                + ", which this version of Cairn does not know; it reads version " + RecordCodec.VERSION + ".");
    }
}
