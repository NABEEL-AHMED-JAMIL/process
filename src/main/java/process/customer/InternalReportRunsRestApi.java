package process.customer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import process.model.enums.JobStatus;
import process.pipeline.DatasetStore;
import process.pipeline.FileAccessLog;
import process.pipeline.RunResults;
import process.pipeline.SensitiveFiles;
import process.pipeline.data.FileFormats;
import process.pipeline.data.RowSource;
import process.security.TenantContext;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Report Studio's scheduled reports: media-service, with no person signed in, asks Core about a workspace's pipeline and
 * reads a finished run's rows to print them -- service to service, with the shared X-Internal-Token (401 without it), always
 * naming the workspace (tenantId &gt;= 1) and read as that workspace (row security and the tenant check): another workspace's
 * pipeline or run is simply not there (404). The gateway never routes /internal from outside.
 *
 * <ul>
 *   <li>GET /internal/reports/pipelineRun?tenantId=&amp;pipelineId= -- the pipeline (Core's job id) and its runs:
 *   {pipelineId, pipelineName, sensitive, running: {jobQueueId, status, startedAt} | null (queued or running now),
 *   latest: {jobQueueId, status, startedAt, endedAt} | null (the newest finished run, whatever its status), lastSucceeded:
 *   {jobQueueId, endedAt} | null}; times ISO-8601 UTC instants, statuses the customer API's words (queued, running,
 *   completed, failed, skipped, interrupted). 404 for a pipeline that is not the workspace's, or deleted.</li>
 *   <li>GET /internal/reports/runRows?tenantId=&amp;jobQueueId=&amp;stepKey=&amp;maxBytes= -- a completed run's rows as one JSON
 *   array of objects (the bytes the console's runDataset json download writes), streamed: its final output (the one the
 *   customer API's run result reads), or the named step's. Unmasked, as the console's Generate prints them; a sensitive
 *   pipeline's run says so (X-Run-Sensitive) and media marks its report sensitive. X-Rows and Content-Length are known up
 *   front: the rows are measured before the first byte is sent, so a dataset over maxBytes (default 50 MB) is a 413, never
 *   a cut-off answer. 404 an unknown run, another workspace's, or no such output; 409 a run that did not complete; 410 an
 *   output past its retention; every answer in file_access_log, as the console's downloads are.</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/reports")
public class InternalReportRunsRestApi {

    private static final Logger logger = LoggerFactory.getLogger(InternalReportRunsRestApi.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the access log names the reader. */
    static final String SERVICE = "media-service";
    static final long DEFAULT_MAX_BYTES = 50L * 1024 * 1024;
    static final long MOST_MAX_BYTES = 200L * 1024 * 1024;
    /** A step's key, as DefinitionValidator holds it. */
    private static final Pattern STEP_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    static final List<String> IN_FLIGHT = Arrays.asList(JobStatus.Queue.name(), JobStatus.Start.name(), JobStatus.Running.name());
    static final List<String> FINISHED = Arrays.asList(JobStatus.Completed.name(), JobStatus.Failed.name(), JobStatus.Interrupt.name(),
        JobStatus.Skip.name(), JobStatus.Missed.name());

    private final PipelineCatalogue catalogue;
    private final CustomerRunStore store;
    private final RunResults results;
    private final DatasetStore datasets;
    private final SensitiveFiles sensitiveFiles;
    private final FileAccessLog accessLog;
    private final byte[] token;

    public InternalReportRunsRestApi(PipelineCatalogue catalogue, CustomerRunStore store, RunResults results, DatasetStore datasets,
        SensitiveFiles sensitiveFiles, FileAccessLog accessLog, @Value("${internal.service-token:}") String token) {
        this.catalogue = catalogue;
        this.store = store;
        this.results = results;
        this.datasets = datasets;
        this.sensitiveFiles = sensitiveFiles;
        this.accessLog = accessLog;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/pipelineRun", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> pipelineRun(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestParam(required = false) Long tenantId, @RequestParam(required = false) Long pipelineId) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        if (tenantId == null || tenantId < 1 || pipelineId == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Name the workspace (tenantId) and the pipeline (pipelineId)."));
        }
        TenantContext.set(tenantId, "TENANT_ADMIN", null, SERVICE);
        try {
            Optional<PipelineCatalogue.Entry> pipeline = this.catalogue.find(tenantId, String.valueOf(pipelineId));
            if (!pipeline.isPresent() || pipeline.get().tenantId != tenantId) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "No pipeline " + pipelineId + " in this workspace."));
            }
            Optional<CustomerRunStore.Row> running = this.newest(tenantId, pipelineId, IN_FLIGHT);
            Optional<CustomerRunStore.Row> latest = this.newest(tenantId, pipelineId, FINISHED);
            Optional<CustomerRunStore.Row> succeeded = latest.isPresent() && JobStatus.Completed.name().equals(latest.get().jobStatus)
                ? latest : this.newest(tenantId, pipelineId, Collections.singletonList(JobStatus.Completed.name()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("pipelineId", pipelineId);
            body.put("pipelineName", pipeline.get().name);
            body.put("sensitive", SensitiveFiles.sensitive(pipeline.get().definition));
            body.put("running", running.map(r -> {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("jobQueueId", r.runId);
                one.put("status", RunIntake.statusOf(r.jobStatus));
                one.put("startedAt", iso(r.startedAt));
                return one;
            }).orElse(null));
            body.put("latest", latest.map(r -> {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("jobQueueId", r.runId);
                one.put("status", RunIntake.statusOf(r.jobStatus));
                one.put("startedAt", iso(r.startedAt));
                one.put("endedAt", iso(r.endedAt));
                return one;
            }).orElse(null));
            body.put("lastSucceeded", succeeded.map(r -> {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("jobQueueId", r.runId);
                one.put("endedAt", iso(r.endedAt));
                return one;
            }).orElse(null));
            return ResponseEntity.ok(body);
        } catch (RuntimeException failed) {
            logger.warn("Pipeline {} of workspace {}: its runs could not be read for a report schedule: {}", pipelineId, tenantId,
                failed.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "The pipeline's runs cannot be read right now."));
        } finally {
            TenantContext.clear();
        }
    }

    /** The newest run of the pipeline in one of these statuses (by run id, as the customer API pages them). */
    private Optional<CustomerRunStore.Row> newest(long tenantId, long jobId, List<String> statuses) {
        return this.store.page(tenantId, null, 1, jobId, statuses, null, null).stream().findFirst();
    }

    private static String iso(Instant at) {
        return at == null ? null : at.toString();
    }

    @GetMapping("/runRows")
    public ResponseEntity<StreamingResponseBody> runRows(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestParam(required = false) Long tenantId, @RequestParam(required = false) Long jobQueueId,
        @RequestParam(required = false) String stepKey, @RequestParam(required = false) Long maxBytes) {
        // Declared as ResponseEntity<StreamingResponseBody>, so Spring streams the body; every refusal is written whole with
        // its length (StepTimelineRestApi.runDataset: a streamed 4xx with no length was left unterminated on Tomcat 11).
        if (!this.admits(presented)) {
            return refused(HttpStatus.UNAUTHORIZED, "The internal token is required.");
        }
        if (tenantId == null || tenantId < 1 || jobQueueId == null) {
            return refused(HttpStatus.BAD_REQUEST, "Name the workspace (tenantId) and the run (jobQueueId).");
        }
        String step = stepKey == null || stepKey.trim().isEmpty() ? null : stepKey.trim();
        if (step != null && !STEP_KEY.matcher(step).matches()) {
            return refused(HttpStatus.BAD_REQUEST, "stepKey is a step's key: lower-case letters, digits and _, starting with a letter.");
        }
        if (maxBytes != null && (maxBytes < 1 || maxBytes > MOST_MAX_BYTES)) {
            return refused(HttpStatus.BAD_REQUEST, String.format("maxBytes is 1 to %d.", MOST_MAX_BYTES));
        }
        long budget = maxBytes == null ? DEFAULT_MAX_BYTES : maxBytes;
        TenantContext.set(tenantId, "TENANT_ADMIN", null, SERVICE);
        Long ownRun = null;
        String fileName = null;
        try {
            Optional<CustomerRunStore.Row> run = this.store.find(tenantId, jobQueueId).filter(r -> r.tenantId == tenantId);
            if (!run.isPresent()) {
                return this.logged(tenantId, null, null, HttpStatus.NOT_FOUND, "No run " + jobQueueId + " in this workspace.");
            }
            ownRun = run.get().runId;
            fileName = String.format("run-%d-%s.json", ownRun, step == null ? RunResults.FINAL : step);
            if (!JobStatus.Completed.name().equals(run.get().jobStatus)) {
                return this.logged(tenantId, ownRun, fileName, HttpStatus.CONFLICT,
                    "The run has not completed (" + RunIntake.statusOf(run.get().jobStatus) + "), so it has no rows to report.");
            }
            RunResults.Result about = new RunResults.Result();
            Optional<RunResults.Output> output = this.results.output(ownRun, step, about);
            if (!output.isPresent()) {
                return this.logged(tenantId, ownRun, fileName, HttpStatus.NOT_FOUND, about.problem == null ? "The run kept no such output." : about.problem);
            }
            fileName = String.format("run-%d-%s.json", ownRun, output.get().stepKey);
            if (output.get().kept.expiredAt(Instant.now())) {
                return this.logged(tenantId, ownRun, fileName, HttpStatus.GONE, "The run's output is past its retention.");
            }
            String key = output.get().kept.storageKey;
            Measured measured;
            try {
                measured = this.measure(key, budget);
            } catch (Exception unreadable) {
                logger.warn("Run {} of workspace {}: its output could not be read for a report schedule: {}", ownRun, tenantId,
                    unreadable.getMessage());
                return this.logged(tenantId, ownRun, fileName, HttpStatus.GONE,
                    "The run's output is no longer available; run the pipeline again to make it anew.");
            }
            if (measured == null) {
                return this.logged(tenantId, ownRun, fileName, HttpStatus.CONTENT_TOO_LARGE,
                    String.format("The run's rows are more than %d bytes of JSON (maxBytes).", budget));
            }
            boolean sensitive = this.sensitiveFiles.sensitiveRun(ownRun);
            String pipelineName = this.catalogue.find(tenantId, String.valueOf(run.get().jobId)).map(p -> p.name).orElse("");
            this.accessLog.serviceRunRows(tenantId, SERVICE, ownRun, fileName, true, 200, null);
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(measured.bytes)
                .header("X-Rows", String.valueOf(measured.rows))
                .header("X-Run-Sensitive", String.valueOf(sensitive))
                .header("X-Pipeline-Name", URLEncoder.encode(pipelineName == null ? "" : pipelineName, StandardCharsets.UTF_8).replace("+", "%20"))
                .body(out -> this.write(key, out));
        } catch (RuntimeException failed) {
            logger.warn("Run {} of workspace {}: its rows could not be read for a report schedule: {}", jobQueueId, tenantId, failed.getMessage());
            return this.logged(tenantId, ownRun, fileName, HttpStatus.SERVICE_UNAVAILABLE, "The run's rows cannot be read right now.");
        } finally {
            TenantContext.clear();
        }
    }

    /** The rows' JSON, written to nowhere and counted: its bytes and rows, or null once past {@code budget}. */
    private Measured measure(String key, long budget) throws Exception {
        Counting counting = new Counting(budget);
        try (RowSource rows = this.datasets.open(key, RowSource.DEFAULT_BATCH)) {
            long written = FileFormats.writeTo(rows, "json", counting);
            return new Measured(counting.bytes, written);
        } catch (IOException stopped) {
            if (counting.over) {
                return null;
            }
            throw stopped;
        }
    }

    /** The same rows, the same bytes, to the answer (the dataset is never changed once written). */
    private void write(String key, OutputStream out) throws IOException {
        try (RowSource rows = this.datasets.open(key, RowSource.DEFAULT_BATCH)) {
            FileFormats.writeTo(rows, "json", out);
        } catch (IOException | RuntimeException failed) {
            throw failed;
        } catch (Exception failed) {
            throw new IOException(failed.getMessage(), failed);
        }
    }

    private ResponseEntity<StreamingResponseBody> logged(long tenantId, Long jobQueueId, String fileName, HttpStatus status, String reason) {
        this.accessLog.serviceRunRows(tenantId, SERVICE, jobQueueId, fileName, false, status.value(), reason);
        return refused(status, reason);
    }

    private static ResponseEntity<StreamingResponseBody> refused(HttpStatus status, String message) {
        byte[] body;
        try {
            body = JSON.writeValueAsBytes(Map.of("message", message));
        } catch (JsonProcessingException impossible) {
            body = "{}".getBytes(StandardCharsets.UTF_8);
        }
        byte[] written = body;
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).contentLength(written.length).body(out -> out.write(written));
    }

    private static final class Measured {
        final long bytes;
        final long rows;

        Measured(long bytes, long rows) {
            this.bytes = bytes;
            this.rows = rows;
        }
    }

    /** Counts the bytes and keeps none; past the budget it stops the writer (an IOException, {@code over} set). */
    private static final class Counting extends OutputStream {
        private final long budget;
        long bytes;
        boolean over;

        Counting(long budget) {
            this.budget = budget;
        }

        @Override
        public void write(int b) throws IOException {
            this.add(1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            this.add(len);
        }

        private void add(long n) throws IOException {
            this.bytes += n;
            if (this.bytes > this.budget) {
                this.over = true;
                throw new IOException("Past the budget of " + this.budget + " bytes.");
            }
        }
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            logger.warn("Refused a report schedule's request without the internal token.");
        }
        return ok;
    }
}
