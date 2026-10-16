package io.github.enricoag1982.cairn.codegen;

import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.Trait;
import java.util.List;
import java.util.Optional;

/**
 * A trait, entity type or relationship type of the schema, as the generator needs it.
 *
 * @param kind        what it is
 * @param name        the name in the documents
 * @param java        the name of its Java interface
 * @param isAbstract  whether it is an abstract entity type
 * @param deprecated  whether it is deprecated
 * @param description what it means
 * @param supertypes  the names of all its supertypes, nearest first
 * @param subtypes    the names of all its subtypes
 * @param traits      the names of the traits it includes itself
 * @param attributes  all its attributes, inherited ones first, each with its declaring element
 * @param source      the name of the source entity type of a relationship type, else empty
 * @param target      the name of the target entity type of a relationship type, else empty
 * @param inverse     the inverse name of a relationship type, else empty
 * @param outlivesTarget whether the relationships of a relationship type may continue after their target ends, else
 *                    {@code false}
 */
record Element(
        Kind kind,
        String name,
        String java,
        boolean isAbstract,
        boolean deprecated,
        Optional<String> description,
        List<String> supertypes,
        List<String> subtypes,
        List<String> traits,
        List<Attribute> attributes,
        String source,
        String target,
        String inverse,
        boolean outlivesTarget) {

    /** What an element is. */
    enum Kind {
        TRAIT("trait"), ENTITY("entity type"), RELATIONSHIP("relationship type");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /** The words that Javadoc uses for an element of this kind. */
        String label() {
            return label;
        }
    }

    static Element of(Trait trait, String java) {
        return new Element(Kind.TRAIT, trait.name(), java, false, trait.deprecated(), trait.description(), List.of(),
                List.of(), List.of(), trait.attributes(), "", "", "", false);
    }

    static Element of(EntityType type, String java) {
        return new Element(Kind.ENTITY, type.name(), java, type.isAbstract(), type.deprecated(), type.description(),
                type.supertypes(), type.subtypes(), type.traits(), type.attributes(), "", "", "", false);
    }

    static Element of(RelationshipType type, String java) {
        return new Element(Kind.RELATIONSHIP, type.name(), java, false, type.deprecated(), type.description(),
                type.supertypes(), type.subtypes(), List.of(), type.attributes(), type.source(), type.target(),
                type.inverseName(), type.outlivesTarget());
    }

    /** The name of the type this one extends, if any. */
    Optional<String> supertype() {
        return supertypes.stream().findFirst();
    }

    /** The attributes that this element declares itself, as opposed to those it inherits. */
    List<Attribute> ownAttributes() {
        return attributes.stream().filter(attribute -> attribute.declaredBy().equals(name)).toList();
    }

    /** The kind of attribute names that this element has: those of entities, or of relationships. */
    JavaNames.Kind nameKind() {
        return kind == Kind.RELATIONSHIP ? JavaNames.Kind.RELATIONSHIP : JavaNames.Kind.ENTITY;
    }
}
