package process.tenancy;

import org.springframework.http.ResponseEntity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.api.CustomerRunsRestApi;
import process.api.RunItemRestApi;
import process.customer.CustomerItems;
import process.customer.CustomerRunStore;
import process.customer.CustomerRuns;
import process.customer.RunFiles;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.pipeline.Dataset;
import process.pipeline.DatasetStore;
import process.pipeline.DefinitionValidator;
import process.pipeline.Definitions;
import process.pipeline.JdbcRunAttemptStore;
import process.pipeline.JdbcStepStore;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.RunRetries;
import process.pipeline.StepTasks;
import process.pipeline.items.ItemAccessLog;
import process.pipeline.items.ItemLinks;
import process.identity.IdentityPort;
import process.pipeline.PipelineDefinition;
import process.pipeline.items.ItemReviews;
import process.pipeline.items.ItemRetries;
import process.pipeline.items.ItemsAcrossRuns;
import process.pipeline.items.JdbcRunItemStore;
import process.pipeline.items.RunItemReviews;
import process.pipeline.items.RunItems;
import process.pipeline.review.JdbcRunReviewStore;
import process.pipeline.review.RunReviews;
import process.security.TenantContext;
import process.storage.remote.StorageServiceClient;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static process.tenancy.CoreProbeFixture.A;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_A;
import static process.tenancy.CoreProbeFixture.A_JOB;
import static process.tenancy.CoreProbeFixture.A_PIPELINE;
import static process.tenancy.CoreProbeFixture.B_JOB;
import static process.tenancy.CoreProbeFixture.A_RUN;
import static process.tenancy.CoreProbeFixture.B_PIPELINE;
import static process.tenancy.CoreProbeFixture.B_RUN;
import static process.tenancy.CoreProbeFixture.COLLEAGUE_RUN;
import static process.tenancy.CoreProbeFixture.Caller;
import static process.tenancy.CoreProbeFixture.NO_WORKSPACE;
import static process.tenancy.CoreProbeFixture.PLATFORM;
import static process.tenancy.CoreProbeFixture.REFUSED;
import static process.tenancy.CoreProbeFixture.USER_OF_A;
import static process.tenancy.CoreProbeFixture.tenantlessUser;

/**
 * MIG-420's cross-tenant probe for the Run Items view: every sourceJob.json/runItems endpoint, the item retry and the
 * customer API's GET /v1/runs/{id}/items(/{itemKey}), called by workspace A's tenant user and admin aimed at workspace B's
 * run (and the tenant user at a colleague's), by callers with no workspace, by the platform administrator's own sign-in, and
 * by A's API client. Against a real etl_job as process_app, so row security is on beside the code's own checks. Each run has
 * an item with its step's output and a kept file, B's carrying B's markers. A probe passes when nothing of B's comes back,
 * every refusal reads as a run that does not exist (404), and row security alone shows A only A's items.
 *
 * MIG-421 (v2): the bulk selection, the selected items' zip and CSV, Send to review, the schedule's items across runs and the
 * compare -- the same callers, the same answers; run_item_report under row security; a team of another workspace refused.
 */
class CoreCrossTenantProbeRunItemsPostgresTest {

    private static final String DEFINITION = "{\"version\":1,\"source\":{\"type\":\"task\"},\"steps\":[{\"key\":\"read\",\"task\":\"sample\","
        + "\"config\":{\"rows\":[{\"key\":\"in/a.m4a\"}]}},{\"key\":\"ask\",\"name\":\"AI prompt\",\"task\":\"ai_prompt\",\"config\":{\"promptId\":1}}]}";

    private static CoreProbeFixture fx;
    private static RunItemRestApi items;
    private static CustomerRunsRestApi customer;
    private static ItemLinks links;
    /** The Task inbox requests Send to review made. */
    private static final List<ItemReviews.ReviewRequest> SENT = new CopyOnWriteArrayList<>();
    /** A's team, and B's. */
    private static final long A_TEAM = 9601L;
    private static final long B_TEAM = 9602L;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_run_items");
        JdbcTemplate sql = fx.db.jdbc();
        for (long pipeline : new long[] {A_PIPELINE, B_PIPELINE}) {
            sql.update("INSERT INTO pipeline_definition (tenant_id, pipeline_key, version, definition, date_created) "
                + "SELECT tenant_id, pipeline_key, 1, ?::json, now() - interval '30 days' FROM pipeline WHERE pipeline_key = ?", DEFINITION, pipeline);
        }
        sql.update("UPDATE job_queue SET job_status = 'Completed' WHERE job_queue_id IN (?, ?, ?)", A_RUN, COLLEAGUE_RUN, B_RUN);
        item(sql, A_RUN, "acme run message");
        item(sql, COLLEAGUE_RUN, "colleague run message");
        item(sql, B_RUN, "bravo payload marker");

        JdbcTemplate app = fx.db.appJdbc();
        JobQueueRepository runRows = fx.jpa.repository(JobQueueRepository.class);
        SourceJobRepository jobRows = fx.jpa.repository(SourceJobRepository.class);
        StepTasks tasks = Definitions.builtInTasks();
        JdbcRunItemStore store = new JdbcRunItemStore(app);
        RunItems runItems = new RunItems(store, runRows, jobRows, new JdbcStepStore(app), new PipelineDefinitionStore(app), tasks, fx.runDatasets,
            new ItemAccessLog(app));
        links = new ItemLinks("probe-service-token");
        runItems.useLinks(links);
        RunRetries retries = new RunRetries(new JdbcStepStore(app), new JdbcRunAttemptStore(app), new PipelineDefinitionStore(app),
            new DefinitionValidator(tasks), tasks, fx.bulkAction, app, fx.jpa.transactions());
        ItemsAcrossRuns across = new ItemsAcrossRuns(runItems, store, jobRows, new JdbcStepStore(app), new PipelineDefinitionStore(app),
            fx.runDatasets);
        RunItemReviews sendToReview = new RunItemReviews(runItems, runRows, jobRows);
        sendToReview.useReviews(new ItemReviews() {
            @Override
            public Optional<String> unavailable() {
                return Optional.empty();
            }

            @Override
            public Started send(ReviewRequest request) {
                SENT.add(request);
                return new Started(1, "Running");
            }
        });
        IdentityPort identity = mock(IdentityPort.class);
        when(identity.isActiveTeamOf(anyLong(), anyLong())).thenReturn(false);
        when(identity.isActiveTeamOf(eq(A), eq(A_TEAM))).thenReturn(true);
        sendToReview.useIdentity(identity);
        items = new RunItemRestApi(runItems, new ItemRetries(store, runRows, jobRows, retries, runItems, tasks), across, sendToReview);
        RunReviews reviews = new RunReviews(new JdbcStepStore(app), new PipelineDefinitionStore(app), new JdbcRunReviewStore(app));
        CustomerRuns customerRuns = new CustomerRuns(new CustomerRunStore(app), runRows, jobRows, reviews, new JdbcStepStore(app),
            new PipelineDefinitionStore(app), new RunFiles(mock(StorageServiceClient.class)));
        customer = new CustomerRunsRestApi(customerRuns, null, null, new CustomerItems(customerRuns, runItems, new JdbcStepStore(app)));
    }

    /** A run's per-item step with its output (the run's marker in its row), a kept file, and the item pointing at both. */
    private static void item(JdbcTemplate sql, long run, String marker) throws Exception {
        long step = sql.queryForObject("INSERT INTO step_execution (job_queue_id, step_index, task_code, step_key, status) "
            + "VALUES (?, 1, 'ai_prompt', 'ask', 'Completed') RETURNING step_execution_id", Long.class, run);
        String outputKey = DatasetStore.keyOf(run, 1, "ask", "output");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", "in/" + marker + ".m4a");
        row.put("insight", marker);
        fx.runDatasets.write(outputKey, new Dataset(Arrays.asList("key", "insight"), Collections.singletonList(row)));
        long output = sql.queryForObject("INSERT INTO run_dataset (step_execution_id, name, storage_key, row_count, columns, expires_at) "
            + "VALUES (?, 'output', ?, 1, '[\"key\",\"insight\"]'::jsonb, now() + interval '1 day') RETURNING run_dataset_id", Long.class, step,
            outputKey);
        String fileKey = DatasetStore.fileKeyOf(run, 1, "ask", "insight.json");
        fx.runDatasets.writeFile(fileKey, ("{\"insight\":\"" + marker + "\"}").getBytes(StandardCharsets.UTF_8));
        long file = sql.queryForObject("INSERT INTO run_dataset (step_execution_id, name, storage_key, row_count, columns, expires_at) "
            + "VALUES (?, 'insight.json', ?, 1, '[]'::jsonb, now() + interval '1 day') RETURNING run_dataset_id", Long.class, step, fileKey);
        sql.update("INSERT INTO run_output (step_execution_id, kind, name, format, row_count, byte_count, run_dataset_id, expires_at) "
            + "VALUES (?, 'file', 'insight.json', 'json', 1, 30, ?, now() + interval '1 day')", step, file);
        String steps = String.format("[{\"i\":1,\"key\":\"ask\",\"task\":\"ai_prompt\",\"status\":\"failed\",\"note\":\"%s\",\"ds\":%d,\"rows\":[0],"
            + "\"n\":1,\"files\":1,\"se\":%d,\"idc\":\"key\"}]", marker, output, step);
        sql.update("INSERT INTO run_item (job_queue_id, attempt, item_key, item_ref, label, position, status, failed_step_index, failed_step_key, "
            + "reason, steps, file_count, started_at, ended_at) VALUES (?, 1, ?, '1', ?, 1, 'failed', 1, 'ask', ?, ?::jsonb, 1, now(), now())",
            run, "in/" + marker + ".m4a", marker + ".m4a", "Failed: " + marker, steps);
        sql.update("INSERT INTO run_item_file (job_queue_id, attempt, item_key, step_index, step_key, role, step_execution_id, file_name) "
            + "VALUES (?, 1, ?, 1, 'ask', 'output', ?, 'insight.json')", run, "in/" + marker + ".m4a", step);
        // MIG-421: the item's values (the across-runs search) and the report kept for it.
        sql.update("UPDATE run_item SET fields = ?::jsonb, fields_ds = ?, pipeline_version = 1 WHERE job_queue_id = ?",
            "{\"key\":\"in/" + marker + ".m4a\",\"status.risk\":\"High\",\"insight\":\"" + marker + "\"}", output, run);
        sql.update("INSERT INTO run_item_report (job_queue_id, step_key, item_tag, item_key, data_hash, report_id, report_version, report_label, "
            + "file_name, made_attempt) VALUES (?, 'ask', 'i0', ?, ?, 'R1', 1, ?, 'insight.pdf', 1)", run, "in/" + marker + ".m4a",
            String.format("%064d", run), "RPT " + marker);
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

    private static RunItems.ListRequest list(long run) {
        RunItems.ListRequest request = new RunItems.ListRequest();
        request.setJobQueueId(run);
        request.setFields(Collections.singletonList("insight"));
        return request;
    }

    private static RunItems.FilesRequest files(long run) {
        RunItems.FilesRequest request = new RunItems.FilesRequest();
        request.setJobQueueId(run);
        request.setRef("1");
        return request;
    }

    private static long fileRefOf(long run) {
        return fx.db.jdbc().queryForObject("SELECT run_item_file_id FROM run_item_file WHERE job_queue_id = ? AND file_name = 'insight.json'",
            Long.class, run);
    }

    @Test
    void noItemOfAnotherWorkspaceOrAColleagueIsListedOpenedDownloadedOrRetried() throws Exception {
        String before = fx.foreignRows();
        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            long[] theirs = caller == USER_OF_A ? new long[] {B_RUN, COLLEAGUE_RUN} : new long[] {B_RUN};
            for (long run : theirs) {
                long fileRef = fileRefOf(run);
                assertThat(fx.probe("POST sourceJob.json/runItems", caller, () -> items.listRunItems(list(run))))
                    .contains(REFUSED).contains("Run not found with jobQueueId.");
                assertThat(fx.probe("GET sourceJob.json/runItems/detail", caller, () -> items.detail(run, "1", null, null, null)))
                    .contains(REFUSED).contains("Run not found with jobQueueId.");
                assertThat(fx.probe("GET sourceJob.json/runItems/data", caller, () -> items.data(run, "1", "ask", null)))
                    .contains(REFUSED).contains("Run not found with jobQueueId.");
                assertThat(fx.probe("POST sourceJob.json/runItems/files", caller, () -> items.listRunItemFiles(files(run))))
                    .contains(REFUSED).contains("Run not found with jobQueueId.");
                assertThat(fx.probe("GET sourceJob.json/runItems/file", caller, () -> items.file(run, "1", fileRef, true)))
                    .startsWith("HTTP 404").contains(REFUSED);
                assertThat(fx.probe("GET sourceJob.json/runItems/zipLink", caller, () -> items.zipLink(run, "1", null, null, false, null, null, null)))
                    .contains(REFUSED).contains("Run not found with jobQueueId.");
                assertThat(fx.probe("GET sourceJob.json/runItems/csv", caller, () -> items.csv(run, null, null, null, null, null)))
                    .startsWith("HTTP 404").contains(REFUSED);
                assertThat(fx.probe("POST sourceJob.json/retryRunItems", caller, () -> items.retryRunItems(retry(run))))
                    .contains(REFUSED).contains("Run not found with jobQueueId.");
                // MIG-421: the bulk actions on their run.
                assertThat(fx.probe("POST sourceJob.json/runItems/selection", caller, () -> items.selectRunItems(selectAll(run))))
                    .contains(REFUSED).contains("Run not found with jobQueueId.");
                assertThat(fx.probe("GET sourceJob.json/runItems/zipLink(many)", caller,
                    () -> items.zipLink(run, null, null, Collections.singletonList("1"), false, null, null, null)))
                    .contains(REFUSED).contains("Run not found with jobQueueId.");
                assertThat(fx.probe("GET sourceJob.json/runItems/csv(refs)", caller,
                    () -> items.csv(run, null, null, null, Collections.singletonList("1"), null))).startsWith("HTTP 404").contains(REFUSED);
                assertThat(fx.probe("POST sourceJob.json/reviewRunItems", caller, () -> items.reviewRunItems(review(run, A_TEAM))))
                    .contains(REFUSED).contains("Run not found with jobQueueId.");
                ItemRetries.Request bulk = retry(run);
                bulk.setFailed(null);
                bulk.setAll(true);
                bulk.setOnlyFailed(true);
                assertThat(fx.probe("POST sourceJob.json/retryRunItems(bulk)", caller, () -> items.retryRunItems(bulk)))
                    .contains(REFUSED).contains("Run not found with jobQueueId.");
                // A link signed for this caller's own workspace and person, naming their run, opens nothing of theirs.
                String token = links.issue(A, caller == USER_OF_A ? CoreProbeFixture.USER_A : CoreProbeFixture.ADMIN_A, run, "1", null)[0];
                assertThat(fx.probe("GET sourceJob.json/runItems/zip", caller, () -> items.zip(run, token)))
                    .startsWith("HTTP 404").contains(REFUSED);
            }
            RunItemRestApi.CountsRequest counts = new RunItemRestApi.CountsRequest();
            counts.setJobQueueIds(Arrays.asList(A_RUN, COLLEAGUE_RUN, B_RUN));
            String counted = fx.probe("POST sourceJob.json/runItems/counts", caller, () -> items.countRunItems(counts));
            assertThat(counted).doesNotContain("\"" + B_RUN + "\"");
            if (caller == USER_OF_A) {
                assertThat(counted).doesNotContain("\"" + COLLEAGUE_RUN + "\"");
            }
            assertThat(fx.probe("GET sourceJob.json/runItems/attention", caller, () -> items.attention(10)))
                .doesNotContain("bravo payload marker").doesNotContain("\"jobQueueId\":" + B_RUN);
            // MIG-421: B's schedule is not there; A's schedule never lists or compares B's runs.
            assertThat(fx.probe("POST sourceJob.json/itemRuns(B's schedule)", caller, () -> items.listItemRuns(across(B_JOB, "bravo"))))
                .contains(REFUSED).contains("Schedule not found.");
            assertThat(fx.probe("GET sourceJob.json/itemRuns/compare(B's schedule)", caller,
                () -> items.compare(B_JOB, "in/bravo payload marker.m4a", B_RUN, A_RUN, null))).contains(REFUSED);
            assertThat(fx.probe("GET sourceJob.json/itemRuns/compare(B's run under A's schedule)", caller,
                () -> items.compare(A_JOB, "in/bravo payload marker.m4a", B_RUN, A_RUN, null))).contains(REFUSED).doesNotContain("bravo payload");
            assertThat(fx.probe("POST sourceJob.json/itemRuns(A's schedule, B's marker)", caller, () -> items.listItemRuns(across(A_JOB, "bravo"))))
                .contains("\"total\":0").doesNotContain("bravo payload marker");
        }
        assertThat(SENT).as("no review was sent for another workspace's items").isEmpty();
        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).isEqualTo(before);
    }

    private static RunItems.ListRequest selectAll(long run) {
        RunItems.ListRequest request = new RunItems.ListRequest();
        request.setJobQueueId(run);
        request.setStatus("all");
        return request;
    }

    private static RunItemReviews.Request review(long run, long team) {
        RunItemReviews.Request request = new RunItemReviews.Request();
        request.setJobQueueId(run);
        request.setRefs(Collections.singletonList("1"));
        request.setReviewers(PipelineDefinition.Reviewers.of("team", String.valueOf(team)));
        return request;
    }

    private static ItemsAcrossRuns.Request across(long job, String search) {
        ItemsAcrossRuns.Request request = new ItemsAcrossRuns.Request();
        request.setJobId(job);
        request.setSearch(search);
        request.setFields(Arrays.asList("status.risk", "insight"));
        return request;
    }

    private static ItemRetries.Request retry(long run) {
        ItemRetries.Request request = new ItemRetries.Request();
        request.setJobQueueId(run);
        request.setFailed(true);
        request.setFrom("failed_step");
        return request;
    }

    /** A document (ref 2) with one page (2.1) and the page's image, in this run; B's page carries B's marker. */
    private static void document(JdbcTemplate sql, long run, String image) {
        long step = sql.queryForObject("SELECT step_execution_id FROM step_execution WHERE job_queue_id = ? AND step_key = 'ask'", Long.class, run);
        sql.update("INSERT INTO run_item (job_queue_id, attempt, item_key, item_ref, label, position, status, steps, child_count, file_count, "
            + "started_at, ended_at) VALUES (?, 1, 'docs/p.pdf', '2', 'p.pdf', 2, 'done', '[]'::jsonb, 1, 0, now(), now())", run);
        sql.update("INSERT INTO run_item (job_queue_id, attempt, item_key, item_ref, parent_key, label, position, status, steps, file_count, "
            + "started_at, ended_at) VALUES (?, 1, 'docs/p.pdf#1', '2.1', 'docs/p.pdf', 'Page 1', 1, 'done', '[]'::jsonb, 1, now(), now())", run);
        sql.update("INSERT INTO run_item_file (job_queue_id, attempt, item_key, step_index, step_key, role, step_execution_id, file_name) "
            + "VALUES (?, 1, 'docs/p.pdf#1', 1, 'ask', 'output', ?, ?)", run, step, image);
    }

    @Test
    void aDocumentsFilesAreItsPagesInItsOwnRunAlone() throws Exception {
        // ZT-I16 (live run 1856): a document's "40 files" opened "0 files" -- its pages' files were never its own.
        JdbcTemplate sql = fx.db.jdbc();
        document(sql, A_RUN, "p-page-01.png");
        document(sql, B_RUN, "bravo-page-01.png");
        try {
            RunItems.FilesRequest request = new RunItems.FilesRequest();
            request.setJobQueueId(A_RUN);
            request.ref = "2";
            request.stepKey = "ask";
            assertThat(fx.probe("POST sourceJob.json/runItems/files(A's document)", ADMIN_OF_A, () -> items.listRunItemFiles(request)))
                .contains("\"total\":1").contains("p-page-01.png").doesNotContain("bravo");
            long page = sql.queryForObject("SELECT run_item_file_id FROM run_item_file WHERE file_name = 'bravo-page-01.png'", Long.class);
            assertThat(fx.probe("GET sourceJob.json/runItems/file(B's page under A's document)", ADMIN_OF_A,
                () -> items.file(A_RUN, "2", page, false))).startsWith("HTTP 404");
            request.setJobQueueId(B_RUN);
            assertThat(fx.probe("POST sourceJob.json/runItems/files(B's document)", ADMIN_OF_A, () -> items.listRunItemFiles(request)))
                .contains(REFUSED).doesNotContain("bravo");
            assertThat(fx.leaks).isEmpty();
        } finally {
            sql.update("DELETE FROM run_item_file WHERE item_key LIKE 'docs/p.pdf%'");
            sql.update("DELETE FROM run_item WHERE item_key LIKE 'docs/p.pdf%'");
        }
    }

    @Test
    void theCallersOwnItemsAnswerAndALinkIsTheirsAlone() throws Exception {
        assertThat(fx.probe("POST sourceJob.json/runItems(A's own)", ADMIN_OF_A, () -> items.listRunItems(list(A_RUN))))
            .contains("\"itemRun\":true").contains("acme run message.m4a").contains("\"insight\":\"acme run message\"");
        assertThat(fx.probe("GET sourceJob.json/runItems/data(A's own)", ADMIN_OF_A, () -> items.data(A_RUN, "1", "ask", null)))
            .contains("acme run message").doesNotContain("bravo");
        long fileRef = fileRefOf(A_RUN);
        assertThat(fx.probe("GET sourceJob.json/runItems/file(A's own)", ADMIN_OF_A, () -> items.file(A_RUN, "1", fileRef, false)))
            .startsWith("HTTP 200").contains("{\"insight\":\"acme run message\"}");
        // A's file named under B's run: not B's, not opened.
        assertThat(fx.probe("GET sourceJob.json/runItems/file(A's file under B's run)", ADMIN_OF_A,
            () -> items.file(B_RUN, "1", fileRef, false))).startsWith("HTTP 404");
        // A link made for A's admin opens A's zip for A's admin only: not for A's tenant user, not once forged.
        String token = links.issue(A, CoreProbeFixture.ADMIN_A, A_RUN, "1", null)[0];
        assertThat(fx.probe("GET sourceJob.json/runItems/zip(A's own)", ADMIN_OF_A, () -> items.zip(A_RUN, token)))
            .startsWith("HTTP 200").contains("insight.json");
        assertThat(fx.probe("GET sourceJob.json/runItems/zip(another person's link)", USER_OF_A, () -> items.zip(A_RUN, token)))
            .startsWith("HTTP 403");
        assertThat(fx.probe("GET sourceJob.json/runItems/zip(forged)", ADMIN_OF_A, () -> items.zip(A_RUN, token + "x")))
            .startsWith("HTTP 403");
        // Row security alone: A's session sees A's item rows and files, none of B's.
        String seen = fx.probe("row security run_item", ADMIN_OF_A, () -> ResponseEntity.ok(
            fx.db.appJdbc().queryForList("SELECT label FROM run_item UNION ALL SELECT file_name || ':' || job_queue_id FROM run_item_file")));
        assertThat(seen).contains("acme run message.m4a").doesNotContain("bravo").doesNotContain(":" + B_RUN);
        assertThat(fx.leaks).isEmpty();
    }

    /** MIG-421: A's own bulk actions, across-runs list and kept reports answer A's, and row security shows A only A's reports. */
    @Test
    void theCallersOwnSelectionAcrossRunsAndReviewAnswerAndAnotherWorkspacesTeamIsRefused() throws Exception {
        assertThat(fx.probe("POST sourceJob.json/runItems/selection(A's own)", ADMIN_OF_A, () -> items.selectRunItems(selectAll(A_RUN))))
            .contains("\"items\":1").contains("\"failed\":1").contains("\"refs\":[\"1\"]");
        assertThat(fx.probe("GET sourceJob.json/runItems/zipLink(A's many)", ADMIN_OF_A,
            () -> items.zipLink(A_RUN, null, null, null, true, "failed", null, null))).contains("\"token\"").contains("\"items\":1");
        assertThat(fx.probe("GET sourceJob.json/runItems/csv(A's refs)", ADMIN_OF_A,
            () -> items.csv(A_RUN, null, null, Collections.singletonList("insight"), Collections.singletonList("1"), null)))
            .startsWith("HTTP 200").contains("acme run message").doesNotContain("bravo");
        assertThat(fx.probe("POST sourceJob.json/itemRuns(A's own, risk = High)", ADMIN_OF_A,
            () -> items.listItemRuns(across(A_JOB, "risk = High")))).contains("acme run message.m4a").contains("\"status.risk\":\"High\"")
            .contains("RPT acme run message").doesNotContain("bravo");
        assertThat(fx.probe("POST sourceJob.json/reviewRunItems(B's team)", ADMIN_OF_A, () -> items.reviewRunItems(review(A_RUN, B_TEAM))))
            .contains(REFUSED).contains("That team is not in this workspace.");
        assertThat(SENT).isEmpty();
        assertThat(fx.probe("POST sourceJob.json/reviewRunItems(A's team)", ADMIN_OF_A, () -> items.reviewRunItems(review(A_RUN, A_TEAM))))
            .contains(CoreProbeFixture.SUCCEEDED).contains("Sent 1 item to review");
        assertThat(SENT).hasSize(1);
        assertThat(SENT.get(0).tenantId).isEqualTo(A);
        assertThat(SENT.get(0).subject.toString()).contains("acme run message.m4a").doesNotContain("bravo").doesNotContain("High");
        SENT.clear();
        String seen = fx.probe("row security run_item_report", ADMIN_OF_A, () -> ResponseEntity.ok(
            fx.db.appJdbc().queryForList("SELECT report_label || ':' || job_queue_id FROM run_item_report")));
        assertThat(seen).contains("RPT acme run message").doesNotContain("bravo").doesNotContain(":" + B_RUN);
        assertThat(fx.leaks).isEmpty();
    }

    @Test
    void callersWithNoWorkspaceAndThePlatformAdministratorsOwnSignInReadNoItems() throws Exception {
        for (Long none : NO_WORKSPACE) {
            Caller caller = tenantlessUser(none);
            assertThat(fx.probe("POST sourceJob.json/runItems", caller, () -> items.listRunItems(list(A_RUN)))).contains(REFUSED);
            assertThat(fx.probe("GET sourceJob.json/runItems/detail", caller, () -> items.detail(A_RUN, "1", null, null, null))).contains(REFUSED);
        }
        for (long run : new long[] {A_RUN, B_RUN}) {
            assertThat(fx.probe("POST sourceJob.json/runItems", PLATFORM, () -> items.listRunItems(list(run))))
                .contains(REFUSED).contains("Work in this workspace");
            assertThat(fx.probe("GET sourceJob.json/runItems/detail", PLATFORM, () -> items.detail(run, "1", null, null, null))).contains(REFUSED);
            assertThat(fx.probe("GET sourceJob.json/runItems/data", PLATFORM, () -> items.data(run, "1", "ask", null))).contains(REFUSED);
            assertThat(fx.probe("GET sourceJob.json/runItems/file", PLATFORM, () -> items.file(run, "1", fileRefOf(run), true)))
                .startsWith("HTTP 403");
            assertThat(fx.probe("GET sourceJob.json/runItems/csv", PLATFORM, () -> items.csv(run, null, null, null, null, null))).startsWith("HTTP 403");
            assertThat(fx.probe("POST sourceJob.json/retryRunItems", PLATFORM, () -> items.retryRunItems(retry(run)))).contains(REFUSED);
        }
        assertThat(fx.probe("GET sourceJob.json/runItems/attention", PLATFORM, () -> items.attention(5))).contains(REFUSED);
        // MIG-421: none of v2's either.
        for (long run : new long[] {A_RUN, B_RUN}) {
            assertThat(fx.probe("POST sourceJob.json/runItems/selection", PLATFORM, () -> items.selectRunItems(selectAll(run)))).contains(REFUSED);
            assertThat(fx.probe("POST sourceJob.json/reviewRunItems", PLATFORM, () -> items.reviewRunItems(review(run, A_TEAM)))).contains(REFUSED);
            assertThat(fx.probe("GET sourceJob.json/runItems/zipLink(many)", PLATFORM,
                () -> items.zipLink(run, null, null, null, true, null, null, null))).contains(REFUSED);
        }
        for (long job : new long[] {A_JOB, B_JOB}) {
            assertThat(fx.probe("POST sourceJob.json/itemRuns", PLATFORM, () -> items.listItemRuns(across(job, "risk = High"))))
                .contains(REFUSED).contains("Work in this workspace");
            assertThat(fx.probe("GET sourceJob.json/itemRuns/compare", PLATFORM, () -> items.compare(job, "in/x.m4a", A_RUN, B_RUN, null))).contains(REFUSED);
        }
        assertThat(SENT).isEmpty();
        assertThat(fx.leaks).isEmpty();
    }

    private static String asClient(String endpoint, CoreProbeFixture.Endpoint call, String... scopes) {
        return fx.probe(endpoint, CoreCrossTenantProbeCustomerReadsPostgresTest.CLIENT_OF_A, () -> {
            TenantContext.setApiClient("cl_acme", new LinkedHashSet<>(Arrays.asList(scopes)));
            return call.call();
        });
    }

    @Test
    void anApiClientReadsItsOwnRunsItemsNeverAnotherWorkspacesAndNeedsRunsRead() {
        String b = String.valueOf(B_RUN);
        String a = String.valueOf(A_RUN);
        assertThat(asClient("GET customer/runs/{runId}/items", () -> customer.items(b, null, null, null, null), "runs:read"))
            .contains("\"status\":404");
        assertThat(asClient("GET customer/runs/{runId}/items/{itemKey}", () -> customer.item(b, "1"), "runs:read"))
            .contains("\"status\":404");
        assertThat(asClient("GET customer/runs/{runId}/items(A's own)", () -> customer.items(a, null, null, "failed", null), "runs:read"))
            .contains("acme run message.m4a").contains("\"failedStep\":\"ask\"");
        assertThat(asClient("GET customer/runs/{runId}/items/{itemKey}(A's own)", () -> customer.item(a, "1"), "runs:read"))
            .contains("\"steps\"").contains("insight.json").doesNotContain("storage").doesNotContain("datasets/");
        assertThat(asClient("GET customer/runs/{runId}/items(no scope)", () -> customer.items(a, null, null, null, null), "files:read"))
            .contains("\"status\":403");
        assertThat(fx.leaks).isEmpty();
    }
}
