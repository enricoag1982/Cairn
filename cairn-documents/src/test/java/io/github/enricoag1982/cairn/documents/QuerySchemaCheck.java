package io.github.enricoag1982.cairn.documents;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;

/** Checks queries against the published JSON Schema with json-schema-validator. */
final class QuerySchemaCheck {

    private static final Schema SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(Queries.jsonSchema(), InputFormat.JSON);

    private QuerySchemaCheck() {
    }

    /**
     * Checks a query, YAML or JSON: it is read into a tree by SnakeYAML Engine with the YAML 1.2 core schema and then
     * checked as JSON.
     *
     * @return the ways in which the query is invalid, none if it is valid
     */
    static List<Error> errors(String text) {
        return SCHEMA.validate(DocumentTrees.toJson(DocumentTrees.load(text)), InputFormat.JSON);
    }
}
