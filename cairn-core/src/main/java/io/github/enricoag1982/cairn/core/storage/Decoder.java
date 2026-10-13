package io.github.enricoag1982.cairn.core.storage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * Reads what {@link Encoder} wrote, from an array. Anything that is not a well-formed record of format version 1, such as
 * bytes that end too soon, a presence byte that is neither 0 nor 1, or a length beyond the bytes that are left, throws a
 * {@link CorruptRecordException}.
 */
final class Decoder {

    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final int NANOS_PER_MICRO = 1_000;

    private final byte[] bytes;
    private int position;

    Decoder(byte[] bytes) {
        this(bytes, 0);
    }

    Decoder(byte[] bytes, int position) {
        this.bytes = bytes;
        this.position = position;
    }

    int position() {
        return position;
    }

    int remaining() {
        return bytes.length - position;
    }

    private void require(int count) {
        if (count < 0 || count > remaining()) {
            throw new CorruptRecordException("The record ends " + (count - remaining()) + " bytes too soon, at "
                    + position + " of " + bytes.length + ".");
        }
    }

    int readByte() {
        require(1);
        return bytes[position++] & 0xFF;
    }

    boolean readBoolean() {
        int value = readByte();
        if (value > 1) {
            throw new CorruptRecordException("The byte " + value + " at " + (position - 1) + " is not a boolean.");
        }
        return value == 1;
    }

    int readInt() {
        require(4);
        int value = 0;
        for (int i = 0; i < 4; i++) {
            value = (value << 8) | (bytes[position++] & 0xFF);
        }
        return value;
    }

    long readLong() {
        require(8);
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[position++] & 0xFF);
        }
        return value;
    }

    /** Reads a length and that many bytes. */
    byte[] readBytes() {
        int length = readInt();
        require(length);
        byte[] value = new byte[length];
        System.arraycopy(bytes, position, value, 0, length);
        position += length;
        return value;
    }

    /**
     * Reads text that {@link Encoder#writeText(String)} wrote: UTF-8, in which a three-byte sequence may be an unpaired
     * surrogate. Anything else that is not well-formed, such as an overlong sequence, a code point beyond U+10FFFF, or a
     * high and a low surrogate in two three-byte sequences, which the encoder writes as one four-byte sequence, is
     * corrupt.
     */
    String readText() {
        int length = readInt();
        require(length);
        int end = position + length;
        StringBuilder text = new StringBuilder(length);
        int i = position;
        while (i < end) {
            int first = bytes[i++] & 0xFF;
            if (first < 0x80) {
                text.append((char) first);
            } else if (first >= 0xC2 && first <= 0xDF) {
                int second = continuation(i++, end);
                text.append((char) (((first & 0x1F) << 6) | second));
            } else if (first >= 0xE0 && first <= 0xEF) {
                int second = continuation(i++, end);
                int third = continuation(i++, end);
                int codePoint = ((first & 0x0F) << 12) | (second << 6) | third;
                if (codePoint < 0x800) {
                    throw new CorruptRecordException("The text at " + (i - 3) + " has an overlong sequence.");
                }
                if (Character.isLowSurrogate((char) codePoint) && !text.isEmpty()
                        && Character.isHighSurrogate(text.charAt(text.length() - 1))) {
                    throw new CorruptRecordException("The text at " + (i - 3) + " has a pair of surrogates in two "
                            + "sequences.");
                }
                text.append((char) codePoint);
            } else if (first >= 0xF0 && first <= 0xF4) {
                int second = continuation(i++, end);
                int third = continuation(i++, end);
                int fourth = continuation(i++, end);
                int codePoint = ((first & 0x07) << 18) | (second << 12) | (third << 6) | fourth;
                if (codePoint < 0x10000 || codePoint > 0x10FFFF) {
                    throw new CorruptRecordException("The text at " + (i - 4) + " has a code point out of range.");
                }
                text.appendCodePoint(codePoint);
            } else {
                throw new CorruptRecordException("The text at " + (i - 1) + " is not UTF-8.");
            }
        }
        position = end;
        return text.toString();
    }

    /** The six bits of a continuation byte at an index of the text that ends at {@code end}. */
    private int continuation(int index, int end) {
        if (index >= end) {
            throw new CorruptRecordException("The text at " + index + " ends inside a sequence.");
        }
        int value = bytes[index] & 0xFF;
        if ((value & 0xC0) != 0x80) {
            throw new CorruptRecordException("The text at " + index + " is not UTF-8.");
        }
        return value & 0x3F;
    }

    Optional<String> readOptionalText() {
        return readBoolean() ? Optional.of(readText()) : Optional.empty();
    }

    OptionalInt readOptionalInt() {
        return readBoolean() ? OptionalInt.of(readInt()) : OptionalInt.empty();
    }

    /** Reads an instant that {@link Encoder#writeInstant(Instant)} wrote. */
    Instant readInstant() {
        long micros = readLong();
        if (micros == Encoder.OPEN) {
            return Instant.MAX;
        }
        if (micros == Encoder.EXACT) {
            return readExact();
        }
        return Instant.ofEpochSecond(Math.floorDiv(micros, MICROS_PER_SECOND),
                Math.floorMod(micros, MICROS_PER_SECOND) * NANOS_PER_MICRO);
    }

    /** Reads an instant that {@link Encoder#writeExact(Instant)} wrote. */
    Instant readExact() {
        long seconds = readLong();
        int nanos = readInt();
        if (nanos < 0 || nanos >= 1_000_000_000) {
            throw new CorruptRecordException("The nanoseconds " + nanos + " are out of range.");
        }
        return Instant.ofEpochSecond(seconds, nanos);
    }

    Optional<Instant> readOptionalInstant() {
        return readBoolean() ? Optional.of(readInstant()) : Optional.empty();
    }

    /**
     * Reads a number of elements, then each element.
     *
     * @throws CorruptRecordException if the number is larger than the bytes that are left, as every element takes at
     *                                least a byte
     */
    <T> List<T> readAll(Function<Decoder, T> element) {
        int count = readInt();
        if (count < 0 || count > remaining()) {
            throw new CorruptRecordException("The count " + count + " at " + (position - 4) + " exceeds the "
                    + remaining() + " bytes that are left.");
        }
        List<T> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(element.apply(this));
        }
        return values;
    }

    /** Checks that the whole record was read. */
    void requireEnd() {
        if (remaining() != 0) {
            throw new CorruptRecordException(remaining() + " bytes are left over after the record, at " + position
                    + ".");
        }
    }
}
