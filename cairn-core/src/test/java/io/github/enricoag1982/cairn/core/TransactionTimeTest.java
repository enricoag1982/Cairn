package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.Results.applied;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class TransactionTimeTest {

    @Test
    void transactionTimesStrictlyIncreaseEvenWithAFrozenClock() {
        Instant frozen = Instant.parse("2026-03-16T10:15:30.123456789Z");
        SchemaRegistry registry = SchemaRegistries.inMemory(InstantSource.fixed(frozen));

        var first = applied(registry.register(new io.github.enricoag1982.cairn.schema.Origin("a", "1"), small("A")));
        var second = applied(registry.register(new io.github.enricoag1982.cairn.schema.Origin("b", "1"), small("B")));
        var third = applied(registry.register(new io.github.enricoag1982.cairn.schema.Origin("c", "1"), small("C")));

        assertThat(first.transactionTime()).isEqualTo(frozen.truncatedTo(ChronoUnit.MICROS));
        assertThat(second.transactionTime()).isEqualTo(first.transactionTime().plus(1, ChronoUnit.MICROS));
        assertThat(third.transactionTime()).isEqualTo(second.transactionTime().plus(1, ChronoUnit.MICROS));
        assertThat(first.basis()).isEqualTo(new Basis(1));
        assertThat(second.basis()).isEqualTo(new Basis(2));
        assertThat(third.basis()).isEqualTo(new Basis(3));
    }

    @Test
    void transactionTimesHaveMicrosecondPrecision() {
        Instant now = Instant.parse("2026-03-16T10:15:30.987654321Z");
        SchemaRegistry registry = SchemaRegistries.inMemory(InstantSource.fixed(now));

        Instant time = applied(registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .transactionTime();

        assertThat(time).isEqualTo(Instant.parse("2026-03-16T10:15:30.987654Z"));
    }

    private static SchemaDefinition small(String name) {
        return SchemaDefinition.builder().entityType(name).build();
    }
}
