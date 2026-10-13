package io.github.enricoag1982.cairn.documents;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;

/** Checks documents against the published JSON Schema with json-schema-validator. */
final class PublishedSchemaCheck {

    private static final Schema SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(SchemaDocuments.jsonSchema(), InputFormat.JSON);

    private PublishedSchemaCheck() {
    }

    /**
     * Checks a document, YAML or JSON. The document is read into a tree by SnakeYAML Engine with the YAML 1.2 core
     * schema and then checked as JSON, because the validator's own YAML reading is not YAML 1.2.
     *
     * @return the ways in which the document is invalid, none if it is valid
     */
    static List<Error> errors(String text) {
        return SCHEMA.validate(DocumentTrees.toJson(DocumentTrees.load(text)), InputFormat.JSON);
    }

    /** Checks a document that is written as JSON, as the validator reads it. */
    static List<Error> errorsOfJson(String json) {
        return SCHEMA.validate(json, InputFormat.JSON);
    }
}
