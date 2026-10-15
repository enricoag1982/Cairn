package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.core.schema.InMemorySchemaRegistry;
import io.github.enricoag1982.cairn.expressions.ExpressionLanguage;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import java.time.InstantSource;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Creates schema registries (capability {@code schema-evolution}).
 *
 * <p>A registry compiles the checks that types declare (capability {@code checks}, ADR-0030) with an expression
 * language. The registries that these methods create look for one with Java's {@link java.util.ServiceLoader}, which
 * finds the module {@code cairn-cel} if it is on the class path, and take the first they find. A registry
 * without an expression language rejects every definition that declares checks. To give a registry a language, or to
 * give it none although one can be found, use {@link #inMemory(InstantSource, Collection, Optional)}.
 */
public final class SchemaRegistries {

    private SchemaRegistries() {
    }

    /**
     * Creates an empty in-memory schema registry without available rules that takes transaction times from the
     * system clock, and an expression language if one can be found.
     *
     * @return the registry
     */
    public static SchemaRegistry inMemory() {
        return inMemory(InstantSource.system());
    }

    /**
     * Creates an empty in-memory schema registry without available rules, and with an expression language if one can be
     * found.
     *
     * @param clock the source of transaction times
     * @return the registry
     */
    public static SchemaRegistry inMemory(InstantSource clock) {
        return inMemory(clock, List.of());
    }

    /**
     * Creates an empty in-memory schema registry with available rules, which entity types and relationship types
     * may name (capability {@code type-model}), and with an expression language if one can be found.
     *
     * @param clock the source of transaction times
     * @param rules the rules that are available, listed by {@link SchemaRegistry#availableRules()}
     * @return the registry
     * @throws IllegalArgumentException if a rule's name or version breaks the rules for rule references (lowercase
     *                                  ASCII letters, digits and hyphens, starting with a letter, at most 128
     *                                  characters; a version of at least 1), or if two rules have the same name and
     *                                  version
     */
    public static SchemaRegistry inMemory(InstantSource clock, Collection<RuleDescriptor> rules) {
        return inMemory(clock, rules, ExpressionLanguages.discovered());
    }

    /**
     * Creates an empty in-memory schema registry with available rules and an expression language that compiles the checks
     * of types (capability {@code checks}).
     *
     * @param clock    the source of transaction times
     * @param rules    the rules that are available, listed by {@link SchemaRegistry#availableRules()}
     * @param language the expression language; if empty, the registry rejects definitions that declare checks, with the
     *                 problem {@code CHECKS_UNAVAILABLE}, and does not look for one
     * @return the registry
     * @throws IllegalArgumentException if a rule's name or version breaks the rules for rule references, or if two rules
     *                                  have the same name and version
     */
    public static SchemaRegistry inMemory(InstantSource clock, Collection<RuleDescriptor> rules,
            Optional<ExpressionLanguage> language) {
        Objects.requireNonNull(language, "language");
        return new InMemorySchemaRegistry(clock, rules, language);
    }
}
