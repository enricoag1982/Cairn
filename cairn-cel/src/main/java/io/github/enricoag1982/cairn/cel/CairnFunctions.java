package io.github.enricoag1982.cairn.cel;

import com.google.common.collect.ImmutableList;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.types.SimpleType;
import dev.cel.runtime.CelFunctionBinding;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The functions of Cairn's own in expressions (ADR-0035, design D1 of {@code add-expression-functions}):
 * {@code cairn.calendarDays(first, last)} and {@code cairn.workingDays(first, last)}.
 *
 * <p>Each takes two timestamps and gives, as an integer, the number of days, or of days from Monday to Friday, from the
 * date of the first to the date of the last, as dates in UTC, both included, and 0 when the last date is before the
 * first. They read nothing but their arguments, count with {@code java.time} in UTC and never in the default time
 * zone, and take the same time for any range: working days are counted for whole weeks, then for the rest. They cannot
 * fail on their arguments, so they count as one evaluation step each, like any other call.
 *
 * <p>The declarations give the checker their types, so that a call with other arguments does not compile; the bindings
 * run them. A language environment adds both.
 */
final class CairnFunctions {

    /** The name of the function that counts days from Monday to Sunday. */
    static final String CALENDAR_DAYS = "cairn.calendarDays";

    /** The name of the function that counts days from Monday to Friday. */
    static final String WORKING_DAYS = "cairn.workingDays";

    private static final String CALENDAR_DAYS_OVERLOAD = "cairn_calendarDays_timestamp_timestamp";
    private static final String WORKING_DAYS_OVERLOAD = "cairn_workingDays_timestamp_timestamp";

    /** The number of days in a week, and of those from Monday to Friday. */
    private static final int WEEK = 7;
    private static final int WORKING_WEEK = 5;

    private CairnFunctions() {
    }

    /** The declarations of the functions, with their overloads, for the checker. */
    static ImmutableList<CelFunctionDecl> declarations() {
        return ImmutableList.of(
                CelFunctionDecl.newFunctionDeclaration(CALENDAR_DAYS, CelOverloadDecl.newGlobalOverload(
                        CALENDAR_DAYS_OVERLOAD,
                        "The number of days from the UTC date of the first timestamp to that of the last, both"
                                + " included; 0 if the last date is before the first.",
                        SimpleType.INT, SimpleType.TIMESTAMP, SimpleType.TIMESTAMP)),
                CelFunctionDecl.newFunctionDeclaration(WORKING_DAYS, CelOverloadDecl.newGlobalOverload(
                        WORKING_DAYS_OVERLOAD,
                        "The number of days from Monday to Friday from the UTC date of the first timestamp to that of"
                                + " the last, both included; 0 if the last date is before the first.",
                        SimpleType.INT, SimpleType.TIMESTAMP, SimpleType.TIMESTAMP)));
    }

    /** The implementations of the functions, for the runtime. */
    static ImmutableList<CelFunctionBinding> bindings() {
        return ImmutableList.of(
                CelFunctionBinding.from(CALENDAR_DAYS_OVERLOAD, Instant.class, Instant.class,
                        (first, last) -> Long.valueOf(calendarDays(first, last))),
                CelFunctionBinding.from(WORKING_DAYS_OVERLOAD, Instant.class, Instant.class,
                        (first, last) -> Long.valueOf(workingDays(first, last))));
    }

    /**
     * The number of days from the UTC date of {@code first} to that of {@code last}, both included.
     *
     * @param first the instant whose UTC date starts the count
     * @param last  the instant whose UTC date ends the count
     * @return the number of days, 0 if the date of {@code last} is before that of {@code first}
     */
    static long calendarDays(Instant first, Instant last) {
        long from = utcDate(first).toEpochDay();
        long to = utcDate(last).toEpochDay();
        return to < from ? 0 : to - from + 1;
    }

    /**
     * The number of Mondays to Fridays from the UTC date of {@code first} to that of {@code last}, both included.
     *
     * @param first the instant whose UTC date starts the count
     * @param last  the instant whose UTC date ends the count
     * @return the number of days, 0 if the date of {@code last} is before that of {@code first}
     */
    static long workingDays(Instant first, Instant last) {
        LocalDate from = utcDate(first);
        long days = calendarDays(first, last);
        long weeks = days / WEEK;
        long rest = days % WEEK;
        // The rest starts on the weekday of the first date, as the whole weeks end where they began: at most six days.
        long working = weeks * WORKING_WEEK;
        int weekday = from.getDayOfWeek().getValue() - 1;
        for (int day = 0; day < rest; day++) {
            if ((weekday + day) % WEEK < WORKING_WEEK) {
                working++;
            }
        }
        return working;
    }

    /** The date of an instant in UTC, never in the default time zone. */
    private static LocalDate utcDate(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC);
    }
}
