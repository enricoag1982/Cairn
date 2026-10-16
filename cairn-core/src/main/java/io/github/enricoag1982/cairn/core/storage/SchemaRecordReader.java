package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Submission;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Reads a {@link SchemaRecord} that {@link SchemaRecordWriter} wrote in format version 1. */
final class SchemaRecordReader {

    private SchemaRecordReader() {
    }

    /** Reads the record after its kind byte. */
    static SchemaRecord read(Decoder in) {
        Basis basis = new Basis(in.readLong());
        Instant transactionTime = in.readInstant();
        String principal = in.readText();
        Optional<String> onBehalfOf = in.readOptionalText();
        Optional<String> reason = in.readOptionalText();
        Origin origin = new Origin(in.readText(), in.readText());
        SchemaDefinition definition = DefinitionCodec.read(in);
        List<SchemaIdentifiers.TypeAssignment> types = in.readAll(decoder ->
                new SchemaIdentifiers.TypeAssignment(decoder.readText(), new TypeId(decoder.readLong())));
        List<SchemaIdentifiers.AttributeAssignment> attributes = in.readAll(decoder ->
                new SchemaIdentifiers.AttributeAssignment(decoder.readText(), decoder.readText(),
                        new AttributeId(decoder.readLong())));
        long nextId = in.readLong();
        definition = DefinitionCodec.readChecks(in, definition);
        definition = DefinitionCodec.readDerivations(in, definition);
        definition = DefinitionCodec.readOutliving(in, definition);
        return new SchemaRecord(basis, transactionTime, new Submission(principal, onBehalfOf, reason), origin,
                definition, new SchemaIdentifiers(types, attributes, nextId));
    }
}
