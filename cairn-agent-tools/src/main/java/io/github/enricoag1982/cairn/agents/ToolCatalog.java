package io.github.enricoag1982.cairn.agents;

import io.github.enricoag1982.cairn.documents.Instants;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The names, descriptions and input JSON Schemas of the tools (design D2 of {@code add-agent-tools}). The
 * descriptions are written for an agent that has no other documentation: what the tool does, what it needs, and which
 * tool to call next.
 */
final class ToolCatalog {

    static final String DESCRIBE = "cairn_describe";
    static final String JSON_SCHEMA = "cairn_json_schema";
    static final String EXPLAIN_PROBLEM = "cairn_explain_problem";
    static final String PLAN_SCHEMA = "cairn_plan_schema";
    static final String REGISTER_SCHEMA = "cairn_register_schema";
    static final String DRY_RUN = "cairn_dry_run";
    static final String COMMIT = "cairn_commit";
    static final String READ_ENTITY = "cairn_read_entity";
    static final String READ_RELATIONSHIP = "cairn_read_relationship";
    static final String LIST_ENTITIES = "cairn_list_entities";
    static final String TIMELINE = "cairn_timeline";
    static final String NAVIGATE = "cairn_navigate";
    static final String TRAVERSE = "cairn_traverse";
    static final String QUERY = "cairn_query";
    static final String APPROVAL_STATUS = "cairn_approval_status";

    private static final String READ_COORDINATES = " Reads through a view: effectiveAt says when the facts are true, "
            + "and knownAt, if given, reads the store as it was at a basis or a transaction time (the default is "
            + "now). The result reports the effectiveAt and the basis it was read at.";

    private static final String ID_OF_ENTITY = "The ID of the entity. Give this or key, not both.";
    private static final String ID_TO_START_FROM = "The ID of the entity to start from. Give this or key, not both.";

    private ToolCatalog() {
    }

    /** Every tool, in the order in which an agent should meet them. */
    static List<AgentTools.ToolDescription> all() {
        List<AgentTools.ToolDescription> tools = new ArrayList<>();
        tools.add(tool(DESCRIBE,
                "Describes the store. Returns the latest basis; the stored schema as one YAML schema document per "
                        + "origin; the rules that types may name, with their descriptions, the types they are for, "
                        + "their codes and whether the store has their code (hasCode); every problem code with its "
                        + "explanation; the approval policy; and the principal this server acts as, which every "
                        + "payload you commit must name. Call this first.",
                object(List.of())));
        tools.add(tool(JSON_SCHEMA,
                "Returns a JSON Schema to build input from. kind \"document\" is the published schema of schema "
                        + "documents; kind \"payload\" is the schema of transaction payloads for the current schema; "
                        + "kind \"values\" is the schema of the values of the entity type or relationship type named "
                        + "in type, with its attributes, constraints and examples; kind \"query\" is the published "
                        + "schema of the queries that cairn_query runs.",
                object(List.of("kind"),
                        "kind", string("Which JSON Schema to return.").set("enum",
                                Json.strings(List.of("document", "payload", "values", "query"))),
                        "type", string("For kind \"values\": the name or alias of an entity type or relationship "
                                + "type."))));
        tools.add(tool(EXPLAIN_PROBLEM,
                "Explains a problem code: what it means and how to resolve it. Accepts a code of Cairn, such as "
                        + "UNKNOWN_ATTRIBUTE, and the code of a rule, qualified by the rule's name, such as "
                        + "lei-checksum/invalid-check-digits.",
                object(List.of("code"), "code", string("The problem code or the qualified code of a rule."))));
        tools.add(tool(PLAN_SCHEMA,
                "Plans registering a schema document without registering it. Returns outcome (wouldApply, "
                        + "unchanged or rejected), every change from the stored definition of the origin with "
                        + "whether it is compatible and a hint to avoid it if it is not, and the problems, each "
                        + "located in the document by line and column. To change the schema, take the document "
                        + "from cairn_describe, edit it, raise the version of the origin, and plan it.",
                object(List.of("document"), "document", string("The text of the schema document, as YAML or JSON."))));
        tools.add(tool(REGISTER_SCHEMA,
                "Registers a schema document, subject to the approval policy. Returns outcome applied (with basis "
                        + "and transactionTime), unchanged, rejected (with located problems; breaking changes are "
                        + "rejected) or pending (with the approval request, which a person has to approve: poll "
                        + "cairn_approval_status). Plan it first.",
                object(List.of("document"),
                        "document", string("The text of the schema document, as YAML or JSON."),
                        "reason", string("Why the schema changes; recorded with the schema transaction."))));
        tools.add(tool(DRY_RUN,
                "Validates a transaction payload without committing it. Returns outcome wouldCommit, or rejected "
                        + "with every problem, located in the payload by line and column, with what was expected, "
                        + "what was found and a hint. Fix the problems and run it again.",
                object(List.of("payload"), "payload", string("The text of the transaction payload, as JSON or YAML. "
                        + "Build it from cairn_json_schema with kind \"payload\"; its principal is the one "
                        + "cairn_describe reports."))));
        tools.add(tool(COMMIT,
                "Commits a transaction payload, subject to the approval policy. Returns outcome committed (with "
                        + "the receipt: basis, transaction time and the IDs created), rejected (with located "
                        + "problems; nothing is recorded) or pending (with the approval request, which a person has "
                        + "to approve: poll cairn_approval_status). Dry-run it first.",
                object(List.of("payload"), "payload", string("The text of the transaction payload, as JSON or YAML. "
                        + "Build it from cairn_json_schema with kind \"payload\"; its principal is the one "
                        + "cairn_describe reports."))));
        tools.add(tool(READ_ENTITY,
                "Reads one entity: its type, values and effective period. Name the entity by its ID (id), or by a "
                        + "unique key (key): its entity type and one unique attribute with its value, such as the "
                        + "Account with the number A-1. Give exactly one of the two." + READ_COORDINATES
                        + " The result is null if the entity does not exist at that effective time, and, for a key, "
                        + "if no entity has the value at that time.",
                coordinates(List.of("effectiveAt"), "id", string(ID_OF_ENTITY), "key", key())));
        tools.add(tool(READ_RELATIONSHIP,
                "Reads one relationship: its type, source, target, values and effective period." + READ_COORDINATES
                        + " The result is null if the relationship does not exist at that effective time.",
                coordinates(List.of("id", "effectiveAt"), "id", string("The ID of the relationship."))));
        tools.add(tool(LIST_ENTITIES,
                "Lists the entities of an entity type that exist at the effective time, including those of its "
                        + "subtypes, ordered by ID." + READ_COORDINATES,
                coordinates(List.of("type", "effectiveAt"),
                        "type", string("The name or alias of an entity type."))));
        tools.add(tool(TIMELINE,
                "Reads every version of an entity or a relationship across effective time, as known at a basis or a "
                        + "transaction time (the default is now). Use it to see corrections and future-dated "
                        + "changes. The result reports the basis it was read at.",
                object(List.of("id"),
                        "id", string("The ID of an entity or a relationship."),
                        "knownAt", knownAt())));
        tools.add(tool(NAVIGATE,
                "Follows the relationships of an entity by a name that exists at the effective time: the name of a "
                        + "relationship type leads from its source to its targets, and its inverse name from the "
                        + "target to the sources. Returns each relationship and the entity at its other end. Name "
                        + "the entity to start from by its ID (id) or by a unique key (key), exactly one of the two; "
                        + "a key that no entity has at that time leads nowhere." + READ_COORDINATES,
                coordinates(List.of("name", "effectiveAt"),
                        "id", string(ID_TO_START_FROM),
                        "key", key(),
                        "name", string("The name or inverse name of a relationship type."))));
        tools.add(tool(TRAVERSE,
                "Follows every relationship of a name, recursively and breadth first, up to a maximum depth. "
                        + "Returns each entity reached, its depth and the relationship it was reached through; "
                        + "each entity once. Name the entity to start from by its ID (id) or by a unique key (key), "
                        + "exactly one of the two; a key that no entity has at that time leads nowhere."
                        + READ_COORDINATES,
                coordinates(List.of("name", "maxDepth", "effectiveAt"),
                        "id", string(ID_TO_START_FROM),
                        "key", key(),
                        "name", string("The name or inverse name of a relationship type."),
                        "maxDepth", integer("The largest number of relationships to follow; 0 follows none."))));
        tools.add(tool(QUERY,
                "Runs a query: filters entities, follows and traverses relationships, groups and aggregates, and "
                        + "returns rows. The query starts from an entity type, binds each entity to a name, and applies "
                        + "steps (where, follow, optionalFollow, traverse) to the rows so far; then groupBy and "
                        + "aggregates, columns, order and limit. Build it from cairn_json_schema with kind \"query\"; "
                        + "the names of types, attributes and relationships are those of the schema from "
                        + "cairn_describe. The result is {columns, rows}, each row a list of cells in the order of the "
                        + "columns: the state of an entity or a relationship, a value, or null. A query that does not fit "
                        + "the schema returns problems located in the query text, with the path of the step or part "
                        + "they concern and a hint, and nothing is read." + READ_COORDINATES,
                coordinates(List.of("query", "effectiveAt"),
                        "query", string("The text of the query, as JSON or YAML, in the form of the JSON Schema of "
                                + "cairn_json_schema with kind \"query\"."))));
        tools.add(tool(APPROVAL_STATUS,
                "Reports where an approval request stands: pending, approved or rejected. Once decided, it holds "
                        + "the outcome: for an approved request what registering or committing it did (a receipt, "
                        + "or the problems if it was no longer valid), for a rejected one the reason of the person.",
                object(List.of("requestId"),
                        "requestId", string("The ID of the request, from the result of cairn_register_schema or "
                                + "cairn_commit."))));
        return List.copyOf(tools);
    }

    private static AgentTools.ToolDescription tool(String name, String description, ObjectNode inputSchema) {
        return new AgentTools.ToolDescription(name, description, Json.render(inputSchema));
    }

    /** An object schema with the given required names, and properties given as alternating names and schemas. */
    private static ObjectNode object(List<String> required, Object... properties) {
        ObjectNode schema = Json.object().put("type", "object");
        ObjectNode map = schema.putObject("properties");
        for (int i = 0; i < properties.length; i += 2) {
            map.set((String) properties[i], (ObjectNode) properties[i + 1]);
        }
        ArrayNode names = schema.putArray("required");
        required.forEach(names::add);
        schema.put("additionalProperties", false);
        return schema;
    }

    /** Like {@link #object} with the optional {@code knownAt} property added; the effective time is a property too. */
    private static ObjectNode coordinates(List<String> required, Object... properties) {
        Object[] all = new Object[properties.length + 4];
        System.arraycopy(properties, 0, all, 0, properties.length);
        all[properties.length] = "effectiveAt";
        all[properties.length + 1] = instant("The effective time to read at: an ISO 8601 instant with seconds, and Z or "
                + "an offset with a colon, such as 2026-03-01T00:00:00Z.");
        all[properties.length + 2] = "knownAt";
        all[properties.length + 3] = knownAt();
        return object(required, all);
    }

    /**
     * The argument that names an entity by a unique key, in the form of transaction payloads. The attribute must be unique
     * in the entity type, and the value is read by the attribute's value type; the effective time of the call decides
     * which entity has the value.
     */
    private static ObjectNode key() {
        ObjectNode schema = Json.object().put("type", "object")
                .put("description", "A unique key to name the entity by, in place of id: its entity type, and a key with "
                        + "exactly one entry, the name of a unique attribute of that type and its value, such as "
                        + "{\"entityType\": \"Account\", \"key\": {\"number\": \"A-1\"}}. The entity is the one of that "
                        + "type, or of a subtype, that has the value at the effective time. Give this or id, not both.");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("entityType").put("type", "string").put("minLength", 1)
                .put("description", "The name or an alias of the entity type.");
        properties.putObject("key").put("type", "object").put("minProperties", 1).put("maxProperties", 1)
                .put("description", "The name of a unique attribute of the entity type, or an alias, and its value.")
                .putObject("additionalProperties").putArray("type").add("string").add("number").add("boolean");
        schema.putArray("required").add("entityType").add("key");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode knownAt() {
        ObjectNode schema = Json.object().put("type", "object")
                .put("description", "Read the store as it was at a basis, {\"basis\": 12}, or at a transaction time, "
                        + "{\"transactionTime\": \"2026-04-05T00:00:00Z\"}. Give exactly one. The default is now.");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("basis").put("type", "integer").put("minimum", 0);
        properties.putObject("transactionTime").put("type", "string").put("format", "date-time")
                .put("pattern", Instants.PATTERN);
        schema.put("minProperties", 1).put("maxProperties", 1).put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode string(String description) {
        return Json.object().put("type", "string").put("description", description);
    }

    /** An instant, in the form that payloads use: the pattern of the generated JSON Schemas as well as the format. */
    private static ObjectNode instant(String description) {
        return string(description).put("format", "date-time").put("pattern", Instants.PATTERN);
    }

    private static ObjectNode integer(String description) {
        return Json.object().put("type", "integer").put("minimum", 0).put("description", description);
    }
}
