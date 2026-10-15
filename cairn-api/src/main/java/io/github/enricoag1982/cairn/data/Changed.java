package io.github.enricoag1982.cairn.data;

import java.util.List;
import java.util.Objects;

/**
 * An entity or relationship that a data transaction changed (ADR-0029, capability {@code change-feed}): its ID, the name
 * of its type, whether the transaction created it, and the effective periods in which a view at the transaction's basis
 * and a view at the basis before read different versions of it, absent included. A write that leaves a period's values
 * as they were records no new version there, so that period is not changed. Versions are compared by their values and the
 * transaction time and basis that recorded them, not by their bounds (ADR-0029). The change names no values: read them
 * through those two views.
 *
 * @param id      the ID of the entity or relationship: an {@link EntityId} or a {@link RelationshipId}
 * @param type    the name of its type in the schema at the transaction's basis; a consumer that compares names across a
 *                rename follows the schema history
 * @param created whether the transaction created it
 * @param periods the effective periods in which it changed, ordered, not overlapping and not touching; at least one,
 *                and only the last can be open
 */
public record Changed(Id id, String type, boolean created, List<EffectivePeriod> periods) {

    /**
     * Rejects null components and a blank type, copies the periods, and checks that there is at least one, that each
     * ends after it starts, and that they are in order, neither overlapping nor touching.
     *
     * @throws IllegalArgumentException if the type is blank, there is no period, or the periods are not as described
     */
    public Changed {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        if (type.isBlank()) {
            throw new IllegalArgumentException("The type of a changed entity or relationship is not blank.");
        }
        periods = List.copyOf(periods);
        if (periods.isEmpty()) {
            throw new IllegalArgumentException("A changed entity or relationship changed in at least one period.");
        }
        for (int i = 0; i < periods.size(); i++) {
            EffectivePeriod period = periods.get(i);
            if (!period.endsAfterStart()) {
                throw new IllegalArgumentException("The period " + period + " does not end after it starts.");
            }
            if (i > 0) {
                EffectivePeriod previous = periods.get(i - 1);
                if (previous.until().isEmpty() || !previous.until().get().isBefore(period.from())) {
                    throw new IllegalArgumentException("The periods " + previous + " and " + period
                            + " are not in order, or overlap or touch.");
                }
            }
        }
    }
}
