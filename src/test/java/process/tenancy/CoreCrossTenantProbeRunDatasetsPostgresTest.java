package process.tenancy;

import org.barco.platform.tenancy.RowSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.pipeline.Dataset;
import process.pipeline.DatasetStore;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static process.tenancy.CoreProbeFixture.*;

/**
 * MIG-166's cross-tenant probe for a run's datasets and result manifest (Wave 4): sourceJob.json/runDataset and
 * sourceJob.json/runOutputs, called by workspace A's tenant user and admin, aimed at workspace B's run and at a
 * colleague's, and by callers with no workspace. Against a real etl_job as process_app (CoreProbeFixture), with the
 * datasets on disk as FileDatasetStore keeps them. Each run has a step output, a kept file and a manifest; B's carry
 * B's markers. A probe passes when nothing of B's (or, for a tenant user, of the colleague's) comes back and every
 * refusal reads exactly as a dataset that does not exist.
 */
class CoreCrossTenantProbeRunDatasetsPostgresTest {

    private static CoreProbeFixture fx;
    private static long[] bDatasets;
    private static long[] colleagueDatasets;
    private static long[] aDatasets;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_run_datasets");
        JdbcTemplate sql = fx.db.jdbc();
        bDatasets = run(sql, B_RUN, "bravo payload marker");
        colleagueDatasets = run(sql, COLLEAGUE_RUN, "colleague run message");
        aDatasets = run(sql, A_RUN, "acme run message");
    }

    /** A run's step with an output dataset, a kept file and its manifest row, each holding the run's marker. */
    private static long[] run(JdbcTemplate sql, long run, String marker) throws Exception {
        long step = sql.queryForObject("INSERT INTO step_execution (job_queue_id, step_index, task_code, step_key, status) "
            + "VALUES (?, 5, 'save_file', 'keep', 'Completed') RETURNING step_execution_id", Long.class, run);
        String outputKey = DatasetStore.keyOf(run, 1, "keep", "output");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("note", marker);
        fx.runDatasets.write(outputKey, new Dataset(Collections.singletonList("note"), Collections.singletonList(row)));
        long output = sql.queryForObject("INSERT INTO run_dataset (step_execution_id, name, storage_key, row_count, columns, expires_at) "
            + "VALUES (?, 'output', ?, 1, '[\"note\"]'::jsonb, now() + interval '1 day') RETURNING run_dataset_id", Long.class, step,
            outputKey);
        String fileKey = DatasetStore.fileKeyOf(run, 1, "keep", "notes.csv");
        fx.runDatasets.writeFile(fileKey, ("note\r\n" + marker + "\r\n").getBytes(StandardCharsets.UTF_8));
        long file = sql.queryForObject("INSERT INTO run_dataset (step_execution_id, name, storage_key, row_count, columns, expires_at) "
            + "VALUES (?, 'notes.csv', ?, 1, '[\"note\"]'::jsonb, now() + interval '1 day') RETURNING run_dataset_id", Long.class, step,
            fileKey);
        sql.update("INSERT INTO run_output (step_execution_id, kind, name, format, row_count, byte_count, run_dataset_id, expires_at) "
            + "VALUES (?, 'file', 'notes.csv', 'csv', 1, 30, ?, now() + interval '1 day')", step, file);
        return new long[] {output, file};
    }

    @AfterAll
    static void drop() throws Exception {
        if (fx != null) {
            fx.close();
        }
    }

    @BeforeEach
    void forgetEarlierCalls() {
        fx.reset();
    }

    @Test
    void noDatasetOfAnotherWorkspaceOrAColleagueDownloads() {
        String before = fx.foreignRows();
        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            long[][] theirs = caller == USER_OF_A ? new long[][] {bDatasets, colleagueDatasets} : new long[][] {bDatasets};
            for (long[] datasets : theirs) {
                for (long dataset : datasets) {
                    for (String format : new String[] {null, "csv", "json"}) {
                        assertThat(fx.probe("GET sourceJob.json/runDataset", caller, () -> fx.stepTimeline.runDataset(dataset, format)))
                            .contains(REFUSED).contains("Dataset not found with runDatasetId.");
                    }
                }
            }
        }
        for (Long none : NO_WORKSPACE) {
            Caller caller = tenantlessUser(none);
            for (long dataset : new long[] {aDatasets[0], aDatasets[1], bDatasets[0]}) {
                assertThat(fx.probe("GET sourceJob.json/runDataset", caller, () -> fx.stepTimeline.runDataset(dataset, "csv")))
                    .contains(REFUSED).contains("Dataset not found with runDatasetId.");
            }
        }
        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).isEqualTo(before);
    }

    /** Another workspace's dataset that has expired reads as not found too -- the 410 is only ever the caller's own. */
    @Test
    void anotherWorkspacesExpiredDatasetIsNotFoundNotGone() {
        fx.db.jdbc().update("UPDATE run_dataset SET expires_at = now() - interval '1 minute' WHERE run_dataset_id = ?", bDatasets[0]);
        try {
            assertThat(fx.probe("GET sourceJob.json/runDataset", ADMIN_OF_A, () -> fx.stepTimeline.runDataset(bDatasets[0], null)))
                .contains(REFUSED).contains("Dataset not found with runDatasetId.").doesNotContain("expired");
        } finally {
            fx.db.jdbc().update("UPDATE run_dataset SET expires_at = now() + interval '1 day' WHERE run_dataset_id = ?", bDatasets[0]);
        }
        assertThat(fx.leaks).isEmpty();
    }

    @Test
    void noManifestOfAnotherWorkspaceOrAColleagueIsListed() {
        String before = fx.foreignRows();
        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            long[] runs = caller == USER_OF_A ? new long[] {B_RUN, COLLEAGUE_RUN} : new long[] {B_RUN};
            for (long theirs : runs) {
                assertThat(fx.probe("GET sourceJob.json/runOutputs", caller, () -> fx.stepTimeline.runOutputs(theirs, null)))
                    .contains(REFUSED).contains("Run not found with jobQueueId.");
            }
        }
        for (Long none : NO_WORKSPACE) {
            assertThat(fx.probe("GET sourceJob.json/runOutputs", tenantlessUser(none), () -> fx.stepTimeline.runOutputs(A_RUN, null)))
                .contains(REFUSED);
        }
        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).isEqualTo(before);
    }

    /**
     * MIG-243: every download is in file_access_log -- served in the dataset's workspace with its run; another workspace's,
     * refused as not found, in the caller's own and without the run, so nothing is written in B; a caller with no
     * workspace leaves no row at all.
     */
    @Test
    void everyDownloadIsInTheFileAccessLog() {
        JdbcTemplate sql = fx.db.jdbc();
        long before = sql.queryForObject("SELECT count(*) FROM file_access_log", Long.class);
        String foreign = fx.foreignRows();

        fx.probe("GET sourceJob.json/runDataset(mine, logged)", ADMIN_OF_A, () -> fx.stepTimeline.runDataset(aDatasets[0], "csv"));
        fx.probe("GET sourceJob.json/runDataset(theirs, logged)", ADMIN_OF_A, () -> fx.stepTimeline.runDataset(bDatasets[1], null));
        fx.probe("GET sourceJob.json/runDataset(no workspace, logged)", tenantlessUser(null), () -> fx.stepTimeline.runDataset(aDatasets[0], null));

        List<Map<String, Object>> rows = sql.queryForList("SELECT tenant_id, user_id, action, kind, run_dataset_id, job_queue_id, format, outcome, "
            + "http_status, file_name FROM file_access_log ORDER BY file_access_id DESC LIMIT 2");
        assertThat(sql.queryForObject("SELECT count(*) FROM file_access_log", Long.class)).isEqualTo(before + 2);
        Map<String, Object> theirs = rows.get(0);
        assertThat(theirs).containsEntry("tenant_id", A).containsEntry("run_dataset_id", bDatasets[1]).containsEntry("outcome", "refused")
            .containsEntry("http_status", 404).containsEntry("job_queue_id", null).containsEntry("file_name", null);
        Map<String, Object> mine = rows.get(1);
        assertThat(mine).containsEntry("tenant_id", A).containsEntry("user_id", ADMIN_OF_A.appUserId).containsEntry("action", "download")
            .containsEntry("kind", "run_dataset").containsEntry("run_dataset_id", aDatasets[0]).containsEntry("job_queue_id", A_RUN)
            .containsEntry("format", "csv").containsEntry("outcome", "served").containsEntry("http_status", 200);
        assertThat(fx.foreignRows()).as("nothing written in B's workspace, or anyone else's").isEqualTo(foreign);
        assertThat(RowSecurity.forTenant(B, () -> fx.db.appJdbc().queryForObject("SELECT count(*) FROM file_access_log", Long.class)))
            .as("B sees none of A's records").isZero();
    }

    /** The control: A's admin downloads A's output and kept file, and a colleague's; lists A's manifest without a storage key. */
    @Test
    void myWorkspacesDatasetsDownloadAndItsManifestLists() {
        assertThat(fx.probe("GET sourceJob.json/runDataset(mine)", ADMIN_OF_A, () -> fx.stepTimeline.runDataset(aDatasets[0], "json")))
            .contains("HTTP 200").contains("attachment; filename=\"run-" + A_RUN + "-attempt-1-keep-output.json\"")
            .contains("[{\"note\":\"acme run message\"}]");
        assertThat(fx.probe("GET sourceJob.json/runDataset(my kept file)", USER_OF_A, () -> fx.stepTimeline.runDataset(aDatasets[1], null)))
            .contains("HTTP 200").contains("notes.csv").contains("acme run message");
        assertThat(fx.probe("GET sourceJob.json/runDataset(a colleague's, as admin)", ADMIN_OF_A,
            () -> fx.stepTimeline.runDataset(colleagueDatasets[0], "csv"))).contains("HTTP 200");
        assertThat(fx.probe("GET sourceJob.json/runOutputs(mine)", ADMIN_OF_A, () -> fx.stepTimeline.runOutputs(A_RUN, null)))
            .contains(SUCCEEDED).contains("\"name\":\"notes.csv\"").contains("\"runDatasetId\":" + aDatasets[1])
            .contains("\"expired\":false").doesNotContain("datasets/");
        assertThat(fx.leaks).isEmpty();
    }

    @Test
    void myExpiredDatasetIsGone() {
        fx.db.jdbc().update("UPDATE run_dataset SET expires_at = now() - interval '1 minute' WHERE run_dataset_id = ?", aDatasets[0]);
        try {
            assertThat(fx.probe("GET sourceJob.json/runDataset(mine, expired)", ADMIN_OF_A,
                () -> fx.stepTimeline.runDataset(aDatasets[0], "csv"))).contains(REFUSED).contains("This dataset expired at")
                .doesNotContain("acme run message");
        } finally {
            fx.db.jdbc().update("UPDATE run_dataset SET expires_at = now() + interval '1 day' WHERE run_dataset_id = ?", aDatasets[0]);
        }
        assertThat(fx.leaks).isEmpty();
    }
}
