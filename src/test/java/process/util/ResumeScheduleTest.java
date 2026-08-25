package process.util;

import org.junit.jupiter.api.Test;
import process.model.pojo.Scheduler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What resuming a paused job has to do to its schedule.
 *
 * next_run_at only moves when a job is dispatched, so a job paused at 09:45 still says 09:45
 * hours later. Coming back, it is overdue: it fires at once, and computeMissedRuns writes down
 * every slot that went by while it was deliberately paused -- 51 of them for a five-minute job
 * paused for four hours, each with an audit log and a notification.
 */
public class ResumeScheduleTest {

    private Scheduler pausedFiveMinuteJob(int hoursAgo) {
        Scheduler scheduler = new Scheduler();
        scheduler.setFrequency("Mint");
        scheduler.setIntervalValue("5");
        scheduler.setStartDate(LocalDate.now().minusDays(1));
        scheduler.setStartTime(LocalTime.of(0, 0));
        // Frozen where the pause left it.
        scheduler.setNextRunAt(LocalDateTime.now().minusHours(hoursAgo));
        return scheduler;
    }

    @Test
    void aPausedScheduleIsOverdueUntilItIsMovedOn() {
        Scheduler scheduler = pausedFiveMinuteJob(4);
        assertTrue(scheduler.getNextRunAt().isBefore(LocalDateTime.now()),
            "the setup is wrong if the paused slot is not already in the past");
        // This is what the pause leaves behind, and what would be replayed on resume.
        assertTrue(ProcessTimeUtil.computeMissedRuns(scheduler).size() > 40,
            "a four-hour pause on a five-minute job should look like many missed slots");
    }

    @Test
    void resumingMovesTheScheduleIntoTheFuture() {
        Scheduler scheduler = pausedFiveMinuteJob(4);
        ProcessTimeUtil.applyInitialSchedule(scheduler);
        assertTrue(scheduler.getNextRunAt().isAfter(LocalDateTime.now()),
            "a resumed job should be waiting for its next slot, not already late");
    }

    @Test
    void nothingIsReplayedOnceTheScheduleHasMovedOn() {
        Scheduler scheduler = pausedFiveMinuteJob(4);
        ProcessTimeUtil.applyInitialSchedule(scheduler);
        assertEquals(0, ProcessTimeUtil.computeMissedRuns(scheduler).size(),
            "the runs skipped during a deliberate pause are not missed runs");
    }

    @Test
    void anEndedScheduleStaysEndedWhenResumed() {
        Scheduler scheduler = pausedFiveMinuteJob(4);
        scheduler.setEndDate(LocalDate.now().minusDays(1));
        ProcessTimeUtil.applyInitialSchedule(scheduler);
        assertTrue(scheduler.isExpired(),
            "resuming must not revive a schedule whose end date has passed");
    }
}
