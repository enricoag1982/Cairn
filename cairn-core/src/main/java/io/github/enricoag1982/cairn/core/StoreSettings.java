package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.expressions.ExpressionLanguage;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import java.security.SecureRandom;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * What a store is created with (design D2 of {@code add-entity-versions} and D2 of {@code add-entity-rules}): the clock
 * that gives transaction times, the generator of entity and relationship IDs, the rules that the store runs, and the
 * rules that it declares without code.
 *
 * <p>The descriptions of both kinds of rules are the rules available to the store's schema registry, which checks the
 * rules that types name against them. A store runs the rules that it has {@linkplain #rules() code} for, at commit, and
 * again when a transaction changes what they read. A transaction that writes an entity or a relationship whose type, or
 * one of whose supertypes, names a {@linkplain #declaredRules() declared rule} is rejected with the problem
 * {@code RULE_UNAVAILABLE}, whatever the write changes.
 *
 * <p>The settings can also hold an {@linkplain #expressionLanguage() expression language}, which compiles and runs the
 * checks that types declare (capability {@code checks}, ADR-0030). The {@linkplain #builder() builder} takes the one that
 * is given to it, and otherwise looks for one with Java's {@link ServiceLoader}, which finds the module {@code cairn-cel}
 * if it is on the class path. A store without an expression language rejects schema definitions that
 * declare checks, and, if its schema declares checks, writes to the types they apply to.
 *
 * <p>The settings are plain data and can be used for several stores. A store takes a {@linkplain IdGenerator#copy()
 * copy} of the generator, so stores made from the same settings do not share its state: two stores with a
 * deterministic generator commit the same transactions and get the same IDs. Stores made from the same settings share
 * the rule objects, so the stores call a rule at the same time, from different threads: rules must be thread-safe, and
 * a rule that keeps no state is.
 *
 * @param clock              the source of transaction times
 * @param idGenerator        the generator of entity and relationship IDs, which also says which supplied IDs the store
 *                           accepts
 * @param rules              the rules, with their code, that the store runs at commit, and again when a transaction
 *                           changes what they read
 * @param declaredRules      the descriptions of rules that the store declares without having their code: types of the
 *                           store's schema may name them, but a transaction that writes an entity or a relationship
 *                           whose type names one is rejected
 * @param expressionLanguage the language that compiles and runs the checks of the store's schema; empty if the store
 *                           has none. The constructors that leave it out give none, and so does the builder if it is
 *                           told so.
 */
public record StoreSettings(
        InstantSource clock,
        IdGenerator idGenerator,
        List<Rule> rules,
        List<RuleDescriptor> declaredRules,
        Optional<ExpressionLanguage> expressionLanguage) {

    /**
     * Rejects null components and copies the rules.
     */
    public StoreSettings {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(idGenerator, "idGenerator");
        rules = List.copyOf(rules);
        declaredRules = List.copyOf(declaredRules);
        Objects.requireNonNull(expressionLanguage, "expressionLanguage");
    }

    /**
     * Creates settings without an expression language.
     *
     * @param clock         the source of transaction times
     * @param idGenerator   the generator of entity and relationship IDs
     * @param rules         the rules, with their code, that the store runs
     * @param declaredRules the descriptions of the rules that the store declares
     */
    public StoreSettings(InstantSource clock, IdGenerator idGenerator, List<Rule> rules,
            List<RuleDescriptor> declaredRules) {
        this(clock, idGenerator, rules, declaredRules, Optional.empty());
    }

    /**
     * Creates settings without rules that have code and without an expression language.
     *
     * @param clock         the source of transaction times
     * @param idGenerator   the generator of entity and relationship IDs
     * @param declaredRules the descriptions of the rules that the store declares
     */
    public StoreSettings(InstantSource clock, IdGenerator idGenerator, List<RuleDescriptor> declaredRules) {
        this(clock, idGenerator, List.of(), declaredRules);
    }

    /**
     * The default settings: the system clock, a UUIDv7 generator, no rules, and an expression language if one can be found.
     *
     * @return the settings
     */
    public static StoreSettings defaults() {
        return builder().build();
    }

    /**
     * Starts building settings, which default to the system clock, a UUIDv7 generator, no rules, and an expression
     * language if one can be found.
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
        private ExpressionLanguage language;
        private boolean noLanguage;

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
         * Sets the generator of entity and relationship IDs.
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
         * Types of the store's schema may name them, but a transaction that writes an entity or a relationship whose
         * type, or one of whose supertypes, names one is rejected with the problem {@code RULE_UNAVAILABLE}, whatever
         * the write changes. A rule with code is described by the rule itself: do not declare it again.
         *
         * @param rules the descriptions; none by default
         * @return this builder
         */
        public Builder declaredRules(Collection<RuleDescriptor> rules) {
            Objects.requireNonNull(rules, "rules");
            this.declared.clear();
            this.declared.addAll(rules);
            return this;
        }

        /**
         * Sets the expression language that compiles and runs the checks of the store's schema (capability
         * {@code checks}), instead of looking for one.
         *
         * @param language the language
         * @return this builder
         */
        public Builder expressionLanguage(ExpressionLanguage language) {
            this.language = Objects.requireNonNull(language, "language");
            this.noLanguage = false;
            return this;
        }

        /**
         * Makes the store have no expression language, although one can be found: the store then rejects schema
         * definitions that declare checks, and writes to types that checks apply to.
         *
         * @return this builder
         */
        public Builder noExpressionLanguage() {
            this.language = null;
            this.noLanguage = true;
            return this;
        }

        /**
         * Builds the settings, loading the rules to discover if {@link #discoverRules()} was called, and looking for an
         * expression language with Java's {@link ServiceLoader} unless one was given or {@link #noExpressionLanguage()}
         * was called.
         *
         * @return the settings
         * @throws java.util.ServiceConfigurationError if a provider of rules or of an expression language cannot be
         *                                             loaded
         */
        public StoreSettings build() {
            IdGenerator generator = idGenerator != null ? idGenerator : IdGenerators.uuidV7(clock, new SecureRandom());
            List<Rule> all = new ArrayList<>(rules);
            if (discoverRules) {
                ServiceLoader.load(Rule.class).forEach(all::add);
            }
            Optional<ExpressionLanguage> expression = language != null ? Optional.of(language)
                    : noLanguage ? Optional.empty() : ExpressionLanguages.discovered();
            return new StoreSettings(clock, generator, all, declared, expression);
        }
    }
}
