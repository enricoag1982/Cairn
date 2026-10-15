package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaPlan;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.SchemaTransaction;
import io.github.enricoag1982.cairn.schema.StoredDefinition;
import io.github.enricoag1982.cairn.schema.Submission;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The schema registry of a durable store, which refuses every call once the store is closed: it checks that the store is
 * open and delegates to the registry of the store's in-memory store.
 */
final class OpenRegistry implements SchemaRegistry {

    private final SchemaRegistry delegate;
    private final Runnable requireOpen;

    /**
     * Wraps a registry.
     *
     * @param delegate    the registry that does the work
     * @param requireOpen throws an {@code IllegalStateException} if the store is closed
     */
    OpenRegistry(SchemaRegistry delegate, Runnable requireOpen) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.requireOpen = Objects.requireNonNull(requireOpen, "requireOpen");
    }

    @Override
    public RegistrationResult register(Origin origin, SchemaDefinition definition, Submission submission) {
        requireOpen.run();
        return delegate.register(origin, definition, submission);
    }

    @Override
    public SchemaPlan plan(Origin origin, SchemaDefinition definition) {
        requireOpen.run();
        return delegate.plan(origin, definition);
    }

    @Override
    public List<Problem> validate(Origin origin, SchemaDefinition definition) {
        requireOpen.run();
        return delegate.validate(origin, definition);
    }

    @Override
    public List<RuleDescriptor> availableRules() {
        requireOpen.run();
        return delegate.availableRules();
    }

    @Override
    public Optional<StoredDefinition> storedDefinition(String originName) {
        requireOpen.run();
        return delegate.storedDefinition(originName);
    }

    @Override
    public Schema current() {
        requireOpen.run();
        return delegate.current();
    }

    @Override
    public Schema asOf(Basis basis) {
        requireOpen.run();
        return delegate.asOf(basis);
    }

    @Override
    public Schema asOf(Instant transactionTime) {
        requireOpen.run();
        return delegate.asOf(transactionTime);
    }

    @Override
    public List<SchemaTransaction> history() {
        requireOpen.run();
        return delegate.history();
    }
}
