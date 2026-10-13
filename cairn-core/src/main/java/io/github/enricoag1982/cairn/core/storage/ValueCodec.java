package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;

/**
 * Writes and reads {@link Value}s in format version 1: a byte for the kind, then the value. A decimal is written with its
 * scale and its unscaled value, so that it reads back equal, with the same scale. A date is its day number, and an
 * instant its seconds and nanoseconds.
 */
final class ValueCodec {

    private static final int STRING = 1;
    private static final int BOOLEAN = 2;
    private static final int INT = 3;
    private static final int LONG = 4;
    private static final int DECIMAL = 5;
    private static final int DATE = 6;
    private static final int INSTANT = 7;

    private ValueCodec() {
    }

    static void write(Encoder out, Value value) {
        switch (value) {
            case Value.StringValue string -> {
                out.writeByte(STRING);
                out.writeText(string.value());
            }
            case Value.BooleanValue bool -> {
                out.writeByte(BOOLEAN);
                out.writeBoolean(bool.value());
            }
            case Value.IntValue number -> {
                out.writeByte(INT);
                out.writeInt(number.value());
            }
            case Value.LongValue number -> {
                out.writeByte(LONG);
                out.writeLong(number.value());
            }
            case Value.DecimalValue decimal -> {
                out.writeByte(DECIMAL);
                out.writeInt(decimal.value().scale());
                out.writeBytes(decimal.value().unscaledValue().toByteArray());
            }
            case Value.DateValue date -> {
                out.writeByte(DATE);
                out.writeLong(date.value().toEpochDay());
            }
            case Value.InstantValue instant -> {
                out.writeByte(INSTANT);
                out.writeExact(instant.value());
            }
        }
    }

    static Value read(Decoder in) {
        int kind = in.readByte();
        return switch (kind) {
            case STRING -> Value.of(in.readText());
            case BOOLEAN -> Value.of(in.readBoolean());
            case INT -> Value.of(in.readInt());
            case LONG -> Value.of(in.readLong());
            case DECIMAL -> {
                int scale = in.readInt();
                byte[] unscaled = in.readBytes();
                if (unscaled.length == 0) {
                    throw new CorruptRecordException("A decimal has no digits.");
                }
                yield Value.of(new BigDecimal(new BigInteger(unscaled), scale));
            }
            case DATE -> Value.of(LocalDate.ofEpochDay(in.readLong()));
            case INSTANT -> Value.of(in.readExact());
            default -> throw new CorruptRecordException("The value kind " + kind + " is not known.");
        };
    }
}
