package io.github.enricoag1982.cairn.documents;

import java.util.List;

/**
 * The keys of schema documents of format version 1, per position, in the order in which the writer writes them: a type
 * has its {@code description} first, then its other properties, and an attribute has its {@code type} first. The
 * reader accepts exactly these keys; the published JSON Schema declares the same ones (design D5, D8).
 */
final class Keys {

    /** The path of problems about the document as a whole, and of those that no element of it is concerned in. */
    static final String DOCUMENT_PATH = "@document";

    /** The path of problems about the origin. */
    static final String ORIGIN_PATH = "@origin";

    /** The key that names the published JSON Schema for editors: the reader checks it is a string and ignores it. */
    static final String SCHEMA = "$schema";

    /** The keys of the document; {@value #SCHEMA} comes last because the writer never writes it. */
    static final List<String> DOCUMENT = List.of("cairn", "origin", "traits", "entityTypes", "relationshipTypes", SCHEMA);
    /** The keys the document must have. */
    static final List<String> DOCUMENT_REQUIRED = List.of("cairn", "origin");

    /** The keys of the origin. */
    static final List<String> ORIGIN = List.of("name", "version");
    /** The keys the origin must have. */
    static final List<String> ORIGIN_REQUIRED = List.of("name", "version");

    /** The keys of a trait. */
    static final List<String> TRAIT = List.of("description", "attributes", "aliases", "deprecated");

    /** The keys of an entity type. */
    static final List<String> ENTITY_TYPE = List.of(
            "description", "abstract", "supertype", "traits", "attributes", "aliases", "deprecated", "rules", "checks");

    /** The keys of a relationship type. */
    static final List<String> RELATIONSHIP_TYPE = List.of(
            "description", "source", "target", "targetsPerSource", "sourcesPerTarget", "inverseName", "inverseAliases",
            "kind", "symmetric", "supertype", "attributes", "aliases", "deprecated", "rules", "checks");
    /** The keys a relationship type must have. */
    static final List<String> RELATIONSHIP_TYPE_REQUIRED = List.of(
            "source", "target", "targetsPerSource", "sourcesPerTarget", "inverseName");

    /** The keys of an attribute. */
    static final List<String> ATTRIBUTE = List.of(
            "type", "required", "unique", "range", "aliases", "deprecated", "description", "examples", "allowedValues",
            "pattern", "minLength", "maxLength");
    /** The keys an attribute must have. */
    static final List<String> ATTRIBUTE_REQUIRED = List.of("type");

    /** The keys of a range. */
    static final List<String> RANGE = List.of("atLeast", "greaterThan", "atMost", "lessThan");

    /** The keys of a rule reference. */
    static final List<String> RULE = List.of("name", "version");
    /** The keys a rule reference must have. */
    static final List<String> RULE_REQUIRED = List.of("name", "version");

    /** The keys of a check. */
    static final List<String> CHECK = List.of("expression", "message");
    /** The keys a check must have. */
    static final List<String> CHECK_REQUIRED = List.of("expression");

    /** The names of the value types, as written in the key {@code type}. */
    static final List<String> VALUE_TYPES = List.of("string", "boolean", "int", "long", "decimal", "date", "instant");

    /** The names of the relationship kinds, as written in the key {@code kind}. */
    static final List<String> KINDS = List.of("association", "composition");

    /** The multiplicities, as written in the keys {@code targetsPerSource} and {@code sourcesPerTarget}. */
    static final List<String> MULTIPLICITIES = List.of("0..1", "1..1", "0..*", "1..*");

    private Keys() {
    }
}
