package io.github.enricoag1982.cairn.core.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The digests by which a store remembers the IDs that purges removed (ADR-0024, design of {@code add-purge}): the
 * SHA-256 of the UTF-8 bytes of the ID's text, as lowercase hexadecimal text. The store needs to refuse a purged ID, and
 * to answer whether one was purged, but a purge leaves a record that it happened, not what was purged, so no file and no
 * memory of the store holds the ID itself. Digests are compared as text.
 */
public final class IdDigests {

    /** The length of a digest in bytes. */
    static final int LENGTH = 32;

    private static final HexFormat HEX = HexFormat.of();

    private IdDigests() {
    }

    /**
     * The digest of an ID.
     *
     * @param id the text of an entity or relationship ID
     * @return the SHA-256 of its UTF-8 bytes as 64 lowercase hexadecimal digits
     */
    public static String of(String id) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform has SHA-256.", e);
        }
    }

    /** The bytes of a digest that {@link #of(String)} made. */
    static byte[] toBytes(String digest) {
        return HEX.parseHex(digest);
    }

    /** The digest of the given bytes, which must be the length of a digest. */
    static String fromBytes(byte[] bytes) {
        if (bytes.length != LENGTH) {
            throw new CorruptRecordException("A digest has " + LENGTH + " bytes, but this one has " + bytes.length
                    + ".");
        }
        return HEX.formatHex(bytes);
    }
}
