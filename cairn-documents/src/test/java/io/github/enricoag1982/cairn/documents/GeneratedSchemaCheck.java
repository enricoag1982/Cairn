package io.github.enricoag1982.cairn.documents;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Checks JSON against generated JSON Schemas with json-schema-validator, and generated JSON Schemas against the dialect. */
final class GeneratedSchemaCheck {

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Schema DIALECT = REGISTRY.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    private static final Map<String, Schema> COMPILED = new ConcurrentHashMap<>();

    private GeneratedSchemaCheck() {
    }

    /** Checks a JSON text against a JSON Schema; the schema is compiled once. */
    static List<Error> errors(String jsonSchema, String json) {
        return COMPILED.computeIfAbsent(jsonSchema, text -> REGISTRY.getSchema(text, InputFormat.JSON))
                .validate(json, InputFormat.JSON);
    }

    /** Tells whether a JSON text is valid against a JSON Schema. */
    static boolean isValid(String jsonSchema, String json) {
        return errors(jsonSchema, json).isEmpty();
    }

    /** Checks a JSON Schema against the JSON Schema dialect it declares: the ways in which it is no JSON Schema. */
    static List<Error> dialectErrors(String jsonSchema) {
        return DIALECT.validate(jsonSchema, InputFormat.JSON);
    }
}
