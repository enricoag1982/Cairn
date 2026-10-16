package io.github.enricoag1982.cairn.core.storage;

/**
 * Writes a {@link SchemaRecord} in format version 1: the basis, the transaction time, the submission, the origin, the
 * definition field by field, and the identifiers that the registration assigned. If any type declares checks, they
 * follow, and if any attribute is derived, so do the expressions of the derived attributes, after the checks (an empty
 * section of checks if no type declares one). If any relationship type lets its relationships outlive their targets,
 * their names follow as the last of the record, after the sections before it, which are empty if there is nothing in
 * them.
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
        boolean outliving = DefinitionCodec.hasOutliving(record.definition());
        DefinitionCodec.writeChecks(out, record.definition(),
                DefinitionCodec.hasDerived(record.definition()) || outliving);
        DefinitionCodec.writeDerivations(out, record.definition(), outliving);
        DefinitionCodec.writeOutliving(out, record.definition());
    }
}
