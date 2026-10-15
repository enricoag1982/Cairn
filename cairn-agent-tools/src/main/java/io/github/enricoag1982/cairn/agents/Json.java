package io.github.enricoag1982.cairn.agents;

import io.github.enricoag1982.cairn.documents.Payloads;
import io.github.enricoag1982.cairn.schema.Problem;
import java.util.List;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes the JSON of tool inputs and outputs (design D2 of {@code add-agent-tools}). Numbers keep their
 * digits and scale: a decimal such as {@code 55.00} comes back as written, in plain notation.
 */
final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private Json() {
    }

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    /**
     * Parses JSON text.
     *
     * @throws tools.jackson.core.JacksonException if the text is not JSON
     */
    static JsonNode parse(String text) {
        return MAPPER.readTree(text);
    }

    /** Writes a tree as compact JSON text. */
    static String render(JsonNode node) {
        return MAPPER.writeValueAsString(node);
    }

    /** Problems in the JSON form of the {@code json-payloads} capability, as an array. */
    static ArrayNode problems(List<Problem> problems) {
        return (ArrayNode) parse(Payloads.problemsToJson(problems));
    }

    /** Converts a list of strings to an array. */
    static ArrayNode strings(List<String> strings) {
        ArrayNode array = array();
        strings.forEach(array::add);
        return array;
    }
}
