package process.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Cron frequency's dialect (Wave 4): which expressions a schedule accepts, the words it refuses the rest in, and the
 * next slot after a Chicago wall-clock reading. No database, no Spring context.
 *
 * The dialect: Unix cron's five fields (minute hour day-of-month month day-of-week) -- what the console writes -- or
 * Spring's six with a leading seconds field that is exactly 0. A schedule runs at most once a minute, so any other
 * seconds field is refused rather than rounded.
 */
class CronScheduleTest {

    // ---- what is accepted, and how it is read ---------------------------------------------------------------------

    @Test
    void fiveFieldUnixCronIsReadWithSecondsZero() {
        assertThat(CronSchedule.problem("0 9 * * MON-FRI")).isNull();
        assertThat(CronSchedule.springForm("0 9 * * MON-FRI")).isEqualTo("0 0 9 * * MON-FRI");
    }

    @Test
    void sixFieldSpringCronIsAcceptedWhenItsSecondsFieldIsZero() {
        assertThat(CronSchedule.problem("0 */15 * * * *")).isNull();
        assertThat(CronSchedule.springForm("0 */15 * * * *")).isEqualTo("0 */15 * * * *");
    }

    @Test
    void whitespaceIsTidiedAndTheTidyFormIsWhatIsStored() {
        assertThat(CronSchedule.normalise("  0   9 * *\tMON-FRI ")).isEqualTo("0 9 * * MON-FRI");
        assertThat(CronSchedule.normalise("   ")).isNull();
        assertThat(CronSchedule.normalise(null)).isNull();
    }

    @Test
    void everyMinuteIsTheMostFrequentScheduleAndIsAccepted() {
        assertThat(CronSchedule.problem("* * * * *")).isNull();
        LocalDateTime from = LocalDateTime.of(2027, 1, 5, 10, 0, 0);
        assertThat(CronSchedule.next("* * * * *", from)).isEqualTo(from.plusMinutes(1));
    }

    @Test
    void namesRangesListsAndStepsAreSpringsDialect() {
        assertThat(CronSchedule.problem("30 6 1,15 JAN-JUN *")).isNull();
        assertThat(CronSchedule.problem("0 8-18/2 * * 1-5")).isNull();
        // Sunday is 0 or 7, as in Unix cron.
        LocalDateTime saturdayNoon = LocalDateTime.of(2027, 3, 6, 12, 0);
        assertThat(CronSchedule.next("0 9 * * 7", saturdayNoon)).isEqualTo(LocalDateTime.of(2027, 3, 7, 9, 0));
        assertThat(CronSchedule.next("0 9 * * 0", saturdayNoon)).isEqualTo(LocalDateTime.of(2027, 3, 7, 9, 0));
    }

    // ---- the next slot ---------------------------------------------------------------------------------------------

    @Test
    void theNextSlotIsStrictlyAfterTheReading() {
        LocalDateTime nine = LocalDateTime.of(2027, 3, 1, 9, 0);
        assertThat(CronSchedule.next("0 9 * * *", nine)).isEqualTo(nine.plusDays(1));
        assertThat(CronSchedule.next("0 9 * * *", nine.minusSeconds(1))).isEqualTo(nine);
    }

    @Test
    void weekdaysOnlySkipsTheWeekend() {
        LocalDateTime fridayNine = LocalDateTime.of(2027, 3, 5, 9, 0);
        assertThat(CronSchedule.next("0 9 * * MON-FRI", fridayNine)).isEqualTo(LocalDateTime.of(2027, 3, 8, 9, 0));
    }

    /**
     * Wall-clock arithmetic: 02:30 on the spring-forward Sunday is a slot like any other here. What it means as an
     * instant (03:30 CDT, the gap's length on) is BusinessTime's, decided once for every schedule.
     */
    @Test
    void theSlotsAreWallClockReadingsWithNoGapAndNoRepeatedHour() {
        assertThat(CronSchedule.next("30 2 * * *", LocalDateTime.of(2026, 3, 7, 2, 30)))
            .isEqualTo(LocalDateTime.of(2026, 3, 8, 2, 30));
        assertThat(CronSchedule.next("*/30 * * * *", LocalDateTime.of(2026, 11, 1, 1, 30)))
            .isEqualTo(LocalDateTime.of(2026, 11, 1, 2, 0));
    }

    /** Spring 5.2's generator gives up after a year without a matching day; a leap day is further off than that. */
    @Test
    void aLeapDayScheduleFindsTheNextLeapYear() {
        assertThat(CronSchedule.problem("0 9 29 2 *")).isNull();
        assertThat(CronSchedule.next("0 9 29 2 *", LocalDateTime.of(2028, 3, 1, 0, 0)))
            .isEqualTo(LocalDateTime.of(2032, 2, 29, 9, 0));
    }

    @Test
    void anUnreadableExpressionHasNoNextSlot() {
        assertThat(CronSchedule.next("not cron", LocalDateTime.of(2027, 1, 1, 0, 0))).isNull();
        assertThat(CronSchedule.next(null, LocalDateTime.of(2027, 1, 1, 0, 0))).isNull();
    }

    // ---- what is refused, in words the caller can act on -----------------------------------------------------------

    @Test
    void aMissingExpressionIsRefused() {
        assertThat(CronSchedule.problem(null)).contains("needs a cron expression").contains("minute hour day-of-month month day-of-week");
        assertThat(CronSchedule.problem("  ")).contains("needs a cron expression");
    }

    @Test
    void secondsLevelExpressionsAreRefused() {
        assertThat(CronSchedule.problem("*/30 * * * * *")).contains("at most once a minute").contains("seconds field must be 0");
        assertThat(CronSchedule.problem("15 0 9 * * *")).contains("at most once a minute");
        assertThat(CronSchedule.problem("0,30 * * * * *")).contains("at most once a minute");
    }

    @Test
    void theWrongNumberOfFieldsIsRefused() {
        assertThat(CronSchedule.problem("0 9 * *")).contains("5 fields").contains("got 4");
        // Quartz's seven, with a year: not this dialect.
        assertThat(CronSchedule.problem("0 0 9 * * ? 2027")).contains("5 fields").contains("got 7");
    }

    @Test
    void aFieldOutOfRangeOrUnreadableIsRefusedNamingTheExpression() {
        assertThat(CronSchedule.problem("61 9 * * *")).contains("'61 9 * * *' is not a cron expression");
        assertThat(CronSchedule.problem("0 25 * * *")).contains("is not a cron expression");
        assertThat(CronSchedule.problem("0 9 * * FUNDAY")).contains("is not a cron expression");
        // Quartz's L/W/# are not in Spring 5.2's dialect.
        assertThat(CronSchedule.problem("0 9 L * *")).contains("is not a cron expression");
    }

    @Test
    void anExpressionThatNeverFiresIsRefused() {
        assertThat(CronSchedule.problem("0 9 30 2 *")).contains("never fires");
        assertThat(CronSchedule.problem("0 9 31 4,6,9,11 *")).contains("never fires");
    }

    @Test
    void anOverlongExpressionIsRefused() {
        String long121 = "0 9 * * " + String.join(",", Collections.nCopies(60, "1"));
        assertThat(long121.length()).isGreaterThan(CronSchedule.MAX_LENGTH);
        assertThat(CronSchedule.problem(long121)).contains("at most 120 characters");
    }
}
