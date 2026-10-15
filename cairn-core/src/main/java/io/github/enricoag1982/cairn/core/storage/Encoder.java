package io.github.enricoag1982.cairn.core.storage;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BiConsumer;
import java.util.zip.CRC32C;

/**
 * Writes the primitives of format version 1 to a growing array: numbers are big-endian, text is UTF-8 with a 4-byte
 * length in bytes, optional values carry a presence byte, and instants are microseconds since the epoch.
 *
 * <p>Every Java string can be written, and reads back equal. UTF-8 cannot hold an unpaired surrogate, so such a char is
 * written as its own three-byte sequence, the one it would have if it were a code point, as WTF-8 does. A high surrogate
 * followed by a low one is a pair, and is written as the four-byte sequence of its code point.
 *
 * <p>An instant whose microseconds do not fit a long, or that has nanoseconds, or that is {@code Instant.MAX} (the
 * open end of an effective period, written as {@link Long#MAX_VALUE}), is still written exactly: the long
 * {@link Long#MIN_VALUE} announces that the seconds and the nanoseconds follow.
 *
 * <p>An encoder that is {@linkplain #framed() framed} reserves the 12 bytes of the frame that surrounds a record: its
 * length, the CRC32C of those four length bytes, and the CRC32C of the payload, which {@link #finishFrame()} fills in.
 * The checksum of the length lets a reader tell a damaged length from a record that was cut short.
 */
final class Encoder {

    /** The size of the length, the checksum of the length and the checksum of the payload that precede a payload. */
    static final int FRAME_HEADER = 12;

    /** Written for {@code Instant.MAX}, which no effective time reaches. */
    static final long OPEN = Long.MAX_VALUE;

    /** Written in front of the seconds and the nanoseconds of an instant that microseconds cannot hold. */
    static final long EXACT = Long.MIN_VALUE;

    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final int NANOS_PER_MICRO = 1_000;

    private byte[] bytes = new byte[256];
    private int size;
    private final boolean framed;

    private Encoder(boolean framed) {
        this.framed = framed;
        this.size = framed ? FRAME_HEADER : 0;
    }

    /** An encoder for a payload on its own. */
    static Encoder plain() {
        return new Encoder(false);
    }

    /** An encoder for a record in a frame: the payload follows the 12 reserved bytes of the frame header. */
    static Encoder framed() {
        return new Encoder(true);
    }

    private void ensure(int extra) {
        if (extra < 0 || size > Integer.MAX_VALUE - 8 - extra) {
            throw new IllegalArgumentException("A record cannot be larger than 2 GiB.");
        }
        if (size + extra > bytes.length) {
            bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + extra));
        }
    }

    void writeByte(int value) {
        ensure(1);
        bytes[size++] = (byte) value;
    }

    void writeBoolean(boolean value) {
        writeByte(value ? 1 : 0);
    }

    void writeInt(int value) {
        ensure(4);
        bytes[size++] = (byte) (value >>> 24);
        bytes[size++] = (byte) (value >>> 16);
        bytes[size++] = (byte) (value >>> 8);
        bytes[size++] = (byte) value;
    }

    void writeLong(long value) {
        ensure(8);
        for (int shift = 56; shift >= 0; shift -= 8) {
            bytes[size++] = (byte) (value >>> shift);
        }
    }

    /** Writes the bytes as they are, without a length. */
    void writeRaw(byte[] value) {
        ensure(value.length);
        System.arraycopy(value, 0, bytes, size, value.length);
        size += value.length;
    }

    /** Writes a length and the bytes. */
    void writeBytes(byte[] value) {
        writeInt(value.length);
        writeRaw(value);
    }

    /**
     * Writes text as UTF-8 with its length in bytes. An unpaired surrogate is written as its own three-byte sequence, so
     * that any string reads back equal.
     */
    void writeText(String value) {
        long length = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                length += 1;
            } else if (c < 0x800) {
                length += 2;
            } else if (startsPair(value, i)) {
                length += 4;
                i++;
            } else {
                length += 3;
            }
        }
        if (length > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("A text cannot be larger than 2 GiB.");
        }
        writeInt((int) length);
        ensure((int) length);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                bytes[size++] = (byte) c;
            } else if (c < 0x800) {
                bytes[size++] = (byte) (0xC0 | (c >> 6));
                bytes[size++] = (byte) (0x80 | (c & 0x3F));
            } else if (startsPair(value, i)) {
                int codePoint = Character.toCodePoint(c, value.charAt(++i));
                bytes[size++] = (byte) (0xF0 | (codePoint >> 18));
                bytes[size++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                bytes[size++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
                bytes[size++] = (byte) (0x80 | (codePoint & 0x3F));
            } else {
                bytes[size++] = (byte) (0xE0 | (c >> 12));
                bytes[size++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                bytes[size++] = (byte) (0x80 | (c & 0x3F));
            }
        }
    }

    /** Tells whether the char at an index is a high surrogate that a low surrogate follows. */
    private static boolean startsPair(String value, int index) {
        return Character.isHighSurrogate(value.charAt(index)) && index + 1 < value.length()
                && Character.isLowSurrogate(value.charAt(index + 1));
    }

    void writeOptionalText(Optional<String> value) {
        writeBoolean(value.isPresent());
        value.ifPresent(this::writeText);
    }

    void writeOptionalInt(OptionalInt value) {
        writeBoolean(value.isPresent());
        value.ifPresent(this::writeInt);
    }

    /** Writes an instant as microseconds since the epoch; see the class description for what cannot be. */
    void writeInstant(Instant value) {
        if (value.equals(Instant.MAX)) {
            writeLong(OPEN);
            return;
        }
        long micros;
        try {
            micros = Math.addExact(Math.multiplyExact(value.getEpochSecond(), MICROS_PER_SECOND),
                    value.getNano() / NANOS_PER_MICRO);
        } catch (ArithmeticException e) {
            writeExactInstant(value);
            return;
        }
        if (value.getNano() % NANOS_PER_MICRO != 0 || micros == OPEN || micros == EXACT) {
            writeExactInstant(value);
        } else {
            writeLong(micros);
        }
    }

    private void writeExactInstant(Instant value) {
        writeLong(EXACT);
        writeLong(value.getEpochSecond());
        writeInt(value.getNano());
    }

    /** Writes an instant by its seconds and nanoseconds, for values that are not times of the store. */
    void writeExact(Instant value) {
        writeLong(value.getEpochSecond());
        writeInt(value.getNano());
    }

    void writeOptionalInstant(Optional<Instant> value) {
        writeBoolean(value.isPresent());
        value.ifPresent(this::writeInstant);
    }

    /** Writes the number of elements, then each element. */
    <T> void writeAll(Collection<? extends T> values, BiConsumer<Encoder, T> element) {
        writeInt(values.size());
        for (T value : values) {
            element.accept(this, value);
        }
    }

    int size() {
        return size;
    }

    /** The bytes written so far, including the reserved bytes of a framed encoder. */
    byte[] toByteArray() {
        return Arrays.copyOf(bytes, size);
    }

    /**
     * Completes a framed record: writes the length of the payload, the CRC32C of those four bytes, and the CRC32C of the
     * payload into the reserved bytes.
     *
     * @return the whole frame
     */
    byte[] finishFrame() {
        if (!framed) {
            throw new IllegalStateException("The encoder has no frame.");
        }
        int length = size - FRAME_HEADER;
        CRC32C payload = new CRC32C();
        payload.update(bytes, FRAME_HEADER, length);
        int end = size;
        size = 0;
        writeInt(length);
        CRC32C lengthChecksum = new CRC32C();
        lengthChecksum.update(bytes, 0, 4);
        writeInt((int) lengthChecksum.getValue());
        writeInt((int) payload.getValue());
        size = end;
        return toByteArray();
    }
}
