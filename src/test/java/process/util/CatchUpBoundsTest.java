package process.util;

import org.junit.jupiter.api.Test;
import process.model.pojo.Scheduler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a long gap costs when the scheduler finally notices it.
 *
 * Every missed slot becomes a queue row, an audit log line and a notification, written inside
 * the tick that noticed, under a lock with a ten-minute timeout. And the walk that finds the
 * next slot is bounded, so a schedule left long enough ran out of steps and returned a time
 * that had already passed -- which makes the job due immediately, and due again every tick.
 */
public class CatchUpBoundsTest {

    private Scheduler every(String frequency, String interval, LocalDateTime lastSlot) {
        Scheduler scheduler = new Scheduler();
        scheduler.setFrequency(frequency);
        scheduler.setIntervalValue(interval);
        scheduler.setStartDate(LocalDate.now().minusYears(2));
        scheduler.setStartTime(LocalTime.of(0, 0));
        scheduler.setNextRunAt(lastSlot);
        return scheduler;
    }

    @Test
    void aLongOutageIsWrittenDownButNotInFull() {
        // Five-minute job, a fortnight adrift: 4,032 slots went by.
        Scheduler scheduler = every("Mint", "5", LocalDateTime.now().minusDays(14));
        List<LocalDateTime> missed = ProcessTimeUtil.computeMissedRuns(scheduler);
        assertEquals(ProcessTimeUtil.MAX_MISSED_RUNS_REPLAYED, missed.size(),
            "a fortnight of five-minute slots should be capped, not replayed in full");
    }

    @Test
    void theSlotsKeptAreTheMostRecentOnes() {
        Scheduler scheduler = every("Mint", "5", LocalDateTime.now().minusDays(14));
        List<LocalDateTime> missed = ProcessTimeUtil.computeMissedRuns(scheduler);
        // Oldest first, and the last of them within an interval of now.
        assertTrue(missed.get(0).isBefore(missed.get(missed.size() - 1)), "ordered oldest first");
        assertTrue(missed.get(missed.size() - 1).isAfter(LocalDateTime.now().minusMinutes(10)),
            "after a long gap the recent slots are the ones worth keeping");
    }

    @Test
    void ashortGapIsStillReportedExactly() {
        // Three slots, well under the cap: nothing should be lost.
        Scheduler scheduler = every("Hr", "1", LocalDateTime.now().minusHours(3));
        assertEquals(3, ProcessTimeUtil.computeMissedRuns(scheduler).size());
    }

    @Test
    void theNextRunIsNeverAlreadyInThePast() {
        // Far enough back that the bounded walk cannot reach the present.
        Scheduler scheduler = every("Mint", "5", LocalDateTime.now().minusYears(2));
        LocalDateTime next = ProcessTimeUtil.computeNextRun(scheduler);
        assertNotNull(next, "a schedule that can still run should be given a next slot");
        assertTrue(next.isAfter(LocalDateTime.now()),
            "a next run in the past makes the job due for ever");
    }

    @Test
    void anOnTimeScheduleIsUnaffected() {
        Scheduler scheduler = every("Daily", "1", LocalDateTime.now().plusHours(2));
        assertEquals(0, ProcessTimeUtil.computeMissedRuns(scheduler).size());
        assertTrue(ProcessTimeUtil.computeNextRun(scheduler).isAfter(LocalDateTime.now()));
    }
}
