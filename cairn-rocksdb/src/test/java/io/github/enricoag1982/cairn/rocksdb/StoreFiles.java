package io.github.enricoag1982.cairn.rocksdb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Reads the files of a store's directory, to show that personal data is, or is no longer, on disk. */
final class StoreFiles {

    private StoreFiles() {
    }

    /**
     * The files of the directory, by their path relative to it, in whose bytes the UTF-8 bytes of a text occur.
     *
     * @param directory the directory, which is searched with everything below it
     * @param text      the text
     * @return the paths of the files, in order
     */
    static List<String> filesContaining(Path directory, String text) {
        return filesContaining(directory, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The files of the directory, by their path relative to it, in whose bytes a sequence of bytes occurs.
     *
     * @param directory the directory, which is searched with everything below it
     * @param needle    the bytes
     * @return the paths of the files, in order
     */
    static List<String> filesContaining(Path directory, byte[] needle) {
        List<String> found = new ArrayList<>();
        contents(directory).forEach((name, bytes) -> {
            if (indexOf(bytes, needle) >= 0) {
                found.add(name);
            }
        });
        return found;
    }

    private static int indexOf(byte[] bytes, byte[] needle) {
        for (int start = 0; start + needle.length <= bytes.length; start++) {
            int i = 0;
            while (i < needle.length && bytes[start + i] == needle[i]) {
                i++;
            }
            if (i == needle.length) {
                return start;
            }
        }
        return -1;
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
