package process.tenancy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.api.CustomerEventsRestApi;
import process.api.CustomerPipelinesRestApi;
import process.api.EventRouteRestApi;
import process.customer.ApiReceipts;
import process.customer.CustomerEvents;
import process.customer.CustomerPipelines;
import process.customer.EventRouteRequest;
import process.customer.EventRouteStore;
import process.customer.EventRoutes;
import process.customer.Idempotency;
import process.customer.InputContracts;
import process.customer.PipelineCatalogue;
import process.customer.RunFiles;
import process.customer.RunIntake;
import process.forms.FormWorkflows;
import process.model.pojo.JobQueue;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.TaskReferenceRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.pipeline.PipelineDefinitionStore;
import process.security.TenantContext;
import process.storage.remote.StorageServiceClient;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.tenancy.CoreProbeFixture.A;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_A;
import static process.tenancy.CoreProbeFixture.A_BUCKET;
import static process.tenancy.CoreProbeFixture.A_JOB;
import static process.tenancy.CoreProbeFixture.A_PIPELINE;
import static process.tenancy.CoreProbeFixture.A_RUN;
import static process.tenancy.CoreProbeFixture.B;
import static process.tenancy.CoreProbeFixture.B_JOB;
import static process.tenancy.CoreProbeFixture.Caller;
import static process.tenancy.CoreProbeFixture.REFUSED;
import static process.tenancy.CoreProbeFixture.SUCCEEDED;

/**
 * MIG-332's cross-tenant probe for the customer API and the event routes: an API client of workspace A aims every /v1
 * endpoint Core serves at B's pipeline, B's files and B's event types, and A's administrator aims every event-route
 * endpoint at B's route. A probe passes when each is answered as a pipeline, file or route that does not exist, nothing of
 * B's comes back or changes, and no run of B's is queued -- while A's own start succeeds once, and a replay of it queues
 * nothing more. Against a real etl_job under row-level security (CoreProbeFixture); opt-in like every ScratchPostgres test.
 */
class CoreCrossTenantProbeCustomerApiPostgresTest {

    static final long B_ROUTE_ID = 9951L;
    static final String B_FILE = "01JBRAVOFILE00000000000000";
    static final String A_FILE = "01JACMEFILE000000000000000";
    static final Caller CLIENT_OF_A = new Caller(A, "API_CLIENT", null, "client:cl_acme");

    private static CoreProbeFixture fx;
    private static CustomerPipelinesRestApi pipelines;
    private static CustomerEventsRestApi events;
    private static EventRouteRestApi routes;
    private static final StorageServiceClient STORAGE = mock(StorageServiceClient.class);
    private static final FormWorkflows WORKFLOWS = mock(FormWorkflows.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_customer");
        JdbcTemplate sql = fx.db.jdbc();
        sql.update("INSERT INTO event_route (route_id, tenant_id, event_type, target_kind, job_id, status) VALUES (?, ?, "
            + "'bravo.secret.event', 'PIPELINE', ?, 'Active')", B_ROUTE_ID, B, B_JOB);
        // A's pipeline as a step pipeline (it takes files); B's stays as the fixture made it.
        sql.update("INSERT INTO pipeline_definition (tenant_id, pipeline_key, version, definition) VALUES (?, ?, 1, ?::json)", A, A_PIPELINE,
            "{\"version\":1,\"source\":{\"type\":\"none\"},\"steps\":[{\"key\":\"read\",\"task\":\"sample\","
                + "\"config\":{\"rows\":[{\"a\":1}]}}]}");
        JdbcTemplate app = fx.db.appJdbc();
        TransactionServiceImpl jobs = new TransactionServiceImpl(fx.jpa.repository(SourceJobRepository.class),
            fx.jpa.repository(SchedulerRepository.class), fx.jpa.repository(JobQueueRepository.class),
            fx.jpa.repository(TaskReferenceRepository.class), fx.jpa.repository(JobAuditLogRepository.class),
            fx.jpa.repository(SourceTaskRepository.class), fx.openSearch);
        InputContracts contracts = mock(InputContracts.class);
        PipelineCatalogue catalogue = new PipelineCatalogue(app, new PipelineDefinitionStore(app));
        RunIntake intake = new RunIntake(jobs, fx.engine, fx.formBuckets, STORAGE, app, fx.jpa.transactionManager());
        Idempotency idempotency = new Idempotency(new ApiReceipts(app));
        pipelines = new CustomerPipelinesRestApi(new CustomerPipelines(catalogue, contracts, new RunFiles(STORAGE), intake, idempotency));
        EventRouteStore routeStore = new EventRouteStore(app);
        events = new CustomerEventsRestApi(new CustomerEvents(routeStore, catalogue, contracts, intake, WORKFLOWS, idempotency));
        routes = new EventRouteRestApi(new EventRoutes(routeStore, app, WORKFLOWS, contracts));

        ObjectNode inbox = JSON.createObjectNode();
        inbox.put("configured", true);
        inbox.put("alias", A_BUCKET);
        when(STORAGE.inboxOf(A)).thenReturn(inbox);
        // storage-service answers a workspace's own files only: asked as A, B's id is not there.
        when(STORAGE.directoryGet(eq("/files"), any())).thenAnswer(asked -> {
            Map<String, String> query = asked.getArgument(1);
            ObjectNode answer = JSON.createObjectNode();
            if (String.valueOf(A).equals(query.get("tenantId")) && query.get("ids").contains(A_FILE)) {
                answer.putArray("files").addObject().put("id", A_FILE).put("name", "acme.csv").put("bucket", A_BUCKET)
                    .put("key", "intake/api/acme.csv").put("bytes", 10);
            } else {
                answer.putArray("files");
            }
            return answer;
        });
        JobQueue run = new JobQueue();
        run.setJobQueueId(A_RUN);
        when(fx.engine.addApiJobInQueue(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(run);
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

    /** The call as an API client of A holding these scopes (JwtAuthenticationFilter sets the client after the caller). */
    private static String asClient(String endpoint, CoreProbeFixture.Endpoint call, String... scopes) {
        return fx.probe(endpoint, CLIENT_OF_A, () -> {
            TenantContext.setApiClient("cl_acme", new LinkedHashSet<>(Arrays.asList(scopes)));
            return call.call();
        });
    }

    private static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void anApiClientOfACannotReadOrStartAnythingOfB() throws Exception {
        String before = fx.foreignRows();

        assertThat(asClient("GET customer/pipelines", () -> pipelines.list(200, null), "pipelines:read"))
            .contains(String.valueOf(A_JOB)).doesNotContain("\"" + B_JOB + "\"");
        assertThat(asClient("GET customer/pipelines/{pipelineId}", () -> pipelines.get(String.valueOf(B_JOB)), "pipelines:read"))
            .contains("\"status\":404");
        assertThat(asClient("POST customer/pipelines/{pipelineId}/runs", () -> pipelines.startRun(String.valueOf(B_JOB),
            "probe-key-b-0001", body("{\"record\":{\"a\":1}}")), "runs:write")).contains("\"status\":404");
        assertThat(asClient("POST customer/pipelines/{pipelineId}/runs(B's file)", () -> pipelines.startRun(String.valueOf(A_JOB),
            "probe-key-a-file", body("{\"files\":[\"" + B_FILE + "\"]}")), "runs:write"))
            .contains("\"status\":422").contains("\"path\":\"files[0]\"").contains("no such file");
        assertThat(asClient("POST customer/events", () -> events.send("probe-key-event-b", body("{\"type\":\"bravo.secret.event\","
            + "\"data\":{}}")), "events:write")).contains("\"status\":422").contains("no event route takes this type");

        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).as("nothing of B's changed").isEqualTo(before);
        verify(fx.engine, never()).addApiJobInQueue(argThat(job -> job != null && Long.valueOf(B_JOB).equals(job.getJobId())), anyString(),
            anyString(), anyString(), anyString());
        verify(fx.formBuckets, never()).upload(eq(B), anyString(), anyString(), any(), anyString());
    }

    @Test
    void aStartOfAsOwnPipelineQueuesOnceAndAReplayQueuesNothing() throws Exception {
        String first = asClient("POST customer/pipelines/{pipelineId}/runs(A's own)", () -> pipelines.startRun(String.valueOf(A_JOB),
            "probe-key-a-0001", body("{\"record\":{\"order\":7},\"files\":[\"" + A_FILE + "\"],\"reference\":\"po-7\"}")), "runs:write");
        String again = asClient("POST customer/pipelines/{pipelineId}/runs(replayed)", () -> pipelines.startRun(String.valueOf(A_JOB),
            "probe-key-a-0001", body("{\"record\":{\"order\":7},\"files\":[\"" + A_FILE + "\"],\"reference\":\"po-7\"}")), "runs:write");
        String other = asClient("POST customer/pipelines/{pipelineId}/runs(same key, other body)", () -> pipelines.startRun(
            String.valueOf(A_JOB), "probe-key-a-0001", body("{\"record\":{\"order\":8}}")), "runs:write");

        assertThat(first).contains("\"id\":\"" + A_RUN + "\"").contains("\"pipelineId\":\"" + A_JOB + "\"").contains("\"reference\":\"po-7\"");
        assertThat(again).isEqualTo(first);
        assertThat(other).contains("\"status\":409").contains("idempotency-conflict");
        verify(fx.engine, times(1)).addApiJobInQueue(any(), eq(A_BUCKET), anyString(), eq("cl_acme"), anyString());
        verify(fx.formBuckets, times(1)).upload(eq(A), eq(A_BUCKET), anyString(), any(), eq("application/json"));
        assertThat(fx.db.jdbc().queryForObject("SELECT tenant_id || ' ' || reference || ' ' || file_ids FROM api_intake WHERE job_queue_id = ?",
            String.class, A_RUN)).isEqualTo(A + " po-7 " + A_FILE);
        assertThat(fx.leaks).isEmpty();
    }

    @Test
    void theScopeIsCheckedForEveryCall() throws Exception {
        assertThat(asClient("GET customer/pipelines(no scope)", () -> pipelines.list(null, null), "runs:write")).contains("\"status\":403");
        assertThat(asClient("POST customer/pipelines/{pipelineId}/runs(no scope)", () -> pipelines.startRun(String.valueOf(A_JOB),
            "probe-key-scope", body("{}")), "pipelines:read")).contains("\"status\":403");
        assertThat(asClient("POST customer/events(no scope)", () -> events.send("probe-key-scope", body("{}")), "runs:write"))
            .contains("\"status\":403");
        verify(fx.engine, never()).addApiJobInQueue(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anAdministratorOfANeitherSeesNorChangesBsEventRoutes() throws Exception {
        String before = fx.foreignRows();
        EventRouteRequest theirs = new EventRouteRequest();
        theirs.setRouteId(B_ROUTE_ID);
        theirs.setEventType("taken.over");
        theirs.setTargetKind("PIPELINE");
        theirs.setJobId(A_JOB);
        EventRouteRequest aimedAtBsJob = new EventRouteRequest();
        aimedAtBsJob.setEventType("acme.order");
        aimedAtBsJob.setTargetKind("PIPELINE");
        aimedAtBsJob.setJobId(B_JOB);

        assertThat(fx.probe("GET eventRoute.json/list", ADMIN_OF_A, routes::list)).contains(SUCCEEDED).doesNotContain("bravo.secret.event");
        assertThat(fx.probe("GET eventRoute.json/targets", ADMIN_OF_A, routes::targets)).contains(SUCCEEDED)
            .doesNotContain("\"jobId\":" + B_JOB);
        assertThat(fx.probe("POST eventRoute.json/save", ADMIN_OF_A, () -> routes.save(theirs))).contains(REFUSED);
        assertThat(fx.probe("POST eventRoute.json/save(B's pipeline)", ADMIN_OF_A, () -> routes.save(aimedAtBsJob))).contains(REFUSED);
        assertThat(fx.probe("POST eventRoute.json/delete", ADMIN_OF_A, () -> routes.delete(theirs))).contains(REFUSED);

        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).as("B's route is as it was").isEqualTo(before);
        assertThat(fx.db.jdbc().queryForObject("SELECT event_type FROM event_route WHERE route_id = ?", String.class, B_ROUTE_ID))
            .isEqualTo("bravo.secret.event");
    }
}
