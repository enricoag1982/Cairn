package io.github.enricoag1982.cairn.core.kv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The reference implementation of the ordered key-value interface (ADR-0011, ADR-0027, design D1 of
 * {@code refactor-storage-onto-key-value-store}): keys are compared as unsigned bytes, a range is [from, to), and a
 * batch applies its puts and deletes in order.
 */
class InMemoryKeyValueStoreTest {

    private final InMemoryKeyValueStore store = new InMemoryKeyValueStore();

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    private static List<List<Integer>> keys(Stream<KeyValueStore.Entry> entries) {
        try (entries) {
            return entries.map(entry -> {
                List<Integer> key = new java.util.ArrayList<>();
                for (byte value : entry.key()) {
                    key.add(value & 0xFF);
                }
                return key;
            }).toList();
        }
    }

    private void put(int... key) {
        store.apply(new Batch().put(bytes(key), bytes(1)));
    }

    @Test
    void aMissingKeyHasNoValueAndAPutKeyHasOne() {
        assertThat(store.get(bytes(1, 2))).isEmpty();

        store.apply(new Batch().put(bytes(1, 2), bytes(9)));

        assertThat(store.get(bytes(1, 2))).hasValueSatisfying(value -> assertThat(value).containsExactly(9));
        assertThat(store.get(bytes(1))).as("a prefix of a key is another key").isEmpty();
    }

    @Test
    void keysAreComparedAsUnsignedBytes() {
        put(0xFF);
        put(0x80);
        put(0x7F);
        put(0x00);
        put(0x7F, 0x00);

        assertThat(keys(store.scan(KeyRange.all()))).containsExactly(List.of(0x00), List.of(0x7F),
                List.of(0x7F, 0x00), List.of(0x80), List.of(0xFF));
    }

    @Test
    void aRangeStartsAtItsFirstKeyAndEndsBeforeItsLast() {
        put(1);
        put(2);
        put(3);
        put(4);

        assertThat(keys(store.scan(bytes(2), bytes(4)))).containsExactly(List.of(2), List.of(3));
        assertThat(keys(store.scan(bytes(2), null))).containsExactly(List.of(2), List.of(3), List.of(4));
        assertThat(keys(store.scan(bytes(3), bytes(3)))).as("an empty range").isEmpty();
        assertThat(keys(store.scan(bytes(4), bytes(2)))).as("a range that ends before it starts").isEmpty();
        assertThat(keys(store.scan(bytes(5), bytes(9)))).isEmpty();
    }

    @Test
    void aPrefixRangeHoldsTheKeysThatStartWithThePrefix() {
        put(1, 2);
        put(1, 2, 0);
        put(1, 2, 0xFF, 0xFF);
        put(1, 3);
        put(1, 1, 0xFF);
        put(2);

        assertThat(keys(store.scan(KeyRange.prefix(bytes(1, 2))))).containsExactly(List.of(1, 2),
                List.of(1, 2, 0), List.of(1, 2, 0xFF, 0xFF));
        assertThat(keys(store.scan(KeyRange.prefix(bytes(1))))).hasSize(5);
        assertThat(keys(store.scan(KeyRange.prefix(bytes(1, 0xFF))))).isEmpty();
    }

    @Test
    void aPrefixOfNothingButMaximumBytesHasNoEnd() {
        put(0xFF);
        put(0xFF, 0xFF, 5);
        put(0xFE);

        KeyRange range = KeyRange.prefix(bytes(0xFF, 0xFF));

        assertThat(range.to()).isNull();
        assertThat(keys(store.scan(range))).containsExactly(List.of(0xFF, 0xFF, 5));
        assertThat(KeyRange.prefix(bytes(1, 0xFF)).to()).containsExactly(2);
        assertThat(KeyRange.prefix(bytes(0xFE, 0xFF, 0xFF)).to()).containsExactly(0xFF);
    }

    @Test
    void aRangeKnowsWhichKeysItHolds() {
        KeyRange range = KeyRange.between(bytes(2), bytes(4));

        assertThat(range.contains(bytes(1, 0xFF))).isFalse();
        assertThat(range.contains(bytes(2))).isTrue();
        assertThat(range.contains(bytes(3, 7))).isTrue();
        assertThat(range.contains(bytes(4))).isFalse();
        assertThat(KeyRange.all().contains(bytes())).isTrue();
        assertThat(KeyRange.all().contains(bytes(0xFF, 0xFF))).isTrue();
    }

    @Test
    void aBatchAppliesItsOperationsInOrder() {
        store.apply(new Batch()
                .put(bytes(1), bytes(1))
                .delete(bytes(1))
                .put(bytes(2), bytes(1))
                .put(bytes(2), bytes(2))
                .put(bytes(3), bytes(1))
                .delete(bytes(3))
                .put(bytes(3), bytes(3)));

        assertThat(store.get(bytes(1))).isEmpty();
        assertThat(store.get(bytes(2))).hasValueSatisfying(value -> assertThat(value).containsExactly(2));
        assertThat(store.get(bytes(3))).hasValueSatisfying(value -> assertThat(value).containsExactly(3));
    }

    @Test
    void deletingAMissingKeyChangesNothing() {
        put(1);

        store.apply(new Batch().delete(bytes(2)));

        assertThat(keys(store.scan(KeyRange.all()))).containsExactly(List.of(1));
    }

    @Test
    void anEmptyBatchChangesNothing() {
        put(1);
        Batch empty = new Batch();

        store.apply(empty);

        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.size()).isZero();
        assertThat(keys(store.scan(KeyRange.all()))).containsExactly(List.of(1));
    }

    @Test
    void aBatchCopiesTheArraysItIsGivenSoTheCallerCannotChangeWhatTheStoreHolds() {
        byte[] key = bytes(1, 2);
        byte[] value = bytes(7);
        Batch batch = new Batch().put(key, value);

        key[0] = 9;
        value[0] = 9;
        store.apply(batch);

        assertThat(store.get(bytes(1, 2))).hasValueSatisfying(held -> assertThat(held).containsExactly(7));
        assertThat(store.get(bytes(9, 2))).isEmpty();
        assertThat(batch.operations()).singleElement().isInstanceOf(Batch.Put.class);
        assertThatThrownBy(() -> batch.operations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRangeCopiesItsBoundsToo() {
        byte[] from = bytes(1);
        KeyRange range = KeyRange.between(from, bytes(5));

        from[0] = 4;

        assertThat(range.from()).containsExactly(1);
    }

    @Test
    void aReaderThatStartsAfterABatchSeesAllOfIt() {
        store.apply(new Batch().put(bytes(1), bytes(1)).put(bytes(2), bytes(1)).put(bytes(3), bytes(1)));

        assertThat(keys(store.scan(KeyRange.all()))).containsExactly(List.of(1), List.of(2), List.of(3));
    }

    @Test
    void aScanOfAKeyRangeThatCrossesAPrefixBoundaryKeepsTheOrderOfTheKeys() {
        put(1, 0xFF);
        put(2, 0);
        put(1, 0xFF, 0xFF);

        assertThat(keys(store.scan(KeyRange.between(bytes(1), bytes(2, 0)))))
                .containsExactly(List.of(1, 0xFF), List.of(1, 0xFF, 0xFF));
    }
}
