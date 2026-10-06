package process.tenancy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.api.CustomerFileLinkRestApi;
import process.api.CustomerFilesRestApi;
import process.api.CustomerRunsRestApi;
import process.customer.ApiReceipts;
import process.customer.CustomerFileReads;
import process.customer.CustomerReviews;
import process.customer.CustomerRunStore;
import process.customer.CustomerRuns;
import process.customer.FileLinks;
import process.customer.Idempotency;
import process.customer.RunFiles;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.TaskReferenceRepository;
import process.model.service.SourceJobService;
import process.model.service.impl.TransactionServiceImpl;
import process.pipeline.DatasetStore;
import process.pipeline.FileAccessLog;
import process.pipeline.JdbcStepStore;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.review.JdbcRunReviewStore;
import process.pipeline.review.RunReviewService;
import process.pipeline.review.RunReviews;
import process.security.TenantContext;
import process.storage.remote.StorageServiceClient;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static process.tenancy.CoreProbeFixture.A;
import static process.tenancy.CoreProbeFixture.A_JOB;
import static process.tenancy.CoreProbeFixture.A_PIPELINE;
import static process.tenancy.CoreProbeFixture.A_RUN;
import static process.tenancy.CoreProbeFixture.B_JOB;
import static process.tenancy.CoreProbeFixture.B_PIPELINE;
import static process.tenancy.CoreProbeFixture.B_RUN;
import static process.tenancy.CoreProbeFixture.Caller;

/**
 * MIG-334's cross-tenant probe for the customer API's read side: an API client of workspace A aims every read Core
 * serves -- runs, a run, its steps, its manifest, its review, a file's facts, its signed link and the link's content --
 * and the customer's review decision at workspace B's run and B's made file, and a link signed for A at B's file.
 * Every one is answered as a run or file that does not exist (404, never 403), nothing of B's comes back, and no review
 * of B's is recorded -- while A's own run reads, downloads through its link and is approved. Both pipelines ask for the
 * customer's review and both runs are completed. Against a real etl_job as process_app (CoreProbeFixture); opt-in like
 * every ScratchPostgres test.
 */
class CoreCrossTenantProbeCustomerReadsPostgresTest {

    static final Caller CLIENT_OF_A = new Caller(A, "API_CLIENT", null, "client:cl_acme");
    static final String A_FILE = "01JACMEMADE000000000000000";
    static final String B_FILE = "01JBRAVMADE000000000000000";
    private static final String REVIEW = "{\"version\":1,\"source\":{\"type\":\"task\"},\"steps\":[{\"key\":\"keep\",\"name\":\"Keep the "
        + "notes\",\"task\":\"save_file\",\"config\":{\"fileName\":\"notes.csv\"}}],\"settings\":{\"review\":{\"required\":[\"customer\"]}}}";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static CoreProbeFixture fx;
    private static CustomerRunsRestApi runs;
    private static CustomerFilesRestApi files;
    private static CustomerFileLinkRestApi content;
    private static FileLinks links;
    private static final StorageServiceClient STORAGE = mock(StorageServiceClient.class);

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_customer_reads");
        JdbcTemplate sql = fx.db.jdbc();
        for (long pipeline : new long[] {A_PIPELINE, B_PIPELINE}) {
            sql.update("INSERT INTO pipeline_definition (tenant_id, pipeline_key, version, definition, date_created) "
                + "SELECT tenant_id, pipeline_key, 1, ?::json, now() - interval '30 days' FROM pipeline WHERE pipeline_key = ?", REVIEW, pipeline);
        }
        sql.update("UPDATE job_queue SET job_status = 'Completed' WHERE job_queue_id IN (?, ?)", A_RUN, B_RUN);
        made(sql, A_RUN, A_FILE, "acme run message");
        made(sql, B_RUN, B_FILE, "bravo payload marker");

        JdbcTemplate app = fx.db.appJdbc();
        JobQueueRepository runRows = fx.jpa.repository(JobQueueRepository.class);
        SourceJobRepository jobRows = fx.jpa.repository(SourceJobRepository.class);
        TransactionServiceImpl transactions = new TransactionServiceImpl(jobRows, fx.jpa.repository(SchedulerRepository.class), runRows,
            fx.jpa.repository(TaskReferenceRepository.class), fx.jpa.repository(JobAuditLogRepository.class),
            fx.jpa.repository(SourceTaskRepository.class), fx.openSearch);
        JdbcRunReviewStore reviewRows = new JdbcRunReviewStore(app);
        RunReviews reviews = new RunReviews(new JdbcStepStore(app), new PipelineDefinitionStore(app), reviewRows);
        RunReviewService reviewService = new RunReviewService(runRows, jobRows, reviews, reviewRows, mock(SourceJobService.class), transactions);
        CustomerRunStore store = new CustomerRunStore(app);
        RunFiles runFiles = new RunFiles(STORAGE);
        CustomerRuns customerRuns = new CustomerRuns(store, runRows, jobRows, reviews, new JdbcStepStore(app), new PipelineDefinitionStore(app),
            runFiles);
        runs = new CustomerRunsRestApi(customerRuns, new CustomerReviews(customerRuns, reviews, reviewService,
            new Idempotency(new ApiReceipts(app))));
        links = new FileLinks("probe-service-token");
        CustomerFileReads reads = new CustomerFileReads(new JdbcStepStore(app), store, runFiles, fx.runDatasets, fx.formBuckets, links,
            new FileAccessLog(app));
        files = new CustomerFilesRestApi(reads);
        content = new CustomerFileLinkRestApi(reads);
        // storage-service's directory answers a workspace's own uploads only; neither workspace has one here.
        when(STORAGE.directoryGet(eq("/files"), any())).thenAnswer(asked -> {
            ObjectNode answer = JSON.createObjectNode();
            answer.putArray("files");
            return answer;
        });
    }

    /** A run's step that kept a file, with the file on disk and its manifest row named by a file id. */
    private static void made(JdbcTemplate sql, long run, String fileId, String marker) throws Exception {
        long step = sql.queryForObject("INSERT INTO step_execution (job_queue_id, step_index, task_code, step_key, status) "
            + "VALUES (?, 0, 'save_file', 'keep', 'Completed') RETURNING step_execution_id", Long.class, run);
        String key = DatasetStore.fileKeyOf(run, 1, "keep", "notes.csv");
        fx.runDatasets.writeFile(key, ("note\r\n" + marker + "\r\n").getBytes(StandardCharsets.UTF_8));
        long dataset = sql.queryForObject("INSERT INTO run_dataset (step_execution_id, name, storage_key, row_count, columns, expires_at) "
            + "VALUES (?, 'notes.csv', ?, 1, '[\"note\"]'::jsonb, now() + interval '1 day') RETURNING run_dataset_id", Long.class, step, key);
        sql.update("INSERT INTO run_output (step_execution_id, kind, name, format, row_count, byte_count, run_dataset_id, expires_at, file_id) "
            + "VALUES (?, 'file', 'notes.csv', 'csv', 1, 30, ?, now() + interval '1 day', ?)", step, dataset, fileId);
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

    private static String asClient(String endpoint, CoreProbeFixture.Endpoint call, String... scopes) {
        return fx.probe(endpoint, CLIENT_OF_A, () -> {
            TenantContext.setApiClient("cl_acme", new LinkedHashSet<>(Arrays.asList(scopes)));
            return call.call();
        });
    }

    private static final String[] READER = {"runs:read", "files:read", "reviews:write"};

    @Test
    void anApiClientOfAReadsNothingOfBsAndDecidesNothingOfBs() {
        String before = fx.foreignRows();
        String b = String.valueOf(B_RUN);

        assertThat(asClient("GET customer/runs", () -> runs.list(200, null, null, null, null, null), READER))
            .contains("\"id\":\"" + A_RUN + "\"").doesNotContain("\"id\":\"" + B_RUN + "\"");
        assertThat(asClient("GET customer/runs(B's pipeline)", () -> runs.list(200, null, String.valueOf(B_JOB), null, null, null), READER))
            .contains("\"data\":[]");
        assertThat(asClient("GET customer/runs/{runId}", () -> runs.get(b), READER)).contains("\"status\":404");
        assertThat(asClient("GET customer/runs/{runId}/steps", () -> runs.steps(b), READER)).contains("\"status\":404");
        assertThat(asClient("GET customer/runs/{runId}/outputs", () -> runs.outputs(b), READER)).contains("\"status\":404");
        assertThat(asClient("GET customer/runs/{runId}/review", () -> runs.review(b), READER)).contains("\"status\":404");
        assertThat(asClient("POST customer/runs/{runId}/review", () -> runs.decide(b, "probe-review-b-0001",
            "{\"decision\":\"rejected\",\"reason\":\"probe says no\"}".getBytes(StandardCharsets.UTF_8)), READER)).contains("\"status\":404");
        assertThat(asClient("GET customer/files/{fileId}/meta", () -> files.meta(B_FILE), READER)).contains("\"status\":404");
        assertThat(asClient("GET customer/files/{fileId}", () -> files.link(B_FILE), READER)).contains("\"status\":404");
        // A link A could sign is for A's workspace: naming B's file there finds nothing.
        String forA = links.issue(A, B_FILE, "cl_acme").token;
        assertThat(asClient("GET customer/files/{fileId}/content", () -> content.content(B_FILE, forA))).startsWith("HTTP 404");
        assertThat(asClient("GET customer/files/{fileId}/content(forged)", () -> content.content(B_FILE, "abc.def"))).startsWith("HTTP 404");

        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).as("nothing of B's changed").isEqualTo(before);
        assertThat(fx.db.jdbc().queryForObject("SELECT count(*) FROM run_review_decision WHERE job_queue_id = ?", Long.class, B_RUN)).isZero();
    }

    @Test
    void aClientOfAReadsDownloadsAndApprovesItsOwnRun() throws Exception {
        String a = String.valueOf(A_RUN);
        assertThat(asClient("GET customer/runs/{runId}(A's own)", () -> runs.get(a), READER))
            .contains("\"status\":\"completed\"").contains("\"review\":\"pending\"").contains("\"pipelineId\":\"" + A_JOB + "\"");
        assertThat(asClient("GET customer/runs/{runId}/steps(A's own)", () -> runs.steps(a), READER))
            .contains("\"key\":\"keep\"").contains("\"task\":\"save_file\"").contains("\"status\":\"completed\"");
        assertThat(asClient("GET customer/runs/{runId}/outputs(A's own)", () -> runs.outputs(a), READER))
            .contains("\"id\":\"" + A_FILE + "\"").contains("\"role\":\"result\"").doesNotContain("runs/" + A_RUN);
        String link = asClient("GET customer/files/{fileId}(A's own)", () -> files.link(A_FILE), READER);
        Matcher token = Pattern.compile("content\\?token=([A-Za-z0-9_.-]+)").matcher(link);
        assertThat(token.find()).as(link).isTrue();
        String signed = token.group(1);
        assertThat(asClient("GET customer/files/{fileId}/content(A's own)", () -> content.content(A_FILE, signed)))
            .startsWith("HTTP 200").contains("acme run message");
        assertThat(fx.db.jdbc().queryForObject("SELECT tenant_id || ' ' || client_id || ' ' || outcome FROM file_access_log WHERE file_id = ? "
            + "ORDER BY file_access_id DESC LIMIT 1", String.class, A_FILE)).isEqualTo(A + " cl_acme served");

        String approved = asClient("POST customer/runs/{runId}/review(A's own)", () -> runs.decide(a, "probe-review-a-0001",
            "{\"decision\":\"approved\",\"comment\":\"probe approves\"}".getBytes(StandardCharsets.UTF_8)), READER);
        assertThat(approved).contains("\"status\":\"approved\"").contains("\"party\":\"customer\"");
        String again = asClient("POST customer/runs/{runId}/review(replayed)", () -> runs.decide(a, "probe-review-a-0001",
            "{\"decision\":\"approved\",\"comment\":\"probe approves\"}".getBytes(StandardCharsets.UTF_8)), READER);
        assertThat(again).isEqualTo(approved);
        assertThat(asClient("POST customer/runs/{runId}/review(decided)", () -> runs.decide(a, "probe-review-a-0002",
            "{\"decision\":\"approved\"}".getBytes(StandardCharsets.UTF_8)), READER)).contains("\"status\":409");
        assertThat(fx.db.jdbc().queryForObject("SELECT count(*) FROM run_review_decision WHERE job_queue_id = ? AND party = 'CUSTOMER'",
            Long.class, A_RUN)).isEqualTo(1L);
        assertThat(fx.leaks).isEmpty();
    }

    @Test
    void theScopeIsCheckedForEveryCall() {
        String a = String.valueOf(A_RUN);
        assertThat(asClient("GET customer/runs(no scope)", () -> runs.list(null, null, null, null, null, null), "files:read"))
            .contains("\"status\":403");
        assertThat(asClient("GET customer/runs/{runId}/outputs(no scope)", () -> runs.outputs(a), "files:read")).contains("\"status\":403");
        assertThat(asClient("POST customer/runs/{runId}/review(no scope)", () -> runs.decide(a, "probe-review-scope",
            "{\"decision\":\"approved\"}".getBytes(StandardCharsets.UTF_8)), "runs:read")).contains("\"status\":403");
        assertThat(asClient("GET customer/files/{fileId}(no scope)", () -> files.link(A_FILE), "runs:read")).contains("\"status\":403");
        assertThat(asClient("GET customer/files/{fileId}/meta(no scope)", () -> files.meta(A_FILE), "runs:read")).contains("\"status\":403");
    }
}
