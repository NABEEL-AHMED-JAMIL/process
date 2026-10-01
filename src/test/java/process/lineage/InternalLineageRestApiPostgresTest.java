package process.lineage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.schema.ScratchEtlJob;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-287: Core's finished runs as the Data Catalog's lineage reads them -- after a run id, Completed only, oldest
 * first, each with the file it was started for, its read steps' literal bucket and key (a templated key named, not
 * followed), and what it wrote to a bucket or kept as a file.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchEtlJob).
 */
class InternalLineageRestApiPostgresTest {

    private static final long A = 8871L;
    private static final long B = 8872L;

    private static ScratchEtlJob db;
    private static JdbcTemplate sql;
    private static InternalLineageRestApi api;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("lineage_runs");
        sql = db.sql();
        api = new InternalLineageRestApi(sql, "t0k");
        for (long tenant : new long[] {A, B}) {
            sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", tenant, "t" + tenant, "T" + tenant);
        }
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (887101, now(), 'Manual', 'Customers clean-up', 'Active', 1, ?), (887201, now(), 'Manual', 'Bravo job', 'Active', 1, ?)", A, B);
        sql.update("INSERT INTO pipeline (pipeline_key, pipeline_id, pipeline_name, tenant_id, status, date_created) "
            + "VALUES (990001, 'LINEAGE_TEST', 'Lineage test', ?, 'Active', now())", A);
        long definition = sql.queryForObject("INSERT INTO pipeline_definition (tenant_id, pipeline_key, version, definition) VALUES (?, 990001, 1, "
            + "'{\"version\":1,\"steps\":[{\"key\":\"read\",\"task\":\"read_file\",\"config\":{\"bucket\":\"store\",\"key\":\"in/customers.csv\"}},"
            + "{\"key\":\"daily\",\"task\":\"read_s3\",\"config\":{\"bucket\":\"store\",\"key\":\"in/{{date}}.csv\"}},"
            + "{\"key\":\"db\",\"task\":\"read_database\",\"config\":{\"connectionId\":77}},"
            + "{\"key\":\"publish\",\"task\":\"upload_bucket\",\"config\":{\"bucket\":\"store\",\"key\":\"out/clean.json\"}}]}'::jsonb) "
            + "RETURNING pipeline_definition_id", Long.class, A);
        run(88710001L, 887101L, "Completed", "store", "intake/2026/10/01/arrival.csv", definition);
        run(88710002L, 887101L, "Failed", null, null, definition);
        run(88720001L, 887201L, "Completed", null, null, null);
        long step = sql.queryForObject("SELECT step_execution_id FROM step_execution WHERE job_queue_id = 88710001 AND step_index = 0", Long.class);
        long step2 = sql.queryForObject("INSERT INTO step_execution (job_queue_id, step_index, task_code, step_key, status, pipeline_definition_id) "
            + "VALUES (88710001, 1, 'save_file', 'keep', 'Completed', ?) RETURNING step_execution_id", Long.class, definition);
        sql.update("INSERT INTO run_output (step_execution_id, kind, name, format, row_count, bucket_alias, object_key) "
            + "VALUES (?, 'bucket', 'clean.json', 'json', 3, 'store', 'out/clean.json')", step);
        sql.update("INSERT INTO run_output (step_execution_id, kind, name, format, row_count, run_dataset_id) VALUES (?, 'file', 'clean.csv', 'csv', 3, 4401)",
            step2);
    }

    private static void run(long id, long job, String status, String bucket, String key, Long definition) {
        sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, date_created, start_time, end_time, status, job_send, attempt, "
            + "input_bucket, input_key) VALUES (?, ?, ?, now(), now(), now(), 'Active', true, 1, ?, ?)", id, job, status, bucket, key);
        sql.update("INSERT INTO step_execution (job_queue_id, step_index, task_code, step_key, status, pipeline_definition_id) "
            + "VALUES (?, 0, 'upload_bucket', 'publish', 'Completed', ?)", id, definition);
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void aCompletedRunWithWhatItReadAndWhatItWrote() {
        Map<String, Object> ask = new HashMap<>();
        ask.put("afterRunId", 88710000L);
        assertThat(api.runs("wrong", ask).getStatusCodeValue()).isEqualTo(401);
        List<Map<String, Object>> runs = (List<Map<String, Object>>) api.runs("t0k", ask).getBody();
        assertThat(runs).extracting(r -> r.get("runId")).containsExactly(88710001L, 88720001L);

        Map<String, Object> run = runs.get(0);
        assertThat(run).containsEntry("tenantId", A).containsEntry("jobId", 887101L).containsEntry("jobName", "Customers clean-up");
        List<Map<String, Object>> reads = (List<Map<String, Object>>) run.get("reads");
        assertThat(reads).extracting(r -> r.get("task") + " " + r.get("bucket") + "/" + r.get("key") + (r.containsKey("templated") ? " (templated)" : ""))
            .containsExactly("input store/intake/2026/10/01/arrival.csv", "read_file store/in/customers.csv", "read_s3 store/in/{{date}}.csv (templated)",
                "read_database null/null");
        assertThat(reads.get(3)).containsEntry("kind", "source").containsEntry("connection", "77");
        List<Map<String, Object>> writes = (List<Map<String, Object>>) run.get("writes");
        assertThat(writes).extracting(w -> w.get("kind") + " " + w.get("name") + " " + w.get("bucket") + "/" + w.get("key") + " " + w.get("runDatasetId"))
            .containsExactly("bucket clean.json store/out/clean.json null", "file clean.csv null/null 4401");

        ask.put("afterRunId", 88710001L);
        ask.put("limit", 1);
        assertThat((List<Map<String, Object>>) api.runs("t0k", ask).getBody()).extracting(r -> r.get("tenantId")).containsExactly(B);
    }
}
