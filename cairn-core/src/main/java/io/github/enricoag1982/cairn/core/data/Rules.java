package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The rules with code that a store runs, by name and version (design D2 of {@code add-entity-rules}). A rule that a
 * schema names but that is not here is declared without code, if the store knows it at all.
 */
final class Rules {

    /**
     * A rule with the description it gave when the store was created, so that what a rule declares does not change
     * while the store runs.
     *
     * @param rule       the rule
     * @param descriptor its description
     * @param codes      the problem codes the description declares
     */
    record Registered(Rule rule, RuleDescriptor descriptor, Set<String> codes) {
    }

    private final Map<RuleReference, Registered> byReference = new HashMap<>();
    private final List<RuleDescriptor> descriptors = new ArrayList<>();

    /**
     * Collects the rules of a store.
     *
     * @param rules the rules
     * @throws NullPointerException     if a rule, or the descriptor of a rule, is null
     * @throws IllegalArgumentException if two rules have the same name and version
     */
    Rules(Collection<? extends Rule> rules) {
        for (Rule rule : rules) {
            Objects.requireNonNull(rule, "rule");
            RuleDescriptor descriptor = Objects.requireNonNull(rule.descriptor(),
                    "The descriptor of the rule " + rule.getClass().getName());
            Set<String> codes = descriptor.codes().stream().map(RuleCode::code).collect(Collectors.toUnmodifiableSet());
            Registered previous = byReference.put(new RuleReference(descriptor.name(), descriptor.version()),
                    new Registered(rule, descriptor, codes));
            if (previous != null) {
                throw new IllegalArgumentException("The rule " + descriptor.name() + " in version "
                        + descriptor.version() + " is given more than once.");
            }
            descriptors.add(descriptor);
        }
    }

    /** The descriptions of the rules, in the order in which the rules were given. */
    List<RuleDescriptor> descriptors() {
        return List.copyOf(descriptors);
    }

    /** The rule that a reference names, if the store has its code. */
    Optional<Registered> find(RuleReference reference) {
        return Optional.ofNullable(byReference.get(reference));
    }
}
