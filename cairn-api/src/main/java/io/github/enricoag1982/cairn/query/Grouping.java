package io.github.enricoag1982.cairn.query;

import java.util.List;

/**
 * How a query groups its rows and what it computes for each group (ADR-0023, capability {@code queries}). A grouped
 * query returns one row per group, with a column for each key and each aggregate.
 *
 * <p>Without keys, all rows form one group, which exists even when there are no rows. With keys, a group exists only for
 * keys that rows have.
 *
 * @param keys       the keys, in order; the groups are ordered by them after the query's order
 * @param aggregates the aggregates
 */
public record Grouping(List<GroupKey> keys, List<Aggregate> aggregates) {

    /**
     * Copies the lists, rejecting nulls.
     */
    public Grouping {
        keys = List.copyOf(keys);
        aggregates = List.copyOf(aggregates);
    }
}
