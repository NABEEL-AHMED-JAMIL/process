package process.pipeline;

import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchPostgres;
import process.model.dto.ResponseDto;
import process.pipeline.registry.JdbcTaskOverrideStore;
import process.pipeline.registry.TaskOverrideStore;
import process.security.TenantContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Owner #16 and #17 on etl_job as the changelog builds it (V215), through the application's pool (process_app, row
 * security): a platform administrator's block and the audit of every change, one workspace's never visible to another,
 * the audit insert-only; and the Task Registry's counts -- pipelines per task (their latest definition), per Legacy row
 * (their pipeline id), and those pipelines' runs this month -- counted in the caller's workspace only, a platform
 * administrator's chosen one, or every one. Opt-in: needs NOTIFICATIONS_TEST_DB_URL.
 */
class TaskRegistryControlsPostgresTest {

    static final long A = 8981L;
    static final long B = 8982L;
    static ScratchPostgres db;
    static JdbcTemplate login;
    static JdbcTemplate app;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("task_registry_controls");
        login = db.jdbc();
        app = db.appJdbc();
        for (long tenant : new long[] {A, B}) {
            login.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", tenant, "t" + tenant, "T" + tenant);
            login.update("INSERT INTO source_task_type (source_task_type_id, tenant_id, service_name, description, queue_topic_partition, "
                + "task_type_status) VALUES (?, ?, 'r', 'r', 'topic=etl.r&partitions=[*]', 'Active')", tenant * 10, tenant);
        }
        // A: a step-engine pipeline running sample, dedupe twice and pivot; its definition's older version ran filter.
        pipeline(89811, A, "ENG");
        definition(89811, 1, "{\"version\":1,\"steps\":[{\"key\":\"f\",\"task\":\"filter\",\"config\":{}}]}");
        definition(89811, 2, "{\"version\":1,\"steps\":[{\"key\":\"read\",\"task\":\"sample\",\"config\":{\"rows\":[{\"x\":1}]}},"
            + "{\"key\":\"dd1\",\"task\":\"dedupe\",\"config\":{}},{\"key\":\"dd2\",\"task\":\"dedupe\",\"config\":{\"keep\":\"last\"}},"
            + "{\"key\":\"wide\",\"task\":\"pivot\",\"config\":{\"column\":\"x\",\"op\":\"count\"}}]}");
        task(A, 89811, "ENG", "Active", "Claims dedupe");
        job(A, 89811, 89811);
        run(89811, 1, "now()", "Active");
        run(89811, 2, "now()", "Active");
        run(89811, 3, LAST_MONTH, "Active");
        run(89811, 4, "now()", "Delete");
        // A second pipeline on the same registry pipeline, deleted: not counted.
        task(A, 89813, "ENG", "Delete", "Old copy");
        job(A, 89813, 89813);
        run(89813, 1, "now()", "Active");
        // A: a legacy pipeline (no definition) and the pipeline on it.
        pipeline(89812, A, "OLD");
        task(A, 89812, "OLD", "Active", "Old worker");
        job(A, 89812, 89812);
        run(89812, 1, "now()", "Active");
        // B: its own pipeline of the same id running dedupe, three runs.
        pipeline(89821, B, "ENG");
        definition(89821, 1, "{\"version\":1,\"steps\":[{\"key\":\"x\",\"task\":\"dedupe\",\"config\":{}}]}");
        task(B, 89821, "ENG", "Active", "Bravo dedupe");
        job(B, 89821, 89821);
        for (int i = 1; i <= 3; i++) {
            run(89821, i, "now()", "Active");
        }
    }

    private static final String LAST_MONTH = "(date_trunc('month', now() AT TIME ZONE 'America/Chicago') AT TIME ZONE 'America/Chicago') - interval '1 hour'";

    private static void pipeline(long key, long tenant, String id) {
        login.update("INSERT INTO pipeline (pipeline_key, tenant_id, pipeline_id, pipeline_name, status, source_task_type_id) "
            + "VALUES (?, ?, ?, ?, 'Active', ?)", key, tenant, id, id + " name", tenant * 10);
    }

    private static void definition(long key, int version, String json) {
        login.update("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (?, ?, ?::json)", key, version, json);
    }

    private static void task(long tenant, long id, String pipelineId, String status, String name) {
        login.update("INSERT INTO source_task (task_detail_id, tenant_id, source_task_type_id, task_name, task_payload, task_status, "
            + "pipeline_id, date_created) VALUES (?, ?, ?, ?, '<p/>', ?, ?, now())", id, tenant, tenant * 10, name, status, pipelineId);
    }

    private static void job(long tenant, long jobId, long taskId) {
        login.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, job_running_status, priority, "
            + "tenant_id, task_detail_id, complete_job, fail_job, skip_job, max_attempts, retry_backoff_seconds) "
            + "VALUES (?, now(), 'Manual', ?, 'Active', 'Queue', 1, ?, ?, false, false, false, 1, 1)", jobId, "job " + jobId, tenant, taskId);
    }

    private static void run(long jobId, int n, String createdAt, String status) {
        login.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status) VALUES (?, ?, 'Completed', "
            + createdAt + ", " + createdAt + ", ?)", jobId * 10 + n, jobId, status);
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    @Test
    void aBlockAndTheAuditAreTheWorkspacesOwnAndTheAuditIsInsertOnly() {
        JdbcTaskOverrideStore store = new JdbcTaskOverrideStore(app);
        TenantContext.set(null, "PLATFORM_ADMIN", 9001L, "qa-platform-admin");
        store.block(A, "dedupe", "Under review", 9001L);
        store.block(B, "pivot", null, 9001L);
        store.set(A, "filter", false, 9001L, "PLATFORM_ADMIN");
        store.audit(A, "dedupe", "block", "default", "blocked", "Under review", 9001L, "PLATFORM_ADMIN");
        store.audit(B, "pivot", "block", "default", "blocked", null, 9001L, "PLATFORM_ADMIN");

        TenantContext.set(A, "TENANT_ADMIN", 9011L, "admin@a");
        assertThat(store.blocks(A)).containsOnlyKeys("dedupe");
        assertThat(store.blocks(A).get("dedupe").reason).isEqualTo("Under review");
        assertThat(store.blocks(B)).as("B's block is not A's to read").isEmpty();
        TaskOverrideStore.Switch filter = store.switchRows(A).get("filter");
        assertThat(filter.enabled).isFalse();
        assertThat(filter.updatedByRole).isEqualTo("PLATFORM_ADMIN");
        assertThat(store.audits(A, null, 10)).extracting(a -> a.taskCode + " " + a.action + " " + a.actorRole).containsExactly("dedupe block PLATFORM_ADMIN");
        assertThat(store.audits(B, null, 10)).isEmpty();
        assertThat(store.unblock(B, "pivot")).as("A cannot lift B's block").isFalse();
        assertThatThrownBy(() -> app.update("UPDATE task_registry_audit SET note = 'x'")).hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.update("DELETE FROM task_registry_audit")).hasStackTraceContaining("permission denied");
        TenantContext.clear();

        assertThat(login.queryForObject("SELECT count(*) FROM task_registry_block WHERE tenant_id = ?", Integer.class, B)).isEqualTo(1);
        assertThatThrownBy(() -> login.update("INSERT INTO task_registry_block (tenant_id, task_code, blocked_by) VALUES (?, 'legacy', 1)", A))
            .hasMessageContaining("ck_task_registry_block_not_legacy");
        assertThatThrownBy(() -> login.update("INSERT INTO task_registry_audit (tenant_id, task_code, action) VALUES (?, 'x', 'paused')", A))
            .hasMessageContaining("ck_task_registry_audit_action");
        assertThat(login.queryForObject("SELECT min(audit_id) FROM task_registry_audit", Long.class)).isGreaterThanOrEqualTo(1000L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCountsArePipelinesAndThisMonthsRunsOfTheCallersWorkspace() {
        TaskUsage usage = new TaskUsage(app, new PipelineDefinitionStore(app));
        TenantContext.set(A, "TENANT_ADMIN", 9011L, "admin@a");
        for (Long asked : new Long[] {null, B}) {
            Map<String, Object> mine = (Map<String, Object>) usage.usage(asked).getData();
            assertThat((List<Map<String, Object>>) mine.get("tasks")).as("asked " + asked)
                .extracting(r -> r.get("code") + " " + r.get("pipelines") + "/" + r.get("runs"))
                .containsExactly("dedupe 1/2", "pivot 1/2", "sample 1/2");
            assertThat((List<Map<String, Object>>) mine.get("legacy")).extracting(r -> r.get("pipelineKey") + " " + r.get("pipelines") + "/" + r.get("runs"))
                .containsExactlyInAnyOrder("89811 1/2", "89812 1/1");
        }
        List<Map<String, Object>> using = (List<Map<String, Object>>) usage.pipelines("dedupe", null, null).getData();
        assertThat(using).singleElement().satisfies(p -> assertThat(p).containsEntry("taskName", "Claims dedupe").containsEntry("runsThisMonth", 2L)
            .containsEntry("status", "Active").containsEntry("steps", Arrays.asList("dd1", "dd2")));
        assertThat((List<?>) usage.pipelines("filter", null, null).getData()).as("only the latest version counts").isEmpty();
        ResponseDto runs = usage.runs("dedupe", null, 10, null);
        Map<String, Object> answer = (Map<String, Object>) runs.getData();
        assertThat(answer).containsEntry("total", 2L).containsEntry("pipelines", 1);
        assertThat((List<Map<String, Object>>) answer.get("runs")).extracting(r -> r.get("jobQueueId")).containsExactly(898112L, 898111L);
        assertThat((List<Map<String, Object>>) ((Map<String, Object>) usage.runs(null, 89812L, null, null).getData()).get("runs"))
            .extracting(r -> r.get("jobQueueId")).containsExactly(898121L);
        assertThat((List<?>) usage.pipelines(null, 89821L, null).getData()).as("B's registry pipeline names none of A's").isEmpty();
        assertThat(usage.pipelines("dedupe", 89811L, null).getStatus()).isEqualTo("ERROR");

        TenantContext.set(null, "PLATFORM_ADMIN", 9001L, "qa-platform-admin");
        Map<String, Object> theirs = (Map<String, Object>) usage.usage(B).getData();
        assertThat((List<Map<String, Object>>) theirs.get("tasks")).extracting(r -> r.get("code") + " " + r.get("pipelines") + "/" + r.get("runs"))
            .containsExactly("dedupe 1/3");
        Map<String, Object> all = (Map<String, Object>) usage.usage(null).getData();
        assertThat((List<Map<String, Object>>) all.get("tasks")).extracting(r -> r.get("code") + " " + r.get("pipelines") + "/" + r.get("runs"))
            .containsExactly("dedupe 2/5", "pivot 1/2", "sample 1/2");
    }
}
