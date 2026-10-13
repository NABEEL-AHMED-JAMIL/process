package process.retention;

import org.barco.platform.tenancy.RowSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.http.HttpStatus;
import process.ScratchPostgres;
import process.pipeline.Dataset;
import process.pipeline.DatasetStore;
import process.pipeline.FileDatasetStore;
import process.util.OpenSearchAuditLogClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-354 against a real Postgres, as the application runs it: process_app under row security, each pass inside
 * RowSecurity.forTenant. Old synthetic runs, logs, datasets on disk, form submissions and audit rows in two workspaces;
 * the pass removes the first workspace's eligible items only, and the record says exactly what it did.
 *
 * <ul>
 *   <li>the audit floor: shorter days are refused, and the cutoff never goes under it;</li>
 *   <li>legal holds: a workspace hold removes nothing; a pipeline hold and a run hold keep theirs;</li>
 *   <li>the preview matches the removal, class by class;</li>
 *   <li>batches: more than a batch goes in batches; a pass out of time is PARTIAL and the next one finishes it;</li>
 *   <li>tenant isolation: the other workspace's old items are untouched, and its records unreadable;</li>
 *   <li>the safety share: over it the class pauses and nothing goes until an administrator confirms;</li>
 *   <li>nothing else is touched: every other table's rows -- billing's copies among them -- are as they were.</li>
 * </ul>
 * Opt-in: runs when NOTIFICATIONS_TEST_DB_URL points at a Postgres server (see ScratchPostgres).
 */
class RetentionPostgresTest {

    static final long A = 9811L;
    static final long B = 9812L;
    static final long A_JOB = 98111L;
    static final long A_HELD_JOB = 98112L;
    static final long B_JOB = 98121L;
    static final long ADMIN = 98119L;

    private static ScratchPostgres db;
    private static JdbcTemplate login;
    private static Path dir;
    private static final AtomicInteger NEXT = new AtomicInteger(900_000);

    private DatasetStore datasets;
    private RemoteRetention remote;
    private RetentionStore store;
    private RetentionPass pass;
    private RetentionService service;
    private final List<String[]> formObjectsDeleted = new java.util.ArrayList<>();

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("retention");
        login = db.jdbc();
        dir = Files.createTempDirectory("etl-datasets-retention");
        long type = 98100L;
        for (long tenant : new long[]{A, B}) {
            login.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", tenant, "t" + tenant,
                "T" + tenant);
            login.update("INSERT INTO source_task_type (source_task_type_id, tenant_id, service_name, description, queue_topic_partition, "
                + "task_type_status) VALUES (?, ?, 'r', 'r', 'topic=etl.r&partitions=[*]', 'Active')", type + tenant, tenant);
        }
        job(A, A_JOB, type + A);
        job(A, A_HELD_JOB, type + A);
        job(B, B_JOB, type + B);
    }

    private static void job(long tenant, long jobId, long type) {
        String id = "RET-" + jobId;
        login.update("INSERT INTO pipeline (pipeline_key, tenant_id, pipeline_id, pipeline_name, status, source_task_type_id) "
            + "VALUES (?, ?, ?, ?, 'Active', ?)", jobId, tenant, id, id, type);
        login.update("INSERT INTO source_task (task_detail_id, tenant_id, source_task_type_id, task_name, task_payload, task_status, "
            + "pipeline_id, date_created) VALUES (?, ?, ?, 'r', '<p/>', 'Active', ?, now())", jobId + 5000, tenant, type, id);
        login.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, job_running_status, priority, "
            + "tenant_id, task_detail_id, complete_job, fail_job, skip_job, max_attempts, retry_backoff_seconds) "
            + "VALUES (?, now(), 'Manual', ?, 'Active', 'Queue', 1, ?, ?, false, false, false, 1, 1)", jobId, id, tenant, jobId + 5000);
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void fresh() throws Exception {
        for (String table : new String[]{"retention_audit", "retention_pass_class", "retention_pass", "retention_rule", "retention_policy",
            "legal_hold", "retention_log", "file_access_log", "form_upload", "form_submission", "form_definition", "job_audit_logs",
            "step_log", "run_output", "run_dataset", "result_record", "step_execution", "job_queue"}) {
            login.update("DELETE FROM " + table);
        }
        login.update("UPDATE retention_platform SET audit_floor_days = 2190, max_share_pct = 100");
        this.datasets = new FileDatasetStore(dir.toString());
        this.remote = mock(RemoteRetention.class);
        RetentionTarget nothing = new RetentionTarget() {
            @Override
            public Tally preview(long tenantId, Instant cutoff, Holds holds) {
                return Tally.of(0, 0L, null, 0);
            }

            @Override
            public Batch purge(long tenantId, Instant cutoff, Holds holds, int limit, long passId) {
                return Batch.of(0, 0, false);
            }
        };
        when(this.remote.target(any())).thenReturn(nothing);
        this.formObjectsDeleted.clear();
        when(this.remote.deleteFormObjects(anyLong(), anyList())).thenAnswer(call -> {
            List<String[]> objects = call.getArgument(1);
            this.formObjectsDeleted.addAll(objects);
            return RetentionTarget.Batch.of(objects.size(), 0, false);
        });
        this.wire(Duration.ofMinutes(20));
    }

    private void wire(Duration budget) {
        JdbcTemplate app = db.appJdbc();
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(db.appPool());
        CoreRetention core = new CoreRetention(app, this.datasets, new OpenSearchAuditLogClient(), this.remote, transactions, Clock.systemUTC());
        this.store = new RetentionStore(app);
        this.pass = new RetentionPass(this.store, core, this.remote, Clock.systemUTC(), budget);
        this.service = new RetentionService(this.store, this.pass);
    }

    // --------------------------------------------------------------------------------------------------------- seeding

    /** A finished run made {@code daysAgo} days ago: a step, a dataset on disk with its manifest, log lines, a result. */
    private long run(long jobId, int daysAgo) throws Exception {
        long run = NEXT.incrementAndGet();
        login.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, date_created, start_time, end_time, status, job_send, attempt) "
            + "VALUES (?, ?, 'Completed', now() - make_interval(days => ?), now() - make_interval(days => ?), now() - make_interval(days => ?), "
            + "'Active', false, 1)", run, jobId, daysAgo, daysAgo, daysAgo);
        long step = login.queryForObject("INSERT INTO step_execution (job_queue_id, step_index, task_code, step_key, status) "
            + "VALUES (?, 1, 'save_file', 'keep', 'Completed') RETURNING step_execution_id", Long.class, run);
        String key = DatasetStore.keyOf(run, 1, "keep", "output");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("patient", "synthetic");
        this.datasets.write(key, new Dataset(Collections.singletonList("patient"), Collections.singletonList(row)));
        long dataset = login.queryForObject("INSERT INTO run_dataset (step_execution_id, name, storage_key, row_count, columns, expires_at) "
            + "VALUES (?, 'output', ?, 1, '[\"patient\"]'::jsonb, now() + interval '1 day') RETURNING run_dataset_id", Long.class, step, key);
        login.update("INSERT INTO run_output (step_execution_id, kind, name, format, row_count, byte_count, run_dataset_id, expires_at) "
            + "VALUES (?, 'file', 'output.csv', 'csv', 1, 100, ?, now() + interval '1 day')", step, dataset);
        login.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status) VALUES (?, now(), ?, "
            + "'synthetic line', 'Active')", run, run);
        login.update("INSERT INTO step_log (step_execution_id, line_no, message) VALUES (?, 1, 'synthetic step line')", step);
        login.update("INSERT INTO result_record (job_queue_id, result) VALUES (?, '{\"a\":1}'::jsonb)", run);
        return run;
    }

    private long form(long tenant, long jobId) {
        long form = NEXT.incrementAndGet();
        login.update("INSERT INTO form_definition (form_id, tenant_id, name, status, fields, job_id) VALUES (?, ?, 'Intake', 'Active', "
            + "'[{\"key\":\"q\",\"label\":\"Q\",\"type\":\"text\",\"required\":false}]'::jsonb, ?)", form, tenant, jobId);
        return form;
    }

    private long submission(long tenant, long form, long jobId, int daysAgo) {
        long id = NEXT.incrementAndGet();
        login.update("INSERT INTO form_submission (submission_id, form_id, tenant_id, form_version, answers, status, submitted_by_name, "
            + "submitted_at, job_id, bucket, storage_key) VALUES (?, ?, ?, 1, '{\"q\":\"synthetic\"}'::jsonb, 'Received', 'synthetic', "
            + "now() - make_interval(days => ?), ?, 'inbox', ?)", id, form, tenant, daysAgo, jobId, "intake/forms/form-" + form + "/s-" + id
            + ".json");
        login.update("INSERT INTO form_upload (form_id, tenant_id, field_key, file_name, content_type, size_bytes, bucket, storage_key, "
            + "submission_id) VALUES (?, ?, 'q', 'scan.pdf', 'application/pdf', 2048, 'inbox', ?, ?)", form, tenant,
            "intake/forms/form-" + form + "/uploads/x/scan.pdf", id);
        return id;
    }

    private void accessLog(long tenant, int daysAgo) {
        login.update("INSERT INTO file_access_log (tenant_id, user_id, action, kind, outcome, http_status, accessed_at) VALUES (?, 1, 'download', "
            + "'run_dataset', 'served', 200, now() - make_interval(days => ?))", tenant, daysAgo);
    }

    private void policy(long tenant, Map<DataClass, Integer> days) {
        Map<String, Object> classes = new LinkedHashMap<>();
        days.forEach((c, d) -> classes.put(c.name(), d));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", true);
        body.put("classes", classes);
        RowSecurity.forTenant(tenant, () -> this.service.save(tenant, body, ADMIN));
    }

    private static Map<DataClass, Integer> days(Object... pairs) {
        Map<DataClass, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((DataClass) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }

    private RetentionPass.Outcome runPass(long tenant) {
        return RowSecurity.forTenant(tenant, () -> this.pass.run(tenant, "MANUAL", ADMIN));
    }

    private static RetentionStore.ClassResult of(RetentionPass.Outcome outcome, DataClass dataClass) {
        return outcome.classes.stream().filter(c -> c.dataClass == dataClass).findFirst().orElseThrow(AssertionError::new);
    }

    private long count(String sql, Object... args) {
        return login.queryForObject(sql, Long.class, args);
    }

    private boolean onDisk(long run) {
        return Files.exists(dir.resolve("datasets").resolve(String.valueOf(run)));
    }

    /** Every table's row count (the login sees all), to prove a pass touched nothing it should not. */
    private Map<String, Long> counts() {
        Map<String, Long> out = new TreeMap<>();
        for (String table : login.queryForList("SELECT table_schema || '.' || table_name FROM information_schema.tables WHERE table_type = "
            + "'BASE TABLE' AND table_schema NOT IN ('pg_catalog', 'information_schema') AND table_name NOT LIKE 'databasechangelog%'", String.class)) {
            out.put(table, count("SELECT count(*) FROM " + table));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------ tests

    @Test
    void theAuditFloorRefusesShorterDaysAndHoldsTheCutoff() {
        assertThatThrownBy(() -> this.policy(A, days(DataClass.AUDIT, 400)))
            .isInstanceOf(RetentionService.Refused.class).hasMessageContaining("at least 2190 days")
            .satisfies(e -> assertThat(((RetentionService.Refused) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        this.policy(A, days(DataClass.AUDIT, 2190));
        Instant now = Instant.parse("2026-10-13T12:00:00Z");
        assertThat(RetentionPass.cutoff(DataClass.AUDIT, 30, 2190, now)).isEqualTo(now.minus(Duration.ofDays(2190)));
        assertThat(RetentionPass.cutoff(DataClass.RUN_LOGS, 30, 2190, now)).isEqualTo(now.minus(Duration.ofDays(30)));

        // A pass never cuts audit records younger than the floor, even when the platform's floor was raised after the save.
        this.accessLog(A, 2000);
        this.accessLog(A, 2500);
        login.update("UPDATE retention_platform SET audit_floor_days = 2400");
        RetentionPass.Outcome outcome = this.runPass(A);
        assertThat(of(outcome, DataClass.AUDIT).deleted).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM file_access_log WHERE tenant_id = ?", A)).isEqualTo(1);
    }

    @Test
    void aWorkspaceHoldRemovesNothingAndPipelineAndRunHoldsKeepTheirs() throws Exception {
        long old1 = this.run(A_JOB, 100);
        long old2 = this.run(A_JOB, 100);
        long heldByPipeline = this.run(A_HELD_JOB, 100);
        long form = this.form(A, A_HELD_JOB);
        long heldSubmission = this.submission(A, form, A_HELD_JOB, 100);
        this.policy(A, days(DataClass.RUN_OUTPUTS, 30, DataClass.RUN_LOGS, 30, DataClass.FORM_SUBMISSIONS, 30));
        Map<String, Object> hold = new LinkedHashMap<>();
        hold.put("scope", "workspace");
        hold.put("reason", "Litigation hold, matter 12");
        Map<String, Object> placed = RowSecurity.forTenant(A, () -> this.service.placeHold(A, hold, ADMIN));

        RetentionPass.Outcome held = this.runPass(A);
        assertThat(held.deleted).isZero();
        assertThat(of(held, DataClass.RUN_OUTPUTS).state).isEqualTo("HELD");
        assertThat(count("SELECT count(*) FROM job_audit_logs WHERE job_queue_id IN (?, ?, ?)", old1, old2, heldByPipeline)).isEqualTo(3);
        assertThat(this.onDisk(old1)).isTrue();

        Map<String, Object> release = new LinkedHashMap<>();
        release.put("id", placed.get("id"));
        release.put("reason", "Matter closed");
        RowSecurity.forTenant(A, () -> this.service.releaseHold(A, release, ADMIN));
        Map<String, Object> pipelineHold = new LinkedHashMap<>();
        pipelineHold.put("scope", "pipeline");
        pipelineHold.put("jobId", A_HELD_JOB);
        pipelineHold.put("reason", "Audit of this pipeline");
        RowSecurity.forTenant(A, () -> this.service.placeHold(A, pipelineHold, ADMIN));
        Map<String, Object> runHold = new LinkedHashMap<>();
        runHold.put("scope", "run");
        runHold.put("runId", old2);
        runHold.put("reason", "Patient request");
        RowSecurity.forTenant(A, () -> this.service.placeHold(A, runHold, ADMIN));

        RetentionPass.Outcome outcome = this.runPass(A);
        assertThat(of(outcome, DataClass.RUN_OUTPUTS).deleted).isEqualTo(1);
        assertThat(this.onDisk(old1)).isFalse();
        assertThat(this.onDisk(old2)).as("run hold").isTrue();
        assertThat(this.onDisk(heldByPipeline)).as("pipeline hold").isTrue();
        assertThat(count("SELECT count(*) FROM job_audit_logs WHERE job_queue_id = ?", old1)).isZero();
        assertThat(count("SELECT count(*) FROM job_audit_logs WHERE job_queue_id IN (?, ?)", old2, heldByPipeline)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM form_submission WHERE submission_id = ?", heldSubmission)).as("its pipeline is held").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM retention_audit WHERE tenant_id = ? AND action IN ('HOLD_PLACED', 'HOLD_RELEASED')", A))
            .isEqualTo(4);
    }

    @Test
    void thePreviewMatchesWhatThePassRemoves() throws Exception {
        for (int i = 0; i < 3; i++) {
            this.run(A_JOB, 60 + i);
        }
        this.run(A_JOB, 5);
        long form = this.form(A, A_JOB);
        this.submission(A, form, A_JOB, 90);
        this.submission(A, form, A_JOB, 91);
        this.submission(A, form, A_JOB, 1);
        this.accessLog(A, 3000);
        this.policy(A, days(DataClass.RUN_OUTPUTS, 30, DataClass.RUN_LOGS, 30, DataClass.FORM_SUBMISSIONS, 30, DataClass.AUDIT, 2190));

        Map<String, Object> preview = RowSecurity.forTenant(A, () -> this.service.preview(A, null));
        Map<String, Long> said = new LinkedHashMap<>();
        Map<String, Long> bytes = new LinkedHashMap<>();
        for (Object one : (List<?>) preview.get("classes")) {
            Map<?, ?> c = (Map<?, ?>) one;
            said.put(String.valueOf(c.get("dataClass")), ((Number) c.get("eligible")).longValue());
            bytes.put(String.valueOf(c.get("dataClass")), c.get("bytes") == null ? -1L : ((Number) c.get("bytes")).longValue());
        }
        assertThat(said).containsEntry("RUN_OUTPUTS", 3L).containsEntry("RUN_LOGS", 3L).containsEntry("FORM_SUBMISSIONS", 2L)
            .containsEntry("AUDIT", 1L);
        assertThat(bytes).containsEntry("RUN_OUTPUTS", 300L).containsEntry("FORM_SUBMISSIONS", 4096L);

        RetentionPass.Outcome outcome = this.runPass(A);
        for (DataClass c : new DataClass[]{DataClass.RUN_OUTPUTS, DataClass.RUN_LOGS, DataClass.FORM_SUBMISSIONS, DataClass.AUDIT}) {
            assertThat(of(outcome, c).deleted).as(c.name()).isEqualTo(said.get(c.name()));
            assertThat(of(outcome, c).state).as(c.name()).isEqualTo("DONE");
        }
        assertThat(of(outcome, DataClass.RUN_OUTPUTS).bytes).isEqualTo(300L);
        assertThat(of(outcome, DataClass.FORM_SUBMISSIONS).bytes).isEqualTo(4096L);
        assertThat(this.formObjectsDeleted).hasSize(4);
        assertThat(count("SELECT count(*) FROM retention_log WHERE tenant_id = ? AND reason = 'policy'", A)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM job_queue WHERE tenant_id = ?", A)).as("the runs themselves stay").isEqualTo(4);
        assertThat(count("SELECT count(*) FROM run_output WHERE tenant_id = ? AND expires_at <= now()", A)).as("manifests say expired")
            .isEqualTo(3);
        Map<String, Object> after = RowSecurity.forTenant(A, () -> this.service.preview(A, null));
        assertThat(((Number) after.get("items")).longValue()).isZero();
        assertThat(count("SELECT deleted FROM retention_pass WHERE retention_pass_id = ?", outcome.passId)).isEqualTo(outcome.deleted);
    }

    @Test
    void moreThanABatchGoesInBatchesAndAPassOutOfTimeIsResumed() throws Exception {
        login.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, date_created, status, job_send, attempt) SELECT g, ?, 'Completed', "
            + "now() - interval '90 days', 'Active', false, 1 FROM generate_series(700000, 700000 + ?) g", A_JOB, RetentionPass.BATCH + 99);
        this.policy(A, days(DataClass.RUN_LOGS, 30));

        this.wire(Duration.ZERO);
        RetentionPass.Outcome partial = this.runPass(A);
        assertThat(partial.state).isEqualTo("PARTIAL");
        assertThat(of(partial, DataClass.RUN_LOGS).state).isEqualTo("PARTIAL");
        assertThat(RetentionScheduler.due(RowSecurity.forTenant(A, () -> this.store.latestPass(A)).orElse(null), Instant.now()))
            .isEqualTo("RESUME");

        this.wire(Duration.ofMinutes(20));
        RetentionPass.Outcome resumed = RowSecurity.forTenant(A, () -> this.pass.run(A, "RESUME", null));
        assertThat(of(resumed, DataClass.RUN_LOGS).deleted).isEqualTo(RetentionPass.BATCH + 100);
        assertThat(resumed.state).isEqualTo("DONE");
        assertThat(count("SELECT count(*) FROM job_queue WHERE tenant_id = ? AND logs_removed_at IS NULL", A)).isZero();
    }

    @Test
    void anotherWorkspacesItemsAreUntouchedAndItsRecordsUnreadable() throws Exception {
        long mine = this.run(A_JOB, 100);
        long theirs = this.run(B_JOB, 100);
        long theirForm = this.form(B, B_JOB);
        long theirSubmission = this.submission(B, theirForm, B_JOB, 100);
        this.accessLog(B, 3000);
        this.policy(A, days(DataClass.RUN_OUTPUTS, 30, DataClass.RUN_LOGS, 30, DataClass.FORM_SUBMISSIONS, 30, DataClass.AUDIT, 2190));
        Map<String, Object> hold = new LinkedHashMap<>();
        hold.put("scope", "workspace");
        hold.put("reason", "B's own hold");
        this.policy(B, days(DataClass.RUN_LOGS, 3650));
        RowSecurity.forTenant(B, () -> this.service.placeHold(B, hold, 1L));

        this.runPass(A);
        assertThat(this.onDisk(mine)).isFalse();
        assertThat(this.onDisk(theirs)).isTrue();
        assertThat(count("SELECT count(*) FROM job_audit_logs WHERE job_queue_id = ?", theirs)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM form_submission WHERE submission_id = ?", theirSubmission)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM file_access_log WHERE tenant_id = ?", B)).isEqualTo(1);

        // As A, B's policy, holds and passes are not there -- even when asked for by B's id.
        assertThat(RowSecurity.forTenant(A, () -> this.store.holds(B, 10))).isEmpty();
        assertThat(RowSecurity.forTenant(A, () -> this.store.policy(B)).rules).isEmpty();
        assertThat(RowSecurity.forTenant(A, () -> this.store.auditLines(B, 10))).isEmpty();
        // And a statement that forgot its workspace still answers A's alone (row security).
        assertThat(RowSecurity.forTenant(A, () -> db.appJdbc().queryForObject("SELECT count(*) FROM legal_hold", Long.class))).isZero();
        // A's classes, counted as B, are empty: row security hides them whatever id is named.
        assertThat(RowSecurity.forTenant(B, () -> this.pass.preview(A, days(DataClass.RUN_LOGS, 1), Collections.emptyMap(),
            new RetentionStore.Platform(), Holds.NONE)).get(DataClass.RUN_LOGS).total).isZero();
    }

    @Test
    void overTheShareTheClassPausesUntilAnAdministratorConfirms() throws Exception {
        login.update("UPDATE retention_platform SET max_share_pct = 20");
        for (int i = 0; i < 3; i++) {
            this.run(A_JOB, 100);
        }
        this.run(A_JOB, 1);
        this.policy(A, days(DataClass.RUN_LOGS, 30));

        RetentionPass.Outcome paused = this.runPass(A);
        assertThat(of(paused, DataClass.RUN_LOGS).state).isEqualTo("PAUSED");
        assertThat(of(paused, DataClass.RUN_LOGS).deleted).isZero();
        assertThat(count("SELECT count(*) FROM job_audit_logs WHERE tenant_id = ?", A)).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM retention_audit WHERE tenant_id = ? AND action = 'CLASS_PAUSED'", A)).isEqualTo(1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataClass", "RUN_LOGS");
        body.put("expectedCount", 3);
        Map<String, Object> confirmed = RowSecurity.forTenant(A, () -> this.service.confirm(A, body, ADMIN));
        assertThat(confirmed.get("confirmedCount")).isEqualTo(3L);

        RetentionPass.Outcome done = this.runPass(A);
        assertThat(of(done, DataClass.RUN_LOGS).state).isEqualTo("DONE");
        assertThat(of(done, DataClass.RUN_LOGS).deleted).isEqualTo(3);
        assertThat(RowSecurity.forTenant(A, () -> this.store.policy(A)).rules.get(DataClass.RUN_LOGS).confirmedCount).as("spent").isNull();
    }

    @Test
    void aPassTouchesNoOtherTableBillingsCopiesAmongThem() throws Exception {
        this.run(A_JOB, 100);
        long form = this.form(A, A_JOB);
        this.submission(A, form, A_JOB, 100);
        this.accessLog(A, 3000);
        this.policy(A, days(DataClass.RUN_OUTPUTS, 30, DataClass.RUN_LOGS, 30, DataClass.FORM_SUBMISSIONS, 30, DataClass.AUDIT, 2190));
        Map<String, Long> before = this.counts();
        this.runPass(A);
        Map<String, Long> after = this.counts();
        Map<String, Long> changed = new TreeMap<>();
        for (Map.Entry<String, Long> e : before.entrySet()) {
            if (!e.getValue().equals(after.get(e.getKey()))) {
                changed.put(e.getKey(), after.get(e.getKey()) - e.getValue());
            }
        }
        assertThat(changed.keySet()).containsOnly("public.run_dataset", "public.result_record", "public.job_audit_logs", "public.step_log",
            "public.form_submission", "public.form_upload", "public.file_access_log", "public.retention_log", "public.retention_audit",
            "public.retention_pass", "public.retention_pass_class");
        // Core's copy of billing's invoices (read-only since MIG-88: a trigger refuses any change) is among the untouched.
        assertThat(after).containsKey("public.invoice");
    }
}
