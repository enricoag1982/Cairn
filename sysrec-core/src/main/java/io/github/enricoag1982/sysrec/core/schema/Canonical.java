package io.github.enricoag1982.sysrec.core.schema;

import io.github.enricoag1982.sysrec.schema.AttributeDefinition;
import io.github.enricoag1982.sysrec.schema.EntityTypeDefinition;
import io.github.enricoag1982.sysrec.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.sysrec.schema.SchemaDefinition;
import io.github.enricoag1982.sysrec.schema.TraitDefinition;
import java.util.Comparator;
import java.util.List;

/** Compares definitions without regard to the order in which types, attributes and aliases are listed. */
final class Canonical {

    private Canonical() {
    }

    static boolean equal(SchemaDefinition first, SchemaDefinition second) {
        return of(first).equals(of(second));
    }

    static SchemaDefinition of(SchemaDefinition definition) {
        return new SchemaDefinition(
                definition.entityTypes().stream().map(Canonical::of)
                        .sorted(Comparator.comparing(EntityTypeDefinition::name)).toList(),
                definition.traits().stream().map(Canonical::of)
                        .sorted(Comparator.comparing(TraitDefinition::name)).toList(),
                definition.relationshipTypes().stream().map(Canonical::of)
                        .sorted(Comparator.comparing(RelationshipTypeDefinition::name)).toList());
    }

    private static EntityTypeDefinition of(EntityTypeDefinition type) {
        return new EntityTypeDefinition(type.name(), type.isAbstract(), type.supertype(), sorted(type.traits()),
                attributes(type.attributes()), sorted(type.aliases()), type.deprecated());
    }

    private static TraitDefinition of(TraitDefinition trait) {
        return new TraitDefinition(trait.name(), attributes(trait.attributes()), sorted(trait.aliases()), trait.deprecated());
    }

    private static RelationshipTypeDefinition of(RelationshipTypeDefinition type) {
        return new RelationshipTypeDefinition(type.name(), type.source(), type.target(), type.targetsPerSource(),
                type.sourcesPerTarget(), type.inverseName(), sorted(type.inverseAliases()), type.kind(), type.symmetric(),
                type.supertype(), attributes(type.attributes()), sorted(type.aliases()), type.deprecated());
    }

    private static List<AttributeDefinition> attributes(List<AttributeDefinition> attributes) {
        return attributes.stream()
                .map(attribute -> new AttributeDefinition(attribute.name(), attribute.valueType(), attribute.required(),
                        attribute.unique(), attribute.range(), sorted(attribute.aliases()), attribute.deprecated()))
                .sorted(Comparator.comparing(AttributeDefinition::name))
                .toList();
    }

    private static List<String> sorted(List<String> names) {
        return names.stream().sorted().toList();
    }
}
