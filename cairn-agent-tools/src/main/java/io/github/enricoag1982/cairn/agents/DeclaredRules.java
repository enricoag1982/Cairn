package io.github.enricoag1982.cairn.agents;

import io.github.enricoag1982.cairn.documents.NamedRules;
import io.github.enricoag1982.cairn.documents.SchemaDocument;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The rules that schema documents name and the server has no code for (design D7 of {@code add-agent-tools}). A
 * store refuses a schema that names a rule it does not know, so the server declares each such rule, without code, from
 * what the documents say about it: the store then accepts the schema, and rejects every write of an entity or
 * relationship whose type names the rule ({@code RULE_UNAVAILABLE}).
 *
 * <p>A rule is for one type, and a type may name it if it is that type or one of its subtypes. When several types name
 * the same rule, the rule is declared for their closest common supertype, or else for the first of them in the order
 * of the documents, as {@link NamedRules} finds it, and registering the documents reports the others.
 */
final class DeclaredRules {

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
        return NamedRules.in(documents, provided).stream()
                .map(named -> new RuleDescriptor(named.rule().name(), named.rule().version(),
                        "Named by the schema document " + files.get(named.document()).getFileName()
                                + "; the server has no code for this rule.",
                        named.appliesTo(), List.of()))
                .toList();
    }
}
