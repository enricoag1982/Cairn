package io.github.enricoag1982.cairn.core.storage;

/**
 * Writes a {@link SchemaRecord} in format version 1: the basis, the transaction time, the submission, the origin, the
 * definition field by field, and the identifiers that the registration assigned. If any type declares checks, they
 * follow, and if any attribute is derived, so do the expressions of the derived attributes, after the checks (an empty
 * section of checks if no type declares one), as the last of the record.
 */
final class SchemaRecordWriter {

    private SchemaRecordWriter() {
    }

    /** Writes the record after its kind byte. */
    static void write(Encoder out, SchemaRecord record) {
        out.writeLong(record.basis().value());
        out.writeInstant(record.transactionTime());
        out.writeText(record.submission().principal());
        out.writeOptionalText(record.submission().onBehalfOf());
        out.writeOptionalText(record.submission().reason());
        out.writeText(record.origin().name());
        out.writeText(record.origin().version());
        DefinitionCodec.write(out, record.definition());
        SchemaIdentifiers identifiers = record.identifiers();
        out.writeAll(identifiers.types(), (encoder, type) -> {
            encoder.writeText(type.name());
            encoder.writeLong(type.id().value());
        });
        out.writeAll(identifiers.attributes(), (encoder, attribute) -> {
            encoder.writeText(attribute.declarer());
            encoder.writeText(attribute.name());
            encoder.writeLong(attribute.id().value());
        });
        out.writeLong(identifiers.nextId());
        DefinitionCodec.writeChecks(out, record.definition(), DefinitionCodec.hasDerived(record.definition()));
        DefinitionCodec.writeDerivations(out, record.definition());
    }
}
