package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import java.security.SecureRandom;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * What a store is created with (design D2 of {@code add-entity-versions} and D2 of {@code add-entity-rules}): the clock
 * that gives transaction times, the generator of entity IDs, the rules that the store runs, and the rules that it
 * declares without code.
 *
 * <p>The descriptions of both kinds of rules are the rules available to the store's schema registry, which checks the
 * rules that types name against them. A store runs the rules that it has {@linkplain #rules() code} for, at commit; a
 * write that would need a declared rule is rejected with the problem {@code RULE_UNAVAILABLE}.
 *
 * <p>The settings are plain data and can be used for several stores. A store takes a {@linkplain IdGenerator#copy()
 * copy} of the generator, so stores made from the same settings do not share its state: two stores with a
 * deterministic generator commit the same transactions and get the same IDs. Stores made from the same settings share
 * the rule objects, which therefore should keep no state.
 *
 * @param clock          the source of transaction times
 * @param idGenerator    the generator of entity IDs, which also says which supplied IDs the store accepts
 * @param rules          the rules, with their code, that the store runs at commit
 * @param availableRules the descriptions of rules that the store declares without having their code: types of the
 *                       store's schema may name them, but a write that would need to run one is rejected
 */
public record StoreSettings(
        InstantSource clock,
        IdGenerator idGenerator,
        List<Rule> rules,
        List<RuleDescriptor> availableRules) {

    /**
     * Rejects null components and copies the rules.
     */
    public StoreSettings {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(idGenerator, "idGenerator");
        rules = List.copyOf(rules);
        availableRules = List.copyOf(availableRules);
    }

    /**
     * Creates settings without rules that have code.
     *
     * @param clock          the source of transaction times
     * @param idGenerator    the generator of entity IDs
     * @param availableRules the descriptions of the rules that the store declares
     */
    public StoreSettings(InstantSource clock, IdGenerator idGenerator, List<RuleDescriptor> availableRules) {
        this(clock, idGenerator, List.of(), availableRules);
    }

    /**
     * The default settings: the system clock, a UUIDv7 generator, and no rules.
     *
     * @return the settings
     */
    public static StoreSettings defaults() {
        return builder().build();
    }

    /**
     * Starts building settings, which default to the system clock, a UUIDv7 generator and no rules.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builds {@link StoreSettings}. A builder is not safe for use by several threads. Every method returns the builder.
     */
    public static final class Builder {

        private InstantSource clock = InstantSource.system();
        private IdGenerator idGenerator;
        private final List<Rule> rules = new ArrayList<>();
        private boolean discoverRules;
        private final List<RuleDescriptor> declared = new ArrayList<>();

        private Builder() {
        }

        /**
         * Sets the source of transaction times.
         *
         * @param clock the clock; the system clock by default
         * @return this builder
         */
        public Builder clock(InstantSource clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Sets the generator of entity IDs.
         *
         * @param idGenerator the generator; by default a UUIDv7 generator that reads the clock of these settings
         * @return this builder
         * @see IdGenerators
         */
        public Builder idGenerator(IdGenerator idGenerator) {
            this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
            return this;
        }

        /**
         * Sets the rules that the store runs, replacing any set before. Their descriptions become available rules of
         * the store's schema registry. Rules found by {@link #discoverRules()} come in addition.
         *
         * @param rules the rules; none by default
         * @return this builder
         */
        public Builder rules(Rule... rules) {
            return rules(Arrays.asList(Objects.requireNonNull(rules, "rules")));
        }

        /**
         * Sets the rules that the store runs, replacing any set before. Their descriptions become available rules of
         * the store's schema registry. Rules found by {@link #discoverRules()} come in addition.
         *
         * @param rules the rules; none by default
         * @return this builder
         */
        public Builder rules(Collection<? extends Rule> rules) {
            Objects.requireNonNull(rules, "rules");
            rules.forEach(rule -> Objects.requireNonNull(rule, "rule"));
            this.rules.clear();
            this.rules.addAll(rules);
            return this;
        }

        /**
         * Adds the rules that Java's {@link ServiceLoader} finds, when the settings are {@linkplain #build() built}:
         * every {@link Rule} provided by a module or jar on the module path or class path. Together with the rules
         * given to {@link #rules(Rule...)}, a rule with the same name and version as another makes
         * {@link Stores#inMemory(StoreSettings)} throw an {@code IllegalArgumentException}.
         *
         * @return this builder
         * @throws java.util.ServiceConfigurationError when the settings are built, if a provider cannot be loaded
         */
        public Builder discoverRules() {
            this.discoverRules = true;
            return this;
        }

        /**
         * Sets the descriptions of rules that the store declares without having their code, replacing any set before.
         * Types of the store's schema may name them, but a write that would need to run one is rejected with the problem
         * {@code RULE_UNAVAILABLE}. A rule with code is described by the rule itself: do not declare it again.
         *
         * @param rules the descriptions; none by default
         * @return this builder
         */
        public Builder availableRules(Collection<RuleDescriptor> rules) {
            Objects.requireNonNull(rules, "rules");
            this.declared.clear();
            this.declared.addAll(rules);
            return this;
        }

        /**
         * Builds the settings, loading the rules to discover if {@link #discoverRules()} was called.
         *
         * @return the settings
         */
        public StoreSettings build() {
            IdGenerator generator = idGenerator != null ? idGenerator : IdGenerators.uuidV7(clock, new SecureRandom());
            List<Rule> all = new ArrayList<>(rules);
            if (discoverRules) {
                ServiceLoader.load(Rule.class).forEach(all::add);
            }
            return new StoreSettings(clock, generator, all, declared);
        }
    }
}
