package io.github.enricoag1982.cairn.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.schema.Value;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@link ReferenceStories} tells the stories of the table in {@code docs/reference/reference-domain.md}:
 * for each story the fixture builds (RD-1 to RD-5, RD-7 to RD-9, RD-12 and RD-13), the page's row names the recording
 * dates in the order of the fixture's transactions, and states every effective date and value that the fixture writes,
 * such as "Recorded 16 March", "15 March", "1,500" and the LEI. RD-8 gives no recording date, so its row is checked for
 * what it writes only.
 */
class ReferenceStoriesTest {

    private static final Path PAGE = Path.of("").toAbsolutePath().getParent()
            .resolve("docs/reference/reference-domain.md");
    /** The page says "Recorded", then a date: the day and the month, and the year if it is not 2026. */
    private static final Pattern RECORDED = Pattern.compile("Recorded (\\d{1,2} [A-Z][a-z]+(?: \\d{4})?)");
    private static final Pattern DATE_WITHOUT_YEAR = Pattern.compile("\\d{1,2} [A-Z][a-z]+");
    /** The year in which the stories happen, unless a story says otherwise. */
    private static final int PAGE_YEAR = 2026;

    /** The story column of the story table, by story ID. */
    private static Map<String, String> rows;

    @BeforeAll
    static void readPage() throws IOException {
        rows = new LinkedHashMap<>();
        for (String line : Files.readAllLines(PAGE)) {
            if (line.startsWith("| RD-")) {
                List<String> cells = Arrays.stream(line.substring(1, line.lastIndexOf('|')).split("\\|"))
                        .map(String::strip).toList();
                rows.put(cells.get(0), cells.get(1));
            }
        }
    }

    @Test
    void rd1MatchesThePage() {
        assertTold("RD-1", ReferenceStories.rd1Parties());
    }

    @Test
    void rd2MatchesThePage() {
        assertTold("RD-2", ReferenceStories.rd2Ownership(ReferenceStories.ACME, ReferenceStories.BETA),
                ReferenceStories.rd2ShareCorrection(ReferenceStories.ACME_OWNS_BETA));
    }

    @Test
    void rd3MatchesThePage() {
        assertTold("RD-3", ReferenceStories.rd3Employment(ReferenceStories.BETA, ReferenceStories.ALICE));
    }

    @Test
    void rd4MatchesThePage() {
        assertTold("RD-4", ReferenceStories.rd4CustomerRole(ReferenceStories.BETA));
    }

    @Test
    void rd5MatchesThePage() {
        assertTold("RD-5", ReferenceStories.rd5Accounts(ReferenceStories.BETA, ReferenceStories.ACME),
                ReferenceStories.rd5BalancePatch(ReferenceStories.A1),
                ReferenceStories.rd5BalanceCorrection(ReferenceStories.A1));
    }

    @Test
    void rd5RelatesTheAccountsToTheHoldersThatThePageNames() {
        assertThat(rows.get("RD-5")).contains("account A-1 is held by Beta and A-2 by ACME");

        List<Write.Relate> relates = ReferenceStories.rd5Accounts(ReferenceStories.BETA, ReferenceStories.ACME)
                .transaction().writes().stream().filter(Write.Relate.class::isInstance).map(Write.Relate.class::cast)
                .toList();

        assertThat(relates).extracting(Write.Relate::relationshipType).containsExactly("HeldBy", "HeldBy");
        assertThat(relates).extracting(Write.Relate::source).containsExactly(ReferenceStories.A1, ReferenceStories.A2);
        assertThat(relates).extracting(Write.Relate::target).containsExactly(ReferenceStories.BETA,
                ReferenceStories.ACME);
    }

    @Test
    void rd7MatchesThePage() {
        assertTold("RD-7", ReferenceStories.rd7Rename(ReferenceStories.ACME));
    }

    @Test
    void rd8MatchesThePage() {
        ReferenceStories.Recording alone = rd8Alone();
        ReferenceStories.Recording together = rd8Together();

        String row = rows.get("RD-8");
        assertThat(row).as("RD-8 gives no recording date").doesNotContainPattern(RECORDED);
        for (ReferenceStories.Recording recording : List.of(alone, together)) {
            for (String fact : facts(recording)) {
                assertThat(row).as("RD-8: the row states '%s'", fact).containsPattern(stated(fact));
            }
        }
        assertThat(recordedDate(alone)).as("the fixture chooses a date").isEqualTo(recordedDate(together));
    }

    @Test
    void rd8EndsWhatThePageNamesTogetherWithBeta() {
        assertThat(rows.get("RD-8")).contains("moves A-1 to ACME (ends `HeldBy` Beta and starts `HeldBy` ACME")
                .contains("ends `Owns` and `Employs` involving Beta")
                .contains("ends Beta's customer role together with its `PlayedBy`");

        List<Write> writes = rd8Together().transaction().writes();

        assertThat(writes).filteredOn(Write.EndDate.class::isInstance).map(write -> ((Write.EndDate) write).target())
                .containsExactly(ReferenceStories.BETA, ReferenceStories.HELD_BY_A1, ReferenceStories.ACME_OWNS_BETA,
                        ReferenceStories.BETA_EMPLOYS_ALICE, ReferenceStories.CUSTOMER_ROLE,
                        ReferenceStories.PLAYED_BY);
        assertThat(writes).filteredOn(Write.Relate.class::isInstance).singleElement().satisfies(write -> {
            Write.Relate relate = (Write.Relate) write;
            assertThat(relate.relationshipType()).isEqualTo("HeldBy");
            assertThat(relate.source()).isEqualTo(ReferenceStories.A1);
            assertThat(relate.target()).isEqualTo(ReferenceStories.ACME);
        });
        assertThat(rd8Alone().transaction().writes()).singleElement().isInstanceOf(Write.EndDate.class)
                .satisfies(write -> assertThat(((Write.EndDate) write).target()).isEqualTo(ReferenceStories.BETA));
    }

    @Test
    void rd9MatchesThePage() {
        assertTold("RD-9", ReferenceStories.rd9AcmeLei(ReferenceStories.ACME), ReferenceStories.rd9Gamma());
    }

    @Test
    void rd12MatchesThePage() {
        assertTold("RD-12", ReferenceStories.rd12GammaWrongCheckDigits());
    }

    @Test
    void rd13MatchesThePage() {
        assertTold("RD-13", ReferenceStories.rd13GammaOwnsBeta(ReferenceStories.BETA));
    }

    @Test
    void rd13CreatesGammaAndRelatesItToBeta() {
        assertThat(rows.get("RD-13")).contains("Gamma, an organization, is created and owns 50% of Beta");

        List<Write> writes = ReferenceStories.rd13GammaOwnsBeta(ReferenceStories.BETA).transaction().writes();

        assertThat(writes).hasSize(2);
        assertThat(writes.get(0)).isInstanceOfSatisfying(Write.Create.class, create -> {
            assertThat(create.entityType()).isEqualTo("Organization");
            assertThat(create.temporaryId()).contains(ReferenceStories.GAMMA);
        });
        assertThat(writes.get(1)).isInstanceOfSatisfying(Write.Relate.class, relate -> {
            assertThat(relate.relationshipType()).isEqualTo("Owns");
            assertThat(relate.source()).isEqualTo(ReferenceStories.GAMMA);
            assertThat(relate.target()).isEqualTo(ReferenceStories.BETA);
        });
    }

    @Test
    void rd13StatesTheTotalOfTheSharesThatTheFixturesWrite() {
        BigDecimal acme = shareOf(ReferenceStories.rd2Ownership(ReferenceStories.ACME, ReferenceStories.BETA));
        BigDecimal gamma = shareOf(ReferenceStories.rd13GammaOwnsBeta(ReferenceStories.BETA));

        assertThat(rows.get("RD-13")).as("ACME's share as RD-2 first records it, Gamma's share, and their total")
                .containsPattern(stated(number(acme) + "%")).containsPattern(stated(number(gamma) + "%"))
                .containsPattern(stated(number(acme.add(gamma)) + "%")).contains("see RD-2");
        assertThat(acme.add(gamma)).as("together they exceed 100").isGreaterThan(BigDecimal.valueOf(100));
    }

    @Test
    void rd2RelatesAcmeToBetaWithOwnsAndRd4GivesBetaACustomerRole() {
        assertThat(ReferenceStories.rd2Ownership(ReferenceStories.ACME, ReferenceStories.BETA).transaction().writes())
                .singleElement().isInstanceOfSatisfying(Write.Relate.class, relate -> {
                    assertThat(relate.relationshipType()).isEqualTo("Owns");
                    assertThat(relate.source()).isEqualTo(ReferenceStories.ACME);
                    assertThat(relate.target()).isEqualTo(ReferenceStories.BETA);
                });
        assertThat(ReferenceStories.rd4CustomerRole(ReferenceStories.BETA).transaction().writes())
                .extracting(write -> write.getClass().getSimpleName()).containsExactly("Create", "Relate");
    }

    @Test
    void theFactsOfAStoryAreWhatTheFixtureWrites() {
        assertThat(facts(ReferenceStories.rd1Parties())).containsExactly("1 January", "ACME", "Beta", "Alice");
        assertThat(facts(ReferenceStories.rd2Ownership(ReferenceStories.ACME, ReferenceStories.BETA)))
                .containsExactly("1 February", "60");
        assertThat(facts(ReferenceStories.rd2ShareCorrection(ReferenceStories.ACME_OWNS_BETA)))
                .containsExactly("1 February", "55");
        assertThat(facts(ReferenceStories.rd3Employment(ReferenceStories.BETA, ReferenceStories.ALICE)))
                .containsExactly("1 March", "Analyst");
        assertThat(facts(ReferenceStories.rd4CustomerRole(ReferenceStories.BETA)))
                .containsExactly("1 March", "1 October", "Corporate");
        assertThat(facts(rd8Alone())).containsExactly("1 June");
        assertThat(facts(rd8Together())).containsExactly("1 June");
        assertThat(facts(ReferenceStories.rd13GammaOwnsBeta(ReferenceStories.BETA)))
                .containsExactly("1 April", "Gamma", "50");
        assertThat(facts(ReferenceStories.rd5Accounts(ReferenceStories.BETA, ReferenceStories.ACME)))
                .containsExactly("1 January", "A-1", "EUR", "1,000", "A-2", "2,000");
        assertThat(facts(ReferenceStories.rd5BalancePatch(ReferenceStories.A1))).containsExactly("15 March", "1,500");
        assertThat(facts(ReferenceStories.rd5BalanceCorrection(ReferenceStories.A1)))
                .containsExactly("15 March", "1,200");
        assertThat(facts(ReferenceStories.rd7Rename(ReferenceStories.ACME)))
                .containsExactly("1 January 2027", "ACME Holdings");
        assertThat(facts(ReferenceStories.rd9AcmeLei(ReferenceStories.ACME)))
                .containsExactly("1 January", ReferenceStories.LEI);
        assertThat(facts(ReferenceStories.rd9Gamma())).containsExactly("1 March", "Gamma", ReferenceStories.LEI);
        assertThat(facts(ReferenceStories.rd12GammaWrongCheckDigits()))
                .containsExactly("1 March", "Gamma", ReferenceStories.LEI_WRONG_CHECK_DIGITS);
        assertThat(recordedDate(ReferenceStories.rd2Ownership(ReferenceStories.ACME, ReferenceStories.BETA)))
                .isEqualTo("10 February");
        assertThat(recordedDate(ReferenceStories.rd2ShareCorrection(ReferenceStories.ACME_OWNS_BETA)))
                .isEqualTo("2 May");
        assertThat(recordedDate(ReferenceStories.rd13GammaOwnsBeta(ReferenceStories.BETA))).isEqualTo("1 April");
        assertThat(recordedDate(ReferenceStories.rd5BalancePatch(ReferenceStories.A1))).isEqualTo("16 March");
        assertThat(recordedDate(ReferenceStories.rd7Rename(ReferenceStories.ACME))).isEqualTo("12 October");
        assertThat(recordedDate(ReferenceStories.rd12GammaWrongCheckDigits())).isEqualTo("1 March");
    }

    @Test
    void aFactIsFoundOnlyWhenTheRowStatesItWhole() {
        assertThat("from 1 March.").containsPattern(stated("1 March"));
        assertThat("from 11 March.").doesNotContainPattern(stated("1 March"));
        assertThat("from 1 March 2027.").as("a date of 2026 has no year on the page")
                .doesNotContainPattern(stated("1 March"));
        assertThat("from 1 March 2027.").containsPattern(stated("1 March 2027"));
        assertThat("a balance of 1,500.").containsPattern(stated("1,500"));
        assertThat("a balance of 11,500.").doesNotContainPattern(stated("1,500"));
        assertThat("a balance of 1,5000.").doesNotContainPattern(stated("1,500"));
        assertThat("both in EUR.").containsPattern(stated("EUR"));
        assertThat("both in EURO.").doesNotContainPattern(stated("EUR"));
        assertThat("the LEI `5493001KJTIIGC8Y1R12` from").containsPattern(stated("5493001KJTIIGC8Y1R12"));
    }

    // ------------------------------------------------------------------ the check

    /** RD-8: ending Beta alone. */
    private static ReferenceStories.Recording rd8Alone() {
        return ReferenceStories.rd8EndBetaAlone(ReferenceStories.BETA);
    }

    /** RD-8: ending Beta together with the relationships and the role. */
    private static ReferenceStories.Recording rd8Together() {
        return ReferenceStories.rd8EndBeta(ReferenceStories.BETA, ReferenceStories.ACME, ReferenceStories.A1,
                ReferenceStories.HELD_BY_A1, ReferenceStories.ACME_OWNS_BETA, ReferenceStories.BETA_EMPLOYS_ALICE,
                ReferenceStories.CUSTOMER_ROLE, ReferenceStories.PLAYED_BY);
    }

    /**
     * Checks one story: its row on the page names the recording dates of the given transactions in their order, and
     * states every fact they write.
     */
    private static void assertTold(String story, ReferenceStories.Recording... recordings) {
        String row = rows.get(story);
        assertThat(row).as("the row of %s in the story table of the page", story).isNotNull();

        List<String> recorded = new ArrayList<>();
        Matcher matcher = RECORDED.matcher(row);
        while (matcher.find()) {
            recorded.add(matcher.group(1));
        }
        assertThat(recorded).as("%s: the recording dates on the page", story)
                .containsExactlyElementsOf(Arrays.stream(recordings).map(ReferenceStoriesTest::recordedDate).toList());

        for (ReferenceStories.Recording recording : recordings) {
            for (String fact : facts(recording)) {
                assertThat(row).as("%s: the row states '%s'", story, fact).containsPattern(stated(fact));
            }
        }
    }

    /** The date a recording is recorded, as the page writes it after "Recorded". */
    private static String recordedDate(ReferenceStories.Recording recording) {
        return date(recording.recordedAt());
    }

    /**
     * The dates and values that a recording writes, as the page writes them, without repeats: the start and end of each
     * effective period, and the values that are set.
     */
    private static List<String> facts(ReferenceStories.Recording recording) {
        List<String> facts = new ArrayList<>();
        for (Write write : recording.transaction().writes()) {
            switch (write) {
                case Write.Create create -> {
                    period(facts, create.period());
                    values(facts, create.values());
                }
                case Write.Relate relate -> {
                    period(facts, relate.period());
                    values(facts, relate.values());
                }
                case Write.Patch patch -> {
                    period(facts, patch.period());
                    values(facts, patch.set());
                }
                case Write.Replace replace -> {
                    period(facts, replace.period());
                    values(facts, replace.values());
                }
                case Write.EndDate endDate -> facts.add(date(endDate.from()));
            }
        }
        return facts.stream().distinct().toList();
    }

    private static void period(List<String> facts, EffectivePeriod period) {
        facts.add(date(period.from()));
        period.until().ifPresent(until -> facts.add(date(until)));
    }

    private static void values(List<String> facts, Map<String, Value> values) {
        values.values().forEach(value -> facts.add(text(value)));
    }

    /** The share that the first relationship of a recording sets. */
    private static BigDecimal shareOf(ReferenceStories.Recording recording) {
        Write.Relate relate = recording.transaction().writes().stream().filter(Write.Relate.class::isInstance)
                .map(Write.Relate.class::cast).findFirst().orElseThrow();
        return switch (relate.values().get("share")) {
            case Value.IntValue number -> BigDecimal.valueOf(number.value());
            case Value.DecimalValue decimal -> decimal.value();
            default -> throw new IllegalArgumentException("Not a share: " + relate.values().get("share"));
        };
    }

    /** A date as the page writes it: "16 March", with the year only if it is not the year of the stories. */
    private static String date(Instant instant) {
        LocalDate date = instant.atZone(ZoneOffset.UTC).toLocalDate();
        assertThat(instant).as("the page states days, not times")
                .isEqualTo(date.atStartOfDay(ZoneOffset.UTC).toInstant());
        String text = date.getDayOfMonth() + " " + date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return date.getYear() == PAGE_YEAR ? text : text + " " + date.getYear();
    }

    /** A value as the page writes it: text as it is, numbers with commas between thousands. */
    private static String text(Value value) {
        return switch (value) {
            case Value.StringValue string -> string.value();
            case Value.DecimalValue decimal -> number(decimal.value());
            case Value.IntValue number -> number(BigDecimal.valueOf(number.value()));
            case Value.LongValue number -> number(BigDecimal.valueOf(number.value()));
            default -> throw new IllegalArgumentException("The page has no way to state " + value);
        };
    }

    private static String number(BigDecimal number) {
        return new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(number);
    }

    /**
     * A pattern that finds a fact as a whole: not inside a longer word or number, and, for a date without a year, not
     * followed by a year.
     */
    private static String stated(String fact) {
        String pattern = "(?<![\\w,.])" + Pattern.quote(fact) + "(?!\\w)";
        return DATE_WITHOUT_YEAR.matcher(fact).matches() ? pattern + "(?! \\d{4})" : pattern;
    }
}
