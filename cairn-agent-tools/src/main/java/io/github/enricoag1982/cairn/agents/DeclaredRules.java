package io.github.enricoag1982.cairn.agents;

import io.github.enricoag1982.cairn.documents.SchemaDocument;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules that schema documents name and the server has no code for (design D7 of {@code add-agent-tools}). A
 * store refuses a schema that names a rule it does not know, so the server declares each such rule, without code, from
 * what the document says about it: the store then accepts the schema, and rejects every write of an entity or
 * relationship whose type names the rule ({@code RULE_UNAVAILABLE}).
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
     * @return the descriptors, each with the first type that names the rule in the first document that names it, and
     *         no codes
     */
    static List<RuleDescriptor> namedBy(List<Path> files, List<SchemaDocument> documents, Set<RuleReference> provided) {
        Map<RuleReference, RuleDescriptor> declared = new LinkedHashMap<>();
        for (int i = 0; i < documents.size(); i++) {
            String file = files.get(i).getFileName().toString();
            for (Naming naming : namings(documents.get(i))) {
                RuleReference rule = naming.rule();
                if (provided.contains(rule) || declared.containsKey(rule) || !RULE_NAME.matcher(rule.name()).matches()
                        || rule.version() < 1) {
                    continue;
                }
                declared.put(rule, new RuleDescriptor(rule.name(), rule.version(),
                        "Named by the schema document " + file + "; the server has no code for this rule.",
                        naming.type(), List.of()));
            }
        }
        return new ArrayList<>(declared.values());
    }

    /** A type that names a rule, and where the type is in the document. */
    private record Naming(String type, RuleReference rule, int line, int column) {
    }

    /** Every naming of a rule in a document, in the order of the text. */
    private static List<Naming> namings(SchemaDocument document) {
        List<Naming> namings = new ArrayList<>();
        document.definition().entityTypes().forEach(type -> type.rules()
                .forEach(rule -> namings.add(naming(document, type.name(), rule))));
        document.definition().relationshipTypes().forEach(type -> type.rules()
                .forEach(rule -> namings.add(naming(document, type.name(), rule))));
        namings.sort(Comparator.comparingInt(Naming::line).thenComparingInt(Naming::column));
        return namings;
    }

    private static Naming naming(SchemaDocument document, String type, RuleReference rule) {
        DocumentLocation location = document.locations().of(type);
        return new Naming(type, rule, location.line(), location.column());
    }
}
