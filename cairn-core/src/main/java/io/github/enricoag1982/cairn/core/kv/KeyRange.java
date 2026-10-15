package io.github.enricoag1982.cairn.core.kv;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A range of keys, from a key up to but not including another, in unsigned byte order. Immutable: it keeps copies of its
 * bounds, and hands out the copies it keeps, which callers must not change.
 */
public final class KeyRange {

    private static final KeyRange ALL = new KeyRange(new byte[0], null);

    private final byte[] from;
    private final byte[] to;

    private KeyRange(byte[] from, byte[] to) {
        this.from = from;
        this.to = to;
    }

    /**
     * The keys from a key up to but not including another.
     *
     * @param from the first key of the range, which is in it
     * @param to   the first key after the range, which is not in it; {@code null} for a range without an end
     * @return the range
     */
    public static KeyRange between(byte[] from, byte[] to) {
        return new KeyRange(Objects.requireNonNull(from, "from").clone(), to == null ? null : to.clone());
    }

    /**
     * The keys that start with a prefix.
     *
     * @param prefix the prefix, which may be empty
     * @return the range of the keys that have the prefix, including the prefix itself
     */
    public static KeyRange prefix(byte[] prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return new KeyRange(prefix.clone(), after(prefix));
    }

    /**
     * Every key.
     *
     * @return the range of all keys
     */
    public static KeyRange all() {
        return ALL;
    }

    /**
     * The smallest key that is greater than every key with a prefix: the prefix with its last byte below 0xFF increased
     * by one, and what follows it dropped.
     *
     * @param prefix the prefix
     * @return the key; {@code null} if every byte of the prefix is 0xFF, because no key is greater than all keys that
     *         have it
     */
    static byte[] after(byte[] prefix) {
        int last = prefix.length - 1;
        while (last >= 0 && prefix[last] == (byte) 0xFF) {
            last--;
        }
        if (last < 0) {
            return null;
        }
        byte[] end = Arrays.copyOf(prefix, last + 1);
        end[last]++;
        return end;
    }

    /**
     * The first key of the range.
     *
     * @return the key, which callers must not change
     */
    public byte[] from() {
        return from;
    }

    /**
     * The first key after the range.
     *
     * @return the key, which callers must not change; {@code null} if the range has no end
     */
    public byte[] to() {
        return to;
    }

    /**
     * Tells whether a key is in the range.
     *
     * @param key the key
     * @return {@code true} if the key is not below the start and below the end
     */
    public boolean contains(byte[] key) {
        return Arrays.compareUnsigned(key, from) >= 0 && (to == null || Arrays.compareUnsigned(key, to) < 0);
    }

    @Override
    public String toString() {
        HexFormat hex = HexFormat.of();
        return "KeyRange[" + hex.formatHex(from) + ", " + (to == null ? "end" : hex.formatHex(to)) + ")";
    }
}
