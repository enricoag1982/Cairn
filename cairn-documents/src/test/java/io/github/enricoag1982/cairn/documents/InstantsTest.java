package io.github.enricoag1982.cairn.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.documents.PayloadFixtures.ReferenceDomainStore;
import java.time.Instant;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The one form of an instant in text, which payloads and the arguments of agent tools share. */
class InstantsTest {

    @Test
    void anInstantIsReadInUtcWhateverTheOffset() {
        assertThat(Instants.read("2026-03-01T00:00:00Z")).contains(Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(Instants.read("2026-03-01T00:00:00+01:00")).contains(Instant.parse("2026-02-28T23:00:00Z"));
        assertThat(Instants.read("2026-03-01T00:00:00.5-05:30")).contains(Instant.parse("2026-03-01T05:30:00.5Z"));
    }

    @Test
    void anOffsetWithoutAColonOrTimeWithoutSecondsIsNoInstant() {
        assertThat(Instants.read("2026-03-01T00:00:00+0100")).isEmpty();
        assertThat(Instants.read("2026-03-01T00:00:00+01")).isEmpty();
        assertThat(Instants.read("2026-03-01T00:00Z")).isEmpty();
        assertThat(Instants.read("2026-03-01")).isEmpty();
        assertThat(Instants.read("2026-13-01T00:00:00Z")).as("a month that does not exist").isEmpty();
    }

    @Test
    void theTextIsRequired() {
        assertThatThrownBy(() -> Instants.read(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theGeneratedPayloadSchemaStatesTheSamePattern() {
        String schema = JsonSchemas.transactions(new ReferenceDomainStore().schema());

        assertThat(schema).contains("\"pattern\": \"" + jsonString(Instants.PATTERN) + "\"");
    }

    @Test
    void everyTextThatIsReadMatchesThePattern() {
        Pattern pattern = Pattern.compile(Instants.PATTERN);

        for (String text : new String[] {"2026-03-01T00:00:00Z", "2026-03-01t00:00:00z", "2026-03-01T00:00:00+01:00",
                "2026-03-01T00:00:00.123456789-05:30", "2026-03-01T00:00:00+01:00:30", "+12345-01-01T00:00:00Z"}) {
            assertThat(Instants.read(text)).as(text).isPresent();
            assertThat(pattern.matcher(text).find()).as(text).isTrue();
        }
    }

    /** The text as it is written in a JSON string: the pattern has backslashes and no quotes. */
    private static String jsonString(String text) {
        return text.replace("\\", "\\\\");
    }
}
