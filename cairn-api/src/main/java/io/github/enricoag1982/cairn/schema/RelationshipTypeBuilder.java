package io.github.enricoag1982.cairn.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Builds a {@link RelationshipTypeDefinition} (capability {@code type-model}). Relationship types start as associations with any number of
 * relationships at both ends. A missing source, target or inverse name is left empty, and validation reports it.
 */
public final class RelationshipTypeBuilder {

    private final String name;
    private String source = "";
    private String target = "";
    private Multiplicity targetsPerSource = Multiplicity.ZERO_OR_MORE;
    private Multiplicity sourcesPerTarget = Multiplicity.ZERO_OR_MORE;
    private String inverseName = "";
    private final List<String> inverseAliases = new ArrayList<>();
    private RelationshipKind kind = RelationshipKind.ASSOCIATION;
    private boolean symmetric;
    private boolean outlivesTarget;
    private String supertype;
    private final List<AttributeDefinition> attributes = new ArrayList<>();
    private final List<String> aliases = new ArrayList<>();
    private boolean deprecated;
    private String description;
    private final List<RuleReference> rules = new ArrayList<>();
    private final List<Check> checks = new ArrayList<>();

    RelationshipTypeBuilder(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * Sets the source entity type.
     *
     * @param name the name of the source type
     * @return this builder
     */
    public RelationshipTypeBuilder from(String name) {
        this.source = Objects.requireNonNull(name, "name");
        return this;
    }

    /**
     * Sets the target entity type.
     *
     * @param name the name of the target type
     * @return this builder
     */
    public RelationshipTypeBuilder to(String name) {
        this.target = Objects.requireNonNull(name, "name");
        return this;
    }

    /**
     * Sets how many targets each source can have.
     *
     * @param multiplicity the multiplicity
     * @return this builder
     */
    public RelationshipTypeBuilder targetsPerSource(Multiplicity multiplicity) {
        this.targetsPerSource = Objects.requireNonNull(multiplicity, "multiplicity");
        return this;
    }

    /**
     * Sets how many sources each target can have.
     *
     * @param multiplicity the multiplicity
     * @return this builder
     */
    public RelationshipTypeBuilder sourcesPerTarget(Multiplicity multiplicity) {
        this.sourcesPerTarget = Objects.requireNonNull(multiplicity, "multiplicity");
        return this;
    }

    /**
     * Sets the name for navigating from target to source.
     *
     * @param name the inverse name
     * @return this builder
     */
    public RelationshipTypeBuilder inverse(String name) {
        this.inverseName = Objects.requireNonNull(name, "name");
        return this;
    }

    /**
     * Adds previous inverse names.
     *
     * @param names the previous inverse names
     * @return this builder
     */
    public RelationshipTypeBuilder inverseAliases(String... names) {
        inverseAliases.addAll(List.of(names));
        return this;
    }

    /**
     * Makes the relationship type a composition.
     *
     * @return this builder
     */
    public RelationshipTypeBuilder composition() {
        this.kind = RelationshipKind.COMPOSITION;
        return this;
    }

    /**
     * Makes the relationship type symmetric.
     *
     * @return this builder
     */
    public RelationshipTypeBuilder symmetric() {
        this.symmetric = true;
        return this;
    }

    /**
     * Lets the relationships of the type outlive their targets: they may continue after their target ends, as records
     * of a past fact, and navigation then returns the target's last version. The target must exist where the
     * relationship starts, and wherever it starts again after a gap in its effective period. Only associations that are
     * not symmetric may declare it; a subtype does not inherit it.
     *
     * @return this builder
     */
    public RelationshipTypeBuilder outlivesTarget() {
        this.outlivesTarget = true;
        return this;
    }

    /**
     * Sets the relationship supertype.
     *
     * @param name the name of the supertype
     * @return this builder
     */
    public RelationshipTypeBuilder supertype(String name) {
        this.supertype = Objects.requireNonNull(name, "name");
        return this;
    }

    /**
     * Declares an optional attribute.
     *
     * @param name      the attribute name
     * @param valueType the kind of value
     * @return this builder
     */
    public RelationshipTypeBuilder attribute(String name, ValueType valueType) {
        return attribute(name, valueType, attribute -> { });
    }

    /**
     * Declares an attribute.
     *
     * @param name      the attribute name
     * @param valueType the kind of value
     * @param spec      sets the attribute's other properties
     * @return this builder
     */
    public RelationshipTypeBuilder attribute(String name, ValueType valueType, Consumer<AttributeBuilder> spec) {
        AttributeBuilder builder = AttributeDefinition.builder(name, valueType);
        spec.accept(builder);
        attributes.add(builder.build());
        return this;
    }

    /**
     * Adds previous names of the type.
     *
     * @param names the previous names
     * @return this builder
     */
    public RelationshipTypeBuilder aliases(String... names) {
        aliases.addAll(List.of(names));
        return this;
    }

    /**
     * Marks the type as deprecated.
     *
     * @return this builder
     */
    public RelationshipTypeBuilder deprecated() {
        this.deprecated = true;
        return this;
    }

    /**
     * Describes the type.
     *
     * @param text what the type means
     * @return this builder
     */
    public RelationshipTypeBuilder description(String text) {
        this.description = Objects.requireNonNull(text, "text");
        return this;
    }

    /**
     * Names a rule that applies to the type and its subtypes.
     *
     * @param name    the rule's name
     * @param version the rule's version, at least 1
     * @return this builder
     */
    public RelationshipTypeBuilder rule(String name, int version) {
        rules.add(new RuleReference(name, version));
        return this;
    }

    /**
     * Declares a check, without a message, that applies to the type and its subtypes (capability {@code checks}).
     *
     * @param name       the check's name
     * @param expression the CEL expression over {@code self}, the version's values
     * @return this builder
     */
    public RelationshipTypeBuilder check(String name, String expression) {
        checks.add(new Check(name, expression));
        return this;
    }

    /**
     * Declares a check with a message that applies to the type and its subtypes (capability {@code checks}).
     *
     * @param name       the check's name
     * @param expression the CEL expression over {@code self}, the version's values
     * @param message    what to tell whoever breaks the check
     * @return this builder
     */
    public RelationshipTypeBuilder check(String name, String expression, String message) {
        checks.add(new Check(name, expression, Optional.of(Objects.requireNonNull(message, "message"))));
        return this;
    }

    /**
     * Builds the definition.
     *
     * @return the relationship type definition
     */
    public RelationshipTypeDefinition build() {
        return new RelationshipTypeDefinition(name, source, target, targetsPerSource, sourcesPerTarget, inverseName,
                inverseAliases, kind, symmetric, outlivesTarget, Optional.ofNullable(supertype), attributes, aliases,
                deprecated, Optional.ofNullable(description), rules, checks);
    }
}
