package process.util;

import org.junit.jupiter.api.Test;
import process.model.pojo.Scheduler;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scheduler decides when every job runs, and had no tests at all. These exercise the
 * arithmetic directly -- no database, no Spring -- so a wrong answer shows up in milliseconds.
 *
 * Times are anchored relative to now() because computeNextRun and computeMissedRuns both
 * compare against the wall clock; a hard-coded date would pass today and fail next year.
 *
 * Flat rather than @Nested: surefire 2.22.2 does not discover nested test classes.
 */
class ProcessTimeUtilTest {

    private static Scheduler scheduler(String frequency, String interval, LocalDateTime nextRunAt) {
        Scheduler s = new Scheduler();
        s.setFrequency(frequency);
        s.setIntervalValue(interval);
        s.setNextRunAt(nextRunAt);
        s.setStartDate(nextRunAt == null ? LocalDate.now() : nextRunAt.toLocalDate());
        s.setStartTime(nextRunAt == null ? LocalTime.of(0, 0) : nextRunAt.toLocalTime());
        return s;
    }

    // ---- advancing a due schedule ----------------------------------------------------------

    @Test
    void minutesAdvanceByTheInterval() {
        Scheduler s = scheduler("Mint", "5", LocalDateTime.now().minusMinutes(1));
        LocalDateTime next = ProcessTimeUtil.computeNextRun(s);
        assertTrue(next.isAfter(LocalDateTime.now()), "next run must be in the future");
        assertTrue(next.isBefore(LocalDateTime.now().plusMinutes(5)));
    }

    @Test
    void hoursAdvanceByTheInterval() {
        Scheduler s = scheduler("Hr", "2", LocalDateTime.now().minusMinutes(30));
        LocalDateTime next = ProcessTimeUtil.computeNextRun(s);
        assertTrue(next.isAfter(LocalDateTime.now()));
        assertTrue(next.isBefore(LocalDateTime.now().plusHours(2)));
    }

    @Test
    void daysAdvanceByTheInterval() {
        LocalDateTime was = LocalDateTime.now().minusHours(1);
        Scheduler s = scheduler("Daily", "1", was);
        assertEquals(was.plusDays(1), ProcessTimeUtil.computeNextRun(s));
    }

    @Test
    void everyStepLandsStrictlyInTheFuture() {
        Scheduler s = scheduler("Mint", "1", LocalDateTime.now().minusDays(3));
        assertTrue(ProcessTimeUtil.computeNextRun(s).isAfter(LocalDateTime.now()));
    }

    @Test
    void theSameScheduleNeverReturnsTheSameSlotTwice() {
        Scheduler s = scheduler("Mint", "10", LocalDateTime.now().minusMinutes(5));
        LocalDateTime first = ProcessTimeUtil.computeNextRun(s);
        s.setNextRunAt(first);
        assertTrue(ProcessTimeUtil.computeNextRun(s).isAfter(first));
    }

    // ---- calendar edges --------------------------------------------------------------------

    @Test
    void dayThirtyOneClampsToAShortMonth() {
        Scheduler s = scheduler("Monthly", "1", LocalDateTime.of(2027, 1, 31, 9, 0));
        s.setDayOfMonth(31);
        LocalDateTime next = ProcessTimeUtil.computeNextRun(s);
        assertEquals(2, next.getMonthValue());
        assertEquals(28, next.getDayOfMonth(), "2027 is not a leap year");
    }

    @Test
    void februaryTwentyNineInALeapYear() {
        Scheduler s = scheduler("Monthly", "1", LocalDateTime.of(2028, 1, 31, 9, 0));
        s.setDayOfMonth(31);
        assertEquals(29, ProcessTimeUtil.computeNextRun(s).getDayOfMonth(), "2028 is a leap year");
    }

    @Test
    void dayOfMonthZeroMeansTheLastDay() {
        Scheduler s = scheduler("Monthly", "1", LocalDateTime.of(2027, 3, 15, 9, 0));
        s.setDayOfMonth(0);
        assertEquals(30, ProcessTimeUtil.computeNextRun(s).getDayOfMonth(), "April has 30 days");
    }

    @Test
    void weeklyByDaysPicksTheNextSelectedDay() {
        LocalDateTime monday = LocalDateTime.of(2027, 3, 1, 9, 0);
        assertEquals(DayOfWeek.MONDAY, monday.getDayOfWeek());
        Scheduler s = scheduler("Weekly", "1", monday);
        s.setDaysOfWeek("MON,WED,FRI");
        assertEquals(DayOfWeek.WEDNESDAY, ProcessTimeUtil.computeNextRun(s).getDayOfWeek());
    }

    @Test
    void weeklyByDaysWrapsAcrossTheWeekend() {
        LocalDateTime friday = LocalDateTime.of(2027, 3, 5, 9, 0);
        assertEquals(DayOfWeek.FRIDAY, friday.getDayOfWeek());
        Scheduler s = scheduler("Weekly", "1", friday);
        s.setDaysOfWeek("MON,WED,FRI");
        assertEquals(DayOfWeek.MONDAY, ProcessTimeUtil.computeNextRun(s).getDayOfWeek());
    }

    @Test
    void theTimeOfDayIsPreservedAcrossADailyStep() {
        LocalDateTime at0230 = LocalDateTime.now()
            .withHour(2).withMinute(30).withSecond(0).withNano(0).minusDays(1);
        Scheduler s = scheduler("Daily", "1", at0230);
        LocalDateTime next = ProcessTimeUtil.computeNextRun(s);
        assertEquals(2, next.getHour());
        assertEquals(30, next.getMinute());
    }

    // ---- refusing bad input ----------------------------------------------------------------

    @Test
    void noIntervalYieldsNoNextRun() {
        assertNull(ProcessTimeUtil.computeNextRun(scheduler("Daily", null, LocalDateTime.now())));
    }

    @Test
    void noNextRunAtYieldsNoNextRun() {
        assertNull(ProcessTimeUtil.computeNextRun(scheduler("Daily", "1", null)));
    }

    @Test
    void anUnknownFrequencyYieldsNoNextRun() {
        assertNull(ProcessTimeUtil.computeNextRun(scheduler("Fortnightly", "1", LocalDateTime.now())));
    }

    @Test
    void aScheduleWithNoNextRunIsMarkedExpired() {
        Scheduler s = scheduler("Daily", null, LocalDateTime.now());
        ProcessTimeUtil.applyNextRun(s);
        assertTrue(s.isExpired(), "a schedule that cannot advance must not stay live");
    }

    @Test
    void passingTheEndDateExpiresTheSchedule() {
        Scheduler s = scheduler("Daily", "1", LocalDateTime.now().minusHours(1));
        s.setEndDate(LocalDate.now());
        ProcessTimeUtil.applyNextRun(s);
        assertTrue(s.isExpired(), "tomorrow is past an end date of today");
    }

    @Test
    void theEndDateItselfStillRuns() {
        Scheduler s = scheduler("Daily", "1", LocalDateTime.now().minusHours(1));
        s.setEndDate(LocalDate.now().plusDays(1));
        ProcessTimeUtil.applyNextRun(s);
        assertFalse(s.isExpired());
        assertEquals(LocalDate.now().plusDays(1), s.getNextRunAt().toLocalDate());
    }

    @Test
    void emptyDaysOfWeekFallsBackRatherThanLooping() {
        Scheduler s = scheduler("Weekly", "1", LocalDateTime.now().minusDays(1));
        s.setDaysOfWeek("");
        assertNotNull(ProcessTimeUtil.computeNextRun(s));
    }

    @Test
    void unrecognisedDayCodesFallBackRatherThanLooping() {
        Scheduler s = scheduler("Weekly", "1", LocalDateTime.now().minusDays(1));
        s.setDaysOfWeek("XXX,YYY");
        LocalDateTime next = ProcessTimeUtil.computeNextRun(s);
        assertNotNull(next);
        assertTrue(next.isAfter(LocalDateTime.now()));
    }

    // ---- runs missed while the scheduler was down -------------------------------------------

    @Test
    void everySkippedSlotIsAccountedFor() {
        // 55 minutes back on a 10-minute step, deliberately off the boundary: slots land at
        // -45, -35, -25, -15 and -5, and the next is +5 which is genuinely in the future.
        // An exact multiple (say -60) puts a slot on now() itself, and whether that counts
        // depends on microseconds -- it does count, since it is neither the slot being run
        // nor the one being scheduled, but it is no basis for an assertion.
        Scheduler s = scheduler("Mint", "10", LocalDateTime.now().minusMinutes(55));
        List<LocalDateTime> missed = ProcessTimeUtil.computeMissedRuns(s);
        assertEquals(5, missed.size(), "slots strictly between the due one and now");
        assertTrue(missed.stream().allMatch(m -> m.isBefore(LocalDateTime.now())));
    }

    @Test
    void aSlotFallingOnNowCountsAsMissed() {
        // It is not the slot being executed (that is nextRunAt) and applyNextRun will pick
        // the first slot strictly after now, so nothing else would ever account for it.
        Scheduler s = scheduler("Mint", "10", LocalDateTime.now().minusMinutes(60));
        assertEquals(6, ProcessTimeUtil.computeMissedRuns(s).size());
    }

    @Test
    void anOnTimeRunMissesNothing() {
        Scheduler s = scheduler("Daily", "1", LocalDateTime.now().minusSeconds(5));
        assertTrue(ProcessTimeUtil.computeMissedRuns(s).isEmpty());
    }

    @Test
    void missedRunsAreOrderedOldestFirst() {
        Scheduler s = scheduler("Mint", "5", LocalDateTime.now().minusMinutes(30));
        List<LocalDateTime> missed = ProcessTimeUtil.computeMissedRuns(s);
        for (int i = 1; i < missed.size(); i++) {
            assertTrue(missed.get(i).isAfter(missed.get(i - 1)), "must read as a timeline");
        }
    }

    @Test
    void aLongOutageIsBoundedRatherThanRunningForever() {
        // A one-minute schedule dormant for two years is over a million slots. The guard has
        // to stop it; without one this call never returns and the cron thread is gone.
        Scheduler s = scheduler("Mint", "1", LocalDateTime.now().minusYears(2));
        long start = System.currentTimeMillis();
        List<LocalDateTime> missed = ProcessTimeUtil.computeMissedRuns(s);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(missed.size() <= 100000, "the guard caps the list");
        assertTrue(elapsed < 5000, "took " + elapsed + "ms -- must not hang the cron thread");
    }

    // ---- a newly created schedule -----------------------------------------------------------

    @Test
    void aFutureStartIsLeftAlone() {
        Scheduler s = new Scheduler();
        s.setFrequency("Daily");
        s.setIntervalValue("1");
        s.setStartDate(LocalDate.now().plusDays(7));
        s.setStartTime(LocalTime.of(3, 0));
        ProcessTimeUtil.applyInitialSchedule(s);
        assertEquals(LocalDate.now().plusDays(7), s.getNextRunAt().toLocalDate());
        assertFalse(s.isExpired());
    }

    @Test
    void aPastStartRollsForwardToTheNextRealSlot() {
        Scheduler s = new Scheduler();
        s.setFrequency("Daily");
        s.setIntervalValue("1");
        s.setStartDate(LocalDate.now().minusDays(10));
        s.setStartTime(LocalTime.of(3, 0));
        ProcessTimeUtil.applyInitialSchedule(s);
        assertTrue(s.getNextRunAt().isAfter(LocalDateTime.now()), "must never be seeded in the past");
    }

    @Test
    void aStartAlreadyPastItsEndIsBornExpired() {
        Scheduler s = new Scheduler();
        s.setFrequency("Daily");
        s.setIntervalValue("1");
        s.setStartDate(LocalDate.now().minusDays(10));
        s.setStartTime(LocalTime.of(3, 0));
        s.setEndDate(LocalDate.now().minusDays(5));
        ProcessTimeUtil.applyInitialSchedule(s);
        assertTrue(s.isExpired(), "a window that has already closed must not run");
    }
}
