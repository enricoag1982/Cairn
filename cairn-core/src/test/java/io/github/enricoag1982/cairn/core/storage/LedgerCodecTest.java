package io.github.enricoag1982.cairn.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.kv.InMemoryKeyValueStore;
import io.github.enricoag1982.cairn.core.kv.KeyRange;
import io.github.enricoag1982.cairn.core.kv.KeyValueStore;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The key layout of the ledger (ADR-0027, design D2 of {@code refactor-storage-onto-key-value-store}): a byte for the
 * kind of key, then parts that make every range of interest a prefix, in an order that is the order of the bases, the
 * instants and the schema identifiers.
 */
class LedgerCodecTest {

    private static final AttributeId NUMBER = new AttributeId(7);
    private static final TypeId ACCOUNT = new TypeId(11);
    private static final Instant JAN_1 = Instant.parse("2026-01-01T00:00:00Z");

    private static boolean before(byte[] first, byte[] second) {
        return Arrays.compareUnsigned(first, second) < 0;
    }

    @Test
    void everyKindOfKeyStartsWithItsOwnByte() {
        Basis basis = new Basis(3);

        assertThat(LedgerCodec.kindOf(LedgerCodec.currentKey("a1"))).isEqualTo(LedgerCodec.Kind.CURRENT);
        assertThat(LedgerCodec.kindOf(LedgerCodec.rowKey("a1", basis, JAN_1))).isEqualTo(LedgerCodec.Kind.ROW);
        assertThat(LedgerCodec.kindOf(LedgerCodec.supersededKey("a1", basis, JAN_1)))
                .isEqualTo(LedgerCodec.Kind.SUPERSEDED);
        assertThat(LedgerCodec.kindOf(LedgerCodec.typeKey(ACCOUNT, new EntityId("a1"))))
                .isEqualTo(LedgerCodec.Kind.TYPE);
        assertThat(LedgerCodec.kindOf(LedgerCodec.endKey("a1", ACCOUNT, true, "r1"))).isEqualTo(LedgerCodec.Kind.END);
        assertThat(LedgerCodec.kindOf(LedgerCodec.uniqueKey(NUMBER, Value.of("A-1"), "a1")))
                .isEqualTo(LedgerCodec.Kind.UNIQUE);
        assertThat(Arrays.stream(LedgerCodec.Kind.values()).map(LedgerCodec.Kind::prefix).distinct()).hasSize(6);
    }

    @Test
    void theRowsOfOneEntityAreInTheOrderOfTheirInsertingBasisAndThenOfTheirStart() {
        Instant early = Instant.parse("1969-06-01T00:00:00Z");
        Instant epoch = Instant.EPOCH;
        Instant late = Instant.parse("2026-03-01T00:00:00.000000123Z");

        List<byte[]> inOrder = List.of(
                LedgerCodec.rowKey("a1", new Basis(1), early),
                LedgerCodec.rowKey("a1", new Basis(1), epoch),
                LedgerCodec.rowKey("a1", new Basis(1), late),
                LedgerCodec.rowKey("a1", new Basis(2), early),
                LedgerCodec.rowKey("a1", new Basis(256), early));

        for (int i = 0; i + 1 < inOrder.size(); i++) {
            assertThat(before(inOrder.get(i), inOrder.get(i + 1))).as("key %d before key %d", i, i + 1).isTrue();
        }
        assertThat(before(LedgerCodec.rowKey("a1", new Basis(1), Instant.MIN),
                LedgerCodec.rowKey("a1", new Basis(1), early))).as("the earliest instant").isTrue();
        assertThat(before(LedgerCodec.rowKey("a1", new Basis(1), Instant.MAX),
                LedgerCodec.rowKey("a1", new Basis(2), Instant.MIN))).as("an open end of time").isTrue();
    }

    @Test
    void aRowKeyReadsBackAndAMarkerHasTheKeyOfItsRowWithTheKindOfAMarker() {
        Instant start = Instant.parse("2026-01-05T09:00:00.000001Z");

        byte[] row = LedgerCodec.rowKey("e-1", new Basis(42), start);
        byte[] marker = LedgerCodec.supersededKey("e-1", new Basis(42), start);

        assertThat(LedgerCodec.parseRowKey(row)).isEqualTo(new LedgerCodec.RowKey("e-1", new Basis(42), start));
        assertThat(LedgerCodec.parseRowKey(marker)).isEqualTo(new LedgerCodec.RowKey("e-1", new Basis(42), start));
        assertThat(Arrays.copyOfRange(marker, 1, marker.length)).containsExactly(Arrays.copyOfRange(row, 1,
                row.length));
        assertThat(LedgerCodec.idOfCurrent(LedgerCodec.currentKey("e-1"))).isEqualTo("e-1");
    }

    @Test
    void theRangeOfTheRowsOfAnEntityHoldsTheRowsInsertedAtOrBelowABasisOfThatEntityOnly() {
        KeyValueStore store = new InMemoryKeyValueStore();
        store.apply(new io.github.enricoag1982.cairn.core.kv.Batch()
                .put(LedgerCodec.rowKey("a", new Basis(1), JAN_1), new byte[] {1})
                .put(LedgerCodec.rowKey("a", new Basis(3), JAN_1), new byte[] {1})
                .put(LedgerCodec.rowKey("a", new Basis(4), JAN_1), new byte[] {1})
                .put(LedgerCodec.rowKey("ab", new Basis(1), JAN_1), new byte[] {1})
                .put(LedgerCodec.rowKey("b", new Basis(1), JAN_1), new byte[] {1})
                .put(LedgerCodec.supersededKey("a", new Basis(1), JAN_1), new byte[] {1}));

        assertThat(inserted(store, LedgerCodec.rows("a", new Basis(0)))).isEmpty();
        assertThat(inserted(store, LedgerCodec.rows("a", new Basis(1)))).containsExactly(1L);
        assertThat(inserted(store, LedgerCodec.rows("a", new Basis(3)))).containsExactly(1L, 3L);
        assertThat(inserted(store, LedgerCodec.rows("a", new Basis(Long.MAX_VALUE)))).containsExactly(1L, 3L, 4L);
        assertThat(inserted(store, LedgerCodec.markers("a", new Basis(Long.MAX_VALUE)))).containsExactly(1L);
        assertThat(inserted(store, LedgerCodec.rows("ab", new Basis(Long.MAX_VALUE)))).containsExactly(1L);
    }

    private static List<Long> inserted(KeyValueStore store, KeyRange range) {
        try (var entries = store.scan(range)) {
            return entries.map(entry -> LedgerCodec.parseRowKey(entry.key()).inserted().value()).toList();
        }
    }

    @Test
    void identifiersThatStartTheSameAreNotInEachOthersRanges() {
        byte[] longer = LedgerCodec.endKey("alpha-2", ACCOUNT, true, "r1");

        assertThat(LedgerCodec.endKeys("alpha").contains(longer)).isFalse();
        assertThat(LedgerCodec.endKeys("alpha-2").contains(longer)).isTrue();
        assertThat(LedgerCodec.typeKeys(ACCOUNT).contains(LedgerCodec.typeKey(ACCOUNT, new EntityId("alpha")))).isTrue();
        assertThat(LedgerCodec.typeKeys(new TypeId(12)).contains(LedgerCodec.typeKey(ACCOUNT, new EntityId("alpha"))))
                .isFalse();
    }

    @Test
    void theEndsOfAnEntityAreInARangeThatNarrowsByTypeAndDirection() {
        TypeId owns = new TypeId(20);
        byte[] forward = LedgerCodec.endKey("acme", owns, true, "r1");
        byte[] backward = LedgerCodec.endKey("acme", owns, false, "r2");
        byte[] otherType = LedgerCodec.endKey("acme", new TypeId(21), true, "r3");
        byte[] otherEntity = LedgerCodec.endKey("beta", owns, true, "r1");

        assertThat(LedgerCodec.endKeys("acme").contains(forward)).isTrue();
        assertThat(LedgerCodec.endKeys("acme").contains(backward)).isTrue();
        assertThat(LedgerCodec.endKeys("acme").contains(otherType)).isTrue();
        assertThat(LedgerCodec.endKeys("acme").contains(otherEntity)).isFalse();
        assertThat(LedgerCodec.endKeys("acme", owns, true).contains(forward)).isTrue();
        assertThat(LedgerCodec.endKeys("acme", owns, true).contains(backward)).isFalse();
        assertThat(LedgerCodec.endKeys("acme", owns, false).contains(backward)).isTrue();
        assertThat(LedgerCodec.endKeys("acme", owns, true).contains(otherType)).isFalse();
        assertThat(LedgerCodec.parseEndKey(backward)).isEqualTo(new LedgerCodec.EndKey("acme", owns, false, "r2"));
        assertThat(LedgerCodec.parseEndKey(forward).forward()).isTrue();
    }

    @Test
    void aTypeKeyAndAUniqueKeyNameTheEntityTheyHold() {
        assertThat(LedgerCodec.entityOfTypeKey(LedgerCodec.typeKey(ACCOUNT, new EntityId("a-1")))).isEqualTo("a-1");
        assertThat(LedgerCodec.idOfUniqueKey(LedgerCodec.uniqueKey(NUMBER, Value.of("A-1"), "a-1"))).isEqualTo("a-1");
    }

    @Test
    void numbersThatAreEqualHaveOneUniqueKeyWhateverTheirNumericType() {
        byte[] whole = LedgerCodec.uniqueKey(NUMBER, Value.of(5), "e");

        assertThat(LedgerCodec.uniqueKey(NUMBER, Value.of(5L), "e")).containsExactly(whole);
        assertThat(LedgerCodec.uniqueKey(NUMBER, Value.of(new BigDecimal("5")), "e")).containsExactly(whole);
        assertThat(LedgerCodec.uniqueKey(NUMBER, Value.of(new BigDecimal("5.000")), "e")).containsExactly(whole);
        assertThat(LedgerCodec.uniqueKey(NUMBER, Value.of(new BigDecimal("50")), "e"))
                .containsExactly(LedgerCodec.uniqueKey(NUMBER, Value.of(new BigDecimal("5.0E+1")), "e"))
                .isNotEqualTo(whole);
        assertThat(LedgerCodec.uniqueKey(NUMBER, Value.of(0), "e"))
                .containsExactly(LedgerCodec.uniqueKey(NUMBER, Value.of(new BigDecimal("0.00")), "e"));
        assertThat(LedgerCodec.uniqueKey(NUMBER, Value.of(new BigDecimal("5.5")), "e")).isNotEqualTo(whole);
        assertThat(LedgerCodec.uniqueKey(NUMBER, Value.of("5"), "e")).as("a string is not a number")
                .isNotEqualTo(whole);
        assertThat(LedgerCodec.uniqueKeys(NUMBER, Value.of(5L)).contains(whole)).isTrue();
    }

    @Test
    void valuesOfOtherTypesAreTheirOwnUniqueKeys() {
        LocalDate day = LocalDate.of(2026, 1, 1);

        assertThat(LedgerCodec.uniqueKey(NUMBER, Value.of(day), "e"))
                .containsExactly(LedgerCodec.uniqueKey(NUMBER, Value.of(LocalDate.of(2026, 1, 1)), "e"))
                .isNotEqualTo(LedgerCodec.uniqueKey(NUMBER, Value.of(day.plusDays(1)), "e"));
        assertThat(LedgerCodec.uniqueKey(NUMBER, Value.of(JAN_1), "e"))
                .isNotEqualTo(LedgerCodec.uniqueKey(NUMBER, Value.of(JAN_1.plusNanos(1)), "e"));
        assertThat(LedgerCodec.uniqueKey(NUMBER, Value.of(true), "e"))
                .isNotEqualTo(LedgerCodec.uniqueKey(NUMBER, Value.of(false), "e"));
    }

    @Test
    void theUniqueKeysOfAValueDoNotIncludeTheKeysOfAnotherValueThatStartsTheSame() {
        byte[] other = LedgerCodec.uniqueKey(NUMBER, Value.of("A-10"), "e");

        assertThat(LedgerCodec.uniqueKeys(NUMBER, Value.of("A-1")).contains(other)).isFalse();
        assertThat(LedgerCodec.uniqueKeys(NUMBER, Value.of("A-10")).contains(other)).isTrue();
        assertThat(LedgerCodec.uniqueKeys(new AttributeId(8), Value.of("A-10")).contains(other)).isFalse();
    }

    @Test
    void anIdentifierWithAnUnpairedSurrogateIsReadBackAsItWasWritten() {
        String odd = "a\uD800b";

        assertThat(LedgerCodec.idOfCurrent(LedgerCodec.currentKey(odd))).isEqualTo(odd);
        assertThat(LedgerCodec.parseRowKey(LedgerCodec.rowKey(odd, new Basis(1), JAN_1)).id()).isEqualTo(odd);
        assertThat(LedgerCodec.entityOfTypeKey(LedgerCodec.typeKey(ACCOUNT, new EntityId(odd)))).isEqualTo(odd);
    }

    @Test
    void theCurrentValueOfAnEntityReadsBackWithTheBasisThatInsertedEachSegment() {
        SegmentData first = new SegmentData(JAN_1, Instant.parse("2026-03-15T00:00:00Z"),
                Map.of(new AttributeId(1), Value.of("A-1"), new AttributeId(2), Value.of(new BigDecimal("1000.00"))),
                Instant.parse("2026-01-05T09:00:00Z"), new Basis(2));
        SegmentData second = new SegmentData(Instant.parse("2026-03-15T00:00:00Z"), Instant.MAX,
                Map.of(new AttributeId(1), Value.of("A-1")), Instant.parse("2026-03-16T09:00:00.000000007Z"),
                new Basis(9));
        LedgerCodec.CurrentValue current = new LedgerCodec.CurrentValue(false, ACCOUNT, Optional.empty(),
                Optional.empty(), new Basis(9), List.of(new LedgerCodec.Placed(new Basis(9), first),
                        new LedgerCodec.Placed(new Basis(9), second)));

        byte[] value = LedgerCodec.encodeCurrent(current);

        assertThat(LedgerCodec.decodeCurrent(value)).isEqualTo(current);
        assertThat(LedgerCodec.isRelationship(value)).isFalse();
    }

    @Test
    void theCurrentValueOfARelationshipHoldsItsEnds() {
        LedgerCodec.CurrentValue current = new LedgerCodec.CurrentValue(true, new TypeId(20),
                Optional.of(new EntityId("acme")), Optional.of(new EntityId("beta")), new Basis(4), List.of());

        byte[] value = LedgerCodec.encodeCurrent(current);

        assertThat(LedgerCodec.decodeCurrent(value)).isEqualTo(current);
        assertThat(LedgerCodec.isRelationship(value)).isTrue();
    }

    @Test
    void aRowHoldsASegmentWithoutItsStartAndAnIndexEntryHoldsABasis() {
        SegmentData segment = new SegmentData(JAN_1, Instant.parse("2026-03-15T00:00:00Z"),
                Map.of(new AttributeId(2), Value.of(new BigDecimal("1000.00"))),
                Instant.parse("2026-01-05T09:00:00Z"), new Basis(2));

        byte[] row = LedgerCodec.encodeRow(segment);

        assertThat(LedgerCodec.decodeRow(JAN_1, row)).isEqualTo(segment);
        assertThat(LedgerCodec.decodeRow(JAN_1.minusSeconds(5), row).from()).isEqualTo(JAN_1.minusSeconds(5));
        assertThat(LedgerCodec.decodeBasis(LedgerCodec.encodeBasis(new Basis(123456789012L))))
                .isEqualTo(new Basis(123456789012L));
        assertThat(LedgerCodec.encodeBasis(new Basis(1))).hasSize(8);
    }

    @Test
    void aValueThatTheLogWritesHasTheBytesThatTheRowHoldsAfterTheStart() {
        SegmentData segment = new SegmentData(JAN_1, Instant.parse("2026-03-15T00:00:00Z"),
                Map.of(new AttributeId(2), Value.of(new BigDecimal("1000.00")), new AttributeId(1), Value.of("x")),
                Instant.parse("2026-01-05T09:00:00Z"), new Basis(2));
        Encoder log = Encoder.plain();
        EffectCodec.writeSegment(log, segment);
        byte[] row = LedgerCodec.encodeRow(segment);

        byte[] logged = log.toByteArray();
        byte[] afterTheStart = Arrays.copyOfRange(logged, logged.length - row.length, logged.length);

        assertThat(afterTheStart).as("segments are encoded once").containsExactly(row);
    }
}
