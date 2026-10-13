package io.github.enricoag1982.cairn.example.hr;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The IDs that a store gave to the entities and relationships the stories name. A story names what it creates by
 * temporary ID, such as {@code e004} for Dev's employment; the receipt of its transaction maps the name to the ID, and
 * {@link #record(Receipt)} keeps it, so that later stories can refer to the thing by the same name.
 */
public final class Ids {

    private final Map<String, Id> byName = new HashMap<>();

    /**
     * Creates an empty set of IDs.
     */
    public Ids() {
    }

    /**
     * Keeps the IDs that a receipt gives to the temporary IDs of its transaction.
     *
     * @param receipt the receipt
     * @return this set
     * @throws IllegalArgumentException if a name was recorded already: names are unique in a company
     */
    public Ids record(Receipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        for (Map.Entry<TemporaryId, Id> entry : receipt.temporaryIds().entrySet()) {
            Id previous = byName.putIfAbsent(entry.getKey().name(), entry.getValue());
            if (previous != null) {
                throw new IllegalArgumentException("The name " + entry.getKey() + " names " + previous + " already.");
            }
        }
        return this;
    }

    /**
     * The ID of a named entity.
     *
     * @param name the temporary ID the story gave it, without the {@code $}
     * @return the ID
     * @throws IllegalArgumentException if no entity has the name
     */
    public EntityId entity(String name) {
        if (byName.get(Objects.requireNonNull(name, "name")) instanceof EntityId id) {
            return id;
        }
        throw new IllegalArgumentException("No entity is named " + name + ".");
    }

    /**
     * The ID of a named relationship.
     *
     * @param name the temporary ID the story gave it, without the {@code $}
     * @return the ID
     * @throws IllegalArgumentException if no relationship has the name
     */
    public RelationshipId relationship(String name) {
        if (byName.get(Objects.requireNonNull(name, "name")) instanceof RelationshipId id) {
            return id;
        }
        throw new IllegalArgumentException("No relationship is named " + name + ".");
    }
}
