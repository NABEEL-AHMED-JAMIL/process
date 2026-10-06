package process.customer;

import org.barco.platform.api.ApiTimes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import process.identity.IdentityPort;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.StepStore;
import process.pipeline.review.RunReviews;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-335: the embeddable run view. A client with runs:read makes a link to a run of its workspace (another's is 404); the
 * link reads that one run -- its status, steps, made files with downloads that end with the view, its review -- and nothing
 * else; an expired link is 410, a changed one 404, a revoked client's 410; the frame check answers the client's origins, or
 * 'none'.
 */
class CustomerRunViewsTest {

    static final long TENANT = CustomerRunsTest.TENANT;
    static final long RUN = CustomerRunsTest.RUN;
    static final long JOB = CustomerRunsTest.JOB;
    static final Instant NOW = Instant.now();

    private final CustomerRunStore store = mock(CustomerRunStore.class);
    private final JobQueueRepository runRepository = mock(JobQueueRepository.class);
    private final SourceJobRepository jobRepository = mock(SourceJobRepository.class);
    private final RunReviews reviews = mock(RunReviews.class);
    private final StepStore steps = mock(StepStore.class);
    private final IdentityPort identity = mock(IdentityPort.class);
    private final CustomerRuns runs = new CustomerRuns(this.store, this.runRepository, this.jobRepository, this.reviews, this.steps,
        mock(PipelineDefinitionStore.class), mock(RunFiles.class));
    private final ViewLinks links = new ViewLinks("svc-token", Clock.fixed(NOW, ZoneOffset.UTC));
    private final FileLinks files = new FileLinks("svc-token", Clock.fixed(NOW, ZoneOffset.UTC));
    private final CustomerRunViews views = new CustomerRunViews(this.runs, this.links, this.files, this.identity,
        TransactionOperations.withoutTransaction(), "http://console.example.com/");

    @BeforeEach
    void asAClient() {
        TenantContext.set(TENANT, TenantContext.API_CLIENT, null, "client:cl_portal");
        TenantContext.setApiClient("cl_portal", new LinkedHashSet<>(Collections.singletonList("runs:read")));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("reviewStatus", "PENDING");
        summary.put("required", Collections.singletonList("customer"));
        summary.put("decisions", Collections.emptyList());
        when(this.reviews.summary(any(), any())).thenReturn(summary);
        when(this.identity.embedClient(TENANT, "cl_portal")).thenReturn(new IdentityPort.EmbedClient(true, 200,
            Arrays.asList("https://portal.example.com", "http://localhost:4200")));
        when(this.store.find(eq(TENANT), anyLong())).thenReturn(Optional.empty());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void exists(long runId, String status) {
        when(this.store.find(TENANT, runId)).thenReturn(Optional.of(CustomerRunsTest.row(runId, status)));
        JobQueue run = new JobQueue();
        run.setJobQueueId(runId);
        run.setJobId(JOB);
        run.setTenantId(TENANT);
        run.setJobStatus(JobStatus.valueOf(status));
        when(this.runRepository.findById(runId)).thenReturn(Optional.of(run));
        SourceJob job = new SourceJob();
        job.setJobId(JOB);
        job.setTenantId(TENANT);
        job.setJobName("Invoice intake");
        when(this.jobRepository.findById(JOB)).thenReturn(Optional.of(job));
    }

    private String token(CustomerAnswer made) {
        String url = (String) made.body.get("url");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    @Test
    void aClientWithRunsReadMakesALinkToARunOfItsWorkspace() {
        this.exists(RUN, "Completed");
        CustomerAnswer made = this.views.create(String.valueOf(RUN), null);
        assertThat(made.status).isEqualTo(201);
        assertThat((String) made.body.get("url")).startsWith("http://console.example.com/embed/runs/").doesNotContain("//embed");
        assertThat(made.body.get("expiresAt")).isEqualTo(ApiTimes.utc(NOW.plusSeconds(900).truncatedTo(
            ChronoUnit.SECONDS)));
        ViewLinks.Checked checked = this.links.check(this.token(made));
        assertThat(checked.link.runId).isEqualTo(RUN);
        assertThat(checked.link.tenantId).isEqualTo(TENANT);
        assertThat(checked.link.clientId).isEqualTo("cl_portal");

        CustomerAnswer shorter = this.views.create(String.valueOf(RUN), "{\"expiresInSeconds\": 120}".getBytes(StandardCharsets.UTF_8));
        assertThat(this.links.check(this.token(shorter)).link.expiresAt).isEqualTo(NOW.plusSeconds(120).truncatedTo(
            ChronoUnit.SECONDS));
    }

    @Test
    void aLinkNeedsRunsReadARunOfTheWorkspaceAndAReadableRequest() {
        this.exists(RUN, "Completed");
        assertThat(this.views.create("9999", null).status).as("not the workspace's").isEqualTo(404);
        assertThat(this.views.create("not-a-number", null).status).isEqualTo(404);
        assertThat(this.views.create(String.valueOf(RUN), "{".getBytes(StandardCharsets.UTF_8)).status).isEqualTo(400);
        assertThat(this.views.create(String.valueOf(RUN), "[1]".getBytes(StandardCharsets.UTF_8)).status).isEqualTo(400);
        for (String bad : new String[] {"{\"expiresInSeconds\": 30}", "{\"expiresInSeconds\": 901}", "{\"expiresInSeconds\": \"600\"}",
            "{\"expiresInSeconds\": 90.5}"}) {
            CustomerAnswer refused = this.views.create(String.valueOf(RUN), bad.getBytes(StandardCharsets.UTF_8));
            assertThat(refused.status).as(bad).isEqualTo(422);
        }
        TenantContext.setApiClient("cl_portal", new LinkedHashSet<>(Collections.singletonList("files:read")));
        CustomerAnswer refused = this.views.create(String.valueOf(RUN), null);
        assertThat(refused.status).isEqualTo(403);
        assertThat(refused.body.get("type")).isEqualTo("/problems/insufficient-scope");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLinkReadsItsRunStepsMadeFilesWithDownloadsAndReview() {
        this.exists(RUN, "Completed");
        String token = this.token(this.views.create(String.valueOf(RUN), "{\"expiresInSeconds\": 120}".getBytes(StandardCharsets.UTF_8)));
        TenantContext.clear();   // the page has no caller
        when(this.steps.stepsOfRun(RUN)).thenReturn(Arrays.asList(CustomerRunsTest.step(1, 0, "read", "Completed"),
            CustomerRunsTest.step(1, 1, "save", "Completed")));
        when(this.steps.pinnedDefinition(RUN)).thenReturn(Optional.empty());
        StepStore.OutputRow kept = CustomerRunsTest.output(77L, "result.csv", "csv", "01JRESULT00000000000000000");
        StepStore.OutputRow gone = CustomerRunsTest.output(78L, "old.csv", "csv", "01JOLD0000000000000000000A");
        gone.expiresAt = Instant.now().minusSeconds(60);
        when(this.steps.outputsOfRun(RUN)).thenReturn(Arrays.asList(kept, gone));

        CustomerAnswer view = this.views.view(token);
        assertThat(view.status).isEqualTo(200);
        assertThat(view.body).containsEntry("id", String.valueOf(RUN)).containsEntry("status", "completed")
            .containsEntry("pipelineName", "Invoice intake").containsEntry("refreshSeconds", null);
        assertThat(((List<Map<String, Object>>) view.body.get("steps"))).extracting(s -> s.get("key")).containsExactly("read", "save");
        List<Map<String, Object>> made = (List<Map<String, Object>>) view.body.get("files");
        assertThat(made).extracting(f -> f.get("id")).containsExactly("01JRESULT00000000000000000", "01JOLD0000000000000000000A");
        Map<String, Object> download = (Map<String, Object>) made.get(0).get("download");
        String url = (String) download.get("url");
        assertThat(url).startsWith("/v1/files/01JRESULT00000000000000000/content?token=");
        FileLinks.Checked fileLink = this.files.check("01JRESULT00000000000000000", url.substring(url.indexOf("token=") + 6));
        assertThat(fileLink.verdict).isEqualTo(FileLinks.Verdict.GOOD);
        assertThat(fileLink.link.expiresAt).as("a download ends with the view").isEqualTo(this.links.check(token).link.expiresAt);
        assertThat(made.get(1).get("download")).as("an expired file has no download").isNull();
        assertThat((Map<String, Object>) view.body.get("review")).containsEntry("status", "pending");
        assertThat(view.body.toString()).doesNotContain("bucket").doesNotContain("storageKey");
        verify(this.store, never()).find(eq(CustomerRunsTest.TENANT + 1), anyLong());
    }

    @Test
    void aRunStillMovingIsReadAgainSoon() {
        this.exists(RUN, "Running");
        String token = this.token(this.views.create(String.valueOf(RUN), null));
        when(this.steps.stepsOfRun(RUN)).thenReturn(Collections.emptyList());
        when(this.steps.outputsOfRun(RUN)).thenReturn(Collections.emptyList());
        assertThat(this.views.view(token).body).containsEntry("status", "running").containsEntry("refreshSeconds", CustomerRunViews.REFRESH_SECONDS);
    }

    @Test
    void anExpiredChangedOrEndedLinkReadsNothing() {
        this.exists(RUN, "Completed");
        String token = this.token(this.views.create(String.valueOf(RUN), null));
        CustomerRunViews later = new CustomerRunViews(this.runs, new ViewLinks("svc-token", Clock.fixed(NOW.plusSeconds(901), ZoneOffset.UTC)),
            this.files, this.identity, TransactionOperations.withoutTransaction(), "http://console.example.com");
        CustomerAnswer expired = later.view(token);
        assertThat(expired.status).isEqualTo(410);
        assertThat(expired.body.get("type")).isEqualTo("/problems/link-expired");

        String changed = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        assertThat(this.views.view(changed).status).isEqualTo(404);
        assertThat(this.views.view(null).status).isEqualTo(404);
        String fileLink = this.files.issue(TENANT, "01JRESULT00000000000000000", "cl_portal").token;
        assertThat(this.views.view(fileLink).status).as("a file link opens no view").isEqualTo(404);

        when(this.identity.embedClient(TENANT, "cl_portal")).thenReturn(IdentityPort.EmbedClient.refused(410));
        CustomerAnswer ended = this.views.view(token);
        assertThat(ended.status).as("a revoked client's links end at once").isEqualTo(410);
        assertThat(ended.body.get("type")).isEqualTo("/problems/link-ended");
        when(this.identity.embedClient(TENANT, "cl_portal")).thenThrow(new IdentityPort.Unavailable("down", null));
        assertThat(this.views.view(token).status).as("fails closed").isEqualTo(503);
        verify(this.steps, never()).stepsOfRun(anyLong());
    }

    @Test
    void aLinkToARunDeletedSinceReadsNothing() {
        this.exists(RUN, "Completed");
        String token = this.token(this.views.create(String.valueOf(RUN), null));
        when(this.store.find(TENANT, RUN)).thenReturn(Optional.empty());
        assertThat(this.views.view(token).status).isEqualTo(404);
    }

    @Test
    void theFrameCheckAnswersTheClientsOriginsOrNone() {
        this.exists(RUN, "Completed");
        String token = this.token(this.views.create(String.valueOf(RUN), null));
        CustomerRunViews.Frame frame = this.views.frame(token);
        assertThat(frame.answer.status).isEqualTo(200);
        assertThat(frame.ancestors).isEqualTo("https://portal.example.com http://localhost:4200");
        assertThat(frame.answer.body).containsEntry("state", "open");

        CustomerRunViews later = new CustomerRunViews(this.runs, new ViewLinks("svc-token", Clock.fixed(NOW.plusSeconds(901), ZoneOffset.UTC)),
            this.files, this.identity, TransactionOperations.withoutTransaction(), "http://console.example.com");
        CustomerRunViews.Frame expired = later.frame(token);
        assertThat(expired.answer.body).as("an expired link may still show 'expired' where it was allowed").containsEntry("state", "expired");
        assertThat(expired.ancestors).isEqualTo("https://portal.example.com http://localhost:4200");

        CustomerRunViews.Frame tampered = this.views.frame(token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A"));
        assertThat(tampered.answer.status).isEqualTo(404);
        assertThat(tampered.ancestors).isEqualTo(CustomerRunViews.NOBODY);

        when(this.identity.embedClient(TENANT, "cl_portal")).thenReturn(new IdentityPort.EmbedClient(true, 200, Collections.emptyList()));
        assertThat(this.views.frame(token).ancestors).as("no list: framed nowhere").isEqualTo("'none'");
        when(this.identity.embedClient(TENANT, "cl_portal")).thenReturn(IdentityPort.EmbedClient.refused(410));
        assertThat(this.views.frame(token).ancestors).isEqualTo("'none'");
        when(this.identity.embedClient(anyLong(), anyString())).thenThrow(new IdentityPort.Unavailable("down", null));
        assertThat(this.views.frame(token).answer.status).isEqualTo(503);
        assertThat(this.views.frame(token).ancestors).isEqualTo("'none'");
    }
}
