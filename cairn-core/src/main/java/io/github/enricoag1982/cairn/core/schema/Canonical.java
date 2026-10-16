package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Compares definitions without regard to the order in which types, attributes, aliases, allowed values, rule references,
 * checks and examples are listed (design D6).
 */
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
                attributes(type.attributes()), sorted(type.aliases()), type.deprecated(), type.description(),
                rules(type.rules()), checks(type.checks()));
    }

    private static TraitDefinition of(TraitDefinition trait) {
        return new TraitDefinition(trait.name(), attributes(trait.attributes()), sorted(trait.aliases()),
                trait.deprecated(), trait.description());
    }

    private static RelationshipTypeDefinition of(RelationshipTypeDefinition type) {
        return new RelationshipTypeDefinition(type.name(), type.source(), type.target(), type.targetsPerSource(),
                type.sourcesPerTarget(), type.inverseName(), sorted(type.inverseAliases()), type.kind(), type.symmetric(),
                type.outlivesTarget(), type.supertype(), attributes(type.attributes()), sorted(type.aliases()), type.deprecated(),
                type.description(), rules(type.rules()), checks(type.checks()));
    }

    private static List<RuleReference> rules(List<RuleReference> rules) {
        return rules.stream()
                .distinct()
                .sorted(Comparator.comparing(RuleReference::name).thenComparingInt(RuleReference::version))
                .toList();
    }

    private static List<Check> checks(List<Check> checks) {
        return checks.stream()
                .distinct()
                .sorted(Comparator.comparing(Check::name))
                .toList();
    }

    private static List<Value> examples(List<Value> examples) {
        return examples.stream()
                .distinct()
                .sorted(Comparator.comparing(Value::toString))
                .toList();
    }

    private static List<AttributeDefinition> attributes(List<AttributeDefinition> attributes) {
        return attributes.stream()
                .map(attribute -> new AttributeDefinition(attribute.name(), attribute.valueType(), attribute.required(),
                        attribute.unique(), attribute.range().map(range -> of(range, attribute.valueType())),
                        sorted(attribute.aliases()), attribute.deprecated(), attribute.description(),
                        examples(attribute.examples()),
                        attribute.allowedValues().stream().distinct().sorted().toList(),
                        attribute.pattern(), attribute.minLength(), attribute.maxLength(), attribute.derived(),
                        attribute.classification()))
                .sorted(Comparator.comparing(AttributeDefinition::name))
                .toList();
    }

    /**
     * Numeric bounds compare by value in the attribute's value type, so for a decimal attribute 100, 100L and 100.0
     * are the same bound.
     */
    private static Range of(Range range, ValueType type) {
        return new Range(range.lower().map(bound -> of(bound, type)), range.upper().map(bound -> of(bound, type)));
    }

    private static Bound of(Bound bound, ValueType type) {
        Value value = bound.value();
        Value normalized = switch (type) {
            case DECIMAL -> switch (value) {
                case Value.IntValue(int number) -> Value.of(BigDecimal.valueOf(number).stripTrailingZeros());
                case Value.LongValue(long number) -> Value.of(BigDecimal.valueOf(number).stripTrailingZeros());
                case Value.DecimalValue(BigDecimal number) -> Value.of(number.stripTrailingZeros());
                default -> value;
            };
            case LONG -> value instanceof Value.IntValue(int number) ? Value.of((long) number) : value;
            default -> value;
        };
        return new Bound(normalized, bound.inclusive());
    }

    private static List<String> sorted(List<String> names) {
        return names.stream().sorted().toList();
    }
}
