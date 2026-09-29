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
 * Wave 4: a Cron job fires at the expected Chicago times on both sides of a DST change -- stored in V187's
 * cron_expression, seeded and advanced by ProcessTimeUtil in Chicago wall-clock, written as an instant, and taken by
 * the enqueuer's own claim (SchedulerRepository.claimNextDueScheduler through TransactionServiceImpl) only once due.
 * SchedulerAcrossDstPostgresTest's walk, for the Cron frequency. The clock is BusinessTime's, set per step.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchPostgres).
 */
class CronSchedulerAcrossDstPostgresTest {

    private static final long TENANT = 3202L;

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private static TransactionServiceImpl transactions;
    private static SchedulerRepository schedulers;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("scheduler_cron_dst");
        jpa = new ScratchJpa(db);
        schedulers = jpa.repository(SchedulerRepository.class);
        transactions = new TransactionServiceImpl(jpa.repository(SourceJobRepository.class), schedulers,
            jpa.repository(JobQueueRepository.class), null, null, null, null);
        JdbcTemplate sql = db.jdbc();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', 'CRON', 'Cron DST')", TENANT);
        for (long job : new long[] {32101, 32102, 32103, 32104}) {
            sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
                + "VALUES (?, now(), 'Auto', 'cron dst job', 'Active', 1, ?)", job, TENANT);
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

    private static long cron(long jobId, String expression, LocalDate start) {
        Scheduler scheduler = new Scheduler();
        scheduler.setJobId(jobId);
        scheduler.setTenantId(TENANT);
        scheduler.setStartDate(start);
        scheduler.setStartTime(LocalTime.MIDNIGHT);
        scheduler.setFrequency("Cron");
        scheduler.setCronExpression(expression);
        ProcessTimeUtil.applyInitialSchedule(scheduler);
        return jpa.transactions().execute(status -> schedulers.save(scheduler)).getSchedulerId();
    }

    private static String nextRun(long schedulerId, String zone) {
        return db.jdbc().queryForObject("SELECT to_char(next_run_at AT TIME ZONE '" + zone + "', 'YYYY-MM-DD HH24:MI') FROM scheduler "
            + "WHERE scheduler_id = ?", String.class, schedulerId);
    }

    /** What the enqueuer would claim now. Rolled back, so the claim leaves nothing behind. */
    private static Optional<Long> claimable() {
        return jpa.transactions().execute(status -> {
            status.setRollbackOnly();
            return transactions.claimNextDueScheduler(BusinessTime.now(), Collections.singletonList(-1L)).map(Scheduler::getSchedulerId);
        });
    }

    /** The enqueuer's advance, after it has run a slot -- from the row as the database hands it back. */
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
    void theExpressionIsStoredAndReadBack() {
        at("2026-03-06T16:00:00Z");
        long id = cron(32101, "0 9 * * MON-FRI", LocalDate.of(2026, 3, 6));
        try {
            assertThat(db.jdbc().queryForObject("SELECT frequency || ' ' || cron_expression FROM scheduler WHERE scheduler_id = ?",
                String.class, id)).isEqualTo("Cron 0 9 * * MON-FRI");
            String read = jpa.transactions().execute(status -> schedulers.findById(id).get().getCronExpression());
            assertThat(read).isEqualTo("0 9 * * MON-FRI");
        } finally {
            remove(id);
        }
    }

    @Test
    void nineOClockWeekdaysStayNineOClockInChicagoOverSpringForward() {
        at("2026-03-05T16:00:00Z"); // Thursday 10:00 CST
        long id = cron(32101, "0 9 * * MON-FRI", LocalDate.of(2026, 3, 5));
        try {
            assertThat(nextRun(id, "America/Chicago")).isEqualTo("2026-03-06 09:00");
            assertThat(nextRun(id, "UTC")).isEqualTo("2026-03-06 15:00");
            at("2026-03-06T14:59:00Z");
            assertThat(claimable()).isEmpty();
            at("2026-03-06T15:00:30Z"); // Friday 09:00:30 CST
            assertThat(claimable()).contains(id);
            advance(id);
            // Over the weekend the clocks went forward: Monday's 09:00 is CDT, 14:00 UTC.
            assertThat(nextRun(id, "America/Chicago")).isEqualTo("2026-03-09 09:00");
            assertThat(nextRun(id, "UTC")).isEqualTo("2026-03-09 14:00");
            at("2026-03-09T13:59:00Z");
            assertThat(claimable()).isEmpty();
            at("2026-03-09T14:00:30Z");
            assertThat(claimable()).contains(id);
        } finally {
            remove(id);
        }
    }

    @Test
    void aSlotInTheSpringForwardGapRunsAtThreeThirtyAndTheNextDayIsTwoThirtyAgain() {
        at("2026-03-07T12:00:00Z");
        long id = cron(32102, "30 2 * * *", LocalDate.of(2026, 3, 7));
        try {
            assertThat(nextRun(id, "America/Chicago")).isEqualTo("2026-03-08 03:30");
            assertThat(nextRun(id, "UTC")).isEqualTo("2026-03-08 08:30");
            at("2026-03-08T08:29:00Z");
            assertThat(claimable()).isEmpty();
            at("2026-03-08T08:30:30Z");
            assertThat(claimable()).contains(id);
            advance(id);
            assertThat(nextRun(id, "America/Chicago")).isEqualTo("2026-03-09 02:30");
            assertThat(nextRun(id, "UTC")).isEqualTo("2026-03-09 07:30");
        } finally {
            remove(id);
        }
    }

    @Test
    void nineOClockStaysNineOClockOverFallBack() {
        at("2026-10-30T15:00:00Z"); // Friday 10:00 CDT
        long id = cron(32103, "0 9 * * *", LocalDate.of(2026, 10, 30));
        try {
            assertThat(nextRun(id, "UTC")).isEqualTo("2026-10-31 14:00");
            at("2026-10-31T14:00:30Z");
            assertThat(claimable()).contains(id);
            advance(id);
            assertThat(nextRun(id, "America/Chicago")).isEqualTo("2026-11-01 09:00");
            assertThat(nextRun(id, "UTC")).isEqualTo("2026-11-01 15:00");
            at("2026-11-01T14:00:30Z"); // 08:00:30 CST -- 09:00 had it still been CDT
            assertThat(claimable()).isEmpty();
            at("2026-11-01T15:00:30Z");
            assertThat(claimable()).contains(id);
        } finally {
            remove(id);
        }
    }

    @Test
    void aSlotInTheRepeatedHourFiresOnceWhenTheWallClockFirstReachesIt() {
        at("2026-10-31T12:00:00Z");
        long id = cron(32104, "30 1 * * *", LocalDate.of(2026, 10, 31));
        try {
            assertThat(nextRun(id, "America/Chicago")).isEqualTo("2026-11-01 01:30");
            at("2026-11-01T06:29:00Z"); // 01:29 CDT
            assertThat(claimable()).isEmpty();
            at("2026-11-01T06:30:30Z"); // the first 01:30
            assertThat(claimable()).contains(id);
            advance(id);
            assertThat(nextRun(id, "America/Chicago")).isEqualTo("2026-11-02 01:30");
            at("2026-11-01T07:30:30Z"); // the second 01:30: not again
            assertThat(claimable()).isEmpty();
        } finally {
            remove(id);
        }
    }
}
