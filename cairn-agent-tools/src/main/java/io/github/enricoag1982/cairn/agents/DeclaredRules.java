package io.github.enricoag1982.cairn.agents;

import io.github.enricoag1982.cairn.documents.SchemaDocument;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules that schema documents name and the server has no code for (design D7 of {@code add-agent-tools}). A
 * store refuses a schema that names a rule it does not know, so the server declares each such rule, without code, from
 * what the documents say about it: the store then accepts the schema, and rejects every write of an entity or
 * relationship whose type names the rule ({@code RULE_UNAVAILABLE}).
 *
 * <p>A rule is for one type, and a type may name it if it is that type or one of its subtypes. When several types name
 * the same rule, the rule is declared for their closest common supertype, found through the {@code supertype} of the
 * types in all the documents. If they have none, or are of different kinds, it is declared for the first of them in
 * the order of the documents, and registering the documents reports the others.
 */
final class DeclaredRules {

    /** The form of a rule name that a store accepts: a lowercase letter, then lowercase letters, digits and hyphens. */
    private static final Pattern RULE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,127}");

    private DeclaredRules() {
    }

    /**
     * Describes the rules that the documents name and that are not provided. The rules are in the order in which the
     * documents first name them. A reference with a malformed name or a version below 1 is left out, so that
     * registering the document reports it, with its location.
     *
     * @param files     the files the documents were read from, in the same order as the documents
     * @param documents the documents, in the order they are registered
     * @param provided  the rules that the server has the code of
     * @return the descriptors, each for the closest common supertype of the types that name the rule, or else the
     *         first of them, in the first document that names the rule, and with no codes
     */
    static List<RuleDescriptor> namedBy(List<Path> files, List<SchemaDocument> documents, Set<RuleReference> provided) {
        Map<RuleReference, List<Naming>> namings = new LinkedHashMap<>();
        Map<String, String> entitySupertypes = new HashMap<>();
        Map<String, String> relationshipSupertypes = new HashMap<>();
        for (int i = 0; i < documents.size(); i++) {
            SchemaDocument document = documents.get(i);
            String file = files.get(i).getFileName().toString();
            document.definition().entityTypes().forEach(type -> type.supertype()
                    .ifPresent(supertype -> entitySupertypes.put(key(type.name()), supertype)));
            document.definition().relationshipTypes().forEach(type -> type.supertype()
                    .ifPresent(supertype -> relationshipSupertypes.put(key(type.name()), supertype)));
            for (Naming naming : namings(document, file)) {
                RuleReference rule = naming.rule();
                if (!provided.contains(rule) && RULE_NAME.matcher(rule.name()).matches() && rule.version() >= 1) {
                    namings.computeIfAbsent(rule, ignored -> new ArrayList<>()).add(naming);
                }
            }
        }
        List<RuleDescriptor> declared = new ArrayList<>();
        namings.forEach((rule, named) -> declared.add(new RuleDescriptor(rule.name(), rule.version(),
                "Named by the schema document " + named.getFirst().file()
                        + "; the server has no code for this rule.",
                appliesTo(named, entitySupertypes, relationshipSupertypes), List.of())));
        return declared;
    }

    /** What a type is: an entity type or a relationship type, which have supertypes of their own kind. */
    private enum Kind {
        ENTITY, RELATIONSHIP
    }

    /** A type that names a rule, in a file, and where the type is in the document. */
    private record Naming(String type, Kind kind, RuleReference rule, String file, int line, int column) {
    }

    /** Every naming of a rule in a document, in the order of the text. */
    private static List<Naming> namings(SchemaDocument document, String file) {
        List<Naming> namings = new ArrayList<>();
        document.definition().entityTypes().forEach(type -> type.rules()
                .forEach(rule -> namings.add(naming(document, file, type.name(), Kind.ENTITY, rule))));
        document.definition().relationshipTypes().forEach(type -> type.rules()
                .forEach(rule -> namings.add(naming(document, file, type.name(), Kind.RELATIONSHIP, rule))));
        namings.sort(Comparator.comparingInt(Naming::line).thenComparingInt(Naming::column));
        return namings;
    }

    private static Naming naming(SchemaDocument document, String file, String type, Kind kind, RuleReference rule) {
        DocumentLocation location = document.locations().of(type);
        return new Naming(type, kind, rule, file, location.line(), location.column());
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
        return names.stream().map(DeclaredRules::key).toList();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
