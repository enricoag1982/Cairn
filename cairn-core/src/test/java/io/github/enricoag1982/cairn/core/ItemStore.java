package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static io.github.enricoag1982.cairn.schema.Multiplicity.ZERO_OR_MORE;
import static io.github.enricoag1982.cairn.schema.ValueType.BOOLEAN;
import static io.github.enricoag1982.cairn.schema.ValueType.DATE;
import static io.github.enricoag1982.cairn.schema.ValueType.DECIMAL;
import static io.github.enricoag1982.cairn.schema.ValueType.INSTANT;
import static io.github.enricoag1982.cairn.schema.ValueType.INT;
import static io.github.enricoag1982.cairn.schema.ValueType.LONG;
import static io.github.enricoag1982.cairn.schema.ValueType.STRING;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A store with a small schema that has every value type, a trait, a subtype, aliases and a relationship type that
 * leads from items to items, and six items with a chain of relationships, for the tests of queries that the reference
 * domain cannot show: comparisons of every value type, cycles and several depths, aggregates and ordering.
 *
 * <pre>
 * i1 Item   alpha   quantity 5  serial 5000000000  price 1.50   active true   born 2020-03-01  seen 2026-02-01T10:00Z  x
 * i2 Item   Beta    quantity 7  serial 7           price 2.5    active false  born 2021-05-05  seen 2026-03-01T00:00Z  x
 * i3 Gadget gamma   quantity 3  voltage 12         price 10.00                                                         y
 * i4 Item   delta                                                                                                      y
 * i5 Item   U+FF21                                                                                                     z
 * i6 Item   U+1F600                                                                                                    z
 *
 * Next, with weights: i1 to i2 (1), i2 to i3 (2), i3 to i4 (3), i1 to i3 (4), i4 to i1 (5)
 * </pre>
 *
 * <p>Everything is effective from 1 January and recorded on 5 January. The IDs are those of the deterministic generator,
 * in the order above: {@code ReferenceStore.id(1)} is {@code i1}, and the relationships follow the items.
 */
final class ItemStore {

    static final TemporaryId I1 = new TemporaryId("i1");
    static final TemporaryId I2 = new TemporaryId("i2");
    static final TemporaryId I3 = new TemporaryId("i3");
    static final TemporaryId I4 = new TemporaryId("i4");
    static final TemporaryId I5 = new TemporaryId("i5");
    static final TemporaryId I6 = new TemporaryId("i6");

    /** A character above the surrogates in UTF-16 order, below U+1F600 in code point order. */
    static final String FULL_WIDTH_A = "Ａ";
    /** A character outside the basic multilingual plane: two UTF-16 code units, below U+FF21 in UTF-16 order. */
    static final String EMOJI = "😀";

    final ReferenceStore reference = ReferenceStore.with(definition());
    final Receipt receipt;

    ItemStore() {
        receipt = reference.commit(Transaction.by("test")
                .create(I1, "Item", from(1, 1), values("label", "alpha", "quantity", 5, "serial", 5_000_000_000L,
                        "price", money("1.50"), "active", Value.of(true), "born", LocalDate.of(2020, 3, 1),
                        "seen", Value.of(Instant.parse("2026-02-01T10:00:00Z")), "category", "x"))
                .create(I2, "Item", from(1, 1), values("label", "Beta", "quantity", 7, "serial", 7L,
                        "price", money("2.5"), "active", Value.of(false), "born", LocalDate.of(2021, 5, 5),
                        "seen", Value.of(Instant.parse("2026-03-01T00:00:00Z")), "category", "x"))
                .create(I3, "Gadget", from(1, 1), values("label", "gamma", "quantity", 3, "voltage", 12,
                        "price", money("10.00"), "category", "y"))
                .create(I4, "Item", from(1, 1), values("label", "delta", "category", "y"))
                .create(I5, "Item", from(1, 1), values("label", FULL_WIDTH_A, "category", "z"))
                .create(I6, "Item", from(1, 1), values("label", EMOJI, "category", "z"))
                .relate("Next", I1, I2, from(1, 1), values("weight", 1))
                .relate("Next", I2, I3, from(1, 1), values("weight", 2))
                .relate("Next", I3, I4, from(1, 1), values("weight", 3))
                .relate("Next", I1, I3, from(1, 1), values("weight", 4))
                .relate("Next", I4, I1, from(1, 1), values("weight", 5))
                .build());
    }

    /** The schema of the items. */
    static SchemaDefinition definition() {
        return SchemaDefinition.builder()
                .trait("Labelled", t -> t.attribute("label", STRING))
                .entityType("Thing", e -> e.abstractType().includes("Labelled"))
                .entityType("Item", e -> e.supertype("Thing").aliases("Article")
                        .attribute("quantity", INT)
                        .attribute("serial", LONG)
                        .attribute("price", DECIMAL, a -> a.aliases("cost"))
                        .attribute("active", BOOLEAN)
                        .attribute("born", DATE)
                        .attribute("seen", INSTANT)
                        .attribute("category", STRING))
                .entityType("Gadget", e -> e.supertype("Item").attribute("voltage", INT))
                .relationshipType("Next", r -> r.from("Item").to("Item")
                        .targetsPerSource(ZERO_OR_MORE).sourcesPerTarget(ZERO_OR_MORE)
                        .inverse("previous").aliases("Successor").inverseAliases("Before")
                        .attribute("weight", INT))
                .build();
    }

    /** The ID of an item. */
    EntityId id(TemporaryId item) {
        return ReferenceStore.entity(receipt, item);
    }

    /** A view effective on 1 March, as known now. */
    View view() {
        return reference.view(on(3, 1));
    }
}
