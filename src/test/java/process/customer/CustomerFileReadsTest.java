package process.customer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.pipeline.DatasetStore;
import process.pipeline.FileAccessLog;
import process.pipeline.RunOutput;
import process.pipeline.StepStore;
import process.pipeline.backing.BucketStore;
import process.security.TenantContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-334: a file by its id -- a run's made file or an upload -- needs files:read and the workspace's own file (404
 * otherwise, never 403); GET answers a 302 to a signed link on this API with {url, expiresAt}, never a bucket URL; the
 * link alone opens the bytes in its own workspace, an expired one is 410 and a forged one 404; every read through a
 * link is in the access log with the client.
 */
class CustomerFileReadsTest {

    static final long TENANT = 2946L;
    static final long OTHER = 2947L;
    static final long RUN = 9100L;
    static final String MADE = "01JMADE0000000000000000000";
    static final String BUCKETED = "01JBKT00000000000000000000";
    static final String UPLOAD = "01JGVN00000000000000000000";
    static final String THEIRS = "01JTHR00000000000000000000";

    private final StepStore steps = mock(StepStore.class);
    private final CustomerRunStore runs = mock(CustomerRunStore.class);
    private final RunFiles files = mock(RunFiles.class);
    // MIG-344: the store's streaming defaults (openFile, fileSize) run as written, over the stubbed readFile.
    private final DatasetStore datasets = mock(DatasetStore.class, Mockito.withSettings().defaultAnswer(
        invocation -> Arrays.asList("openFile", "fileSize").contains(invocation.getMethod().getName()) ? invocation.callRealMethod()
            : Mockito.RETURNS_DEFAULTS.answer(invocation)));
    private final BucketStore buckets = mock(BucketStore.class);
    private final FileAccessLog log = mock(FileAccessLog.class);
    private final FileLinks links = new FileLinks("svc-token", Clock.systemUTC());
    private final CustomerFileReads reads = new CustomerFileReads(this.steps, this.runs, this.files, this.datasets, this.buckets, this.links,
        this.log);

    @BeforeEach
    void asAClient() throws Exception {
        this.client("files:read");
        when(this.runs.find(TENANT, RUN)).thenReturn(Optional.of(CustomerRunsTest.row(RUN, "Completed")));
        when(this.steps.outputByFileId(MADE)).thenReturn(Optional.of(CustomerRunsTest.output(77L, "result.csv", "csv", MADE)));
        StepStore.OutputRow bucketed = CustomerRunsTest.output(79L, "export.json", "json", BUCKETED);
        bucketed.kind = RunOutput.BUCKET;
        bucketed.runDatasetId = null;
        bucketed.expiresAt = null;
        bucketed.bucketAlias = "northwind-exports";
        bucketed.objectKey = "out/export.json";
        when(this.steps.outputByFileId(BUCKETED)).thenReturn(Optional.of(bucketed));
        when(this.steps.outputByFileId(UPLOAD)).thenReturn(Optional.empty());
        when(this.steps.outputByFileId(THEIRS)).thenReturn(Optional.empty());
        StepStore.DatasetFile kept = new StepStore.DatasetFile();
        kept.storageKey = "runs/9100/1/save/files/result.csv";
        when(this.steps.datasetById(1077L)).thenReturn(Optional.of(kept));
        when(this.datasets.readFile(kept.storageKey)).thenReturn("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> upload = new LinkedHashMap<>();
        upload.put("id", UPLOAD);
        upload.put("name", "order.csv");
        upload.put("bucket", "northwind-inbox");
        upload.put("key", "intake/api/2026/10/06/order.csv");
        upload.put("contentType", "text/csv");
        upload.put("bytes", 25L);
        upload.put("role", "INPUT");
        upload.put("createdAt", "2026-10-06T13:59:00Z");
        when(this.files.of(eq(TENANT), eq(Collections.singletonList(UPLOAD)))).thenReturn(Collections.singletonMap(UPLOAD, upload));
        when(this.files.of(eq(TENANT), eq(Collections.singletonList(THEIRS)))).thenReturn(Collections.emptyMap());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void client(String... scopes) {
        TenantContext.set(TENANT, TenantContext.API_CLIENT, null, "client:cl_test");
        TenantContext.setApiClient("cl_test", new LinkedHashSet<>(Arrays.asList(scopes)));
    }

    @Test
    void readingAFileNeedsFilesRead() {
        this.client("runs:read");
        assertThat(this.reads.meta(MADE).status).isEqualTo(403);
        assertThat(this.reads.link(MADE).status).isEqualTo(403);
        verify(this.steps, never()).outputByFileId(anyString());
    }

    @Test
    void aFileIsTheWorkspacesMadeFileOrUploadAndNothingElse() {
        assertThat(this.reads.meta(MADE).body).containsEntry("id", MADE).containsEntry("role", "result").containsEntry("name", "result.csv");
        CustomerAnswer upload = this.reads.meta(UPLOAD);
        assertThat(upload.body).containsEntry("id", UPLOAD).containsEntry("role", "input").containsEntry("createdAt", "2026-10-06T13:59:00Z");
        assertThat(upload.body.toString()).doesNotContain("northwind-inbox").doesNotContain("intake/api");
        assertThat(this.reads.meta(THEIRS).status).isEqualTo(404);
        assertThat(this.reads.meta("../../etc/passwd").status).isEqualTo(404);
        // A made file whose run is not the workspace's (deleted, or row security let nothing through) is not there either.
        when(this.runs.find(TENANT, RUN)).thenReturn(Optional.empty());
        assertThat(this.reads.meta(MADE).status).isEqualTo(404);
    }

    @Test
    void getAnswersA302ToItsSignedLinkOnThisApiNeverABucket() {
        CustomerAnswer answer = this.reads.link(MADE);
        assertThat(answer.status).isEqualTo(302);
        assertThat(answer.location).startsWith("/v1/files/" + MADE + "/content?token=");
        assertThat(answer.body.get("url")).isEqualTo(answer.location);
        assertThat(String.valueOf(answer.body.get("expiresAt"))).endsWith("Z");
        assertThat(answer.location).doesNotContain("northwind").doesNotContain("runs/9100");
        assertThat(this.reads.link(THEIRS).status).isEqualTo(404);
    }

    @Test
    void aMadeFilePastItsExpiryIsGone() {
        StepStore.OutputRow old = CustomerRunsTest.output(77L, "result.csv", "csv", MADE);
        old.expiresAt = Instant.now().minusSeconds(1);
        when(this.steps.outputByFileId(MADE)).thenReturn(Optional.of(old));
        CustomerAnswer answer = this.reads.link(MADE);
        assertThat(answer.status).isEqualTo(410);
        assertThat(answer.body).containsEntry("type", "/problems/file-expired");
    }

    @Test
    void theLinkAloneOpensTheBytesInItsWorkspaceAndIsLogged() throws Exception {
        String token = this.links.issue(TENANT, MADE, "cl_test").token;
        TenantContext.clear();
        CustomerFileReads.Content content = this.reads.content(MADE, token);
        assertThat(content.status).isEqualTo(200);
        assertThat(content.name).isEqualTo("result.csv");
        assertThat(content.contentType).startsWith("text/csv");
        assertThat(new String(readAll(content), StandardCharsets.UTF_8)).isEqualTo("a,b\n1,2\n");
        verify(this.log).apiFile(TENANT, "cl_test", MADE, RUN, "result.csv", true, 200, null);

        when(this.buckets.stream(TENANT, "northwind-exports", "out/export.json"))
            .thenReturn(new BucketStore.Streamed(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), 2, "application/json"));
        CustomerFileReads.Content bucketed = this.reads.content(BUCKETED, this.links.issue(TENANT, BUCKETED, "cl_test").token);
        assertThat(bucketed.status).isEqualTo(200);
        assertThat(bucketed.size).isEqualTo(2);

        when(this.buckets.stream(TENANT, "northwind-inbox", "intake/api/2026/10/06/order.csv"))
            .thenReturn(new BucketStore.Streamed(new ByteArrayInputStream("order_id\nORD-1\n".getBytes(StandardCharsets.UTF_8)), 15, "text/csv"));
        assertThat(this.reads.content(UPLOAD, this.links.issue(TENANT, UPLOAD, "cl_test").token).status).isEqualTo(200);
    }

    @Test
    void aForgedExpiredOrOtherWorkspacesLinkOpensNothing() throws Exception {
        assertThat(this.reads.content(MADE, "forged.token").status).isEqualTo(404);
        assertThat(this.reads.content(MADE, null).status).isEqualTo(404);
        assertThat(this.reads.content(UPLOAD, this.links.issue(TENANT, MADE, "cl_test").token).status).as("another file's link").isEqualTo(404);

        FileLinks past = new FileLinks("svc-token", Clock.fixed(Instant.now().minusSeconds(600), ZoneOffset.UTC));
        CustomerFileReads.Content expired = this.reads.content(MADE, past.issue(TENANT, MADE, "cl_test").token);
        assertThat(expired.status).isEqualTo(410);
        assertThat(expired.problem).containsEntry("type", "/problems/link-expired");
        verify(this.log).apiFile(eq(TENANT), eq("cl_test"), eq(MADE), isNull(), isNull(), eq(false), eq(410), anyString());

        // A link signed for workspace B naming A's file: read in B's workspace, where A's file is not.
        when(this.runs.find(OTHER, RUN)).thenReturn(Optional.empty());
        when(this.files.of(eq(OTHER), any())).thenReturn(Collections.emptyMap());
        assertThat(this.reads.content(MADE, this.links.issue(OTHER, MADE, "cl_other").token).status).isEqualTo(404);
        assertThat(this.reads.content(UPLOAD, this.links.issue(OTHER, UPLOAD, "cl_other").token).status).isEqualTo(404);
        verify(this.buckets, never()).stream(eq(OTHER), anyString(), anyString());
    }

    private static byte[] readAll(CustomerFileReads.Content content) throws Exception {
        return content.body.readAllBytes();
    }
}
