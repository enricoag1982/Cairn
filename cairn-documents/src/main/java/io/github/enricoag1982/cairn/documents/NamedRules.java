package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.RuleReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules that schema documents name, and the type each rule is for (capability {@code schema-documents}; design D5
 * of {@code add-java-type-generation} and D7 of {@code add-agent-tools}). Tools that read documents without having the
 * code of their rules, such as the agent server and the generator of Java types, declare each rule from what the
 * documents say about it, and use this class to find the type it is for.
 *
 * <p>A rule is for one type, and a type may name it if it is that type or one of its subtypes. When several types name
 * the same rule, the rule is for their closest common supertype, found through the {@code supertype} of the types in
 * all the documents. If they have none, or are of different kinds (entity types and relationship types), it is for the
 * first of them in the order of the documents, and registering the documents reports the others.
 */
public final class NamedRules {

    /** The form of a rule name that a registry accepts: a lowercase letter, then lowercase letters, digits and hyphens. */
    private static final Pattern RULE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,127}");

    private NamedRules() {
    }

    /**
     * A rule that documents name.
     *
     * @param rule      the rule's name and version
     * @param appliesTo the name of the type the rule is for, as the document writes it: the closest common supertype of
     *                  the types that name the rule, or else the first of them
     * @param document  the position, counted from 0, of the first document that names the rule
     */
    public record Named(RuleReference rule, String appliesTo, int document) {

        /**
         * Rejects null components.
         */
        public Named {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(appliesTo, "appliesTo");
        }
    }

    /**
     * Finds the rules that the documents name and that are not provided. The rules are in the order in which the
     * documents first name them: by document, and in a document by the position of the type that names the rule. A
     * reference with a malformed name or a version below 1 is left out, so that registering the document reports it,
     * with its location.
     *
     * @param documents the documents, in the order they are registered
     * @param provided  the rules that the caller has the code of
     * @return the rules, each for the closest common supertype of the types that name it, or else the first of them,
     *         and with the position of the first document that names it
     */
    public static List<Named> in(List<SchemaDocument> documents, Set<RuleReference> provided) {
        Objects.requireNonNull(documents, "documents");
        Objects.requireNonNull(provided, "provided");
        Map<RuleReference, List<Naming>> namings = new LinkedHashMap<>();
        Map<String, String> entitySupertypes = new HashMap<>();
        Map<String, String> relationshipSupertypes = new HashMap<>();
        for (int i = 0; i < documents.size(); i++) {
            SchemaDocument document = documents.get(i);
            document.definition().entityTypes().forEach(type -> type.supertype()
                    .ifPresent(supertype -> entitySupertypes.put(key(type.name()), supertype)));
            document.definition().relationshipTypes().forEach(type -> type.supertype()
                    .ifPresent(supertype -> relationshipSupertypes.put(key(type.name()), supertype)));
            for (Naming naming : namings(document, i)) {
                RuleReference rule = naming.rule();
                if (!provided.contains(rule) && RULE_NAME.matcher(rule.name()).matches() && rule.version() >= 1) {
                    namings.computeIfAbsent(rule, ignored -> new ArrayList<>()).add(naming);
                }
            }
        }
        List<Named> named = new ArrayList<>();
        namings.forEach((rule, list) -> named.add(new Named(rule,
                appliesTo(list, entitySupertypes, relationshipSupertypes), list.getFirst().document())));
        return named;
    }

    /** What a type is: an entity type or a relationship type, which have supertypes of their own kind. */
    private enum Kind {
        ENTITY, RELATIONSHIP
    }

    /** A type that names a rule, in a document, and where the type is in the document. */
    private record Naming(String type, Kind kind, RuleReference rule, int document, int line, int column) {
    }

    /** Every naming of a rule in a document, in the order of the text. */
    private static List<Naming> namings(SchemaDocument document, int position) {
        List<Naming> namings = new ArrayList<>();
        document.definition().entityTypes().forEach(type -> type.rules()
                .forEach(rule -> namings.add(naming(document, position, type.name(), Kind.ENTITY, rule))));
        document.definition().relationshipTypes().forEach(type -> type.rules()
                .forEach(rule -> namings.add(naming(document, position, type.name(), Kind.RELATIONSHIP, rule))));
        namings.sort(Comparator.comparingInt(Naming::line).thenComparingInt(Naming::column));
        return namings;
    }

    private static Naming naming(SchemaDocument document, int position, String type, Kind kind, RuleReference rule) {
        DocumentLocation location = document.locations().of(type);
        return new Naming(type, kind, rule, position, location.line(), location.column());
    }

    /**
     * The type that a rule is for: the closest type that is, or is a supertype of, every type that names it. If there
     * is none, or the types are of different kinds, the first type that names it.
     */
    private static String appliesTo(List<Naming> named, Map<String, String> entitySupertypes,
            Map<String, String> relationshipSupertypes) {
        String first = named.getFirst().type();
        Kind kind = named.getFirst().kind();
        if (named.stream().anyMatch(naming -> naming.kind() != kind)) {
            return first;
        }
        Map<String, String> supertypes = kind == Kind.ENTITY ? entitySupertypes : relationshipSupertypes;
        List<Set<String>> others = new ArrayList<>();
        for (Naming naming : named.subList(1, named.size())) {
            others.add(new HashSet<>(keys(chain(naming.type(), supertypes))));
        }
        for (String candidate : chain(first, supertypes)) {
            if (others.stream().allMatch(chain -> chain.contains(key(candidate)))) {
                return candidate;
            }
        }
        return first;
    }

    /** A type, then its supertype, then that one's, and so on, as the types are written; a cycle ends the chain. */
    private static List<String> chain(String type, Map<String, String> supertypes) {
        List<String> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String next = type; next != null && seen.add(key(next)); next = supertypes.get(key(next))) {
            chain.add(next);
        }
        return chain;
    }

    private static List<String> keys(List<String> names) {
        return names.stream().map(NamedRules::key).toList();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
