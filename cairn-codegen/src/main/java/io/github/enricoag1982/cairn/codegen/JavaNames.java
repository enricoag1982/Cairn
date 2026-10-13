package io.github.enricoag1982.cairn.codegen;

import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.Schema;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Maps the names in the documents to Java names (design D4 of {@code add-java-type-generation}). A name is used as it
 * is, except that it gets the suffix {@code _} where it would not compile or would clash:
 *
 * <ul>
 * <li>a Java keyword, a literal ({@code true}, {@code false}, {@code null}) or a restricted identifier ({@code var},
 * {@code yield}, {@code record}, {@code sealed}, {@code permits});
 * <li>an attribute with the name of a member that generated code adds to the type, such as {@code id}, {@code from}
 * or, on builders, {@code check}, or of a method of {@code Object}, or of a library type that generated code uses
 * before a dot;
 * <li>a rule's base class with the name of a type.
 * </ul>
 *
 * <p>A suffixed name is lengthened with further underscores until it is no other type's or attribute's name.
 */
final class JavaNames {

    private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
            "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile",
            "while", "true", "false", "null");

    /** Words that are keywords only in some places, and cannot be the name of a type or a variable there. */
    private static final Set<String> RESTRICTED = Set.of("permits", "record", "sealed", "var", "yield");

    /** The methods of {@code Object}: an accessor with such a name would clash with them. */
    private static final Set<String> OBJECT_METHODS = Set.of("clone", "equals", "finalize", "getClass", "hashCode",
            "notify", "notifyAll", "toString", "wait");

    /** The methods of every builder, other than setters: a setter with such a name would be confused with them. */
    private static final Set<String> BUILDER_METHODS = Set.of("as", "check", "effective", "effectiveFrom");

    private static final Set<String> ENTITY_MEMBERS = Set.of("entityState", "id", "from", "create", "patch", "replace");

    private static final Set<String> RELATIONSHIP_MEMBERS = Set.of("relationshipState", "id", "source", "target", "from",
            "relate", "patch", "replace");

    /** What kind of type an attribute belongs to, which decides the members that generated code adds. */
    enum Kind {
        ENTITY, RELATIONSHIP
    }

    private final Map<String, String> types = new HashMap<>();
    private final Map<RuleReference, String> rules = new HashMap<>();
    private final Set<String> attributeNames = new HashSet<>();

    /**
     * Chooses the Java names of the types and rules of a schema.
     *
     * @param schema the schema of the documents
     * @param rules  the rules that the documents name, in a stable order
     */
    JavaNames(Schema schema, List<RuleReference> rules) {
        Map<String, String> names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        schema.traits().forEach(trait -> {
            names.put(trait.name(), trait.name());
            trait.attributes().forEach(attribute -> attributeNames.add(attribute.name()));
        });
        schema.entityTypes().forEach(type -> {
            names.put(type.name(), type.name());
            type.attributes().forEach(attribute -> attributeNames.add(attribute.name()));
        });
        schema.relationshipTypes().forEach(type -> {
            names.put(type.name(), type.name());
            type.attributes().forEach(attribute -> attributeNames.add(attribute.name()));
        });
        Set<String> taken = new HashSet<>();
        names.keySet().stream().filter(name -> !isReserved(name)).forEach(name -> {
            types.put(name, name);
            taken.add(key(name));
        });
        names.keySet().stream().filter(JavaNames::isReserved).forEach(name -> types.put(name, free(name, taken)));
        for (RuleReference rule : rules) {
            this.rules.put(rule, free(pascal(rule.name()) + "RuleV" + rule.version(), taken));
        }
    }

    /** Tells whether a word is a keyword or a literal, so that it cannot be a name or a part of a package name. */
    static boolean isKeyword(String word) {
        return KEYWORDS.contains(word);
    }

    private static boolean isReserved(String word) {
        return KEYWORDS.contains(word) || RESTRICTED.contains(word);
    }

    /** The Java name of a trait, entity type or relationship type, from its name in the documents. */
    String type(String name) {
        return types.get(name);
    }

    /** The Java name of the base class of a rule. */
    String rule(RuleReference rule) {
        return rules.get(rule);
    }

    /** The name of the accessor and the setters of an attribute. */
    String attribute(Kind kind, Attribute attribute) {
        String name = attribute.name();
        if (!isReserved(kind, name)) {
            return name;
        }
        String candidate = name + "_";
        while (isReserved(kind, candidate) || attributeNames.contains(candidate)) {
            candidate += "_";
        }
        return candidate;
    }

    private static boolean isReserved(Kind kind, String name) {
        return isReserved(name) || OBJECT_METHODS.contains(name) || BUILDER_METHODS.contains(name)
                || Library.QUALIFIERS.contains(name)
                || (kind == Kind.ENTITY ? ENTITY_MEMBERS : RELATIONSHIP_MEMBERS).contains(name);
    }

    /** A name for a type that is free: the name with underscores until no other type of the package has it. */
    private static String free(String name, Set<String> taken) {
        String candidate = name;
        while (isReserved(candidate) || taken.contains(key(candidate))) {
            candidate += "_";
        }
        taken.add(key(candidate));
        return candidate;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** {@code lei-checksum} becomes {@code LeiChecksum}: each part starts with an uppercase letter. */
    static String pascal(String ruleName) {
        StringBuilder pascal = new StringBuilder();
        for (String part : ruleName.split("-")) {
            if (!part.isEmpty()) {
                pascal.append(Character.toUpperCase(part.charAt(0))).append(part, 1, part.length());
            }
        }
        return pascal.toString();
    }
}
