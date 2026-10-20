package process.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import process.pipeline.FileAccessLog;
import process.pipeline.StepTimelineService;

/**
 * A run's steps (MIG-230), for the console's step timeline (MIG-251), under the prefix the gateway already sends to
 * Core and gates as the jobs page -- gated like the run reads beside it (fetchSourceJobQueueListWithJobId,
 * findSourceJobAuditLog), so no gateway route or page key is new:
 *
 * <ul>
 *   <li>GET sourceJob.json/stepExecutions?jobQueueId=&amp;attempt=: one attempt of a run (the latest by default) --
 *       each step's status, times, records, tries, on-error, error and datasets, and the run's AI steps; a legacy run
 *       as its one legacy step;</li>
 *   <li>GET sourceJob.json/stepLogs?stepExecutionId=: one step's log lines;</li>
 *   <li>GET sourceJob.json/runDataset?runDatasetId=&amp;format=csv|json|jsonl: a run dataset as a file (Wave 4);</li>
 *   <li>GET sourceJob.json/runOutputs?jobQueueId=&amp;attempt=: the run's result manifest (Wave 4).</li>
 * </ul>
 *
 * A missing id is Spring's 400; a run or step that is not the caller's is the envelope's "not found".
 */
@RestController
@PreAuthorize("hasRole('TENANT_USER')")
public class StepTimelineRestApi {

    private final StepTimelineService service;

    /** MIG-243: every run dataset download, served or refused. */
    private final FileAccessLog accessLog;

    private static final ObjectMapper REFUSALS = new ObjectMapper();

    public StepTimelineRestApi(StepTimelineService service, FileAccessLog accessLog) {
        this.service = service;
        this.accessLog = accessLog;
    }

    @RequestMapping(value = "/sourceJob.json/stepExecutions", method = RequestMethod.GET)
    public ResponseEntity<?> stepExecutions(@RequestParam Long jobQueueId, @RequestParam(required = false) Integer attempt) {
        return new ResponseEntity<>(this.service.timeline(jobQueueId, attempt), HttpStatus.OK);
    }

    /**
     * Wave 4: a run dataset as a file -- csv (the default), json or jsonl. Streamed with a Content-Disposition; refused
     * with the envelope: 400 for a format it does not write, 404 for a dataset that is not the caller's (another
     * workspace's included), 410 once it has expired. Every answer is in file_access_log (MIG-243).
     */
    @RequestMapping(value = "/sourceJob.json/runDataset", method = RequestMethod.GET)
    public ResponseEntity<StreamingResponseBody> runDataset(@RequestParam Long runDatasetId,
        @RequestParam(required = false) String format) throws JsonProcessingException {
        // Declared as ResponseEntity<StreamingResponseBody>: only then does Spring stream the body. A ResponseEntity<?>
        // hands it to the message converters instead ("{}" for JSON, a 500 "No converter" for CSV -- live, run 7405).
        // So a refusal is streamed too, as its JSON envelope.
        StepTimelineService.Download download = this.service.download(runDatasetId, format);
        this.accessLog.runDataset(runDatasetId, format, download);
        if (download.refusal != null) {
            byte[] refusal = REFUSALS.writeValueAsBytes(download.refusal);
            // Its length declared and written whole (MIG-389): on Tomcat 11 a streamed 4xx with no length was left
            // unterminated (curl: partial file; api-check: no answer). The same JSON envelope.
            return ResponseEntity.status(download.status).contentType(MediaType.APPLICATION_JSON).contentLength(refusal.length)
                .body(out -> out.write(refusal));
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.fileName + "\"")
            .contentType(MediaType.parseMediaType(download.contentType))
            .body(download.body);
    }

    /** Wave 4: a run's result manifest -- the files its steps wrote, every attempt unless one is named. */
    @RequestMapping(value = "/sourceJob.json/runOutputs", method = RequestMethod.GET)
    public ResponseEntity<?> runOutputs(@RequestParam Long jobQueueId, @RequestParam(required = false) Integer attempt) {
        return new ResponseEntity<>(this.service.outputs(jobQueueId, attempt), HttpStatus.OK);
    }

    /**
     * MIG-345: what retrying a failed run would do -- the step it resumes from, the steps it reuses, or why it cannot -- and
     * whether the caller may run it again from the start.
     */
    @RequestMapping(value = "/sourceJob.json/retryPlan", method = RequestMethod.GET)
    public ResponseEntity<?> retryPlan(@RequestParam Long jobQueueId) {
        return new ResponseEntity<>(this.service.retryPlan(jobQueueId), HttpStatus.OK);
    }

    /**
     * MIG-345: retries a failed run as its next attempt -- body {jobQueueId, from}: "failed_step" (the default) resumes from
     * the step that failed, reusing the outputs before it; "start" runs every step again (a workspace administrator's).
     * Not a builder action: a managed workspace's customers retry their runs, as they Run now.
     */
    @RequestMapping(value = "/sourceJob.json/retryRun", method = RequestMethod.POST)
    public ResponseEntity<?> retryRun(@RequestBody RetryRequest request) {
        return new ResponseEntity<>(this.service.retry(request == null ? null : request.getJobQueueId(),
            request == null ? null : request.getFrom()), HttpStatus.OK);
    }

    /** The console's retry: the run and where to start ("failed_step" or "start"). */
    public static class RetryRequest {
        private Long jobQueueId;
        private String from;

        public Long getJobQueueId() { return jobQueueId; }

        public void setJobQueueId(Long jobQueueId) { this.jobQueueId = jobQueueId; }

        public String getFrom() { return from; }

        public void setFrom(String from) { this.from = from; }
    }

    @RequestMapping(value = "/sourceJob.json/stepLogs", method = RequestMethod.GET)
    public ResponseEntity<?> stepLogs(@RequestParam Long stepExecutionId) {
        return new ResponseEntity<>(this.service.log(stepExecutionId), HttpStatus.OK);
    }
}
