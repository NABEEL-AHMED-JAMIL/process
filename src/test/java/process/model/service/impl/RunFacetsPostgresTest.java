package process.model.service.impl;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.model.dto.MessageQSearchDto;
import process.model.enums.JobStatus;
import process.pipeline.PipelineDefinitionStore;
import process.schema.ScratchEtlJob;
import process.security.TenantContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * UX review 2026-10-13 (B3, I1, the Pipelines list): the new reads' SQL on a real changelog -- how a run was started, told
 * apart from what Core records (attempt, api_intake, document_approval, input_key and inbox_arrival, run_manual); the
 * facets each counted without their own filter; the histogram's slots on Chicago's wall clock; the newest run of each
 * pipeline the caller may see; and the pipelines' latest definitions.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class RunFacetsPostgresTest {

    private static final long TENANT = 9301L;
    private static final long OTHER = 9302L;
    private static final long ALPHA = 93011L;
    private static final long BETA = 93012L;
    private static final long ALPHA_JOB = 9301L;
    private static final long BETA_JOB = 9302L;
    private static final long THEIR_JOB = 9399L;
    private static final long OWNER = 77L;

    private static ScratchEtlJob db;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("run_facets");
        JdbcTemplate sql = db.sql();
        for (long tenant : new long[] {TENANT, OTHER}) {
            sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", tenant, "T" + tenant, "T" + tenant);
            sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, tenant_id, "
                + "task_type_status) VALUES (?, 'etl', 'etl', 'topic=etl.jobs&partitions=[*]', ?, 'Active')", tenant * 10 + 9, tenant);
        }
        task(ALPHA, TENANT, "Alpha intake", "ALPHA");
        task(BETA, TENANT, "Beta labs", "BETA");
        task(93021L, OTHER, "Theirs", "THEIRS");
        job(ALPHA_JOB, TENANT, ALPHA, OWNER);
        job(BETA_JOB, TENANT, BETA, 88L);
        job(THEIR_JOB, OTHER, 93021L, OWNER);
        // Chicago is UTC-5 in October: 23:30 there on the 12th is 04:30 UTC on the 13th.
        run(1, ALPHA_JOB, TENANT, "Completed", "2026-10-12 10:15:00-05", "true", null, 1, "Active");
        run(2, ALPHA_JOB, TENANT, "Failed", "2026-10-12 10:40:00-05", "false", "inbox/a.csv", 1, "Active");
        run(3, BETA_JOB, TENANT, "Completed", "2026-10-12 23:30:00-05", "true", null, 2, "Active");
        run(4, BETA_JOB, TENANT, "Completed", "2026-10-13 01:00:00-05", null, null, 1, "Active");
        run(5, ALPHA_JOB, TENANT, "Completed", "2026-10-13 02:00:00-05", "false", "api/in.json", 1, "Active");
        run(6, BETA_JOB, TENANT, "Failed", "2026-10-13 03:00:00-05", "false", null, 1, "Active");
        run(7, ALPHA_JOB, TENANT, "Completed", "2026-10-13 04:00:00-05", "true", null, 1, "Delete");
        run(8, THEIR_JOB, OTHER, "Completed", "2026-10-13 04:00:00-05", "true", null, 1, "Active");
        run(9, ALPHA_JOB, TENANT, "Skip", "2026-10-13 05:00:00-05", "false", null, 1, "Active");
        sql.update("INSERT INTO api_intake (tenant_id, job_id, job_queue_id, client_id, input_bucket, input_key) "
            + "VALUES (?, ?, 930105, 'client-1', 'b', 'api/in.json')", TENANT, ALPHA_JOB);
        sql.update("INSERT INTO document_approval (tenant_id, job_id, extraction_id, type_version, document_type_id, outcome, job_queue_id) "
            + "VALUES (?, ?, 5001, 1, 1008, 'Started', 930106)", TENANT, BETA_JOB);
        sql.update("INSERT INTO inbox_arrival (arrival_id, tenant_id, job_id, bucket, storage_key, file_name, bytes, outcome, job_queue_id) "
            + "VALUES ('arr-9', ?, ?, 'b', 'inbox/z.csv', 'z.csv', 10, 'Started', 930109)", TENANT, ALPHA_JOB);

        // Definitions for the Pipelines list and Sources: Alpha has two versions, Beta none (legacy), Theirs one.
        pipeline(93101L, TENANT, "ALPHA", "Active");
        pipeline(93102L, TENANT, "ALPHA", "Delete");
        pipeline(93201L, OTHER, "THEIRS", "Active");
        sql.update("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (93101, 1, '{\"version\":1,\"steps\":[{\"key\":\"s\",\"task\":\"sample\",\"config\":{\"rows\":[]}}]}'::json)");
        sql.update("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (93101, 2, "
            + "'{\"version\":1,\"steps\":[{\"key\":\"r\",\"task\":\"read_api\",\"config\":{\"requestId\":1083}}]}'::json)");
        sql.update("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (93102, 9, '{\"version\":1,\"steps\":[{\"key\":\"s\",\"task\":\"sample\",\"config\":{\"rows\":[]}}]}'::json)");
        sql.update("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (93201, 1, '{\"version\":1,\"steps\":[{\"key\":\"s\",\"task\":\"sample\",\"config\":{\"rows\":[]}}]}'::json)");
        sql.execute("ANALYZE job_queue");
    }

    private static void task(long id, long tenant, String name, String pipelineId) {
        db.sql().update("INSERT INTO source_task (task_detail_id, task_name, task_status, task_payload, source_task_type_id, tenant_id, pipeline_id) "
            + "VALUES (?, ?, 'Active', '<p/>', ?, ?, ?)", id, name, tenant * 10 + 9, tenant, pipelineId);
    }

    private static void job(long id, long tenant, long task, long createdBy) {
        db.sql().update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id, task_detail_id, "
            + "created_by, complete_job, fail_job, skip_job) VALUES (?, now(), 'Auto', ?, 'Active', 1, ?, ?, ?, false, false, false)",
            id, "job " + id, tenant, task, createdBy);
    }

    private static void run(int n, long job, long tenant, String status, String at, String manual, String inputKey, int attempt, String rowStatus) {
        db.sql().update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status, job_send, attempt, "
            + "run_manual, input_key, tenant_id) VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz, ?, false, ?, ?::boolean, ?, ?)",
            930100L + n, job, status, at, at, rowStatus, attempt, manual, inputKey, tenant);
    }

    private static void pipeline(long key, long tenant, String id, String status) {
        db.sql().update("INSERT INTO pipeline (pipeline_key, tenant_id, pipeline_id, pipeline_name, status) VALUES (?, ?, ?, ?, ?)",
            key, tenant, id, id + " name", status);
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static MessageQSearchDto week() {
        MessageQSearchDto search = new MessageQSearchDto();
        search.setFromDate("2026-10-12");
        search.setToDate("2026-10-13");
        return search;
    }

    /** The facet query's rows as the service reads them (facet, key, name, slot, count). */
    private static Map<String, Object> facets(MessageQSearchDto search) {
        String bucket = RunFacets.bucketOf(search.getFromDate(), search.getToDate());
        List<Object[]> rows = db.sql().query(new QueryService().runFacets(search, bucket), (rs, n) -> new Object[] {
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)});
        return RunFacets.assemble(rows, search, bucket);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> counts(Map<String, Object> answer, String facet, String key) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> one : (List<Map<String, Object>>) answer.get(facet)) {
            counts.put(String.valueOf(one.get(key)), (Long) one.get("count"));
        }
        return counts;
    }

    @Test
    void eachRunNamesItsPipelineAndHowItWasStarted() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 1L, "admin@t.test");
        List<Map<String, Object>> rows = db.sql().queryForList(new QueryService().fetchJobQLog(week(), false));
        Map<Long, String> triggers = rows.stream().collect(Collectors.toMap(r -> ((Number) r.get("job_queue_id")).longValue(),
            r -> r.get("task_name") + "/" + r.get("run_trigger")));
        assertThat(triggers).containsOnly(
            entry(930101L, "Alpha intake/manual"),
            entry(930102L, "Alpha intake/file"),
            entry(930103L, "Beta labs/retry"),
            entry(930104L, "Beta labs/schedule"),
            entry(930105L, "Alpha intake/api"),
            entry(930106L, "Beta labs/document"),
            entry(930109L, "Alpha intake/file"));

        MessageQSearchDto narrowed = week();
        narrowed.setTaskDetailIds(new HashSet<>(Collections.singletonList(ALPHA)));
        narrowed.setTriggers(new HashSet<>(Collections.singletonList("file")));
        assertThat(db.sql().queryForList(new QueryService().fetchJobQLog(narrowed, false))).extracting(r -> ((Number) r.get("job_queue_id")).longValue())
            .containsExactly(930109L, 930102L);
        assertThat(db.sql().queryForList(new QueryService().fetchJobQLog(narrowed, true))).extracting(r -> r.get("job_status") + "=" + r.get("total_count"))
            .containsExactlyInAnyOrder("FAILED=1", "SKIP=1");
    }

    /** ZT-P15: a run a form submission started says so, and so does a backfill's run (they read "a file arrived" and "on its timetable"). */
    @Test
    void aFormSubmissionsRunAndABackfillsRunSayWhatStartedThem() {
        JdbcTemplate sql = db.sql();
        task(93031L, TENANT, "Gamma intake", "GAMMA");
        job(93032L, TENANT, 93031L, OWNER);
        run(21, 93032L, TENANT, "Completed", "2026-10-20 10:00:00-05", "false", "intake/forms/form-77/submission-1.json", 1, "Active");
        run(22, 93032L, TENANT, "Completed", "2026-10-20 11:00:00-05", "false", null, 1, "Active");
        sql.update("INSERT INTO form_definition (form_id, tenant_id, name, status) VALUES (77, ?, 'Intake', 'Active')", TENANT);
        sql.update("INSERT INTO form_submission (form_id, tenant_id, form_version, answers, status, job_id, job_queue_id) "
            + "VALUES (77, ?, 1, '{}'::jsonb, 'RunStarted', ?, 930121)", TENANT, 93032L);
        long backfill = sql.queryForObject("INSERT INTO backfill (tenant_id, job_id, step, range_from, range_to, zone, concurrency, total) "
            + "VALUES (?, ?, 'DAILY', '2026-10-01', '2026-10-01', 'America/Chicago', 1, 1) RETURNING backfill_id", Long.class, TENANT, 93032L);
        sql.update("INSERT INTO backfill_slot (backfill_id, slot_start, slot_end, run_date, tenant_id, job_id, state, job_queue_id) "
            + "VALUES (?, '2026-10-01 05:00:00+00', '2026-10-02 05:00:00+00', '2026-10-01', ?, ?, 'COMPLETED', 930122)", backfill, TENANT, 93032L);
        TenantContext.set(TENANT, "TENANT_ADMIN", 1L, "admin@t.test");
        MessageQSearchDto day = new MessageQSearchDto();
        day.setFromDate("2026-10-20");
        day.setToDate("2026-10-20");
        Map<Long, Object> triggers = db.sql().queryForList(new QueryService().fetchJobQLog(day, false)).stream()
            .collect(Collectors.toMap(r -> ((Number) r.get("job_queue_id")).longValue(), r -> r.get("run_trigger")));
        assertThat(triggers).containsOnly(entry(930121L, "form"), entry(930122L, "backfill"));
        assertThat(RunFacets.TRIGGERS).containsEntry("form", "Form submitted").containsEntry("backfill", "Backfill");
    }

    @Test
    void eachFacetIsCountedWithTheOtherFiltersButNotItsOwn() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 1L, "admin@t.test");
        Map<String, Object> all = facets(week());
        assertThat(all).containsEntry("total", 7L);
        assertThat(counts(all, "statuses", "key")).containsOnly(entry("Completed", 4L),
            entry("Failed", 2L), entry("Skip", 1L));
        assertThat(counts(all, "pipelines", "taskName")).containsExactly(entry("Alpha intake", 4L),
            entry("Beta labs", 3L));
        assertThat(counts(all, "triggers", "key")).containsExactly(entry("schedule", 1L),
            entry("manual", 1L), entry("file", 2L),
            entry("document", 1L), entry("form", 0L), entry("backfill", 0L), entry("api", 1L),
            entry("retry", 1L));

        MessageQSearchDto failed = week();
        failed.setJobStatuses(new HashSet<>(Collections.singletonList(JobStatus.Failed)));
        Map<String, Object> picked = facets(failed);
        assertThat(picked).as("the total and histogram take the status").containsEntry("total", 2L);
        assertThat(counts(picked, "statuses", "key")).as("the statuses do not").containsEntry("Completed", 4L).containsEntry("Failed", 2L);
        assertThat(counts(picked, "pipelines", "taskName")).containsOnly(entry("Alpha intake", 1L),
            entry("Beta labs", 1L));
        assertThat(counts(picked, "triggers", "key")).containsEntry("file", 1L).containsEntry("document", 1L).containsEntry("manual", 0L);

        MessageQSearchDto beta = week();
        beta.setTaskDetailIds(new HashSet<>(Collections.singletonList(BETA)));
        beta.setTriggers(new HashSet<>(Arrays.asList("retry", "schedule")));
        Map<String, Object> both = facets(beta);
        assertThat(both).containsEntry("total", 2L);
        assertThat(counts(both, "pipelines", "taskName")).as("pipelines with the trigger filter, not their own")
            .containsOnly(entry("Beta labs", 2L));
        assertThat(counts(both, "triggers", "key")).as("triggers with the pipeline filter, not their own")
            .containsEntry("retry", 1L).containsEntry("schedule", 1L).containsEntry("document", 1L).containsEntry("manual", 0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theHistogramSlotsAreChicagoHoursForTwoDays() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 1L, "admin@t.test");
        Map<String, Object> answer = facets(week());
        Map<String, Object> histogram = (Map<String, Object>) answer.get("histogram");
        assertThat(histogram).containsEntry("bucket", "hour");
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) histogram.get("buckets");
        assertThat(buckets).hasSize(48);
        Map<String, Long> nonEmpty = new LinkedHashMap<>();
        buckets.stream().filter(b -> (Long) b.get("total") > 0).forEach(b -> nonEmpty.put((String) b.get("start"), (Long) b.get("total")));
        assertThat(nonEmpty).containsExactly(entry("2026-10-12T10:00:00", 2L),
            entry("2026-10-12T23:00:00", 1L), entry("2026-10-13T01:00:00", 1L),
            entry("2026-10-13T02:00:00", 1L), entry("2026-10-13T03:00:00", 1L),
            entry("2026-10-13T05:00:00", 1L));
        assertThat((Map<String, Long>) buckets.get(10).get("counts")).containsEntry("Completed", 1L).containsEntry("Failed", 1L);
    }

    @Test
    void aTenantUserCountsTheirOwnJobsRunsOnlyAndAnotherWorkspaceNever() {
        TenantContext.set(TENANT, "TENANT_USER", OWNER, "owner@t.test");
        Map<String, Object> mine = facets(week());
        assertThat(mine).as("Alpha's runs: the job names them").containsEntry("total", 4L);
        assertThat(counts(mine, "pipelines", "taskName")).containsOnlyKeys("Alpha intake");

        TenantContext.set(OTHER, "TENANT_ADMIN", 2L, "admin@other.test");
        assertThat(facets(week())).containsEntry("total", 1L);
    }

    /** The day index itself is proven on a year of runs in ScaleReadsPostgresTest; here, no scan of the whole table. */
    @Test
    void theFacetsReadTheRangeWithoutScanningEveryRun() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 1L, "admin@t.test");
        db.sql().execute("SET enable_seqscan = off");
        try {
            String plan = String.join("\n", db.sql().queryForList("EXPLAIN " + new QueryService().runFacets(week(), RunFacets.HOUR), String.class));
            assertThat(plan).doesNotContain("Seq Scan on job_queue");
        } finally {
            db.sql().execute("SET enable_seqscan = on");
        }
    }

    @Test
    void eachPipelinesNewestRunTheCallerMaySee() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 1L, "admin@t.test");
        List<Map<String, Object>> rows = db.sql().queryForList(new QueryService().lastRunsOfTasks(Arrays.asList(ALPHA, BETA, 93021L)));
        Map<Long, Long> newest = new LinkedHashMap<>();
        rows.forEach(r -> newest.put(((Number) r.get("id")).longValue(), ((Number) r.get("job_queue_id")).longValue()));
        assertThat(newest).as("not the deleted run 7; not another workspace's pipeline")
            .containsOnly(entry(ALPHA, 930109L), entry(BETA, 930106L));

        TenantContext.set(TENANT, "TENANT_USER", 88L, "beta@t.test");
        assertThat(db.sql().queryForList(new QueryService().lastRunsOfTasks(Arrays.asList(ALPHA, BETA))))
            .extracting(r -> ((Number) r.get("id")).longValue()).containsExactly(BETA);
    }

    @Test
    void thePipelinesLatestDefinitionsAreTheLiveOnesOfTheWorkspace() {
        PipelineDefinitionStore store = new PipelineDefinitionStore(db.sql());
        List<PipelineDefinitionStore.TaskDefinition> found = store.latestForTasks(TENANT, null);
        assertThat(found).extracting(t -> t.taskName + " v" + t.stored.version).containsExactly("Alpha intake v2");
        assertThat(found.get(0).stored.json).contains("read_api");
        assertThat(store.latestForTasks(TENANT, Collections.singletonList(BETA))).as("Beta has no definition: legacy").isEmpty();
        assertThat(store.latestForTasks(null, new ArrayList<>(Arrays.asList(ALPHA, 93021L)))).extracting(t -> t.taskDetailId)
            .containsExactlyInAnyOrder(ALPHA, 93021L);
        assertThat(store.latestForTasks(OTHER, Collections.singletonList(ALPHA))).isEmpty();
        assertThat(store.latestForTasks(TENANT, Collections.emptyList())).isEmpty();
    }
}
