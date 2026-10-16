package io.github.enricoag1982.cairn.codegen;

import io.github.enricoag1982.cairn.schema.Attribute;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The names of the constants that hold the typed references of attributes (design D6 of
 * {@code add-queries-and-aggregation}): one for each attribute that a type declares itself, in upper snake case, so that
 * {@code birthDate} becomes {@code BIRTH_DATE}.
 *
 * <p>A name gets the suffix {@code _}, repeated until it clashes with nothing, where it would clash: with the constant
 * {@code TYPE} that every entity type and relationship type declares, with the constant {@code INVERSE} that every
 * relationship type declares (both are reserved for every element, so that a name does not depend on the kind of
 * element), with the Java name of a type of the documents (which generated code writes before a dot, and a field of that
 * name would hide), or with a constant that a supertype or an included trait declares, which the constants of this type
 * would otherwise hide. The names of an element depend on the element's own attributes and on its supertypes and traits
 * only, so that registering a type elsewhere never renames a constant.
 */
final class Constants {

    /** The constant that every entity type and relationship type declares for its name in the schema. */
    private static final String TYPE = "TYPE";

    /** The constant that every relationship type declares for its inverse name in the schema. */
    private static final String INVERSE = "INVERSE";

    private Constants() {
    }

    /**
     * Upper snake case: a word starts at an uppercase letter that follows a lowercase letter or a digit, and at the last
     * uppercase letter of a run that is followed by a lowercase letter, so that {@code leiCode} is {@code LEI_CODE} and
     * {@code ISINCode} is {@code ISIN_CODE}. Underscores of the name stay.
     *
     * @param name the name of an attribute
     * @return the name in upper case, with an underscore between words
     */
    static String snake(String name) {
        return name.replaceAll("([\\p{Ll}\\p{Nd}])(\\p{Lu})", "$1_$2")
                .replaceAll("(\\p{Lu}+)(\\p{Lu}\\p{Ll})", "$1_$2")
                .toUpperCase(Locale.ROOT);
    }

    /**
     * Chooses the names of the constants of every element.
     *
     * @param elements  the traits, entity types and relationship types
     * @param typeNames the Java names of the types in the package of the generated files
     * @return for each element by its name in the documents, the constant of each attribute it declares itself, by the
     *         attribute's name
     */
    static Map<String, Map<String, String>> of(List<Element> elements, Set<String> typeNames) {
        Map<String, Element> byName = new HashMap<>();
        elements.forEach(element -> byName.put(element.name(), element));
        Map<String, Map<String, String>> result = new HashMap<>();
        for (Element element : elements) {
            resolve(element, byName, typeNames, result);
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> resolve(Element element, Map<String, Element> byName, Set<String> typeNames,
            Map<String, Map<String, String>> result) {
        Map<String, String> known = result.get(element.name());
        if (known != null) {
            return known;
        }
        Set<String> taken = new HashSet<>(typeNames);
        taken.add(TYPE);
        taken.add(INVERSE);
        for (String parent : inheritedFrom(element, byName)) {
            taken.addAll(resolve(byName.get(parent), byName, typeNames, result).values());
        }
        Map<String, String> own = new LinkedHashMap<>();
        for (Attribute attribute : element.ownAttributes()) {
            String candidate = snake(attribute.name());
            while (taken.contains(candidate)) {
                candidate += "_";
            }
            taken.add(candidate);
            own.put(attribute.name(), candidate);
        }
        result.put(element.name(), own);
        return own;
    }

    /** The elements whose constants an element inherits: its supertypes, its traits and the traits of its supertypes. */
    private static List<String> inheritedFrom(Element element, Map<String, Element> byName) {
        List<String> parents = new ArrayList<>(element.supertypes());
        parents.addAll(element.traits());
        for (String supertype : element.supertypes()) {
            parents.addAll(byName.get(supertype).traits());
        }
        return parents.stream().distinct().toList();
    }
}
