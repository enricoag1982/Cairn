package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.data.RelationshipRule;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A relationship rule made of a description and a function, for tests that need a rule which does something
 * particular, as {@link TestEntityRule} does for entities. The rule is deterministic only if its function is.
 *
 * @param descriptor what the rule says about itself
 * @param body       what {@link #check} does
 */
record TestRelationshipRule(RuleDescriptor descriptor, BiConsumer<RelationshipState, RuleContext> body)
        implements RelationshipRule {

    /**
     * A rule in version 1 for a relationship type that declares the given codes.
     *
     * @param name      the rule's name
     * @param appliesTo the relationship type it is for
     * @param body      what it does
     * @param codes     the problem codes it declares
     */
    static TestRelationshipRule of(String name, String appliesTo, BiConsumer<RelationshipState, RuleContext> body,
            String... codes) {
        List<RuleCode> declared = Arrays.stream(codes)
                .map(code -> new RuleCode(code, "Test code " + code + ".")).toList();
        return new TestRelationshipRule(new RuleDescriptor(name, 1, "Test rule " + name + ".", appliesTo, declared),
                body);
    }

    @Override
    public void check(RelationshipState version, RuleContext context) {
        body.accept(version, context);
    }

    /**
     * Adds a reference to this rule to a relationship type of a definition.
     *
     * @param definition       the definition
     * @param relationshipType the name of the relationship type that names the rule
     * @return the changed definition
     */
    SchemaDefinition namedIn(SchemaDefinition definition, String relationshipType) {
        return namedIn(definition, relationshipType, descriptor);
    }

    /**
     * Adds a reference to a rule to a relationship type of a definition.
     *
     * @param definition       the definition
     * @param relationshipType the name of the relationship type that names the rule
     * @param rule             the rule to name
     * @return the changed definition
     */
    static SchemaDefinition namedIn(SchemaDefinition definition, String relationshipType, RuleDescriptor rule) {
        return Definitions.changeRelationshipType(definition, relationshipType, type -> {
            List<RuleReference> rules = new ArrayList<>(type.rules());
            rules.add(new RuleReference(rule.name(), rule.version()));
            return new RelationshipTypeDefinition(type.name(), type.source(), type.target(), type.targetsPerSource(),
                    type.sourcesPerTarget(), type.inverseName(), type.inverseAliases(), type.kind(), type.symmetric(),
                    type.supertype(), type.attributes(), type.aliases(), type.deprecated(), type.description(), rules);
        });
    }
}
