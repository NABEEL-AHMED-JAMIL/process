package process.time;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.model.pojo.Scheduler;
import process.model.repository.JobQueueRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.util.BusinessTime;
import process.util.ProcessTimeUtil;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-28: a Daily job at 09:00 fires at 09:00 in Chicago on both sides of a DST change, as it did when the JVM
 * was told it lived in Chicago -- now with the JVM on UTC and next_run_at an instant.
 *
 * The walk is the enqueuer's own: ProcessTimeUtil seeds and advances next_run_at in Chicago wall-clock, JPA
 * stores it, and the claim (SchedulerRepository.claimNextDueScheduler, through TransactionServiceImpl, which is
 * where the application's "now" becomes the query's cutoff) takes the slot only once it is due. The clock is
 * BusinessTime's, set per step.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchPostgres).
 */
class SchedulerAcrossDstPostgresTest {

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private static TransactionServiceImpl transactions;
    private static SchedulerRepository schedulers;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("scheduler_dst");
        jpa = new ScratchJpa(db);
        schedulers = jpa.repository(SchedulerRepository.class);
        transactions = new TransactionServiceImpl(jpa.repository(SourceJobRepository.class), schedulers,
            jpa.repository(JobQueueRepository.class), null, null, null, null);
        JdbcTemplate sql = db.jdbc();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (3201, 'Active', 'DST', 'Daylight')");
        for (long job : new long[] {32001, 32002, 32003}) {
            sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
                + "VALUES (?, now(), 'Auto', 'dst job', 'Active', 1, 3201)", job);
        }
    }

    @AfterAll
    static void drop() throws Exception {
        if (jpa != null) {
            jpa.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @AfterEach
    void systemClock() {
        BusinessTime.useSystemClock();
    }

    private static void at(String instant) {
        BusinessTime.useClock(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static Scheduler daily(long jobId, LocalDate start, LocalTime time) {
        Scheduler scheduler = new Scheduler();
        scheduler.setJobId(jobId);
        scheduler.setStartDate(start);
        scheduler.setStartTime(time);
        scheduler.setFrequency("Daily");
        scheduler.setIntervalValue("1");
        ProcessTimeUtil.applyInitialSchedule(scheduler);
        return jpa.transactions().execute(status -> schedulers.save(scheduler));
    }

    private static String nextRunUtc(long schedulerId) {
        return db.jdbc().queryForObject("SELECT to_char(next_run_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') FROM scheduler "
            + "WHERE scheduler_id = ?", String.class, schedulerId);
    }

    private static String nextRunChicago(long schedulerId) {
        return db.jdbc().queryForObject("SELECT to_char(next_run_at AT TIME ZONE 'America/Chicago', 'YYYY-MM-DD HH24:MI') FROM scheduler "
            + "WHERE scheduler_id = ?", String.class, schedulerId);
    }

    /** What the enqueuer would claim now: the one due slot, if any. Rolled back, so the claim leaves nothing behind. */
    private static Optional<Long> claimable() {
        return jpa.transactions().execute(status -> {
            status.setRollbackOnly();
            return transactions.claimNextDueScheduler(BusinessTime.now(), Collections.singletonList(-1L)).map(Scheduler::getSchedulerId);
        });
    }

    /** The enqueuer's advance, after it has run a slot. */
    private static void advance(long schedulerId) {
        jpa.transactions().execute(status -> {
            Scheduler scheduler = schedulers.findById(schedulerId).get();
            ProcessTimeUtil.applyNextRun(scheduler);
            return schedulers.save(scheduler);
        });
    }

    private static void remove(long schedulerId) {
        jpa.transactions().execute(status -> {
            schedulers.deleteById(schedulerId);
            return null;
        });
    }

    @Test
    void nineOClockStaysNineOClockOverSpringForward() {
        at("2026-03-06T16:00:00Z"); // Friday 10:00 CST
        long id = daily(32001, LocalDate.of(2026, 3, 6), LocalTime.of(9, 0)).getSchedulerId();
        assertThat(nextRunChicago(id)).isEqualTo("2026-03-07 09:00");
        assertThat(nextRunUtc(id)).isEqualTo("2026-03-07 15:00");

        at("2026-03-07T14:59:00Z"); // 08:59 CST: not yet
        assertThat(claimable()).isEmpty();
        at("2026-03-07T15:00:30Z"); // 09:00:30 CST
        assertThat(claimable()).contains(id);
        advance(id);
        // Sunday: clocks went forward at 02:00. Still 09:00 in Chicago -- which is now 14:00 UTC, not 15:00.
        assertThat(nextRunChicago(id)).isEqualTo("2026-03-08 09:00");
        assertThat(nextRunUtc(id)).isEqualTo("2026-03-08 14:00");

        at("2026-03-08T13:59:00Z"); // 08:59 CDT
        assertThat(claimable()).isEmpty();
        at("2026-03-08T14:00:30Z"); // 09:00:30 CDT
        assertThat(claimable()).contains(id);
        advance(id);
        assertThat(nextRunChicago(id)).isEqualTo("2026-03-09 09:00");
        remove(id);
    }

    @Test
    void nineOClockStaysNineOClockOverFallBack() {
        at("2026-10-30T15:00:00Z"); // Friday 10:00 CDT
        long id = daily(32002, LocalDate.of(2026, 10, 30), LocalTime.of(9, 0)).getSchedulerId();
        assertThat(nextRunUtc(id)).isEqualTo("2026-10-31 14:00");

        at("2026-10-31T14:00:30Z");
        assertThat(claimable()).contains(id);
        advance(id);
        // Sunday 1 November: clocks went back at 02:00. 09:00 CST is 15:00 UTC.
        assertThat(nextRunChicago(id)).isEqualTo("2026-11-01 09:00");
        assertThat(nextRunUtc(id)).isEqualTo("2026-11-01 15:00");
        at("2026-11-01T14:00:30Z"); // 08:00:30 CST -- 09:00 had it still been CDT
        assertThat(claimable()).isEmpty();
        at("2026-11-01T15:00:30Z");
        assertThat(claimable()).contains(id);
        remove(id);
    }

    @Test
    void aSlotInTheRepeatedHourIsStoredAsTheSecondAndStillFiresWhenTheWallClockFirstReachesIt() {
        // 01:30 happens twice on 1 November. V100 read a stored 01:30 as the second (CST, 07:30Z), as the old JVM
        // did, and a slot the scheduler writes now means the same: one reading, whichever wrote it.
        at("2026-10-31T12:00:00Z");
        long repeated = daily(32003, LocalDate.of(2026, 10, 31), LocalTime.of(1, 30)).getSchedulerId();
        try {
            assertThat(nextRunChicago(repeated)).isEqualTo("2026-11-01 01:30");
            assertThat(nextRunUtc(repeated)).isEqualTo("2026-11-01 07:30");
            // The application's "now" is wall-clock too, and goes to the database the same way: the first 01:29 is
            // 01:29, 07:29Z. So the slot is due when the wall clock first reads 01:30 -- the first time, 06:30Z --
            // exactly as the naive column compared it before. Wall-clock comparisons are what V100 keeps.
            at("2026-11-01T06:29:00Z");
            assertThat(claimable()).isEmpty();
            at("2026-11-01T06:30:30Z");
            assertThat(claimable()).contains(repeated);
        } finally {
            remove(repeated);
        }

        // 02:30 does not exist on 8 March: the slot is 03:30 CDT (08:30Z), the gap's length on.
        at("2026-03-07T12:00:00Z");
        long gap = daily(32003, LocalDate.of(2026, 3, 7), LocalTime.of(2, 30)).getSchedulerId();
        try {
            assertThat(nextRunChicago(gap)).isEqualTo("2026-03-08 03:30");
            assertThat(nextRunUtc(gap)).isEqualTo("2026-03-08 08:30");
            at("2026-03-08T08:30:30Z");
            assertThat(claimable()).contains(gap);
            advance(gap);
            // And the schedule is still 02:30 after it: the slot read back as 03:30, but a Daily job at 02:30 steps from
            // 02:30. Stepping from the reading moved it to 03:30 for good -- the naive column never did that.
            assertThat(nextRunChicago(gap)).isEqualTo("2026-03-09 02:30");
        } finally {
            remove(gap);
        }
    }
}
