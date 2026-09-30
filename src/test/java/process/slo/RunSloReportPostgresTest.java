package process.slo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import process.AcrossTenantsProxy;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.engine.BulkAction;
import process.engine.DispatchFailures;
import process.engine.ProducerBulkEngine;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.RunEnd;
import process.model.pojo.JobQueue;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.service.impl.MessageQServiceImpl;
import process.model.service.impl.NotifyServiceImpl;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.notifications.NotificationPort;
import process.security.RunCallbackTokens;
import process.security.TenantContext;
import process.util.BusinessTime;
import process.util.OpenSearchAuditLogClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * MIG-196 against a changelog-built database: the pipeline execution SLI is reproduced from stored rows for a known
 * past window, and agrees with the live counter.
 *
 * Runs are driven through the application's own paths -- the worker callback with its retries (NotifyServiceImpl),
 * the stall sweep, a person's fail, a skip, the dispatch side -- on the real JPA mapping, with the application clock
 * fixed for the paths that read it. Beside them, rows as they stand from before V174 (no end_reason), classified by
 * their status line. Then the window [2026-09-20T00:00Z, 2026-09-21T00:00Z) is asked for, twice.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchPostgres).
 */
class RunSloReportPostgresTest {

    private static final Instant FROM = Instant.parse("2026-09-20T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-21T00:00:00Z");
    private static final long TENANT = 2905L;
    /** Retries three times. */
    private static final long JOB = 1196L;
    /** Tries once. */
    private static final long ONE_SHOT = 1197L;

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private static TransactionServiceImpl store;
    private static BulkAction bulkAction;
    private static SimpleMeterRegistry registry;
    private static RunSloReport report;
    private static final JobMail MAIL = mock(JobMail.class);

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("run_slo");
        jpa = new ScratchJpa(db);
        JdbcTemplate sql = db.jdbc();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', 'SLO', 'Slo')", TENANT);
        for (long[] job : new long[][] {{JOB, 3}, {ONE_SHOT, 1}}) {
            sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id, "
                + "complete_job, fail_job, skip_job, max_attempts, retry_backoff_seconds, assigned_user_id) "
                // Assigned to user 1, the person who fails a run by hand below: a TENANT_USER acts on their own jobs only.
                + "VALUES (?, now(), 'Auto', 'slo', 'Active', 1, ?, false, false, false, ?, 60, 1)", job[0], TENANT, job[1]);
        }
        store = new TransactionServiceImpl(jpa.repository(SourceJobRepository.class), jpa.repository(SchedulerRepository.class),
            jpa.repository(JobQueueRepository.class), null, jpa.repository(JobAuditLogRepository.class),
            jpa.repository(SourceTaskRepository.class), mock(OpenSearchAuditLogClient.class));
        registry = new SimpleMeterRegistry();
        bulkAction = new BulkAction(store, mock(NotificationPort.class), new RunOutcomes(registry));
        // As the application runs it since MIG-258: process_app, nobody signed in, the across-tenants proxy.
        report = AcrossTenantsProxy.of(new RunSloReport(db.appJdbc()));
        try {
            drive();
        } finally {
            BusinessTime.useSystemClock();
            TenantContext.clear();
        }
        legacy(sql);
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

    private static <T> T tx(Supplier<T> work) {
        return jpa.transactions().execute(status -> work.get());
    }

    private static void at(Instant instant) {
        BusinessTime.useClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static Instant w(int hours) {
        return FROM.plus(Duration.ofHours(hours));
    }

    private static long queue(long job, Instant scheduled) {
        return tx(() -> bulkAction.createJobQueue(job, BusinessTime.wallClockOf(scheduled), JobStatus.Queue,
            "Job %s now in the queue.", false)).getJobQueueId();
    }

    /** What the dispatcher's hand-off and the worker's Running write. */
    private static void running(long job, long run) {
        tx(() -> {
            bulkAction.changeJobQueueStatus(run, JobStatus.Start, null);
            bulkAction.changeJobStatus(job, JobStatus.Start);
            bulkAction.changeJobQueueStatus(run, JobStatus.Running, null);
            bulkAction.changeJobStatus(job, JobStatus.Running);
            return null;
        });
    }

    private static void reports(long job, long run, JobStatus status, Instant at) {
        SourceJobQueueDto dto = new SourceJobQueueDto();
        dto.setJobId(job);
        dto.setJobQueueId(run);
        dto.setJobStatus(status);
        dto.setJobStatusMessage("worker: " + status);
        dto.setEndTime(BusinessTime.wallClockOf(at));
        tx(() -> new NotifyServiceImpl(bulkAction, MAIL, store, mock(NotificationPort.class)).changeState(dto));
    }

    private static long completed(Instant at) {
        long run = queue(JOB, at.minusSeconds(600));
        running(JOB, run);
        reports(JOB, run, JobStatus.Completed, at);
        return run;
    }

    static long retriedTwiceThenCompleted;
    static long declined;
    static long failedThreeTimes;

    /** The runs, in order: one job has one run in flight at a time (V83). */
    private static void drive() {
        completed(FROM);                                            // at the window's start: in it
        completed(w(1));
        retriedTwiceThenCompleted = queue(JOB, w(2).minusSeconds(900));
        for (int attempt = 1; attempt <= 3; attempt++) {
            running(JOB, retriedTwiceThenCompleted);
            reports(JOB, retriedTwiceThenCompleted, attempt < 3 ? JobStatus.Failed : JobStatus.Completed, w(2));
        }
        failedThreeTimes = queue(JOB, w(3).minusSeconds(900));
        for (int attempt = 1; attempt <= 3; attempt++) {
            running(JOB, failedThreeTimes);
            reports(JOB, failedThreeTimes, JobStatus.Failed, w(3));
        }
        // The stall sweep at w(4): a run started seven hours before, silent since.
        long stalled = queue(JOB, w(4).minus(Duration.ofHours(7)));
        running(JOB, stalled);
        at(w(4));
        tx(() -> {
            new ProducerBulkEngine(bulkAction, store, MAIL, mock(RunCallbackTokens.class), null).reconcileStalledRuns();
            return null;
        });
        // A person fails a run at w(5).
        long failedByHand = queue(JOB, w(5).minusSeconds(60));
        running(JOB, failedByHand);
        at(w(5));
        TenantContext.set(TENANT, "TENANT_USER", 1L, "a@example.com");
        tx(() -> new MessageQServiceImpl(bulkAction, null, jpa.repository(JobQueueRepository.class),
            jpa.repository(SourceJobRepository.class), MAIL).failJobLogs(failedByHand));
        TenantContext.clear();
        // A skip at w(6), and the dispatch side at w(7) and w(8): refused on configuration, and out of retries.
        tx(() -> bulkAction.createJobQueue(JOB, BusinessTime.wallClockOf(w(6)), JobStatus.Skip, "Job %s skip, already in queue.", true));
        DispatchFailures failures = new DispatchFailures(bulkAction, store, MAIL, TransactionOperations.withoutTransaction());
        long refused = queue(JOB, w(7).minusSeconds(60));
        at(w(7));
        tx(() -> {
            failures.close(store.findJobQueueByJobQueueId(refused).get(), "Broker is not active for job 1196.", false);
            return null;
        });
        long sendFailed = queue(ONE_SHOT, w(8).minusSeconds(60));
        at(w(8));
        tx(() -> {
            failures.close(store.findJobQueueByJobQueueId(sendFailed).get(), "the broker would not take it", true);
            return null;
        });
        // MIG-201's decline at w(10): the worker fails a run it never started -- never retried, counted bad.
        declined = queue(JOB, w(10).minusSeconds(60));
        tx(() -> {
            bulkAction.changeJobQueueStatus(declined, JobStatus.Start, null);
            bulkAction.changeJobStatus(JOB, JobStatus.Start);
            return null;
        });
        reports(JOB, declined, JobStatus.Failed, w(10));
        // A slot missed while the platform was down, recorded at w(11): never attempted, excluded.
        tx(() -> bulkAction.createJobQueue(JOB, BusinessTime.wallClockOf(w(11)), JobStatus.Missed,
            "Job %s missed its scheduled run -- the system was catching up after downtime.", true));
        // Outside the window: exactly at its end, and the day before.
        completed(TO);
        completed(FROM.minus(Duration.ofDays(1)));
    }

    /** Rows as they stood before V174: no end_reason, their status line the only witness. */
    private static void legacy(JdbcTemplate sql) {
        Object[][] rows = {
            {9101L, "Completed", "Job 1196 now complete."},
            {9102L, "Failed", "Job 1196 fail by manual."},
            {9103L, "Interrupt", "Job 1196 stopped reporting and was closed after 6 hours. Its worker may have finished the work "
                + "-- check the output before running it again."},
            {9104L, "Failed", "source refused the connection"},
            {9105L, "Interrupt", "Job 1196 interrupted."},
        };
        int minute = 0;
        for (Object[] row : rows) {
            sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, job_status_message, start_time, end_time, "
                    + "date_created, status, tenant_id) VALUES (?, ?, ?, ?, cast(? as timestamptz), cast(? as timestamptz), "
                    + "cast(? as timestamptz), 'Active', ?)", row[0], JOB, row[1], row[2], w(9).toString(),
                w(9).plusSeconds(60L * ++minute).toString(), w(9).toString(), TENANT);
        }
    }

    private static double counted(String slo) {
        return registry.find(RunOutcomes.ENDED).tag("slo", slo).counters().stream().mapToDouble(Counter::count).sum();
    }

    @Test
    void theWindowIsReproducedFromStoredRows() {
        RunSloReport.Window window = report.measure(FROM, TO);
        // good: two completions, the run completed on its third attempt, a pre-V174 completion
        assertThat(window.good).isEqualTo(4);
        // bad: the run that failed three times (once), the sweep's Interrupt, the dispatch out of retries, the decline,
        //      a pre-V174 sweep Interrupt and a pre-V174 failure that proves nothing else
        assertThat(window.bad).isEqualTo(6);
        // excluded: a person's fail, a skip, a missed slot, a configuration refusal, and two pre-V174 console closes
        assertThat(window.excluded).isEqualTo(6);
        assertThat(window.successRate()).isEqualTo(4.0 / 10);
        assertThat(window.errorBudget()).isCloseTo(10 * 0.0001, Offset.offset(1e-12));
        assertThat(groups(window)).containsExactlyInAnyOrder(
            "Completed/WORKER/good=3", "Completed/UNATTRIBUTED/good=1",
            "Failed/WORKER/bad=1", "Failed/DISPATCH/bad=1", "Failed/DECLINED/bad=1", "Failed/UNATTRIBUTED/bad=1",
            "Interrupt/STALLED/bad=2",
            "Failed/OPERATOR/excluded=2", "Interrupt/OPERATOR/excluded=1", "Failed/REFUSED/excluded=1",
            "Skip/SKIPPED/excluded=1", "Missed/MISSED/excluded=1");
        Map<String, Object> figures = window.toMap();
        assertThat(figures.get("numerator")).isEqualTo(4L);
        assertThat(figures.get("denominator")).isEqualTo(10L);
        assertThat((Double) figures.get("errorBudgetRemaining")).isCloseTo(1 - 6 / (10 * 0.0001), Offset.offset(1e-6));
    }

    /** C7c: each retried run is one row and one run -- counted once in the window, and once by the counter. */
    @Test
    void aRetriedRunIsCountedOnce() {
        JdbcTemplate sql = db.jdbc();
        assertThat(sql.queryForObject("SELECT attempt FROM job_queue WHERE job_queue_id = ?", Integer.class, retriedTwiceThenCompleted))
            .isEqualTo(3);
        assertThat(sql.queryForObject("SELECT attempt FROM job_queue WHERE job_queue_id = ?", Integer.class, failedThreeTimes))
            .isEqualTo(3);
        RunSloReport.Window justThem = report.measure(w(2).minusSeconds(1), w(3).plusSeconds(1));
        assertThat(justThem.good).isEqualTo(1);
        assertThat(justThem.bad).isEqualTo(1);
    }

    /** The same question asked again, after more runs ended elsewhere, has the same answer. */
    @Test
    void askingAgainGivesTheSameAnswer() {
        RunSloReport.Window first = report.measure(FROM, TO);
        db.jdbc().update("INSERT INTO job_queue (job_queue_id, job_id, job_status, end_time, date_created, status, tenant_id) "
            + "VALUES (9201, ?, 'Failed', cast(? as timestamptz), now(), 'Active', ?)", ONE_SHOT, TO.plus(Duration.ofDays(3)).toString(), TENANT);
        RunSloReport.Window second = report.measure(FROM, TO);
        assertThat(groups(second)).containsExactlyInAnyOrderElementsOf(groups(first));
    }

    /** The counter and the rows agree on every run the application closed (the pre-V174 rows were never counted). */
    @Test
    void theLiveCounterAgreesWithTheRows() {
        RunSloReport.Window everything = report.measure(FROM.minus(Duration.ofDays(2)), TO.plus(Duration.ofDays(2)));
        long legacyGood = 1, legacyBad = 2, legacyExcluded = 2;
        assertThat(counted("bad")).isPositive();
        assertThat(counted("good")).isEqualTo(everything.good - legacyGood);
        assertThat(counted("bad")).isEqualTo(everything.bad - legacyBad);
        assertThat(counted("excluded")).isEqualTo(everything.excluded - legacyExcluded);
    }

    /** MIG-201: a decline is one run, closed at once, never retried, counted bad. */
    @Test
    void aDeclineIsOneFailedRunNeverRetried() {
        JdbcTemplate sql = db.jdbc();
        assertThat(sql.queryForObject("SELECT attempt FROM job_queue WHERE job_queue_id = ?", Integer.class, declined)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT end_reason FROM job_queue WHERE job_queue_id = ?", String.class, declined))
            .isEqualTo("DECLINED");
        RunSloReport.Window justIt = report.measure(w(10).minusSeconds(1), w(10).plusSeconds(1));
        assertThat(justIt.good).isZero();
        assertThat(justIt.bad).isEqualTo(1);
    }

    /** The console's daily series: one row per UTC day holding ended runs, and the days add up to the window. */
    @Test
    void theDailySeriesAddsUpToTheWindow() {
        Instant from = FROM.minus(Duration.ofDays(2));
        Instant to = TO.plus(Duration.ofDays(2));
        List<RunSloReport.Day> days = report.measureDaily(from, to);
        assertThat(days).extracting(day -> day.day.toString()).containsExactly("2026-09-19", "2026-09-20", "2026-09-21");
        RunSloReport.Day theDay = days.get(1);
        RunSloReport.Window window = report.measure(FROM, TO);
        assertThat(new long[] {theDay.good, theDay.bad, theDay.excluded}).containsExactly(window.good, window.bad, window.excluded);
        RunSloReport.Window everything = report.measure(from, to);
        assertThat(days.stream().mapToLong(day -> day.good).sum()).isEqualTo(everything.good);
        assertThat(days.stream().mapToLong(day -> day.bad).sum()).isEqualTo(everything.bad);
        assertThat(days.stream().mapToLong(day -> day.excluded).sum()).isEqualTo(everything.excluded);
        assertThat(days.get(0).successRate()).isEqualTo(1.0);
    }

    /** The burn alert's once-per-window row: taken once, refused while held, taken again when it has passed. */
    @Test
    void anAlertIsTakenOncePerWindow() {
        SloBurnAlerts.Once once = SloBurnAlerts.shedlockTable(db.appJdbc());
        assertThat(once.first("sloBurn:test:PAGE_1H", Duration.ofHours(1))).isTrue();
        assertThat(once.first("sloBurn:test:PAGE_1H", Duration.ofHours(1))).isFalse();
        assertThat(once.first("sloBurn:test:PAGE_6H", Duration.ofHours(6))).isTrue();
        db.jdbc().update("UPDATE shedlock SET lock_until = now() - interval '1 second' WHERE name = 'sloBurn:test:PAGE_1H'");
        assertThat(once.first("sloBurn:test:PAGE_1H", Duration.ofHours(1))).isTrue();
        assertThat(db.jdbc().queryForObject("SELECT lock_until > now() + interval '59 minutes' FROM shedlock WHERE name = ?",
            Boolean.class, "sloBurn:test:PAGE_1H")).isTrue();
    }

    @Test
    void theEndReasonColumnTakesOnlyTheEnumsSpellings() {
        assertThat(db.jdbc().queryForList("SELECT DISTINCT end_reason FROM job_queue WHERE end_reason IS NOT NULL", String.class))
            .allSatisfy(reason -> assertThat(RunEnd.valueOf(reason)).isNotNull());
        assertThatThrownBy(() -> db.jdbc().update(
            "UPDATE job_queue SET end_reason = 'worker' WHERE job_queue_id = 9101")).hasMessageContaining("ck_job_queue_end_reason_enum");
    }

    /** One indexed read: the window is answered from idx_job_queue_ended_at, not a scan of job_queue. */
    @Test
    void theWindowIsReadThroughItsIndex() {
        List<String> plan = db.transactions().execute(status -> {
            JdbcTemplate sql = db.jdbc();
            sql.execute("SET LOCAL enable_seqscan = off");
            return sql.queryForList("EXPLAIN " + RunSloReport.QUERY.replaceFirst("\\?", "'" + FROM + "'")
                .replaceFirst("\\?", "'" + TO + "'"), String.class);
        });
        assertThat(String.join("\n", plan)).contains("idx_job_queue_ended_at");
    }

    private static List<String> groups(RunSloReport.Window window) {
        return window.groups.stream().map(g -> g.status + "/" + (g.reason == null ? RunSloReport.UNATTRIBUTED : g.reason.name())
            + "/" + g.slo.tag() + "=" + g.runs).collect(Collectors.toList());
    }
}
