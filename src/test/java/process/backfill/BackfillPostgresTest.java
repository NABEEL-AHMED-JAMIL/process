package process.backfill;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import process.ScratchPostgres;
import process.billing.BillingPort;
import process.billing.WorkspaceZones;
import process.notifications.NotificationPort;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.RunParameters;
import process.security.TenantContext;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * MIG-347 end to end on etl_job as the changelog builds it, through the application's pool (process_app, row security):
 * backfills of two workspaces' schedules -- the preview, the start, the dispatcher's ticks with runs completing and failing
 * in between -- and every rule: the concurrency cap and the job's lanes, skip or re-run of completed periods, one open
 * period per job and date, pause, resume, cancel and retry-failed, holds for a paused workspace or a schedule switched off,
 * the workspace's cap on open backfills, the cost estimate, and that one workspace neither reads nor changes another's.
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL.
 */
class BackfillPostgresTest {

    static final long A = 9401L;
    static final long B = 9402L;
    static final long ADMIN_A = 9411L;
    static final long USER_A = 9412L;
    static final long OTHER_A = 9413L;
    static final long ADMIN_B = 9421L;
    static final long JOB_A = 940101L;
    static final long JOB_A2 = 940102L;
    static final long JOB_OTHER = 940103L;
    static final long JOB_B = 940201L;
    static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    /** "Now": 13 October 2026, 10:00 in Chicago. */
    static final Instant NOW = Instant.parse("2026-10-13T15:00:00Z");

    static ScratchPostgres db;
    static JdbcTemplate login;
    static JdbcTemplate app;
    static long nextRun = 94000001L;

    final Map<Long, BigDecimal> billed = new HashMap<>();
    final Map<Long, String> pauses = new HashMap<>();
    final List<Object[]> audit = new ArrayList<>();
    final NotificationPort notifications = mock(NotificationPort.class);
    boolean billingDown;
    BackfillStore store;
    Backfills backfills;
    BackfillDispatcher dispatcher;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("backfill");
        login = db.jdbc();
        app = db.appJdbc();
        for (long t : new long[] {A, B}) {
            login.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", t, "t" + t, "T" + t);
            login.update("INSERT INTO pipeline (pipeline_key, tenant_id, pipeline_id, pipeline_name, status) VALUES (?, ?, ?, ?, 'Active')",
                t, t, "BF-" + t, "Synthetic pipeline " + t);
            login.update("INSERT INTO pipeline_definition (tenant_id, pipeline_key, version, definition) VALUES (?, ?, 1, ?::json)", t, t,
                "{\"version\":1,\"parameters\":[{\"name\":\"region\",\"type\":\"choice\",\"choices\":[\"north\",\"south\"],\"default\":\"north\"},"
                    + "{\"name\":\"limit\",\"type\":\"number\",\"default\":10}],"
                    + "\"steps\":[{\"key\":\"read\",\"task\":\"sample\",\"config\":{\"rows\":[{\"region\":\"{{param.region}}\"}]}}]}");
            login.update("INSERT INTO source_task (task_detail_id, tenant_id, task_name, task_status, pipeline_id, bucket, output_folder, "
                + "date_created) VALUES (?, ?, ?, 'Active', ?, 'synthetic-bucket', 'out/', now())", t, t, "Synthetic task " + t, "BF-" + t);
        }
        job(JOB_A, A, "Synthetic daily A", USER_A);
        job(JOB_A2, A, "Synthetic daily A2", USER_A);
        job(JOB_OTHER, A, "Colleague's synthetic schedule", OTHER_A);
        job(JOB_B, B, "Synthetic daily B", ADMIN_B);
    }

    static void job(long id, long tenant, String name, long owner) {
        login.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, job_running_status, priority, tenant_id, "
            + "task_detail_id, created_by, assigned_user_id) VALUES (?, now(), 'Auto', ?, 'Active', 'Completed', 1, ?, ?, ?, ?)", id, name,
            tenant, tenant, owner, owner);
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void wire() {
        login.update("DELETE FROM backfill");
        login.update("DELETE FROM job_queue WHERE job_id IN (?, ?, ?, ?)", JOB_A, JOB_A2, JOB_OTHER, JOB_B);
        login.update("UPDATE source_job SET job_status = 'Active' WHERE job_id IN (?, ?, ?, ?)", JOB_A, JOB_A2, JOB_OTHER, JOB_B);
        this.billed.clear();
        this.pauses.clear();
        this.audit.clear();
        this.billingDown = false;
        reset(this.notifications);
        BillingPort billing = new BillingPort() {
            @Override
            public String zoneOf(long tenantId) {
                return "America/Chicago";
            }

            @Override
            public RunCosts runCosts(long tenantId, LocalDate from, LocalDate to, Collection<Long> ids) {
                if (BackfillPostgresTest.this.billingDown) {
                    throw new BillingUnavailable("down", null);
                }
                Map<Long, BigDecimal> costs = new LinkedHashMap<>();
                for (Long id : ids) {
                    if (BackfillPostgresTest.this.billed.containsKey(id)) {
                        costs.put(id, BackfillPostgresTest.this.billed.get(id));
                    }
                }
                return new RunCosts("USD", "America/Chicago", costs);
            }
        };
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(db.appPool()));
        this.store = new BackfillStore(app);
        BackfillSettings settings = BackfillSettings.defaults();
        this.backfills = new Backfills(this.store, new PipelineDefinitionStore(app), new WorkspaceZones(billing), billing, settings, tx,
            Clock.fixed(NOW, ZoneOffset.UTC), tenant -> Optional.ofNullable(this.pauses.get(tenant)));
        this.dispatcher = new BackfillDispatcher(this.store, settings, tx, tenant -> Optional.ofNullable(this.pauses.get(tenant)),
            (run, line) -> this.audit.add(new Object[] {run, line}), this.notifications, true);
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    static void as(long tenant, String role, long user) {
        TenantContext.set(tenant, role, user, "user" + user + "@example.test");
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    static Backfills.Request request(long job, String from, String to) {
        Backfills.Request r = new Backfills.Request();
        r.jobId = job;
        r.from = from;
        r.to = to;
        return r;
    }

    /** A finished run of the job for this Chicago wall-clock slot (as a scheduled run records its slot). */
    static long finishedRun(long job, long tenant, String slot, String status) {
        long id = nextRun++;
        login.update("INSERT INTO job_queue (job_queue_id, date_created, start_time, end_time, job_id, job_status, status, tenant_id, run_slot_at) "
            + "VALUES (?, now(), now() - interval '2 minutes', now(), ?, ?, 'Active', ?, ?)", id, job, status, tenant,
            Timestamp.from(LocalDateTime.parse(slot).atZone(CHICAGO).toInstant()));
        return id;
    }

    static void end(long run, String status) {
        login.update("UPDATE job_queue SET job_status = ?, end_time = now(), start_time = now() - interval '30 seconds', job_status_message = ? "
            + "WHERE job_queue_id = ?", status, "Failed".equals(status) ? "Step 1 <read> failed: synthetic failure." : "Done.", run);
    }

    static List<Map<String, Object>> runs(long backfill) {
        return login.queryForList("SELECT job_queue_id, job_status, run_lane, run_params, run_slot_at, tenant_id, run_manual FROM job_queue "
            + "WHERE backfill_id = ? ORDER BY job_queue_id", backfill);
    }

    static List<Long> inFlight(long backfill) {
        return login.queryForList("SELECT job_queue_id FROM job_queue WHERE backfill_id = ? AND job_status IN ('Queue', 'Start', 'Running') "
            + "ORDER BY job_queue_id", Long.class, backfill);
    }

    static String slotState(long backfill, String date) {
        return login.queryForObject("SELECT state FROM backfill_slot WHERE backfill_id = ? AND run_date = ?::date ORDER BY slot_start LIMIT 1",
            String.class, backfill, date);
    }

    long start(long tenant, String role, long user, Backfills.Request r) {
        as(tenant, role, user);
        try {
            return ((Number) this.backfills.start(r, Backfills.Origin.console(user)).get("backfillId")).longValue();
        } finally {
            TenantContext.clear();
        }
    }

    // ---- preview ------------------------------------------------------------------------------------------------

    @Test
    void thePreviewSaysWhichPeriodsAlreadyCompletedAndWhatTheRestShouldCost() {
        long done = finishedRun(JOB_A, A, "2026-10-03T09:00:00", "Completed");
        finishedRun(JOB_A, A, "2026-10-05T09:00:00", "Failed");
        this.billed.put(done, new BigDecimal("0.25000"));
        as(A, "TENANT_ADMIN", ADMIN_A);
        Map<String, Object> preview = this.backfills.preview(request(JOB_A, "2026-10-01", "2026-10-07"));
        assertThat(preview.get("periods")).isEqualTo(7);
        assertThat(preview.get("alreadyCompleted")).isEqualTo(1);
        assertThat(preview.get("willRun")).isEqualTo(6);
        assertThat(preview.get("zone")).isEqualTo("America/Chicago");
        assertThat(preview.get("concurrency")).isEqualTo(2);
        assertThat(preview.get("maxConcurrency")).isEqualTo(4);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dates = (List<Map<String, Object>>) preview.get("dates");
        assertThat(dates.get(2)).containsEntry("date", "2026-10-03").containsEntry("completed", true)
            .containsEntry("start", "2026-10-03T05:00:00Z");
        assertThat(dates.get(4)).containsEntry("completed", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> estimate = (Map<String, Object>) preview.get("estimate");
        assertThat(estimate.get("perRun")).isEqualTo(new BigDecimal("0.25000"));
        assertThat(estimate.get("total")).isEqualTo(new BigDecimal("1.50000"));
        assertThat((String) estimate.get("basis")).startsWith("The average of 1 billed run(s) of this schedule");
        assertThat(preview.get("values")).isEqualTo(map("region", "north", "limit", "10"));

        Backfills.Request rerun = request(JOB_A, "2026-10-01", "2026-10-07");
        rerun.rerunCompleted = true;
        Map<String, Object> again = this.backfills.preview(rerun);
        assertThat(again.get("willRun")).isEqualTo(7);
        // ZT-P8: re-running still says which periods completed before ("1 run again"), not "0 run again".
        assertThat(again.get("alreadyCompleted")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> againDates = (List<Map<String, Object>>) again.get("dates");
        assertThat(againDates.get(2)).containsEntry("completed", true);

        this.billingDown = true;
        @SuppressWarnings("unchecked")
        Map<String, Object> none = (Map<String, Object>) this.backfills.preview(request(JOB_A, "2026-10-01", "2026-10-07")).get("estimate");
        assertThat(none.get("perRun")).isNull();
        assertThat((String) none.get("unavailable")).startsWith("Billing could not be asked just now");

        // A tenant user who may run the schedule sees no cost.
        as(A, "TENANT_USER", USER_A);
        Map<String, Object> theirs = this.backfills.preview(request(JOB_A, "2026-10-01", "2026-10-07"));
        assertThat(theirs.get("estimate")).isNull();
        assertThat(theirs.get("estimateNote")).isEqualTo("Cost estimates are shown to workspace administrators.");
        assertThat(this.store.openCount(A)).isZero();
    }

    @Test
    void theRangeTheStepTheConcurrencyAndTheParametersAreChecked() {
        as(A, "TENANT_ADMIN", ADMIN_A);
        assertThatThrownBy(() -> this.backfills.preview(request(JOB_A, "2026-10-10", "2026-10-14"))).isInstanceOf(Backfills.Refused.class)
            .hasMessage("A backfill is for the past: the range ends 2026-10-14, after today (2026-10-13 in America/Chicago).");
        Backfills.Request hourly = request(JOB_A, "2026-09-01", "2026-09-30");
        hourly.step = "hourly";
        assertThatThrownBy(() -> this.backfills.preview(hourly)).hasMessageContaining("a backfill has at most 366");
        Backfills.Request five = request(JOB_A, "2026-10-01", "2026-10-02");
        five.concurrency = 5;
        assertThatThrownBy(() -> this.backfills.preview(five)).hasMessageStartingWith("Concurrency is 1 to 4 here");
        Backfills.Request monthly = request(JOB_A, "2026-10-01", "2026-10-02");
        monthly.step = "monthly";
        assertThatThrownBy(() -> this.backfills.preview(monthly)).hasMessage("The step is daily, hourly or weekly.");
        Backfills.Request wrong = request(JOB_A, "2026-10-01", "2026-10-02");
        wrong.parameters = Collections.singletonMap("region", "east");
        assertThatThrownBy(() -> this.backfills.preview(wrong)).isInstanceOf(Backfills.Refused.class)
            .hasMessage("The runs' parameters do not stand: region: one of north, south.")
            .extracting(t -> ((Backfills.Refused) t).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        Backfills.Request today = request(JOB_A, "2026-10-13", "2026-10-13");
        today.step = "hourly";
        // 00:00 to 10:00 Chicago have begun.
        assertThat(this.backfills.preview(today).get("periods")).isEqualTo(11);
    }

    // ---- running ------------------------------------------------------------------------------------------------

    @Test
    void runsAreQueuedTwoAtATimeInLanesOfTheirOwnWithTheirDateAndParametersAndCompletedDaysAreSkipped() {
        finishedRun(JOB_A, A, "2026-10-03T09:00:00", "Completed");
        Backfills.Request r = request(JOB_A, "2026-10-01", "2026-10-05");
        r.parameters = Collections.singletonMap("region", "south");
        long id = this.start(A, "TENANT_USER", USER_A, r);
        assertThat(login.queryForObject("SELECT count(*) FROM backfill_slot WHERE backfill_id = ? AND state = 'PENDING'", Integer.class, id))
            .isEqualTo(5);

        // A live run of the job in its own lane does not hold the backfill back, and the backfill's do not make the job busy.
        long live = nextRun++;
        login.update("INSERT INTO job_queue (job_queue_id, date_created, start_time, job_id, job_status, status, tenant_id) VALUES "
            + "(?, now(), now(), ?, 'Running', 'Active', ?)", live, JOB_A, A);

        BackfillDispatcher.Pass first = this.dispatcher.tick();
        assertThat(first.queued).isEqualTo(2);
        List<Map<String, Object>> made = runs(id);
        assertThat(made).hasSize(2);
        assertThat(made).extracting(m -> ((Number) m.get("run_lane")).intValue()).containsExactly(1, 2);
        assertThat(made).extracting(m -> m.get("tenant_id")).containsOnly(A);
        assertThat(made).extracting(m -> m.get("run_manual")).containsOnly(false);
        RunParameters.Stored values = RunParameters.read((String) made.get(0).get("run_params"));
        assertThat(values.run.date).isEqualTo("2026-10-01");
        assertThat(values.run.windowStart).isEqualTo("2026-10-01T00:00:00-05:00");
        assertThat(values.run.windowEnd).isEqualTo("2026-10-02T00:00:00-05:00");
        assertThat(values.params).containsEntry("region", "south");
        assertThat(((Timestamp) made.get(1).get("run_slot_at")).toInstant()).isEqualTo(Instant.parse("2026-10-02T05:00:00Z"));
        assertThat((String) this.audit.get(0)[1]).contains("backfill #" + id + ", period 1 of 5 -- 2026-10-01");
        assertThat(login.queryForObject("SELECT count(*) FROM job_queue WHERE job_id = ? AND run_lane = 0 AND "
            + "UPPER(job_status) IN ('QUEUE', 'START', 'RUNNING')", Integer.class, JOB_A)).isEqualTo(1);

        // Both lanes busy: nothing more until one ends.
        assertThat(this.dispatcher.tick().queued).isZero();
        end(((Number) made.get(0).get("job_queue_id")).longValue(), "Completed");
        BackfillDispatcher.Pass third = this.dispatcher.tick();
        assertThat(third.settled).isEqualTo(1);
        // 10-03 already had a completed run: skipped, and 10-04 queued in the freed lane 1.
        assertThat(third.skipped).isEqualTo(1);
        assertThat(third.queued).isEqualTo(1);
        assertThat(slotState(id, "2026-10-03")).isEqualTo("SKIPPED");
        assertThat(inFlight(id)).hasSize(2);
        assertThat(((Number) runs(id).get(2).get("run_lane")).intValue()).isEqualTo(1);

        // The rest, until it is done: never more than two in flight.
        for (int pass = 0; pass < 10 && !"COMPLETED".equals(this.status(id)); pass++) {
            assertThat(inFlight(id).size()).isLessThanOrEqualTo(2);
            for (Long run : inFlight(id)) {
                end(run, "Completed");
            }
            this.dispatcher.tick();
        }
        assertThat(this.status(id)).isEqualTo("COMPLETED");
        assertThat(runs(id)).hasSize(4);
        verify(this.notifications, times(1)).notificationCreated(eq(A), any());
        as(A, "TENANT_ADMIN", ADMIN_A);
        Map<String, Object> detail = this.backfills.detail(id, null, 1, false);
        assertThat(detail.get("counts")).isEqualTo(map("pending", 0, "queued", 0, "completed", 4, "failed", 0, "skipped", 1, "cancelled", 0));
        assertThat(detail.get("endedAt")).isNotNull();
        // The run's page: part of this backfill, for its date.
        Optional<Map<String, Object>> of = this.backfills.ofRun(((Number) runs(id).get(0).get("job_queue_id")).longValue());
        assertThat(of.get()).containsEntry("backfillId", id).containsEntry("date", "2026-10-01");
        assertThat(this.backfills.ofRun(live)).isEmpty();
    }

    @Test
    void reRunningCompletedPeriodsRunsThemAgain() {
        finishedRun(JOB_A, A, "2026-10-03T09:00:00", "Completed");
        Backfills.Request r = request(JOB_A, "2026-10-03", "2026-10-03");
        assertThatThrownBy(() -> this.start(A, "TENANT_ADMIN", ADMIN_A, r)).isInstanceOf(Backfills.Refused.class)
            .hasMessageStartingWith("Every period of this range already has a completed run");
        r.rerunCompleted = true;
        long id = this.start(A, "TENANT_ADMIN", ADMIN_A, r);
        assertThat(this.dispatcher.tick().queued).isEqualTo(1);
        assertThat(slotState(id, "2026-10-03")).isEqualTo("QUEUED");
    }

    @Test
    void theDatabaseHoldsOneRunInFlightPerJobAndLane() {
        long id = this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_A, "2026-10-01", "2026-10-01"));
        login.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, tenant_id) VALUES (?, now(), ?, 'Queue', "
            + "'Active', ?)", nextRun++, JOB_A, A);
        login.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, tenant_id, run_lane, backfill_id) VALUES "
            + "(?, now(), ?, 'Queue', 'Active', ?, 1, ?)", nextRun++, JOB_A, A, id);
        assertThatThrownBy(() -> login.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, tenant_id, run_lane, "
            + "backfill_id) VALUES (?, now(), ?, 'Running', 'Active', ?, 1, ?)", nextRun++, JOB_A, A, id)).isInstanceOf(DuplicateKeyException.class)
            .hasMessageContaining("ux_job_queue_one_in_flight_per_job");
        assertThatThrownBy(() -> login.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, tenant_id) VALUES "
            + "(?, now(), ?, 'Start', 'Active', ?)", nextRun++, JOB_A, A)).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> login.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, tenant_id, run_lane) "
            + "VALUES (?, now(), ?, 'Queue', 'Active', ?, 2)", nextRun++, JOB_A, A)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_job_queue_lane_backfill");
        assertThatThrownBy(() -> login.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, tenant_id, run_lane, "
            + "backfill_id) VALUES (?, now(), ?, 'Queue', 'Active', ?, 5, ?)", nextRun++, JOB_A, A, id))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_job_queue_run_lane");
    }

    @Test
    void aDateIsInOneOpenBackfillOfAJobAtATime() {
        long first = this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_A, "2026-10-01", "2026-10-05"));
        assertThatThrownBy(() -> this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_A, "2026-10-05", "2026-10-08")))
            .isInstanceOf(Backfills.Refused.class)
            .hasMessage("2026-10-05 is already in backfill #" + first + " of this schedule, which is still open; pick a range after it, or cancel "
                + "that one.")
            .extracting(t -> ((Backfills.Refused) t).getKind()).isEqualTo("backfill-overlaps");
        // The database says so too, whoever writes.
        long other = login.queryForObject("INSERT INTO backfill (tenant_id, job_id, step, range_from, range_to, zone, concurrency, total) "
            + "VALUES (?, ?, 'DAILY', '2026-10-05', '2026-10-05', 'America/Chicago', 1, 1) RETURNING backfill_id", Long.class, A, JOB_A);
        assertThatThrownBy(() -> login.update("INSERT INTO backfill_slot (backfill_id, slot_start, slot_end, run_date, tenant_id, job_id) VALUES "
            + "(?, '2026-10-05T05:00:00Z', '2026-10-06T05:00:00Z', '2026-10-05', ?, ?)", other, A, JOB_A)).isInstanceOf(DuplicateKeyException.class)
            .hasMessageContaining("ux_backfill_slot_open_per_job");
        login.update("DELETE FROM backfill WHERE backfill_id = ?", other);
        // Another schedule's dates are its own; and once the first is cancelled, its dates are free.
        this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_A2, "2026-10-05", "2026-10-08"));
        as(A, "TENANT_ADMIN", ADMIN_A);
        this.backfills.cancel(first, ADMIN_A, false);
        TenantContext.clear();
        this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_A, "2026-10-05", "2026-10-08"));
    }

    @Test
    void aWorkspaceHasAtMostThreeOpenBackfills() {
        this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_A, "2026-10-01", "2026-10-02"));
        this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_A, "2026-10-03", "2026-10-04"));
        long third = this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_A2, "2026-10-01", "2026-10-02"));
        assertThatThrownBy(() -> this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_A2, "2026-10-05", "2026-10-06")))
            .hasMessage("This workspace has 3 open backfills, the most it may have at once. Wait for one to finish, or cancel one.");
        // Another workspace's are its own.
        this.start(B, "TENANT_ADMIN", ADMIN_B, request(JOB_B, "2026-10-01", "2026-10-02"));
        as(A, "TENANT_ADMIN", ADMIN_A);
        this.backfills.cancel(third, ADMIN_A, false);
        TenantContext.clear();
        this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_A2, "2026-10-05", "2026-10-06"));
    }

    @Test
    void pauseResumeAndCancel() {
        long id = this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_A, "2026-10-01", "2026-10-06"));
        as(A, "TENANT_ADMIN", ADMIN_A);
        this.backfills.pause(id, ADMIN_A, false);
        assertThatThrownBy(() -> this.backfills.pause(id, ADMIN_A, false)).hasMessage("Only a running backfill can be paused; this one is paused.");
        TenantContext.clear();
        assertThat(this.dispatcher.tick().queued).isZero();
        as(A, "TENANT_ADMIN", ADMIN_A);
        this.backfills.resume(id, false);
        TenantContext.clear();
        assertThat(this.dispatcher.tick().queued).isEqualTo(2);
        as(A, "TENANT_ADMIN", ADMIN_A);
        Map<String, Object> cancelled = this.backfills.cancel(id, ADMIN_A, false);
        TenantContext.clear();
        assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
        assertThat(login.queryForObject("SELECT count(*) FROM backfill_slot WHERE backfill_id = ? AND state = 'CANCELLED'", Integer.class, id))
            .isEqualTo(4);
        // The two already started finish and are recorded; nothing more is queued.
        for (Long run : inFlight(id)) {
            end(run, "Completed");
        }
        BackfillDispatcher.Pass after = this.dispatcher.tick();
        assertThat(after.settled).isEqualTo(2);
        assertThat(after.queued).isZero();
        assertThat(login.queryForObject("SELECT count(*) FROM backfill_slot WHERE backfill_id = ? AND state = 'COMPLETED'", Integer.class, id))
            .isEqualTo(2);
        as(A, "TENANT_ADMIN", ADMIN_A);
        assertThatThrownBy(() -> this.backfills.resume(id, false)).hasMessage("Only a paused backfill can be resumed; this one is cancelled.");
    }

    @Test
    void aFailedDateFailsTheBackfillAndRetryFailedRunsItAgain() {
        long id = this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_A, "2026-10-01", "2026-10-02"));
        this.dispatcher.tick();
        List<Long> runs = inFlight(id);
        end(runs.get(0), "Completed");
        end(runs.get(1), "Failed");
        this.dispatcher.tick();
        assertThat(this.status(id)).isEqualTo("FAILED");
        assertThat(slotState(id, "2026-10-02")).isEqualTo("FAILED");
        assertThat(login.queryForObject("SELECT note FROM backfill_slot WHERE backfill_id = ? AND state = 'FAILED'", String.class, id))
            .isEqualTo("Step 1 <read> failed: synthetic failure.");
        as(A, "TENANT_ADMIN", ADMIN_A);
        Map<String, Object> retried = this.backfills.retryFailed(id, false);
        assertThat(retried.get("status")).isEqualTo("RUNNING");
        assertThat(retried.get("endedAt")).isNull();
        assertThatThrownBy(() -> this.backfills.retryFailed(id, false)).hasMessage("This backfill has no failed period to retry.");
        TenantContext.clear();
        assertThat(this.dispatcher.tick().queued).isEqualTo(1);
        assertThat(login.queryForObject("SELECT tries FROM backfill_slot WHERE backfill_id = ? AND run_date = '2026-10-02'", Integer.class, id))
            .isEqualTo(2);
        end(inFlight(id).get(0), "Completed");
        this.dispatcher.tick();
        assertThat(this.status(id)).isEqualTo("COMPLETED");
        // Each date's runs are kept: three runs, the failed one too.
        assertThat(runs(id)).hasSize(3);
        as(A, "TENANT_ADMIN", ADMIN_A);
        assertThat(this.store.runsOf(id)).hasSize(3);
    }

    @Test
    void aPausedWorkspaceOrASwitchedOffScheduleHoldsItAndADeletedOneCancelsIt() {
        long id = this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_A, "2026-10-01", "2026-10-03"));
        this.pauses.put(A, "Suspended");
        BackfillDispatcher.Pass held = this.dispatcher.tick();
        assertThat(held.queued).isZero();
        assertThat(held.held).isEqualTo(1);
        assertThat(login.queryForObject("SELECT hold_reason FROM backfill WHERE backfill_id = ?", String.class, id))
            .isEqualTo("Waiting: the workspace is Suspended, so its runs are paused until it is Active again.");
        this.pauses.clear();
        login.update("UPDATE source_job SET job_status = 'Inactive' WHERE job_id = ?", JOB_A);
        assertThat(this.dispatcher.tick().queued).isZero();
        assertThat(login.queryForObject("SELECT hold_reason FROM backfill WHERE backfill_id = ?", String.class, id)).contains("switched off");
        login.update("UPDATE source_job SET job_status = 'Active' WHERE job_id = ?", JOB_A);
        assertThat(this.dispatcher.tick().queued).isEqualTo(2);
        assertThat(login.queryForObject("SELECT hold_reason FROM backfill WHERE backfill_id = ?", String.class, id)).isNull();
        login.update("UPDATE source_job SET job_status = 'Delete' WHERE job_id = ?", JOB_A);
        this.dispatcher.tick();
        assertThat(this.status(id)).isEqualTo("CANCELLED");
        verify(this.notifications).notificationCreated(eq(A), any());
    }

    @Test
    void aSwitchedOffScheduleCannotStartOne() {
        login.update("UPDATE source_job SET job_status = 'Inactive' WHERE job_id = ?", JOB_A);
        assertThatThrownBy(() -> this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_A, "2026-10-01", "2026-10-03")))
            .hasMessage("The schedule is switched off; switch it on to backfill it.");
    }

    // ---- isolation ----------------------------------------------------------------------------------------------

    @Test
    void oneWorkspaceNeitherReadsNorChangesAnothersAndAUserOnlyTheirOwnSchedules() {
        long a = this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_A, "2026-10-01", "2026-10-02"));
        long other = this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_OTHER, "2026-10-01", "2026-10-02"));
        this.dispatcher.tick();
        String before = login.queryForList("SELECT * FROM backfill b WHERE backfill_id = ? ORDER BY b::text", a).toString()
            + login.queryForList("SELECT * FROM backfill_slot s WHERE backfill_id = ? ORDER BY s::text", a);

        as(B, "TENANT_ADMIN", ADMIN_B);
        assertThat((List<?>) this.backfills.list(null, null, 1, 25).get("rows")).isEmpty();
        assertThatThrownBy(() -> this.backfills.detail(a, null, 1, false)).hasMessage("No such backfill.");
        assertThatThrownBy(() -> this.backfills.pause(a, ADMIN_B, false)).hasMessage("No such backfill.");
        assertThatThrownBy(() -> this.backfills.cancel(a, ADMIN_B, false)).hasMessage("No such backfill.");
        assertThatThrownBy(() -> this.backfills.retryFailed(a, false)).hasMessage("No such backfill.");
        assertThatThrownBy(() -> this.backfills.preview(request(JOB_A, "2026-10-01", "2026-10-02"))).hasMessage("No such schedule.");
        assertThatThrownBy(() -> this.backfills.start(request(JOB_A, "2026-10-03", "2026-10-04"), Backfills.Origin.console(ADMIN_B)))
            .hasMessage("No such schedule.");
        assertThat(this.backfills.ofRun(((Number) runs(a).get(0).get("job_queue_id")).longValue())).isEmpty();
        // An API client of B: the same.
        TenantContext.set(B, "API_CLIENT", null, "client-b");
        TenantContext.setApiClient("client-b", Collections.singleton("runs:write"));
        assertThatThrownBy(() -> this.backfills.cancel(a, null, true)).hasMessage("No such backfill.");
        TenantContext.clear();

        // A's tenant user: their own schedule's, not a colleague's.
        as(A, "TENANT_USER", USER_A);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mine = (List<Map<String, Object>>) this.backfills.list(null, null, 1, 25).get("rows");
        assertThat(mine).extracting(m -> m.get("backfillId")).containsExactly(a);
        assertThatThrownBy(() -> this.backfills.detail(other, null, 1, false)).hasMessage("No such backfill.");
        assertThatThrownBy(() -> this.backfills.preview(request(JOB_OTHER, "2026-10-03", "2026-10-04"))).hasMessage("No such schedule.");
        TenantContext.clear();

        String after = login.queryForList("SELECT * FROM backfill b WHERE backfill_id = ? ORDER BY b::text", a).toString()
            + login.queryForList("SELECT * FROM backfill_slot s WHERE backfill_id = ? ORDER BY s::text", a);
        assertThat(after).isEqualTo(before);
        // Row security: as B, A's rows are not there at all.
        as(B, "TENANT_ADMIN", ADMIN_B);
        assertThat(app.queryForObject("SELECT count(*) FROM backfill", Integer.class)).isZero();
        assertThat(app.queryForObject("SELECT count(*) FROM backfill_slot", Integer.class)).isZero();
        assertThat(this.store.find(a)).isEmpty();
        // ... and B cannot write a row of A's.
        assertThatThrownBy(() -> app.update("INSERT INTO backfill (tenant_id, job_id, step, range_from, range_to, zone, concurrency, total) "
            + "VALUES (?, ?, 'DAILY', '2026-10-01', '2026-10-01', 'UTC', 1, 1)", A, JOB_A)).isInstanceOf(DataAccessException.class)
            .hasStackTraceContaining("row-level security");
        TenantContext.clear();
        verify(this.notifications, never()).notificationCreated(eq(B), any());
    }

    private String status(long id) {
        return login.queryForObject("SELECT status FROM backfill WHERE backfill_id = ?", String.class, id);
    }

    /**
     * Owner 2026-10-27 ("add a sort on each table"): a backfill's periods sorted by Date, State, Run or Tries, both ways, read in
     * pages of 100: every period once, in the column's order (problems first for State; a period with no run last both
     * ways), ties by the period's start in the sort's direction. An unknown column is a 400 naming the four.
     */
    @Test
    void thePeriodsSortByEachColumnAcrossPagesWithoutRepeatsOrGaps() {
        long id = this.start(A, "TENANT_ADMIN", ADMIN_A, request(JOB_A, "2026-05-01", "2026-08-28"));
        login.update("UPDATE backfill_slot s SET state = x.state, tries = x.tries, job_queue_id = x.run FROM (SELECT slot_start, "
            + "CASE n % 4 WHEN 0 THEN 'FAILED' WHEN 1 THEN 'COMPLETED' WHEN 2 THEN 'PENDING' ELSE 'SKIPPED' END AS state, "
            + "CAST(n % 3 AS smallint) AS tries, CASE WHEN n % 4 IN (0, 1) THEN 5000000 + (n * 37) % 50 END AS run "
            + "FROM (SELECT slot_start, row_number() OVER (ORDER BY slot_start) AS n FROM backfill_slot WHERE backfill_id = ?) r) x "
            + "WHERE s.backfill_id = ? AND s.slot_start = x.slot_start", id, id);
        List<Map<String, Object>> all = login.queryForList("SELECT slot_start, state, job_queue_id, tries FROM backfill_slot "
            + "WHERE backfill_id = ?", id);
        assertThat(all).hasSize(120);
        List<String> states = Arrays.asList("FAILED", "CANCELLED", "QUEUED", "PENDING", "SKIPPED", "COMPLETED");
        Map<String, Function<Map<String, Object>, Comparable<?>>> keys = new LinkedHashMap<>();
        keys.put("date", r -> ((Timestamp) r.get("slot_start")).toInstant());
        keys.put("state", r -> states.indexOf((String) r.get("state")));
        keys.put("run", r -> r.get("job_queue_id") == null ? null : ((Number) r.get("job_queue_id")).longValue());
        keys.put("tries", r -> ((Number) r.get("tries")).intValue());
        as(A, "TENANT_ADMIN", ADMIN_A);
        for (Map.Entry<String, Function<Map<String, Object>, Comparable<?>>> column : keys.entrySet()) {
            for (String dir : new String[] {"asc", "desc"}) {
                int sign = "asc".equals(dir) ? 1 : -1;
                List<Map<String, Object>> expected = new ArrayList<>(all);
                expected.sort((x, y) -> {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Comparable a = column.getValue().apply(x);
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Comparable b = column.getValue().apply(y);
                    if (a == null || b == null) {
                        int nulls = a == null ? (b == null ? 0 : 1) : -1;
                        if (nulls != 0) {
                            return nulls;
                        }
                    } else {
                        @SuppressWarnings("unchecked")
                        int by = a.compareTo(b) * sign;
                        if (by != 0) {
                            return by;
                        }
                    }
                    return ((Timestamp) x.get("slot_start")).compareTo((Timestamp) y.get("slot_start")) * sign;
                });
                List<String> want = new ArrayList<>();
                expected.forEach(r -> want.add(((Timestamp) r.get("slot_start")).toInstant().toString()));
                List<String> got = new ArrayList<>();
                for (int page = 1; page <= 2; page++) {
                    Map<String, Object> detail = this.backfills.detail(id, null, page, false, column.getKey(), dir);
                    assertThat(detail.get("periodsSort")).isEqualTo(column.getKey());
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> periods = (List<Map<String, Object>>) detail.get("periods");
                    assertThat(periods).hasSize(page == 1 ? 100 : 20);
                    periods.forEach(p -> got.add((String) p.get("start")));
                }
                assertThat(got).as("%s %s", column.getKey(), dir).doesNotHaveDuplicates().containsExactlyElementsOf(want);
            }
        }
        // No sort: the periods' own order, oldest first, and no sort named in the answer.
        Map<String, Object> plain = this.backfills.detail(id, null, 1, false);
        assertThat(plain).doesNotContainKey("periodsSort");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> first = (List<Map<String, Object>>) plain.get("periods");
        assertThat(first.get(0).get("date")).isEqualTo("2026-05-01");
        assertThatThrownBy(() -> this.backfills.detail(id, null, 1, false, "note", "asc")).isInstanceOf(Backfills.Refused.class)
            .hasMessage("sort must be one of: date, state, run, tries.")
            .satisfies(e -> assertThat(((Backfills.Refused) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> this.backfills.detail(id, null, 1, false, "date", "up")).hasMessage("dir must be asc or desc.");
    }
}
