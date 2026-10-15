package io.github.enricoag1982.cairn.documents;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The one form of an instant in text that Cairn reads (capabilities {@code json-payloads} and {@code agent-tools}): the
 * date, {@code T}, the time of day with seconds and, if need be, a fraction of up to nine digits, and then {@code Z} or
 * an offset with a colon, {@code ±hh:mm}, which is converted to UTC. An example is {@code 2026-03-01T00:00:00Z}, or
 * {@code 2026-03-01T00:00:00+01:00}, which is {@code 2026-02-28T23:00:00Z}.
 *
 * <p>The form is decided by {@link #PATTERN}, which the generated JSON Schemas state too, and not by {@code java.time},
 * whose forms differ between Java versions: an offset without a colon, such as {@code +0100}, and a time without
 * seconds are no instants, whatever Java reads the text. Transaction payloads, schema documents and the arguments of
 * agent tools all read instants through this form.
 */
public final class Instants {

    /**
     * The pattern of an instant, in ECMAScript, for JSON Schemas: validators need not check {@code format}. It accepts
     * every text that {@link #read} reads, and some that it does not, such as a date or an offset that does not exist.
     */
    public static final String PATTERN = ValuesSchema.INSTANT_PATTERN;

    private Instants() {
    }

    /**
     * Reads an instant from text.
     *
     * @param text the text
     * @return the instant, in UTC; empty if the text is not of the form of {@link #PATTERN} or stands for an instant
     *         that does not exist, such as a thirteenth month
     */
    public static Optional<Instant> read(String text) {
        return Scalars.instant(Objects.requireNonNull(text, "text"));
    }
}
