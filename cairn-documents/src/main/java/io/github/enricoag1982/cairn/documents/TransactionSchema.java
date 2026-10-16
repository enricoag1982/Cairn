package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.documents.WrittenNode.Mapping;
import io.github.enricoag1982.cairn.documents.WrittenNode.Plain;
import io.github.enricoag1982.cairn.documents.WrittenNode.Sequence;
import io.github.enricoag1982.cairn.documents.WrittenNode.Text;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.Schema;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the {@linkplain WrittenNode tree} of the JSON Schema (draft 2020-12) for transaction payloads (capability
 * {@code json-payloads}, design D5).
 *
 * <p>The structure has the keys and the required keys of {@link PayloadKeys}, which the reader accepts; a test compares
 * the two. The schema defines the values of each concrete entity type and each relationship type once, as
 * {@code $defs} named {@code entityValues.<type>} and {@code relationshipValues.<type>}. A {@code create} or
 * {@code relate} is a {@code oneOf} with a branch for each type, which matches the names and aliases of the type, as
 * the schema writes them, in {@code entityType} or {@code relationshipType}, checks {@code values} against the
 * values of the type, and requires {@code values} if the type has required attributes. The engine also accepts other
 * capitalizations of the names, which the schema does not. Instants, in {@code from} and {@code until}, are checked by
 * pattern as well as by format, as are the dates and instants among the values.
 *
 * <p>The type of a {@code patch} or {@code replace} is known only to the store, so their values are checked by name
 * only: against the union of the names and aliases of the attributes of all types.
 *
 * <p>A write cannot set a derived attribute (capability {@code derived-attributes}), so derived attributes are left out of
 * the values of a {@code create} or {@code relate}, which then do not require them, and out of the names that a
 * {@code patch} or {@code replace} may set, unless another type has an attribute of that name that is not derived.
 */
final class TransactionSchema {

    private static final Plain FALSE = new Plain("false");
    private static final Plain ONE = new Plain("1");
    private static final String DEFINITIONS = "#/$defs/";

    private TransactionSchema() {
    }

    static Mapping of(Schema schema) {
        List<EntityType> entityTypes = schema.entityTypes().stream().filter(type -> !type.isAbstract()).toList();
        List<RelationshipType> relationshipTypes = schema.relationshipTypes();

        Mapping properties = new Mapping()
                .put(Keys.SCHEMA, string("The location of the JSON Schema that describes this payload, for editors that "
                        + "look for it. Cairn does not read it."))
                .put("cairn", new Mapping()
                        .put("description", "The version of the payload format. It must be the integer 1.")
                        .put("type", "integer")
                        .put("const", ONE))
                .put("principal", string("Who submits the transaction, for example agent:onboarding or a user. It is "
                        + "recorded with the transaction, and must not be blank."))
                .put("onBehalfOf", string("The party the principal acts for, if it acts for one."))
                .put("reason", string("Why the transaction is submitted."))
                .put("idempotencyKey", string("A key that identifies this transaction. Submitting the same transaction "
                        + "again with the same key is a retry, and gets the receipt of the first submission, unless a "
                        + "purge removed what it wrote."))
                .put("writes", new Mapping()
                        .put("description", "The writes of the transaction, in order. They are committed together, or "
                                + "not at all.")
                        .put("type", "array")
                        .put("items", ref("write")));

        Mapping definitions = new Mapping()
                .put("write", write())
                .put("create", create(entityTypes))
                .put("relate", relate(relationshipTypes))
                .put("patch", patch())
                .put("replace", replace())
                .put("endDate", endDate())
                .put("conflictCheck", conflictCheck())
                .put("basis", new Mapping()
                        .put("description", "A basis: the number of a committed transaction, a whole number from 0.")
                        .put("type", "integer")
                        .put("minimum", new Plain("0"))
                        .put("maximum", new Plain(Long.toString(Long.MAX_VALUE))))
                .put("instant", new Mapping()
                        .put("description", "An instant in ISO 8601 form, such as 2026-01-01T00:00:00Z. An offset "
                                + "other than Z, such as +01:00, is accepted and converted to UTC. Microseconds are "
                                + "kept and finer fractions are cut off.")
                        .put("type", "string")
                        .put("format", "date-time")
                        .put("pattern", ValuesSchema.INSTANT_PATTERN)
                        .put("examples", strings("2026-01-01T00:00:00Z", "2026-01-01T01:00:00+01:00")))
                .put("reference", new Mapping()
                        .put("description", "A reference to an entity or a relationship: $ and the temporary ID of a "
                                + "write earlier in this transaction, such as $acme, or an ID. An existing entity can "
                                + "also be named by a unique key: its entity type and one unique attribute with its "
                                + "value, such as { entityType: Account, key: { number: A-1 } }, which the store "
                                + "resolves when it commits.")
                        .put("oneOf", new Sequence(List.of(new Mapping().put("type", "string").put("minLength", ONE),
                                ref("uniqueKey")))))
                .put("uniqueKey", new Mapping()
                        .put("description", "A unique key: the name of an entity type, or an alias, and a key with "
                                + "exactly one entry, the name of an attribute of that type that is unique, or an "
                                + "alias, and its value. The store resolves it when it commits, at the start of the "
                                + "write's period, to the entity of the type, or of a subtype, that has the value then; "
                                + "it checks the type, the attribute and the value.")
                        .put("type", "object")
                        .put("additionalProperties", FALSE)
                        .put("required", strings(PayloadKeys.UNIQUE_KEY_REQUIRED.toArray(String[]::new)))
                        .put("properties", new Mapping()
                                .put("entityType", new Mapping()
                                        .put("description", "The name of the entity type of the entity, or an alias of "
                                                + "it. The entity can be of a subtype.")
                                        .put("type", "string")
                                        .put("minLength", ONE))
                                .put("key", new Mapping()
                                        .put("description", "The attribute and its value, as a mapping with exactly one "
                                                + "entry: the name of a unique attribute of the entity type, or an "
                                                + "alias, and the value, which has the attribute's value type.")
                                        .put("type", "object")
                                        .put("minProperties", ONE)
                                        .put("maxProperties", ONE)
                                        .put("additionalProperties", ref("scalar")))))
                .put("scalar", new Mapping()
                        .put("description", "A value: a string, a number or true or false. A date or an instant is a "
                                + "string in ISO 8601 form, and a decimal is a number.")
                        .put("type", strings("string", "number", "boolean")));

        Set<String> names = attributeNames(schema);
        definitions.put("attributeName", new Mapping()
                .put("description", "The name of an attribute, or an alias of one, of any entity type or relationship "
                        + "type of the schema.")
                .put("enum", strings(names.toArray(String[]::new))));
        Mapping known = new Mapping();
        names.forEach(name -> known.put(name, ref("scalar")));
        definitions.put("attributeValues", new Mapping()
                .put("description", "Values by attribute name. The type of the entity or relationship is known only to "
                        + "the store, so only the names are checked here, against the attributes of all types; the "
                        + "store checks the values against the type when the transaction is committed.")
                .put("type", "object")
                .put("additionalProperties", FALSE)
                .put("properties", known));

        for (EntityType type : entityTypes) {
            definitions.put(entityValues(type.name()), ValuesSchema.definition("entity type", type.name(),
                    type.description(), type.attributes()));
        }
        for (RelationshipType type : relationshipTypes) {
            definitions.put(relationshipValues(type.name()), ValuesSchema.definition("relationship type", type.name(),
                    type.description(), type.attributes()));
        }

        return new Mapping()
                .put("$schema", ValuesSchema.DIALECT)
                .put("title", "Cairn transaction payload, format version 1")
                .put("description", "A transaction payload: the writes of one transaction, written as YAML 1.2 or as "
                        + "JSON. The values of create and relate writes are checked against the entity type or "
                        + "relationship type that the write names. The names of types are spelled as the schema spells "
                        + "them; the engine also accepts other capitalizations, which this schema does not.")
                .put("type", "object")
                .put("additionalProperties", FALSE)
                .put("required", strings(PayloadKeys.TRANSACTION_REQUIRED.toArray(String[]::new)))
                .put("properties", properties)
                .put("$defs", definitions);
    }

    // ------------------------------------------------------------------ writes

    private static Mapping write() {
        Mapping kinds = new Mapping();
        for (String kind : PayloadKeys.KINDS) {
            String description = switch (kind) {
                case "create" -> "Creates an entity.";
                case "relate" -> "Relates two entities.";
                case "patch" -> "Changes some values of an entity or relationship.";
                case "replace" -> "Replaces all values of an entity or relationship.";
                case "endDate" -> "Ends an entity or relationship.";
                default -> throw new IllegalStateException("The schema of transaction payloads does not know the write kind "
                        + kind);
            };
            kinds.put(kind, new Mapping().put("description", description).put("$ref", DEFINITIONS + kind));
        }
        return new Mapping()
                .put("description", "One write: a mapping with exactly one key, which names its kind.")
                .put("type", "object")
                .put("additionalProperties", FALSE)
                .put("minProperties", ONE)
                .put("maxProperties", ONE)
                .put("properties", kinds);
    }

    private static Mapping create(List<EntityType> types) {
        List<String> names = new ArrayList<>();
        List<WrittenNode> branches = new ArrayList<>();
        for (EntityType type : types) {
            List<String> own = names(type.name(), type.aliases());
            names.addAll(own);
            branches.add(branch(type.name(), "entityType", own, entityValues(type.name()), type.attributes()));
        }
        Mapping entityType = new Mapping()
                .put("description", "The name of a concrete entity type, or one of its aliases.")
                .put("enum", strings(names.toArray(String[]::new)));
        Mapping mapping = new Mapping()
                .put("description", "Creates an entity of a concrete entity type, from an instant.")
                .put("type", "object")
                .put("additionalProperties", FALSE)
                .put("required", strings(PayloadKeys.CREATE_REQUIRED.toArray(String[]::new)))
                .put("properties", new Mapping()
                        .put("temporaryId", temporaryId("entity"))
                        .put("id", string("An ID for the new entity, if the caller supplies one. The store generates "
                                + "one otherwise, and rejects a supplied ID that its generator does not accept."))
                        .put("entityType", entityType)
                        .put("from", instant("The first instant at which the entity exists."))
                        .put("until", instant("The first instant after the entity; leave it out for an entity without "
                                + "an end."))
                        .put("values", values("The values of the entity, by attribute name. They are checked against the "
                                + "values of the entity type.")));
        if (!branches.isEmpty()) {
            mapping.put("oneOf", new Sequence(branches));
        }
        return mapping;
    }

    private static Mapping relate(List<RelationshipType> types) {
        List<String> names = new ArrayList<>();
        List<WrittenNode> branches = new ArrayList<>();
        for (RelationshipType type : types) {
            List<String> own = names(type.name(), type.aliases());
            names.addAll(own);
            branches.add(branch(type.name(), "relationshipType", own, relationshipValues(type.name()), type.attributes()));
        }
        Mapping relationshipType = new Mapping()
                .put("description", "The name of a relationship type, or one of its aliases.")
                .put("enum", strings(names.toArray(String[]::new)));
        Mapping mapping = new Mapping()
                .put("description", "Relates a source entity to a target entity with a relationship type, from an "
                        + "instant.")
                .put("type", "object")
                .put("additionalProperties", FALSE)
                .put("required", strings(PayloadKeys.RELATE_REQUIRED.toArray(String[]::new)))
                .put("properties", new Mapping()
                        .put("temporaryId", temporaryId("relationship"))
                        .put("id", string("An ID for the new relationship, if the caller supplies one. The store "
                                + "generates one otherwise."))
                        .put("relationshipType", relationshipType)
                        .put("source", reference("The source: $ and the temporary ID of an entity that an earlier write "
                                + "creates, the ID of an entity, or a unique key that names it."))
                        .put("target", reference("The target: $ and the temporary ID of an entity that an earlier write "
                                + "creates, the ID of an entity, or a unique key that names it."))
                        .put("from", instant("The first instant at which the relationship exists."))
                        .put("until", instant("The first instant after the relationship; leave it out for a relationship "
                                + "without an end."))
                        .put("values", values("The values of the relationship, by attribute name. They are checked "
                                + "against the values of the relationship type.")));
        if (!branches.isEmpty()) {
            mapping.put("oneOf", new Sequence(branches));
        }
        return mapping;
    }

    /**
     * The branch for a type: when the type is named, the values are the values of the type, and they are there if the
     * type has required attributes, because a write without {@code values} gives none of them.
     */
    private static Mapping branch(
            String title, String key, List<String> names, String valuesDefinition, List<Attribute> attributes) {
        boolean valuesRequired = attributes.stream().anyMatch(attribute -> attribute.required() && attribute.derived().isEmpty());
        return new Mapping()
                .put("title", title)
                .put("required", valuesRequired ? strings(key, "values") : strings(key))
                .put("properties", new Mapping()
                        .put(key, new Mapping().put("enum", strings(names.toArray(String[]::new))))
                        .put("values", ref(valuesDefinition)));
    }

    private static Mapping patch() {
        return new Mapping()
                .put("description", "Changes the values of an entity or relationship from an instant. The attributes in "
                        + "set get the new values; the attributes in clear lose theirs; the others stay.")
                .put("type", "object")
                .put("additionalProperties", FALSE)
                .put("required", strings(PayloadKeys.PATCH_REQUIRED.toArray(String[]::new)))
                .put("properties", new Mapping()
                        .put("target", reference("The entity or relationship to change: $ and the temporary ID of a "
                                + "write earlier in this transaction, an ID, or a unique key."))
                        .put("from", instant("The first instant at which the change applies."))
                        .put("until", instant("The first instant after the change; leave it out for a change that lasts "
                                + "until further notice."))
                        .put("set", new Mapping()
                                .put("description", "The attributes to set, by name, with their new values.")
                                .put("$ref", DEFINITIONS + "attributeValues"))
                        .put("clear", new Mapping()
                                .put("description", "The names of the attributes to clear.")
                                .put("type", "array")
                                .put("items", ref("attributeName")))
                        .put("conflictCheck", conflictCheckReference()));
    }

    private static Mapping replace() {
        return new Mapping()
                .put("description", "Replaces all values of an entity or relationship from an instant: the attributes "
                        + "that are not in values lose theirs. To change some values and keep the others, use patch.")
                .put("type", "object")
                .put("additionalProperties", FALSE)
                .put("required", strings(PayloadKeys.REPLACE_REQUIRED.toArray(String[]::new)))
                .put("properties", new Mapping()
                        .put("target", reference("The entity or relationship to replace: $ and the temporary ID of a "
                                + "write earlier in this transaction, an ID, or a unique key."))
                        .put("from", instant("The first instant at which the new values apply."))
                        .put("until", instant("The first instant after the new values; leave it out for values that "
                                + "last until further notice."))
                        .put("values", new Mapping()
                                .put("description", "All the values the entity or relationship has, by attribute name.")
                                .put("$ref", DEFINITIONS + "attributeValues"))
                        .put("conflictCheck", conflictCheckReference()));
    }

    private static Mapping endDate() {
        return new Mapping()
                .put("description", "Ends an entity or relationship at an instant.")
                .put("type", "object")
                .put("additionalProperties", FALSE)
                .put("required", strings(PayloadKeys.END_DATE_REQUIRED.toArray(String[]::new)))
                .put("properties", new Mapping()
                        .put("target", reference("The entity or relationship to end: $ and the temporary ID of a write "
                                + "earlier in this transaction, an ID, or a unique key."))
                        .put("from", instant("The first instant at which the entity or relationship no longer exists."))
                        .put("conflictCheck", conflictCheckReference()));
    }

    private static Mapping conflictCheck() {
        return new Mapping()
                .put("description", "Rejects the write if the entity or relationship changed after the caller read it: "
                        + "perEntity checks all of it, perAttribute only the attributes that the write sets or clears. "
                        + "The value is the basis at which the caller read it. Leave the key out for no check.")
                .put("type", "object")
                .put("additionalProperties", FALSE)
                .put("minProperties", ONE)
                .put("maxProperties", ONE)
                .put("properties", new Mapping()
                        .put("perEntity", basis("The basis at which the whole entity or relationship was read."))
                        .put("perAttribute", basis("The basis at which the attributes of the write were read.")));
    }

    // ------------------------------------------------------------------ parts

    private static Mapping temporaryId(String what) {
        return new Mapping()
                .put("description", "A name for the new " + what + ", so that later writes of this transaction can refer "
                        + "to it as $name, before it has an ID. It may be written with or without the $.")
                .put("type", "string")
                .put("minLength", ONE);
    }

    private static Mapping string(String description) {
        return new Mapping().put("description", description).put("type", "string");
    }

    private static Mapping conflictCheckReference() {
        return new Mapping()
                .put("description", "Rejects the write if the entity or relationship changed after the caller read it.")
                .put("$ref", DEFINITIONS + "conflictCheck");
    }

    private static Mapping reference(String description) {
        return new Mapping().put("description", description).put("$ref", DEFINITIONS + "reference");
    }

    private static Mapping instant(String description) {
        return new Mapping().put("description", description).put("$ref", DEFINITIONS + "instant");
    }

    private static Mapping basis(String description) {
        return new Mapping().put("description", description).put("$ref", DEFINITIONS + "basis");
    }

    private static Mapping values(String description) {
        return new Mapping().put("description", description).put("type", "object");
    }

    private static Mapping ref(String definition) {
        return new Mapping().put("$ref", DEFINITIONS + definition);
    }

    private static Sequence strings(String... values) {
        List<WrittenNode> items = new ArrayList<>();
        for (String value : values) {
            items.add(new Text(value));
        }
        return new Sequence(items);
    }

    private static List<String> names(String name, List<String> aliases) {
        List<String> names = new ArrayList<>();
        names.add(name);
        names.addAll(aliases);
        return names;
    }

    private static String entityValues(String typeName) {
        return "entityValues." + typeName;
    }

    private static String relationshipValues(String typeName) {
        return "relationshipValues." + typeName;
    }

    /** The names and aliases of the attributes of all entity types and relationship types, without repeats, sorted. */
    private static Set<String> attributeNames(Schema schema) {
        Set<String> names = new TreeSet<>(Comparator.comparing((String name) -> name.toLowerCase(Locale.ROOT))
                .thenComparing(Comparator.naturalOrder()));
        for (EntityType type : schema.entityTypes()) {
            addNames(names, type.attributes());
        }
        for (RelationshipType type : schema.relationshipTypes()) {
            addNames(names, type.attributes());
        }
        return names;
    }

    /** Adds the names of the attributes that a write may set: derived attributes are computed, so they are left out. */
    private static void addNames(Set<String> names, List<Attribute> attributes) {
        for (Attribute attribute : attributes) {
            if (attribute.derived().isEmpty()) {
                names.add(attribute.name());
                names.addAll(attribute.aliases());
            }
        }
    }
}
