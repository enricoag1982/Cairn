package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.SchemaTransaction;
import java.time.Instant;
import java.util.Objects;

/**
 * The change that a schema registration made (ADR-0029, capability {@code change-feed}): its schema transaction, as the
 * schema registry's history lists it. A registration that changes nothing uses no basis and leaves no change.
 *
 * @param registration the schema transaction, with its origin, the schema it produced, the principal, the party acted
 *                     for and the reason
 */
public record SchemaChange(SchemaTransaction registration) implements Change {

    /**
     * Rejects a null registration.
     */
    public SchemaChange {
        Objects.requireNonNull(registration, "registration");
    }

    @Override
    public Basis basis() {
        return registration.basis();
    }

    @Override
    public Instant transactionTime() {
        return registration.transactionTime();
    }
}
