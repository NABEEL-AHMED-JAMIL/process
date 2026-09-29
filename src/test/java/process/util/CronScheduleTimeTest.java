package process.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import process.model.pojo.Scheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Cron schedule (Wave 4) through the scheduler's own arithmetic -- ProcessTimeUtil's seed, step, catch-up and last
 * flight, the ones every frequency uses -- on BusinessTime's clock, set per test. What a slot means as an instant is
 * BusinessTime.instantOf's; the DST cases assert both the Chicago reading and the instant. The same walk against a real
 * database, through the enqueuer's claim, is CronSchedulerAcrossDstPostgresTest.
 */
class CronScheduleTimeTest {

    @AfterEach
    void systemClock() {
        BusinessTime.useSystemClock();
    }

    private static void at(String instant) {
        BusinessTime.useClock(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static Scheduler cron(String expression, LocalDate start, LocalTime time) {
        Scheduler scheduler = new Scheduler();
        scheduler.setFrequency("Cron");
        scheduler.setCronExpression(expression);
        scheduler.setStartDate(start);
        scheduler.setStartTime(time);
        ProcessTimeUtil.applyInitialSchedule(scheduler);
        return scheduler;
    }

    /** The enqueuer's advance after it ran the due slot. */
    private static LocalDateTime advance(Scheduler scheduler) {
        ProcessTimeUtil.applyNextRun(scheduler);
        return scheduler.getNextRunAt();
    }

    private static String utc(LocalDateTime wallClock) {
        return BusinessTime.instantOf(wallClock).toString();
    }

    // ---- seed and step -----------------------------------------------------------------------------------------------

    @Test
    void theFirstRunIsTheFirstSlotAfterNowWhenTheStartHasPassed() {
        at("2027-03-03T16:10:00Z"); // Wednesday 10:10 CST
        Scheduler scheduler = cron("0 9 * * MON-FRI", LocalDate.of(2027, 3, 1), LocalTime.MIDNIGHT);
        assertThat(scheduler.getNextRunAt()).isEqualTo(LocalDateTime.of(2027, 3, 4, 9, 0));
        assertThat(scheduler.isExpired()).isFalse();
    }

    @Test
    void theStepSkipsToTheNextWeekdayAndIntervalIsNotNeeded() {
        at("2027-03-05T15:00:30Z"); // Friday 09:00:30 CST: the 09:00 slot is due
        Scheduler scheduler = cron("0 9 * * MON-FRI", LocalDate.of(2027, 3, 5), LocalTime.of(8, 0));
        scheduler.setNextRunAt(LocalDateTime.of(2027, 3, 5, 9, 0));
        assertThat(scheduler.getIntervalValue()).isNull();
        assertThat(advance(scheduler)).isEqualTo(LocalDateTime.of(2027, 3, 8, 9, 0));
    }

    @Test
    void anEndDateExpiresTheScheduleAfterItsLastSlot() {
        at("2027-03-05T15:00:30Z");
        Scheduler scheduler = cron("0 9 * * MON-FRI", LocalDate.of(2027, 3, 5), LocalTime.of(8, 0));
        scheduler.setNextRunAt(LocalDateTime.of(2027, 3, 5, 9, 0));
        scheduler.setEndDate(LocalDate.of(2027, 3, 7));
        assertThat(ProcessTimeUtil.isLastFlight(scheduler)).isTrue();
        advance(scheduler);
        assertThat(scheduler.isExpired()).isTrue();
    }

    @Test
    void aStoredExpressionThatCannotBeReadExpiresRatherThanLooping() {
        at("2027-03-05T15:00:30Z");
        Scheduler scheduler = new Scheduler();
        scheduler.setFrequency("Cron");
        scheduler.setCronExpression("not cron at all");
        scheduler.setStartDate(LocalDate.of(2027, 3, 5));
        scheduler.setStartTime(LocalTime.of(8, 0));
        scheduler.setNextRunAt(LocalDateTime.of(2027, 3, 5, 9, 0));
        assertThat(ProcessTimeUtil.computeNextRun(scheduler)).isNull();
        advance(scheduler);
        assertThat(scheduler.isExpired()).isTrue();
        assertThat(ProcessTimeUtil.computeMissedRuns(scheduler)).isEmpty();
    }

    // ---- catch-up: the same Missed handling as every frequency -------------------------------------------------------

    @Test
    void slotsThatWentByWhileDownAreMissedOldestFirstAndTheNextRunIsInTheFuture() {
        at("2027-03-03T16:10:00Z"); // Wednesday 10:10 CST
        Scheduler scheduler = cron("0 * * * *", LocalDate.of(2027, 3, 3), LocalTime.MIDNIGHT);
        scheduler.setNextRunAt(LocalDateTime.of(2027, 3, 3, 6, 0)); // due at 06:00, the system came back at 10:10
        List<LocalDateTime> missed = ProcessTimeUtil.computeMissedRuns(scheduler);
        assertThat(missed).containsExactly(LocalDateTime.of(2027, 3, 3, 7, 0), LocalDateTime.of(2027, 3, 3, 8, 0),
            LocalDateTime.of(2027, 3, 3, 9, 0), LocalDateTime.of(2027, 3, 3, 10, 0));
        assertThat(advance(scheduler)).isEqualTo(LocalDateTime.of(2027, 3, 3, 11, 0));
    }

    @Test
    void aLongOutageReplaysOnlyTheCappedMostRecentSlots() {
        at("2027-03-10T16:10:00Z");
        Scheduler scheduler = cron("*/5 * * * *", LocalDate.of(2027, 3, 1), LocalTime.MIDNIGHT);
        scheduler.setNextRunAt(LocalDateTime.of(2027, 3, 1, 0, 0));
        List<LocalDateTime> missed = ProcessTimeUtil.computeMissedRuns(scheduler);
        assertThat(missed).hasSize(ProcessTimeUtil.MAX_MISSED_RUNS_REPLAYED);
        assertThat(missed.get(missed.size() - 1)).isEqualTo(LocalDateTime.of(2027, 3, 10, 10, 10));
        assertThat(advance(scheduler)).isEqualTo(LocalDateTime.of(2027, 3, 10, 10, 15));
    }

    // ---- DST: spring forward, 2026-03-08 02:00 CST -> 03:00 CDT ------------------------------------------------------

    @Test
    void nineOClockStaysNineOClockInChicagoOverSpringForward() {
        at("2026-03-06T16:00:00Z"); // Friday 10:00 CST
        Scheduler scheduler = cron("0 9 * * *", LocalDate.of(2026, 3, 6), LocalTime.MIDNIGHT);
        assertThat(scheduler.getNextRunAt()).isEqualTo(LocalDateTime.of(2026, 3, 7, 9, 0));
        assertThat(utc(scheduler.getNextRunAt())).isEqualTo("2026-03-07T15:00:00Z");
        at("2026-03-07T15:00:30Z");
        assertThat(advance(scheduler)).isEqualTo(LocalDateTime.of(2026, 3, 8, 9, 0));
        // 09:00 CDT: an hour earlier in UTC than the day before.
        assertThat(utc(scheduler.getNextRunAt())).isEqualTo("2026-03-08T14:00:00Z");
    }

    /**
     * 02:30 does not exist on 8 March. The slot runs the gap's length on -- 03:30 CDT, as a Daily 02:30 does -- and the
     * next day is 02:30 again: a cron slot is absolute, so reading the gap slot back as 03:30 moves nothing.
     */
    @Test
    void aSlotInTheSpringForwardGapRunsAnHourOnAndTheScheduleKeepsItsTime() {
        at("2026-03-07T12:00:00Z");
        Scheduler scheduler = cron("30 2 * * *", LocalDate.of(2026, 3, 7), LocalTime.MIDNIGHT);
        assertThat(scheduler.getNextRunAt()).isEqualTo(LocalDateTime.of(2026, 3, 8, 2, 30));
        assertThat(utc(scheduler.getNextRunAt())).isEqualTo("2026-03-08T08:30:00Z"); // 03:30 CDT
        // As the database hands it back: the instant's Chicago reading.
        scheduler.setNextRunAt(BusinessTime.wallClockOf(BusinessTime.instantOf(scheduler.getNextRunAt())));
        assertThat(scheduler.getNextRunAt()).isEqualTo(LocalDateTime.of(2026, 3, 8, 3, 30));
        at("2026-03-08T08:30:30Z");
        assertThat(advance(scheduler)).isEqualTo(LocalDateTime.of(2026, 3, 9, 2, 30));
    }

    /** A quarter-hourly schedule over the gap: the slots the clock skipped are not reported as missed. */
    @Test
    void theSkippedHoursSlotsAreNotMissedRuns() {
        at("2026-03-08T09:10:00Z"); // 04:10 CDT, back after being down since before 02:00 CST
        Scheduler scheduler = cron("*/15 * * * *", LocalDate.of(2026, 3, 7), LocalTime.MIDNIGHT);
        scheduler.setNextRunAt(LocalDateTime.of(2026, 3, 8, 1, 45));
        assertThat(ProcessTimeUtil.computeMissedRuns(scheduler)).containsExactly(
            LocalDateTime.of(2026, 3, 8, 3, 0), LocalDateTime.of(2026, 3, 8, 3, 15), LocalDateTime.of(2026, 3, 8, 3, 30),
            LocalDateTime.of(2026, 3, 8, 3, 45), LocalDateTime.of(2026, 3, 8, 4, 0));
        assertThat(advance(scheduler)).isEqualTo(LocalDateTime.of(2026, 3, 8, 4, 15));
    }

    // ---- DST: fall back, 2026-11-01 02:00 CDT -> 01:00 CST -----------------------------------------------------------

    @Test
    void nineOClockStaysNineOClockInChicagoOverFallBack() {
        at("2026-10-30T15:00:00Z"); // Friday 10:00 CDT
        Scheduler scheduler = cron("0 9 * * *", LocalDate.of(2026, 10, 30), LocalTime.MIDNIGHT);
        assertThat(utc(scheduler.getNextRunAt())).isEqualTo("2026-10-31T14:00:00Z");
        at("2026-10-31T14:00:30Z");
        assertThat(advance(scheduler)).isEqualTo(LocalDateTime.of(2026, 11, 1, 9, 0));
        assertThat(utc(scheduler.getNextRunAt())).isEqualTo("2026-11-01T15:00:00Z"); // 09:00 CST
    }

    /**
     * 01:30 happens twice on 1 November. A slot there is one reading and runs once -- when the wall clock first reaches
     * it, as the scheduler has always compared (SchedulerAcrossDstPostgresTest) -- and the step after it is the next
     * day's, not the second 01:30.
     */
    @Test
    void aSlotInTheRepeatedHourRunsOnce() {
        at("2026-10-31T12:00:00Z");
        Scheduler scheduler = cron("30 1 * * *", LocalDate.of(2026, 10, 31), LocalTime.MIDNIGHT);
        assertThat(scheduler.getNextRunAt()).isEqualTo(LocalDateTime.of(2026, 11, 1, 1, 30));
        at("2026-11-01T06:30:30Z"); // the first 01:30 (CDT)
        assertThat(advance(scheduler)).isEqualTo(LocalDateTime.of(2026, 11, 2, 1, 30));
        at("2026-11-01T07:30:30Z"); // the second 01:30 (CST): nothing is due
        assertThat(ProcessTimeUtil.computeMissedRuns(scheduler)).isEmpty();
    }

    /**
     * A half-hourly schedule over the repeated hour steps in wall-clock: 01:00, 01:30, then 02:00 -- the repeated hour's
     * readings are not slots twice. (The interval frequencies behave the same way; see the report for the owner.)
     */
    @Test
    void aHalfHourlyScheduleWalksTheRepeatedHourOnce() {
        at("2026-11-01T06:00:30Z"); // 01:00:30 CDT
        Scheduler scheduler = cron("*/30 * * * *", LocalDate.of(2026, 10, 31), LocalTime.MIDNIGHT);
        scheduler.setNextRunAt(LocalDateTime.of(2026, 11, 1, 1, 0));
        assertThat(advance(scheduler)).isEqualTo(LocalDateTime.of(2026, 11, 1, 1, 30));
        at("2026-11-01T06:30:30Z"); // 01:30:30 CDT
        assertThat(advance(scheduler)).isEqualTo(LocalDateTime.of(2026, 11, 1, 2, 0));
        assertThat(utc(scheduler.getNextRunAt())).isEqualTo("2026-11-01T08:00:00Z"); // 02:00 CST
    }

    // ---- the interval frequencies are untouched ----------------------------------------------------------------------

    @Test
    void aDailyScheduleIgnoresAStrayExpression() {
        at("2027-03-03T16:10:00Z");
        Scheduler scheduler = new Scheduler();
        scheduler.setFrequency("Daily");
        scheduler.setIntervalValue("1");
        scheduler.setCronExpression("*/5 * * * *");
        scheduler.setStartDate(LocalDate.of(2027, 3, 3));
        scheduler.setStartTime(LocalTime.of(9, 0));
        ProcessTimeUtil.applyInitialSchedule(scheduler);
        assertThat(scheduler.getNextRunAt()).isEqualTo(LocalDateTime.of(2027, 3, 4, 9, 0));
    }
}
