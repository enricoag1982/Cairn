package io.github.enricoag1982.cairn.agents;

import io.github.enricoag1982.cairn.agents.AgentPolicy.Decision;
import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.documents.JsonSchemas;
import io.github.enricoag1982.cairn.documents.Queries;
import io.github.enricoag1982.cairn.documents.SchemaDocuments;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.StoredDefinition;
import io.github.enricoag1982.cairn.schema.Submission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tools that a store offers to AI agents, as plain Java (ADR-0017, commitment 7, design D2 of
 * {@code add-agent-tools}, capability {@code agent-tools}). There is one method for each tool; each takes the
 * arguments of the call as JSON text and returns JSON text. {@link McpAdapter} registers them with the Model Context
 * Protocol; they can be used and tested without it.
 *
 * <p>A tool never throws for input that is wrong. A call that cannot be done returns problems as JSON: located in the
 * document or payload where one was given, and otherwise with the path {@code @arguments.<name>} of the argument
 * concerned. Documents and payloads are passed as text, so that the locations of problems are the lines and columns of
 * what the caller wrote.
 *
 * <p>The tools act as one principal, optionally for another party. Every payload must name that principal; a payload
 * that names another is rejected with {@code PRINCIPAL_MISMATCH}. A payload that names no party acted for gets the one
 * that the tools were created with. Schema registrations record the principal too, with the reason the agent gives.
 * The {@link AgentPolicy} says whether a registration or a commit is applied, waits for a person in the
 * {@link ApprovalQueue}, or is denied.
 *
 * <p>The tools read and write through a {@linkplain Clearance clearance} (ADR-0036, design D5 of
 * {@code add-attribute-access-control}), which is none unless the tools are created with one: every tool that reads uses
 * views restricted to it, so a state lists the attributes it {@code withheld} and a query or a key that names one is a
 * problem {@code ACCESS_DENIED}, and every commit and dry run carries it, so a write of a classified attribute that it does
 * not cover is rejected with {@code ACCESS_DENIED} too. A payload has no way to give or widen a clearance.
 *
 * <p>The tools are safe for use by several threads.
 */
public final class AgentTools {

    /**
     * The description of a tool for a client that lists tools.
     *
     * @param name        the name of the tool, such as {@code cairn_describe}
     * @param description what the tool does and when to use it, written for an agent
     * @param inputSchema the JSON Schema of the arguments of the tool, as JSON text
     */
    public record ToolDescription(String name, String description, String inputSchema) {
        /**
         * Rejects null components.
         */
        public ToolDescription {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(inputSchema, "inputSchema");
        }
    }

    private final Store store;
    private final AgentPolicy policy;
    private final ApprovalQueue queue;
    private final String principal;
    private final Optional<String> onBehalfOf;
    private final Clearance clearance;
    private final Operations operations;
    private final Reads reads;
    private final Map<String, UnaryOperator<String>> handlers;

    /**
     * Creates the tools of a store with the clearance that covers no classification: they read no value of a classified
     * attribute and write none.
     *
     * @param store      the store the tools serve
     * @param policy     what the tools do with registrations and commits
     * @param queue      where requests wait for a person's approval; it must be the queue of the same store
     * @param principal  the principal the tools act as, for example {@code agent:onboarding}; recorded on every
     *                   transaction they submit
     * @param onBehalfOf the party the principal acts for, if any
     * @throws IllegalArgumentException if the principal is blank, or the queue belongs to another store
     */
    public AgentTools(Store store, AgentPolicy policy, ApprovalQueue queue, String principal,
            Optional<String> onBehalfOf) {
        this(store, policy, queue, principal, onBehalfOf, Clearance.none());
    }

    /**
     * Creates the tools of a store.
     *
     * @param store      the store the tools serve
     * @param policy     what the tools do with registrations and commits
     * @param queue      where requests wait for a person's approval; it must be the queue of the same store
     * @param principal  the principal the tools act as, for example {@code agent:onboarding}; recorded on every
     *                   transaction they submit
     * @param onBehalfOf the party the principal acts for, if any
     * @param clearance  the classifications the tools may read and write: they read through views restricted to it, and
     *                   every commit and dry run carries it
     * @throws IllegalArgumentException if the principal is blank, or the queue belongs to another store
     */
    public AgentTools(Store store, AgentPolicy policy, ApprovalQueue queue, String principal,
            Optional<String> onBehalfOf, Clearance clearance) {
        this.store = Objects.requireNonNull(store, "store");
        this.clearance = Objects.requireNonNull(clearance, "clearance");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.onBehalfOf = Objects.requireNonNull(onBehalfOf, "onBehalfOf");
        if (principal.isBlank()) {
            throw new IllegalArgumentException("The principal must not be blank.");
        }
        if (!queue.isFor(store)) {
            throw new IllegalArgumentException("The approval queue belongs to another store.");
        }
        this.operations = queue.operations();
        this.reads = new Reads(store, clearance);
        this.handlers = Map.ofEntries(
                Map.entry(ToolCatalog.DESCRIBE, this::describe),
                Map.entry(ToolCatalog.JSON_SCHEMA, this::jsonSchema),
                Map.entry(ToolCatalog.EXPLAIN_PROBLEM, this::explainProblem),
                Map.entry(ToolCatalog.PLAN_SCHEMA, this::planSchema),
                Map.entry(ToolCatalog.REGISTER_SCHEMA, this::registerSchema),
                Map.entry(ToolCatalog.DRY_RUN, this::dryRun),
                Map.entry(ToolCatalog.COMMIT, this::commit),
                Map.entry(ToolCatalog.READ_ENTITY, this::readEntity),
                Map.entry(ToolCatalog.READ_RELATIONSHIP, this::readRelationship),
                Map.entry(ToolCatalog.LIST_ENTITIES, this::listEntities),
                Map.entry(ToolCatalog.TIMELINE, this::timeline),
                Map.entry(ToolCatalog.NAVIGATE, this::navigate),
                Map.entry(ToolCatalog.TRAVERSE, this::traverse),
                Map.entry(ToolCatalog.QUERY, this::query),
                Map.entry(ToolCatalog.LIST_CHANGES, this::listChanges),
                Map.entry(ToolCatalog.APPROVAL_STATUS, this::approvalStatus));
    }

    /**
     * Lists the tools with their descriptions and the JSON Schemas of their arguments.
     *
     * @return the tools: {@code cairn_describe}, {@code cairn_json_schema}, {@code cairn_explain_problem},
     *         {@code cairn_plan_schema}, {@code cairn_register_schema}, {@code cairn_dry_run}, {@code cairn_commit},
     *         {@code cairn_read_entity}, {@code cairn_read_relationship}, {@code cairn_list_entities},
     *         {@code cairn_timeline}, {@code cairn_navigate}, {@code cairn_traverse}, {@code cairn_query},
     *         {@code cairn_list_changes} and {@code cairn_approval_status}
     */
    public static List<ToolDescription> toolDescriptions() {
        return ToolCatalog.all();
    }

    /**
     * Calls a tool by its name.
     *
     * @param tool      the name of the tool, such as {@code cairn_describe}
     * @param arguments the arguments as a JSON object; null or blank is the empty object
     * @return the result as JSON text
     * @throws IllegalArgumentException if there is no tool of that name
     */
    public String call(String tool, String arguments) {
        UnaryOperator<String> handler = handlers.get(Objects.requireNonNull(tool, "tool"));
        if (handler == null) {
            throw new IllegalArgumentException("There is no tool " + tool + ".");
        }
        return handler.apply(arguments);
    }

    // ------------------------------------------------------------------ describing

    /**
     * {@code cairn_describe}: the latest basis, the stored definition of every origin as a YAML schema document, the
     * available rules with their descriptions, types and codes and whether the store has their code, every problem
     * code with its explanation, the approval policy, the principal the tools act as, and the clearance they read and
     * write with (in the documents of the origins, each attribute states its classification). Takes no arguments.
     *
     * @param arguments the arguments, an empty JSON object
     * @return the description, as JSON text
     */
    public String describe(String arguments) {
        Arguments in = Arguments.parse(arguments);
        if (!in.valid()) {
            return Json.render(Operations.problemsOnly(in.problems()));
        }
        ObjectNode out = Json.object();
        out.put("basis", store.latestBasis().value());
        out.put("principal", principal);
        onBehalfOf.ifPresent(party -> out.put("onBehalfOf", party));
        ArrayNode cleared = out.putArray("clearance");
        clearance.classifications().forEach(cleared::add);
        ObjectNode policyNode = out.putObject("policy");
        policyNode.put("schemaRegistrations", name(policy.schemaRegistrations()));
        policyNode.put("dataCommits", name(policy.dataCommits()));

        ArrayNode origins = out.putArray("origins");
        for (Origin origin : store.schema().current().origins()) {
            Optional<StoredDefinition> stored = store.schema().storedDefinition(origin.name());
            stored.ifPresent(definition -> origins.addObject()
                    .put("name", definition.origin().name())
                    .put("version", definition.origin().version())
                    .put("document", SchemaDocuments.writeYaml(definition.origin(), definition.definition())));
        }

        Set<String> declared = new HashSet<>();
        store.declaredRules().forEach(rule -> declared.add(rule.name() + "@" + rule.version()));
        ArrayNode rules = out.putArray("rules");
        for (RuleDescriptor rule : store.schema().availableRules()) {
            ObjectNode node = rules.addObject()
                    .put("name", rule.name())
                    .put("version", rule.version())
                    .put("description", rule.description())
                    .put("appliesTo", rule.appliesTo());
            ArrayNode codes = node.putArray("codes");
            for (RuleCode code : rule.codes()) {
                codes.addObject().put("code", rule.name() + "/" + code.code()).put("explanation", code.explanation());
            }
            node.put("hasCode", !declared.contains(rule.name() + "@" + rule.version()));
        }

        ArrayNode problemCodes = out.putArray("problemCodes");
        for (ProblemCode code : ProblemCode.values()) {
            problemCodes.addObject().put("code", code.name()).put("explanation", code.explanation());
        }
        return Json.render(out);
    }

    /**
     * {@code cairn_json_schema}: the published JSON Schema of schema documents ({@code kind} {@code document}), the
     * generated JSON Schema of transaction payloads ({@code payload}), the generated JSON Schema of the values of
     * the entity type or relationship type named in {@code type} ({@code values}), or the published JSON Schema of
     * queries ({@code query}). The result is the JSON Schema itself.
     *
     * @param arguments the arguments: {@code {kind, type?}}
     * @return the JSON Schema as JSON text, or the problems
     */
    public String jsonSchema(String arguments) {
        Arguments in = Arguments.parse(arguments, "kind", "type");
        String kind = in.string("kind");
        Optional<String> type = in.optionalString("type");
        if (!in.valid()) {
            return Json.render(Operations.problemsOnly(in.problems()));
        }
        Schema schema = store.schema().current();
        return switch (kind) {
            case "document" -> SchemaDocuments.jsonSchema();
            case "query" -> Queries.jsonSchema();
            case "payload" -> JsonSchemas.transactions(schema);
            case "values" -> {
                if (type.isEmpty()) {
                    in.string("type");
                    yield Json.render(Operations.problemsOnly(in.problems()));
                }
                if (schema.entityType(type.get()).isEmpty() && schema.relationshipType(type.get()).isEmpty()) {
                    yield Json.render(Operations.problemsOnly(List.of(unknownType(schema, type.get()))));
                }
                yield JsonSchemas.values(schema, type.get());
            }
            default -> Json.render(Operations.problemsOnly(List.of(new Problem(ProblemCode.INVALID_VALUE,
                    "@arguments.kind", "There is no JSON Schema of the kind '" + kind + "'.",
                    Optional.of("document, payload, values or query"), Optional.of(kind),
                    Arguments.closest(kind, "document", "payload", "values", "query")
                            .map(closest -> "Did you mean '" + closest + "'?")))));
        };
    }

    /**
     * {@code cairn_explain_problem}: the explanation of a problem code, or of a rule's code qualified by the rule's
     * name, such as {@code lei-checksum/invalid-check-digits}.
     *
     * @param arguments the arguments: {@code {code}}
     * @return {@code {code, explanation}} as JSON text, or the problems
     */
    public String explainProblem(String arguments) {
        Arguments in = Arguments.parse(arguments, "code");
        String code = in.string("code");
        if (!in.valid()) {
            return Json.render(Operations.problemsOnly(in.problems()));
        }
        String wanted = code.trim();
        for (ProblemCode candidate : ProblemCode.values()) {
            if (candidate.name().equalsIgnoreCase(wanted)) {
                return Json.render(explanation(candidate.name(), candidate.explanation()));
            }
        }
        List<RuleDescriptor> rules = new ArrayList<>(store.schema().availableRules());
        rules.sort(Comparator.comparingInt(RuleDescriptor::version).reversed());
        List<String> known = new ArrayList<>(Arrays.stream(ProblemCode.values()).map(ProblemCode::name).toList());
        for (RuleDescriptor rule : rules) {
            for (RuleCode ruleCode : rule.codes()) {
                String qualified = rule.name() + "/" + ruleCode.code();
                if (qualified.equals(wanted)) {
                    return Json.render(explanation(qualified, ruleCode.explanation()));
                }
                known.add(qualified);
            }
        }
        return Json.render(Operations.problemsOnly(List.of(new Problem(ProblemCode.INVALID_VALUE, "@arguments.code",
                "There is no problem code or rule code '" + code + "'.",
                Optional.of("a problem code such as UNKNOWN_ATTRIBUTE, or a rule code such as "
                        + "lei-checksum/invalid-check-digits"),
                Optional.of(code), Arguments.closest(wanted, known).map(closest -> "Did you mean '" + closest + "'?")
                        .or(() -> Optional.of("cairn_describe lists every code."))))));
    }

    // ------------------------------------------------------------------ schema changes

    /**
     * {@code cairn_plan_schema}: plans the registration of a schema document without registering it. The policy does
     * not matter: planning changes nothing.
     *
     * @param arguments the arguments: {@code {document}}
     * @return {@code {outcome, changes, problems}} as JSON text; for wrong arguments the outcome is {@code rejected}
     *         and there are no changes
     */
    public String planSchema(String arguments) {
        Arguments in = Arguments.parse(arguments, "document");
        String document = in.string("document");
        if (!in.valid()) {
            return Json.render(Operations.rejectedPlan(in.problems()));
        }
        return Json.render(operations.planSchema(document));
    }

    /**
     * {@code cairn_register_schema}: registers a schema document, subject to the approval policy. The schema
     * transaction records the principal of the tools, the party they act for and the reason given.
     *
     * @param arguments the arguments: {@code {document, reason?}}
     * @return {@code {outcome: applied | unchanged | rejected | pending, ...}} as JSON text
     */
    public String registerSchema(String arguments) {
        Arguments in = Arguments.parse(arguments, "document", "reason");
        String document = in.string("document");
        Optional<String> reason = in.optionalString("reason");
        if (!in.valid()) {
            return Json.render(Operations.rejected(in.problems()));
        }
        Submission submission = new Submission(principal, onBehalfOf, reason);
        return Json.render(switch (policy.schemaRegistrations()) {
            case ALLOW -> operations.registerSchema(document, submission);
            case REQUIRE_APPROVAL -> queue.requestSchemaRegistration(document, submission);
            case DENY -> operations.denySchema(document);
        });
    }

    // ------------------------------------------------------------------ writing data

    /**
     * {@code cairn_dry_run}: runs a transaction payload dry. The policy does not matter: a dry run changes nothing.
     *
     * @param arguments the arguments: {@code {payload}}
     * @return {@code {outcome: wouldCommit | rejected, problems?}} as JSON text
     */
    public String dryRun(String arguments) {
        Arguments in = Arguments.parse(arguments, "payload");
        String payload = in.string("payload");
        if (!in.valid()) {
            return Json.render(Operations.rejected(in.problems()));
        }
        return Json.render(operations.dryRun(payload, principal, onBehalfOf, clearance));
    }

    /**
     * {@code cairn_commit}: commits a transaction payload, subject to the approval policy.
     *
     * @param arguments the arguments: {@code {payload}}
     * @return {@code {outcome: committed | rejected | pending, receipt?, problems?, request?}} as JSON text
     */
    public String commit(String arguments) {
        Arguments in = Arguments.parse(arguments, "payload");
        String payload = in.string("payload");
        if (!in.valid()) {
            return Json.render(Operations.rejected(in.problems()));
        }
        return Json.render(switch (policy.dataCommits()) {
            case ALLOW -> operations.commit(payload, principal, onBehalfOf, clearance);
            case REQUIRE_APPROVAL -> queue.requestCommit(payload, principal, onBehalfOf, clearance);
            case DENY -> operations.denyCommit(payload, principal, onBehalfOf, clearance);
        });
    }

    // ------------------------------------------------------------------ reading data

    /**
     * {@code cairn_read_entity}: reads an entity at an effective time. The entity is named by its ID ({@code id}) or by a
     * unique key ({@code key}, as payloads write it), exactly one of the two; a key that no entity has at that time reads
     * as nothing.
     *
     * @param arguments the arguments: {@code {id | key, effectiveAt, knownAt?}}
     * @return {@code {effectiveAt, basis, result}} as JSON text, or the problems
     */
    public String readEntity(String arguments) {
        return Json.render(reads.entity(arguments));
    }

    /**
     * {@code cairn_read_relationship}: reads a relationship at an effective time.
     *
     * @param arguments the arguments: {@code {id, effectiveAt, knownAt?}}
     * @return {@code {effectiveAt, basis, result}} as JSON text, or the problems
     */
    public String readRelationship(String arguments) {
        return Json.render(reads.relationship(arguments));
    }

    /**
     * {@code cairn_list_entities}: lists the entities of a type at an effective time.
     *
     * @param arguments the arguments: {@code {type, effectiveAt, knownAt?}}
     * @return {@code {effectiveAt, basis, result}} as JSON text, or the problems
     */
    public String listEntities(String arguments) {
        return Json.render(reads.entities(arguments));
    }

    /**
     * {@code cairn_timeline}: reads every version of an entity or a relationship across effective time.
     *
     * @param arguments the arguments: {@code {id, knownAt?}}
     * @return {@code {basis, kind, result}} as JSON text, or the problems
     */
    public String timeline(String arguments) {
        return Json.render(reads.timeline(arguments));
    }

    /**
     * {@code cairn_navigate}: follows the relationships of an entity by a name. The entity is named by {@code id} or by
     * {@code key}, as for {@link #readEntity}; a key that no entity has at that time leads nowhere.
     *
     * @param arguments the arguments: {@code {id | key, name, effectiveAt, knownAt?}}
     * @return {@code {effectiveAt, basis, result}} as JSON text, or the problems
     */
    public String navigate(String arguments) {
        return Json.render(reads.navigate(arguments));
    }

    /**
     * {@code cairn_traverse}: follows every relationship of a name up to a maximum depth, or to the end if the call gives
     * none, without a condition. The entity to start from is named by {@code id} or by {@code key}, as for
     * {@link #readEntity}; a key that no entity has at that time leads nowhere.
     *
     * @param arguments the arguments: {@code {id | key, name, maxDepth?, effectiveAt, knownAt?}}
     * @return {@code {effectiveAt, basis, result}} as JSON text, or the problems
     */
    public String traverse(String arguments) {
        return Json.render(reads.traverse(arguments));
    }

    /**
     * {@code cairn_query}: runs a query, written as JSON or YAML, through a view.
     *
     * @param arguments the arguments: {@code {query, effectiveAt, knownAt?}}
     * @return {@code {effectiveAt, basis, result: {columns, rows}}} as JSON text, or the problems, located in the text of
     *         the query where they concern it
     */
    public String query(String arguments) {
        return Json.render(reads.query(arguments));
    }

    /**
     * {@code cairn_list_changes}: the changes of the store after a basis (ADR-0029). It reads and does not write, so the
     * approval policy does not apply to it.
     *
     * @param arguments the arguments: {@code {after?, limit?}}, the basis to read after (0 by default) and the most changes
     *                  to return (1 to 1,000, 100 by default)
     * @return {@code {changes, next, latestBasis}} as JSON text, with the changes in the form of the {@code json-payloads}
     *         capability and {@code next} the basis to give as {@code after} to continue; or the problems
     */
    public String listChanges(String arguments) {
        return Json.render(reads.changes(arguments));
    }

    // ------------------------------------------------------------------ approval

    /**
     * {@code cairn_approval_status}: where an approval request stands and, once decided, its outcome.
     *
     * @param arguments the arguments: {@code {requestId}}
     * @return the request as JSON text, or the problems
     */
    public String approvalStatus(String arguments) {
        Arguments in = Arguments.parse(arguments, "requestId");
        String id = in.string("requestId");
        if (!in.valid()) {
            return Json.render(Operations.problemsOnly(in.problems()));
        }
        return Json.render(queue.find(id)
                .<ObjectNode>map(ApprovalQueue::toJson)
                .orElseGet(() -> Operations.problemsOnly(List.of(new Problem(ProblemCode.INVALID_VALUE,
                        "@arguments.requestId", "There is no approval request with the ID " + id + ".",
                        Optional.of("the ID of a request from cairn_register_schema or cairn_commit"),
                        Optional.of(id), Optional.empty())))));
    }

    // ------------------------------------------------------------------ helpers

    private static String name(Decision decision) {
        return switch (decision) {
            case ALLOW -> "allow";
            case REQUIRE_APPROVAL -> "requireApproval";
            case DENY -> "deny";
        };
    }

    private static ObjectNode explanation(String code, String explanation) {
        return Json.object().put("code", code).put("explanation", explanation);
    }

    private static Problem unknownType(Schema schema, String type) {
        List<String> names = new ArrayList<>();
        schema.entityTypes().forEach(entityType -> names.add(entityType.name()));
        schema.relationshipTypes().forEach(relationshipType -> names.add(relationshipType.name()));
        return new Problem(ProblemCode.UNKNOWN_ENTITY_TYPE, "@arguments.type",
                "The schema has no entity type or relationship type '" + type + "'.",
                Optional.of(String.join(", ", names)), Optional.of(type),
                Arguments.closest(type, names).map(closest -> "Did you mean '" + closest + "'?"));
    }
}
