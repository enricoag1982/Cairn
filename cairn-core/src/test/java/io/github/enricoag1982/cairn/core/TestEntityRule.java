package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * An entity rule made of a description and a function, for tests that need a rule which does something particular. The
 * rule is deterministic only if its function is.
 *
 * @param descriptor what the rule says about itself
 * @param body       what {@link #check} does
 */
record TestEntityRule(RuleDescriptor descriptor, BiConsumer<EntityState, RuleContext> body) implements EntityRule {

    /**
     * A rule in version 1 for an entity type that declares the given codes.
     *
     * @param name      the rule's name
     * @param appliesTo the entity type it is for
     * @param body      what it does
     * @param codes     the problem codes it declares
     */
    static TestEntityRule of(String name, String appliesTo, BiConsumer<EntityState, RuleContext> body,
            String... codes) {
        List<RuleCode> declared = Arrays.stream(codes)
                .map(code -> new RuleCode(code, "Test code " + code + ".")).toList();
        return new TestEntityRule(new RuleDescriptor(name, 1, "Test rule " + name + ".", appliesTo, declared), body);
    }

    @Override
    public void check(EntityState version, RuleContext context) {
        body.accept(version, context);
    }

    /**
     * Adds a reference to this rule to an entity type of a definition.
     *
     * @param definition the definition
     * @param entityType the name of the entity type that names the rule
     * @return the changed definition
     */
    SchemaDefinition namedIn(SchemaDefinition definition, String entityType) {
        return namedIn(definition, entityType, descriptor);
    }

    /**
     * Adds a reference to a rule to an entity type of a definition.
     *
     * @param definition the definition
     * @param entityType the name of the entity type that names the rule
     * @param rule       the rule to name
     * @return the changed definition
     */
    static SchemaDefinition namedIn(SchemaDefinition definition, String entityType, RuleDescriptor rule) {
        return Definitions.changeEntityType(definition, entityType, type -> {
            List<RuleReference> rules = new ArrayList<>(type.rules());
            rules.add(new RuleReference(rule.name(), rule.version()));
            return new EntityTypeDefinition(type.name(), type.isAbstract(), type.supertype(), type.traits(),
                    type.attributes(), type.aliases(), type.deprecated(), type.description(), rules);
        });
    }
}
