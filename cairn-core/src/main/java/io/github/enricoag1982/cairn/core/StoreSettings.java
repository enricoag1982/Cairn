package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import java.security.SecureRandom;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * What a store is created with (design D2 of {@code add-entity-versions}): the clock that gives transaction times, the
 * generator of entity IDs, and the rules that the store's schema may name.
 *
 * <p>The settings are plain data and can be used for several stores. A store takes a {@linkplain IdGenerator#copy()
 * copy} of the generator, so stores made from the same settings do not share its state: two stores with a
 * deterministic generator commit the same transactions and get the same IDs.
 *
 * @param clock          the source of transaction times
 * @param idGenerator    the generator of entity IDs, which also says which supplied IDs the store accepts
 * @param availableRules the rules that types of the store's schema may name; the store's registry lists them
 */
public record StoreSettings(InstantSource clock, IdGenerator idGenerator, List<RuleDescriptor> availableRules) {

    /**
     * Rejects null components and copies the rules.
     */
    public StoreSettings {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(idGenerator, "idGenerator");
        availableRules = List.copyOf(availableRules);
    }

    /**
     * The default settings: the system clock, a UUIDv7 generator, and no available rules.
     *
     * @return the settings
     */
    public static StoreSettings defaults() {
        return builder().build();
    }

    /**
     * Starts building settings, which default to the system clock, a UUIDv7 generator and no available rules.
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
        private final List<RuleDescriptor> rules = new ArrayList<>();

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
         * Sets the rules that types of the store's schema may name, replacing any set before.
         *
         * @param rules the rules; none by default
         * @return this builder
         */
        public Builder availableRules(Collection<RuleDescriptor> rules) {
            Objects.requireNonNull(rules, "rules");
            this.rules.clear();
            this.rules.addAll(rules);
            return this;
        }

        /**
         * Builds the settings.
         *
         * @return the settings
         */
        public StoreSettings build() {
            IdGenerator generator = idGenerator != null ? idGenerator : IdGenerators.uuidV7(clock, new SecureRandom());
            return new StoreSettings(clock, generator, rules);
        }
    }
}
