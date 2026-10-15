package io.github.enricoag1982.cairn.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The unique key, the third kind of reference (ADR-0026, design D1 of {@code add-unique-key-references}). */
class UniqueKeyTest {

    @Test
    void aUniqueKeyIsTheThirdKindOfReferenceAndNamesOnlyEntities() {
        assertThat(Ref.class.getPermittedSubclasses()).containsExactlyInAnyOrder(Id.class, TemporaryId.class,
                UniqueKey.class);
        assertThat(new UniqueKey("Account", "number", Value.of("A-1"))).isInstanceOf(Ref.class)
                .isNotInstanceOf(Id.class);
    }

    @Test
    void aUniqueKeyIsItsTypeItsAttributeAndItsValue() {
        UniqueKey key = new UniqueKey("Account", "number", Value.of("A-1"));

        assertThat(key.entityType()).isEqualTo("Account");
        assertThat(key.attribute()).isEqualTo("number");
        assertThat(key.value()).isEqualTo(Value.of("A-1"));
        assertThat(key).isEqualTo(new UniqueKey("Account", "number", Value.of("A-1")))
                .isNotEqualTo(new UniqueKey("Account", "number", Value.of("A-2")))
                .isNotEqualTo(new UniqueKey("Account", "Number", Value.of("A-1")));
        assertThat(key).hasSameHashCodeAs(new UniqueKey("Account", "number", Value.of("A-1")));
    }

    @Test
    void aUniqueKeyDoesNotCheckAgainstASchemaBecauseTheStoreDoes() {
        assertThat(new UniqueKey("", "", Value.of(1))).isNotNull();
        assertThat(new UniqueKey("Nothing", "nowhere", Value.of(true)).value()).isEqualTo(Value.of(true));
    }

    @Test
    void aUniqueKeyRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> new UniqueKey(null, "number", Value.of("A-1")));
        assertThatNullPointerException().isThrownBy(() -> new UniqueKey("Account", null, Value.of("A-1")));
        assertThatNullPointerException().isThrownBy(() -> new UniqueKey("Account", "number", null));
    }

    @Test
    void aUniqueKeyIsWrittenAsItsTypeItsAttributeAndItsValueInPlainText() {
        List<Value> values = List.of(Value.of("A-1"), Value.of(true), Value.of(7), Value.of(8L),
                Value.of(new BigDecimal("1500.50")), Value.of(LocalDate.of(2026, 3, 1)),
                Value.of(Instant.parse("2026-03-01T00:00:00Z")));
        List<String> texts = values.stream().map(value -> new UniqueKey("T", "a", value).toString()).toList();

        assertThat(texts).containsExactly("T(a: A-1)", "T(a: true)", "T(a: 7)", "T(a: 8)", "T(a: 1500.50)",
                "T(a: 2026-03-01)", "T(a: 2026-03-01T00:00:00Z)");
    }

    @Test
    void everyKindOfReferenceCanBeTakenApartByAnExhaustiveSwitch() {
        List<Ref> refs = List.of(new EntityId("e"), new RelationshipId("r"), new TemporaryId("t"),
                new UniqueKey("Account", "number", Value.of("A-1")));

        List<String> kinds = refs.stream().map(ref -> switch (ref) {
            case TemporaryId temporaryId -> "temporary";
            case EntityId id -> "entity";
            case RelationshipId id -> "relationship";
            case UniqueKey key -> "key";
        }).toList();

        assertThat(kinds).containsExactly("entity", "relationship", "temporary", "key");
        assertThat(Arrays.stream(Ref.class.getPermittedSubclasses()).count()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ what a key resolved to

    @Test
    void aKeyResolutionIsThePositionOfTheWriteTheKeyAndTheEntity() {
        UniqueKey key = new UniqueKey("Account", "number", Value.of("A-1"));
        KeyResolution resolution = new KeyResolution(2, key, new EntityId("e-1"));

        assertThat(resolution.write()).isEqualTo(2);
        assertThat(resolution.key()).isEqualTo(key);
        assertThat(resolution.entity()).isEqualTo(new EntityId("e-1"));
        assertThat(resolution).isEqualTo(new KeyResolution(2, key, new EntityId("e-1")))
                .isNotEqualTo(new KeyResolution(3, key, new EntityId("e-1")));
        assertThat(new KeyResolution(0, key, new EntityId("e-1")).write()).isZero();
    }

    @Test
    void aKeyResolutionRejectsANegativePositionAndNull() {
        UniqueKey key = new UniqueKey("Account", "number", Value.of("A-1"));

        assertThatThrownBy(() -> new KeyResolution(-1, key, new EntityId("e-1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("-1");
        assertThatNullPointerException().isThrownBy(() -> new KeyResolution(0, null, new EntityId("e-1")));
        assertThatNullPointerException().isThrownBy(() -> new KeyResolution(0, key, null));
    }

    private static Receipt receipt(List<KeyResolution> keys) {
        return new Receipt(new Basis(3), Instant.parse("2026-03-01T00:00:00Z"), Map.of(), List.of(), List.of(), keys,
                "importer", Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    void aReceiptListsTheKeyResolutionsInOrderAndCopiesThem() {
        UniqueKey key = new UniqueKey("Account", "number", Value.of("A-1"));
        List<KeyResolution> keys = new ArrayList<>(List.of(new KeyResolution(0, key, new EntityId("e-1")),
                new KeyResolution(2, key, new EntityId("e-2"))));

        Receipt receipt = receipt(keys);
        keys.clear();

        assertThat(receipt.keys()).extracting(KeyResolution::write).containsExactly(0, 2);
        assertThatThrownBy(() -> receipt.keys().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatNullPointerException().isThrownBy(() -> receipt(null));
    }

    @Test
    void aReceiptWithoutKeysIsBuiltWithoutThemAndEqualToOneWithAnEmptyList() {
        Receipt without = new Receipt(new Basis(3), Instant.parse("2026-03-01T00:00:00Z"), Map.of(), List.of(),
                List.of(), "importer", Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(without.keys()).isEmpty();
        assertThat(without).isEqualTo(receipt(List.of()));
    }
}
