package io.github.enricoag1982.cairn.codegen;

import io.github.enricoag1982.cairn.documents.NamedRules;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.Schema;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the generator writes from: the checked schema of the documents, the rules they name and the Java names.
 *
 * @param packageName  the package of the generated files
 * @param documents    the file names of the documents, sorted
 * @param names        the Java names
 * @param deprecations whether any type or attribute of the schema is deprecated
 * @param elements     the traits, entity types and relationship types, sorted by name
 * @param rules        the rules the documents name, sorted by name and version
 * @param types        the Java names of everything in the package: interfaces and rule base classes
 */
record Model(
        String packageName,
        List<String> documents,
        JavaNames names,
        boolean deprecations,
        List<Element> elements,
        List<RuleBase> rules,
        Set<String> types) {

    /**
     * A rule base class.
     *
     * @param rule      the rule's name and version
     * @param java      the name of the class
     * @param appliesTo the type the rule is for
     */
    record RuleBase(RuleReference rule, String java, Element appliesTo) {
    }

    static Model of(Schema schema, List<NamedRules.Named> named, List<String> documents, String packageName) {
        List<RuleReference> references = named.stream().map(NamedRules.Named::rule)
                .sorted(Comparator.comparing(RuleReference::name).thenComparingInt(RuleReference::version)).toList();
        JavaNames names = new JavaNames(schema, references);
        Map<String, Element> byName = new LinkedHashMap<>();
        schema.traits().forEach(trait -> byName.put(trait.name(), Element.of(trait, names.type(trait.name()))));
        schema.entityTypes().forEach(type -> byName.put(type.name(), Element.of(type, names.type(type.name()))));
        schema.relationshipTypes().forEach(type ->
                byName.put(type.name(), Element.of(type, names.type(type.name()))));
        List<Element> elements = byName.values().stream()
                .sorted(Comparator.comparing(Element::name, String.CASE_INSENSITIVE_ORDER)).toList();

        List<RuleBase> rules = new ArrayList<>();
        for (RuleReference reference : references) {
            NamedRules.Named rule = named.stream().filter(candidate -> candidate.rule().equals(reference))
                    .findFirst().orElseThrow();
            Element appliesTo = elements.stream().filter(element -> element.kind() != Element.Kind.TRAIT
                    && element.name().equalsIgnoreCase(rule.appliesTo())).findFirst().orElseGet(() ->
                    // An alias of a type: the schema finds the type by it.
                    schema.entityType(rule.appliesTo()).map(type -> byName.get(type.name()))
                            .or(() -> schema.relationshipType(rule.appliesTo()).map(type -> byName.get(type.name())))
                            .orElseThrow());
            rules.add(new RuleBase(reference, names.rule(reference), appliesTo));
        }

        Set<String> types = new HashSet<>();
        elements.forEach(element -> types.add(element.java()));
        rules.forEach(rule -> types.add(rule.java()));
        boolean deprecations = elements.stream().anyMatch(element -> element.deprecated()
                || element.attributes().stream().anyMatch(Attribute::deprecated));
        return new Model(packageName, documents, names, deprecations, elements, rules, Set.copyOf(types));
    }

    Element element(String name) {
        return elements.stream().filter(element -> element.name().equals(name)).findFirst().orElseThrow();
    }
}
