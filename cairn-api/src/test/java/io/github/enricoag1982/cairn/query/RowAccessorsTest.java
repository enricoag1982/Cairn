package io.github.enricoag1982.cairn.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The typed accessors of a row (design D4 of {@code add-query-and-traversal-conveniences}): a value as its Java type, a
 * number as a decimal, and a bound entity as another type; and the exception for a cell of another kind.
 */
class RowAccessorsTest {

    private static final Instant NOW = Instant.parse("2026-03-31T00:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 3, 31);

    private static EntityState entity(String id) {
        return new EntityState(new EntityId(id), "Account", Map.of("number", Value.of("A-1")),
                EffectivePeriod.from(NOW), NOW, new Basis(1));
    }

    private static RelationshipState relationship(String id) {
        return new RelationshipState(new RelationshipId(id), "HeldBy", new EntityId("a"), new EntityId("b"), Map.of(),
                EffectivePeriod.from(NOW), NOW, new Basis(1));
    }

    /** A row with a cell of every kind of value, an entity, a relationship and an empty cell. */
    private static Row row() {
        Map<String, Cell> cells = new LinkedHashMap<>();
        cells.put("text", new Cell.ValueCell(Value.of("ACME")));
        cells.put("flag", new Cell.ValueCell(Value.of(true)));
        cells.put("small", new Cell.ValueCell(Value.of(7)));
        cells.put("big", new Cell.ValueCell(Value.of(5_000_000_000L)));
        cells.put("money", new Cell.ValueCell(Value.of(new BigDecimal("3200.50"))));
        cells.put("day", new Cell.ValueCell(Value.of(DAY)));
        cells.put("moment", new Cell.ValueCell(Value.of(NOW)));
        cells.put("account", new Cell.EntityCell(entity("1")));
        cells.put("held", new Cell.RelationshipCell(relationship("2")));
        cells.put("nothing", Cell.empty());
        return new Row(cells);
    }

    @Test
    void aValueIsReadAsItsJavaType() {
        Row row = row();

        assertThat(row.string("text")).contains("ACME");
        assertThat(row.bool("flag")).contains(true);
        assertThat(row.intValue("small")).contains(7);
        assertThat(row.longValue("big")).contains(5_000_000_000L);
        assertThat(row.decimal("money")).hasValueSatisfying(money -> assertThat(money).isEqualTo(new BigDecimal("3200.50")));
        assertThat(row.date("day")).contains(DAY);
        assertThat(row.instant("moment")).contains(NOW);
    }

    @Test
    void aNumberIsReadAsADecimalWhateverItsNumericType() {
        Row row = row();

        assertThat(row.number("small")).hasValueSatisfying(number -> assertThat(number).isEqualTo(BigDecimal.valueOf(7)));
        assertThat(row.number("big")).hasValueSatisfying(number ->
                assertThat(number).isEqualTo(new BigDecimal("5000000000")));
        assertThat(row.number("money")).hasValueSatisfying(number ->
                assertThat(number).isEqualTo(new BigDecimal("3200.50")));
    }

    @Test
    void anEmptyCellReadsAsNothingWhateverTheAccessor() {
        Row row = row();

        assertThat(row.string("nothing")).isEmpty();
        assertThat(row.bool("nothing")).isEmpty();
        assertThat(row.intValue("nothing")).isEmpty();
        assertThat(row.longValue("nothing")).isEmpty();
        assertThat(row.decimal("nothing")).isEmpty();
        assertThat(row.date("nothing")).isEmpty();
        assertThat(row.instant("nothing")).isEmpty();
        assertThat(row.number("nothing")).isEmpty();
        assertThat(row.entity("nothing", EntityState::id)).isEmpty();
    }

    @Test
    void aBoundEntityIsReadAsAnotherTypeThroughAFunction() {
        Row row = row();
        Function<EntityState, String> number = state -> ((Value.StringValue) state.values().get("number")).value();

        assertThat(row.entity("account", number)).contains("A-1");
        assertThat(row.entity("account", EntityState::id)).contains(new EntityId("1"));
        assertThat(row.entity("account", state -> (String) null)).as("a function that gives nothing: nothing").isEmpty();
        assertThatThrownBy(() -> row.entity("account", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aCellOfAnotherKindFailsNamingTheColumnAndBothKinds() {
        Row row = row();

        assertThatThrownBy(() -> row.string("small")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'small'").hasMessageContaining("an int").hasMessageContaining("a string");
        assertThatThrownBy(() -> row.bool("text")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'text'").hasMessageContaining("a string").hasMessageContaining("a boolean");
        assertThatThrownBy(() -> row.intValue("big")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'big'").hasMessageContaining("a long").hasMessageContaining("an int");
        assertThatThrownBy(() -> row.longValue("small")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'small'").hasMessageContaining("an int").hasMessageContaining("a long");
        assertThatThrownBy(() -> row.decimal("small")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'small'").hasMessageContaining("an int").hasMessageContaining("a decimal");
        assertThatThrownBy(() -> row.date("moment")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'moment'").hasMessageContaining("an instant").hasMessageContaining("a date");
        assertThatThrownBy(() -> row.instant("day")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'day'").hasMessageContaining("a date").hasMessageContaining("an instant");
        assertThatThrownBy(() -> row.number("text")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'text'").hasMessageContaining("a string").hasMessageContaining("a number");
        assertThatThrownBy(() -> row.number("flag")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a boolean");
        assertThatThrownBy(() -> row.string("account")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'account'").hasMessageContaining("EntityCell").hasMessageContaining("a string");
        assertThatThrownBy(() -> row.decimal("held")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'held'").hasMessageContaining("RelationshipCell");
        assertThatThrownBy(() -> row.entity("text", EntityState::id)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'text'").hasMessageContaining("an entity");
        assertThatThrownBy(() -> row.entity("held", EntityState::id)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'held'");
    }

    @Test
    void aColumnThatTheRowDoesNotHaveFailsNamingIt() {
        Row row = row();

        assertThatThrownBy(() -> row.string("other")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'other'");
        assertThatThrownBy(() -> row.number("other")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'other'");
        assertThatThrownBy(() -> row.entity("other", EntityState::id)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'other'");
    }

    @Test
    void theAccessorsThatAlreadyWereStayAsTheyWere() {
        Row row = row();

        assertThat(row.value("small")).isEqualTo(Optional.of(Value.of(7)));
        assertThat(row.entity("account")).isPresent();
        assertThat(row.relationship("held")).isPresent();
        assertThatThrownBy(() -> row.value("account")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EntityCell").hasMessageContaining("a value");
    }
}
