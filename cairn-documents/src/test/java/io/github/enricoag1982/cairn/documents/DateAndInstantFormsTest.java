package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.document;
import static io.github.enricoag1982.cairn.documents.Documents.valid;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The reader decides the form of a date or an instant itself, with the patterns of the generated JSON Schemas, before
 * {@code java.time} reads the text. What {@code java.time} accepts differs between Java versions (an offset without a
 * colon, such as {@code +0100}, is read by some), so these tests pin the forms on every Java: none of them asks what
 * {@code java.time} would do with the text.
 */
class DateAndInstantFormsTest {

    /** Forms that are no instant, among them those that some Java versions read. */
    static Stream<String> textsThatAreNoInstants() {
        return Stream.of("2026-01-01T00:00:00+0100", "2026-01-01T00:00:00+01", "2026-01-01T00:00:00-0530",
                "2026-01-01T00:00:00+010000", "2026-01-01T00:00:00+1", "2026-01-01T00:00:00+01:0",
                "2026-01-01T00:00:00+01:00[Europe/Paris]", "2026-01-01T00:00:00 +01:00", "2026-01-01T00:00:00Z ",
                " 2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z\n", "2026-01-01T00:00:00+01:00\n", "2026-01-01T00:00Z",
                "2026-01-01T00:00:00", "2026-01-01", "2026-01-01 00:00:00Z", "2026-1-1T00:00:00Z",
                "20260101T000000Z", "2026-01-01T00:00:00.1234567890Z", "999-01-01T00:00:00Z", "yesterday", "");
    }

    /** Forms that are instants, in the offsets the pattern allows. */
    static Stream<String> textsThatAreInstants() {
        return Stream.of("2026-01-01T00:00:00Z", "2026-01-01t00:00:00z", "2026-01-01T00:00:00+01:00",
                "2026-01-01T00:00:00-05:30", "2026-01-01T00:00:00+01:00:30", "2026-01-01T00:00:00.123456789Z",
                "+12345-01-01T00:00:00Z", "-0001-01-01T00:00:00Z");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("textsThatAreNoInstants")
    void textInAFormThatIsNoInstantIsNoInstant(String text) {
        assertThat(Scalars.instant(text)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("textsThatAreInstants")
    void textInTheFormOfAnInstantIsRead(String text) {
        assertThat(Scalars.instant(text)).isPresent();
    }

    @Test
    void anOffsetIsConvertedToUtcAndOnlyWithAColon() {
        assertThat(Scalars.instant("2026-01-01T00:00:00+01:00")).contains(Instant.parse("2025-12-31T23:00:00Z"));
        assertThat(Scalars.instant("2026-01-01T00:00:00-05:30")).contains(Instant.parse("2026-01-01T05:30:00Z"));
        assertThat(Scalars.instant("2026-01-01T00:00:00+0100")).isEmpty();
        assertThat(Scalars.instant("2026-01-01T00:00:00+01")).isEmpty();
    }

    @Test
    void anInstantThatDoesNotExistIsNoInstantEvenInTheRightForm() {
        assertThat(Scalars.instant("2026-13-01T00:00:00Z")).isEmpty();
        assertThat(Scalars.instant("2026-02-30T00:00:00Z")).isEmpty();
        assertThat(Scalars.instant("2026-01-01T25:00:00Z")).isEmpty();
    }

    /** Forms that are no date. */
    static Stream<String> textsThatAreNoDates() {
        return Stream.of("2026-5-17", "2026-05-7", "20260517", "2026/05/17", "+2026-01-01", "12345-01-01",
                "2026-05-17 ", " 2026-05-17", "2026-05-17\n", "2026-05-17T00:00:00Z", "yesterday", "");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("textsThatAreNoDates")
    void textInAFormThatIsNoDateIsNoDate(String text) {
        assertThat(Scalars.date(text)).isEmpty();
    }

    @Test
    void textInTheFormOfADateIsReadIfTheDateExists() {
        assertThat(Scalars.date("2026-05-17")).contains(LocalDate.of(2026, 5, 17));
        assertThat(Scalars.date("-0001-01-01")).contains(LocalDate.of(-1, 1, 1));
        assertThat(Scalars.date("+12345-01-01")).contains(LocalDate.of(12345, 1, 1));
        assertThat(Scalars.date("2026-02-30")).isEmpty();
        assertThat(Scalars.date("2026-13-01")).isEmpty();
    }

    private static List<Value> examples(String type, String example) {
        return valid(document(
                "entityTypes:",
                "  Thing:",
                "    attributes:",
                "      value: { type: " + type + ", examples: [\"" + example + "\"] }")).definition().entityTypes()
                .getFirst().attributes().getFirst().examples();
    }

    @Test
    void aSchemaDocumentReadsOnlyTheFormsAsInstantsAndDates() {
        assertThat(examples("instant", "2026-01-01T00:00:00+01:00"))
                .containsExactly(Value.of(Instant.parse("2025-12-31T23:00:00Z")));
        assertThat(examples("instant", "2026-01-01T00:00:00+0100"))
                .as("not an instant, so a string, which validating the definition reports")
                .containsExactly(Value.of("2026-01-01T00:00:00+0100"));
        assertThat(examples("date", "2026-05-17")).containsExactly(Value.of(LocalDate.of(2026, 5, 17)));
        assertThat(examples("date", "2026-5-17")).containsExactly(Value.of("2026-5-17"));
    }
}
