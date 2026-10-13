package io.github.enricoag1982.cairn.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaTransaction;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Who and why on a schema transaction (design D6 of {@code add-agent-tools}). */
class SchemaTransactionTest {

    private static final Schema SCHEMA = SchemaRegistries.inMemory().current();
    private static final Basis BASIS = new Basis(1);
    private static final Instant TIME = Instant.parse("2026-01-05T09:00:00Z");
    private static final Origin ORIGIN = new Origin("model", "1.0.0");

    @Test
    void aSchemaTransactionWithoutASubmissionIsRecordedAsUnknown() {
        SchemaTransaction transaction = new SchemaTransaction(BASIS, TIME, ORIGIN, SCHEMA);

        assertThat(transaction.principal()).isEqualTo("@unknown");
        assertThat(transaction.onBehalfOf()).isEmpty();
        assertThat(transaction.reason()).isEmpty();
    }

    @Test
    void aSchemaTransactionKeepsWhoAndWhy() {
        SchemaTransaction transaction = new SchemaTransaction(BASIS, TIME, ORIGIN, SCHEMA, "agent:modeler",
                Optional.of("alice"), Optional.of("Contact data"));

        assertThat(transaction.principal()).isEqualTo("agent:modeler");
        assertThat(transaction.onBehalfOf()).contains("alice");
        assertThat(transaction.reason()).contains("Contact data");
    }

    @Test
    void aSchemaTransactionRejectsNullWhoAndWhy() {
        assertThatThrownBy(() -> new SchemaTransaction(BASIS, TIME, ORIGIN, SCHEMA, null, Optional.empty(),
                Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SchemaTransaction(BASIS, TIME, ORIGIN, SCHEMA, "p", null, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SchemaTransaction(BASIS, TIME, ORIGIN, SCHEMA, "p", Optional.empty(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
