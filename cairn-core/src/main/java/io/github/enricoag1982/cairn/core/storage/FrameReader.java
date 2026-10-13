package io.github.enricoag1982.cairn.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/**
 * Reads the frames of a log segment or snapshot file from a stream, one at a time. A frame is 4 bytes of length, 4 bytes
 * of CRC32C of those length bytes, 4 bytes of CRC32C of the payload, and the payload. The checksum of the length means a
 * reader never takes a damaged length for a record that was cut short: it tells where each frame starts, and what is
 * wrong with a frame that is not whole.
 *
 * <p>A frame is bad in one of these ways, which {@link Bad#kind()} tells:
 *
 * <ul>
 *   <li>{@link Kind#SHORT_HEADER}: the file ends before the 12 bytes of the header are complete;
 *   <li>{@link Kind#HEADER_CHECKSUM}: the checksum of the length does not match, so the length is not to be trusted; a
 *       header of zero bytes, as a file that was extended but never written has, is of this kind, and so is a header that
 *       was written in part;
 *   <li>{@link Kind#LENGTH}: the checksum of the length matches, but the length is not at least 1, the kind byte;
 *   <li>{@link Kind#PAYLOAD_PAST_END}: the length is right, but the file ends inside the payload;
 *   <li>{@link Kind#PAYLOAD_CHECKSUM}: the payload is whole, but its checksum does not match.
 * </ul>
 *
 * <p>Which of these can be where a crash ended a log is for {@link Recovery} to decide; this class only reports. A
 * payload that fails its checksum can be where a crash ended a log if nothing but zero bytes follows it, which
 * {@link #restIsZero()} tells.
 */
final class FrameReader {

    /** What is wrong with a bad frame. */
    enum Kind {
        SHORT_HEADER, HEADER_CHECKSUM, LENGTH, PAYLOAD_PAST_END, PAYLOAD_CHECKSUM
    }

    /** What {@link #next()} found. */
    sealed interface Result {
    }

    /** The stream ended at a frame boundary. */
    record End() implements Result {
    }

    /**
     * A whole frame.
     *
     * @param start   the position of the frame in the file
     * @param payload the payload, which starts with the kind byte
     */
    record Frame(long start, byte[] payload) implements Result {
    }

    /**
     * A frame that is not whole or does not match its checksums.
     *
     * @param start  the position of the frame in the file
     * @param reason what is wrong
     * @param kind   the kind of what is wrong
     */
    record Bad(long start, String reason, Kind kind) implements Result {

        /** Tells whether the file ends inside the frame: in its header, or in its payload after a right header. */
        boolean cutShort() {
            return kind == Kind.SHORT_HEADER || kind == Kind.PAYLOAD_PAST_END;
        }
    }

    private final InputStream in;
    private final long fileLength;
    private long position;

    /**
     * Creates a reader.
     *
     * @param in         the stream, positioned at {@code start}
     * @param start      the position in the file at which the first frame begins
     * @param fileLength the length of the file, so that a frame whose length reaches beyond it is not read
     */
    FrameReader(InputStream in, long start, long fileLength) {
        this.in = in;
        this.position = start;
        this.fileLength = fileLength;
    }

    /** The position in the file after what was read last. */
    long position() {
        return position;
    }

    Result next() throws IOException {
        long start = position;
        byte[] header = in.readNBytes(Encoder.FRAME_HEADER);
        if (header.length == 0) {
            return new End();
        }
        position += header.length;
        if (header.length < Encoder.FRAME_HEADER) {
            return new Bad(start, "the file ends inside the header of a record", Kind.SHORT_HEADER);
        }
        ByteBuffer buffer = ByteBuffer.wrap(header);
        int length = buffer.getInt();
        int expectedLengthChecksum = buffer.getInt();
        int expectedPayloadChecksum = buffer.getInt();
        CRC32C lengthChecksum = new CRC32C();
        lengthChecksum.update(header, 0, 4);
        if ((int) lengthChecksum.getValue() != expectedLengthChecksum) {
            return new Bad(start, "the checksum of the length of a record does not match", Kind.HEADER_CHECKSUM);
        }
        if (length < 1) {
            return new Bad(start, "a record claims the length " + length, Kind.LENGTH);
        }
        if (length > fileLength - position) {
            position = fileLength;
            return new Bad(start, "a record of " + length + " bytes is cut short by the end of the file",
                    Kind.PAYLOAD_PAST_END);
        }
        byte[] payload = in.readNBytes(length);
        position += payload.length;
        if (payload.length < length) {
            return new Bad(start, "a record of " + length + " bytes is cut short by the end of the file",
                    Kind.PAYLOAD_PAST_END);
        }
        CRC32C checksum = new CRC32C();
        checksum.update(payload, 0, payload.length);
        if ((int) checksum.getValue() != expectedPayloadChecksum) {
            return new Bad(start, "the checksum of a record of " + length + " bytes does not match",
                    Kind.PAYLOAD_CHECKSUM);
        }
        return new Frame(start, payload);
    }

    private static boolean allZero(byte[] bytes, int count) {
        for (int i = 0; i < count; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tells whether everything that is left in the stream is zero bytes, or nothing. Call it after a bad frame; the
     * bytes it reads are consumed.
     */
    boolean restIsZero() throws IOException {
        return zeroToTheEnd(in);
    }

    /** Tells whether everything that is left in a stream is zero bytes, or nothing; the bytes are consumed. */
    static boolean zeroToTheEnd(InputStream stream) throws IOException {
        byte[] block = new byte[8192];
        int read;
        while ((read = stream.read(block)) > 0) {
            if (!allZero(block, read)) {
                return false;
            }
        }
        return true;
    }
}
