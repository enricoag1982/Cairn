package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Attribute;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A set of classifications that a reader or a writer is cleared for (ADR-0036, capability {@code attribute-access}). An
 * attribute without a classification is covered by every clearance, including {@linkplain #none() none}; an attribute
 * with one is covered only by a clearance that holds it.
 *
 * <p>A {@link View} restricted to a clearance withholds the values of the attributes that the clearance does not cover,
 * and a {@link Transaction} with a clearance cannot set or clear them. Classifications are compared exactly, as the
 * schema spells them.
 *
 * @param classifications the classifications that the clearance covers, in the order they were given; none for the
 *                        clearance that covers only attributes without a classification
 */
public record Clearance(Set<String> classifications) {

    private static final Clearance NONE = new Clearance(Set.of());

    /**
     * Rejects null components and copies the classifications, keeping their order.
     */
    public Clearance {
        classifications = Copies.set(classifications, "classifications");
    }

    /**
     * The clearance that covers no classification: only the attributes that have none.
     *
     * @return the clearance
     */
    public static Clearance none() {
        return NONE;
    }

    /**
     * A clearance that covers some classifications.
     *
     * @param classifications the classifications, for example {@code "pay"}; repeated ones count once
     * @return the clearance
     * @throws NullPointerException if the array or one of its elements is null
     */
    public static Clearance of(String... classifications) {
        Objects.requireNonNull(classifications, "classifications");
        Set<String> given = new LinkedHashSet<>();
        for (String classification : classifications) {
            given.add(Objects.requireNonNull(classification, "classifications element"));
        }
        return new Clearance(given);
    }

    /**
     * Tells whether this clearance lets its holder read and write the values of an attribute.
     *
     * @param attribute the attribute, as the schema reports it
     * @return {@code true} if the attribute has no classification, or has one that this clearance covers
     */
    public boolean covers(Attribute attribute) {
        Objects.requireNonNull(attribute, "attribute");
        return attribute.classification().map(classifications::contains).orElse(true);
    }

    /**
     * The clearance that covers what both this clearance and another one cover: a view restricted twice is restricted to
     * the intersection, never to more.
     *
     * @param other the other clearance
     * @return the classifications that both cover, in the order of this clearance
     */
    public Clearance intersect(Clearance other) {
        Objects.requireNonNull(other, "other");
        Set<String> common = new LinkedHashSet<>(classifications);
        common.retainAll(other.classifications);
        return new Clearance(common);
    }
}
