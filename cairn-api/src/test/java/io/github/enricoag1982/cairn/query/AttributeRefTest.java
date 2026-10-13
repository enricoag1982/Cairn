package io.github.enricoag1982.cairn.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * The typed attribute references (design D6 of {@code add-queries-and-aggregation}): they name the value type of an
 * attribute, give conditions, columns, keys, orders and aggregates compile-time types, and build the same records as the
 * untyped builder.
 */
class AttributeRefTest {

    private static final AttributeRef<BigDecimal> BALANCE_AS_PLAIN = AttributeRef.decimal("balance");
    private static final NumericAttributeRef<BigDecimal> BALANCE = AttributeRef.decimal("balance");
    private static final OrderedAttributeRef<String> NUMBER = AttributeRef.string("number");
    private static final AttributeRef<Boolean> ACTIVE = AttributeRef.bool("active");

    @Test
    void everyFactoryNamesTheValueTypeAndKeepsTheName() {
        assertThat(AttributeRef.string("x").valueType()).isEqualTo(ValueType.STRING);
        assertThat(AttributeRef.bool("x").valueType()).isEqualTo(ValueType.BOOLEAN);
        assertThat(AttributeRef.integer("x").valueType()).isEqualTo(ValueType.INT);
        assertThat(AttributeRef.longInteger("x").valueType()).isEqualTo(ValueType.LONG);
        assertThat(AttributeRef.decimal("x").valueType()).isEqualTo(ValueType.DECIMAL);
        assertThat(AttributeRef.date("x").valueType()).isEqualTo(ValueType.DATE);
        assertThat(AttributeRef.instant("x").valueType()).isEqualTo(ValueType.INSTANT);
        assertThat(BALANCE.name()).isEqualTo("balance");
        assertThat(BALANCE).hasToString("decimal attribute balance");
        assertThatThrownBy(() -> AttributeRef.string(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void anAttributeOfABindingOffersWhatItsValueTypeAllows() {
        NumericBoundAttribute<BigDecimal> numeric = BALANCE.of("a");
        OrderedBoundAttribute<String> ordered = NUMBER.of("a");
        BoundAttribute<Boolean> plain = ACTIVE.of("a");

        assertThat(numeric).isInstanceOf(OrderedBoundAttribute.class);
        assertThat(ordered).isNotInstanceOf(NumericBoundAttribute.class);
        assertThat(plain).isNotInstanceOf(OrderedBoundAttribute.class);
        assertThat(BALANCE_AS_PLAIN.of("a")).as("the level follows the factory, not the variable's type")
                .isInstanceOf(NumericBoundAttribute.class);
        assertThat(AttributeRef.integer("x").of("a")).isInstanceOf(NumericBoundAttribute.class);
        assertThat(AttributeRef.longInteger("x").of("a")).isInstanceOf(NumericBoundAttribute.class);
        assertThat(AttributeRef.date("x").of("a")).isInstanceOf(OrderedBoundAttribute.class)
                .isNotInstanceOf(NumericBoundAttribute.class);
        assertThat(AttributeRef.instant("x").of("a")).isInstanceOf(OrderedBoundAttribute.class)
                .isNotInstanceOf(NumericBoundAttribute.class);
        assertThat(numeric.binding()).isEqualTo("a");
        assertThat(numeric.reference()).isSameAs(BALANCE);
        assertThat(numeric.operand()).isEqualTo(Operand.attribute("a", "balance"));
        assertThat(numeric).hasToString("a.balance");
        assertThatThrownBy(() -> BALANCE.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void conditionsAreTheRecordsOfTheUntypedBuilder() {
        NumericBoundAttribute<BigDecimal> balance = BALANCE.of("a");
        Operand.AttributeOf of = Operand.attribute("a", "balance");
        Operand twelveHundred = Operand.literal(new BigDecimal("1200"));

        assertThat(balance.eq(new BigDecimal("1200"))).isEqualTo(Condition.eq(of, twelveHundred));
        assertThat(balance.ne(new BigDecimal("1200"))).isEqualTo(Condition.ne(of, twelveHundred));
        assertThat(balance.lt(new BigDecimal("1200"))).isEqualTo(Condition.lt(of, twelveHundred));
        assertThat(balance.le(new BigDecimal("1200"))).isEqualTo(Condition.le(of, twelveHundred));
        assertThat(balance.gt(new BigDecimal("1200"))).isEqualTo(Condition.gt(of, twelveHundred));
        assertThat(balance.ge(new BigDecimal("1200"))).isEqualTo(Condition.ge(of, twelveHundred));
        assertThat(balance.in(List.of(new BigDecimal("1"), new BigDecimal("2"))))
                .isEqualTo(Condition.in(of, Value.of(new BigDecimal("1")), Value.of(new BigDecimal("2"))));
        assertThat(balance.hasValue()).isEqualTo(Condition.hasValue("a", "balance"));
        assertThat(balance.eq(BALANCE.of("b"))).isEqualTo(Condition.eq(of, Operand.attribute("b", "balance")));
        assertThat(balance.ne(BALANCE.of("b"))).isEqualTo(Condition.ne(of, Operand.attribute("b", "balance")));
        assertThat(balance.lt(BALANCE.of("b"))).isEqualTo(Condition.lt(of, Operand.attribute("b", "balance")));
        assertThat(balance.le(BALANCE.of("b"))).isEqualTo(Condition.le(of, Operand.attribute("b", "balance")));
        assertThat(balance.gt(BALANCE.of("b"))).isEqualTo(Condition.gt(of, Operand.attribute("b", "balance")));
        assertThat(balance.ge(BALANCE.of("b"))).isEqualTo(Condition.ge(of, Operand.attribute("b", "balance")));
    }

    @Test
    void javaValuesBecomeTheValuesOfTheirValueType() {
        assertThat(AttributeRef.string("x").of("a").eq("text"))
                .isEqualTo(Condition.eq(Operand.attribute("a", "x"), Operand.literal(Value.of("text"))));
        assertThat(ACTIVE.of("a").eq(true)).isEqualTo(Condition.eq(Operand.attribute("a", "active"),
                Operand.literal(Value.of(true))));
        assertThat(AttributeRef.integer("x").of("a").gt(5)).isEqualTo(Condition.gt(Operand.attribute("a", "x"),
                Operand.literal(Value.of(5))));
        assertThat(AttributeRef.longInteger("x").of("a").gt(5L)).isEqualTo(Condition.gt(Operand.attribute("a", "x"),
                Operand.literal(Value.of(5L))));
        assertThat(AttributeRef.date("x").of("a").ge(LocalDate.of(2026, 1, 1)))
                .isEqualTo(Condition.ge(Operand.attribute("a", "x"), Operand.literal(Value.of(LocalDate.of(2026, 1, 1)))));
        Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(AttributeRef.instant("x").of("a").lt(instant)).isEqualTo(Condition.lt(Operand.attribute("a", "x"),
                Operand.literal(Value.of(instant))));
        assertThatThrownBy(() -> NUMBER.of("a").eq((String) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void columnsKeysAndOrdersAreTheRecordsOfTheUntypedBuilder() {
        NumericBoundAttribute<BigDecimal> balance = BALANCE.of("a");

        assertThat(balance.column()).isEqualTo(Column.attribute("a", "balance"));
        assertThat(balance.column("total")).isEqualTo(Column.attribute("a", "balance", "total"));
        assertThat(balance.groupKey()).isEqualTo(GroupKey.attribute("a", "balance"));
        assertThat(balance.groupKey("total")).isEqualTo(GroupKey.attribute("a", "balance", "total"));
        assertThat(balance.ascending()).isEqualTo(Order.ascending(Column.attribute("a", "balance")));
        assertThat(balance.descending()).isEqualTo(Order.descending(Column.attribute("a", "balance")));
    }

    @Test
    void aggregatesAreTheRecordsOfTheUntypedBuilder() {
        Operand.AttributeOf of = Operand.attribute("a", "balance");

        assertThat(BALANCE.of("a").count("n")).isEqualTo(Aggregate.count(of, "n"));
        assertThat(BALANCE.of("a").sum("n")).isEqualTo(Aggregate.sum(of, "n"));
        assertThat(BALANCE.of("a").avg("n")).isEqualTo(Aggregate.avg(of, "n"));
        assertThat(BALANCE.of("a").min("n")).isEqualTo(Aggregate.min(of, "n"));
        assertThat(BALANCE.of("a").max("n")).isEqualTo(Aggregate.max(of, "n"));
        assertThat(NUMBER.of("a").min("n")).isEqualTo(Aggregate.min(Operand.attribute("a", "number"), "n"));
        assertThat(ACTIVE.of("a").count("n")).isEqualTo(Aggregate.count(Operand.attribute("a", "active"), "n"));
    }

    @Test
    void aTypedQueryIsTheSameQueryAsTheUntypedOne() {
        NumericBoundAttribute<BigDecimal> balance = BALANCE.of("a");

        Query typed = Query.from("Account", "a")
                .where(balance.gt(new BigDecimal("1200")))
                .groupBy(AttributeRef.string("currency").of("a").groupKey("currency"))
                .aggregate(balance.sum("total"), balance.max("largest"))
                .select(Column.key("currency"), Column.aggregate("total"))
                .orderBy(Order.descending(Column.aggregate("total")))
                .limit(1)
                .build();
        Query untyped = Query.from("Account", "a")
                .where(Condition.gt(Operand.attribute("a", "balance"), Operand.literal(new BigDecimal("1200"))))
                .groupBy(GroupKey.attribute("a", "currency", "currency"))
                .aggregate(Aggregate.sum(Operand.attribute("a", "balance"), "total"),
                        Aggregate.max(Operand.attribute("a", "balance"), "largest"))
                .select(Column.key("currency"), Column.aggregate("total"))
                .orderBy(Order.descending(Column.aggregate("total")))
                .limit(1)
                .build();

        assertThat(typed).isEqualTo(untyped);
        assertThat(typed.start()).isEqualTo(new Start("Account", "a", false));
        assertThat(typed.grouping()).isPresent();
        assertThat(typed.steps()).singleElement().isInstanceOf(QueryStep.Filter.class);
        assertThat(typed.limit()).isEqualTo(OptionalInt.of(1));
    }
}
