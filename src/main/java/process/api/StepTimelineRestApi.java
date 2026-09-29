package process.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import process.model.dto.ResponseDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
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
@CrossOrigin(origins = "*", exposedHeaders = {HttpHeaders.CONTENT_DISPOSITION})
@PreAuthorize("hasRole('TENANT_USER')")
public class StepTimelineRestApi {

    private final StepTimelineService service;

    private static final ObjectMapper REFUSALS = new ObjectMapper();

    public StepTimelineRestApi(StepTimelineService service) {
        this.service = service;
    }

    @RequestMapping(value = "/sourceJob.json/stepExecutions", method = RequestMethod.GET)
    public ResponseEntity<?> stepExecutions(@RequestParam Long jobQueueId, @RequestParam(required = false) Integer attempt) {
        return new ResponseEntity<>(this.service.timeline(jobQueueId, attempt), HttpStatus.OK);
    }

    /**
     * Wave 4: a run dataset as a file -- csv (the default), json or jsonl. Streamed with a Content-Disposition; refused
     * with the envelope: 400 for a format it does not write, 404 for a dataset that is not the caller's (another
     * workspace's included), 410 once it has expired.
     */
    @RequestMapping(value = "/sourceJob.json/runDataset", method = RequestMethod.GET)
    public ResponseEntity<StreamingResponseBody> runDataset(@RequestParam Long runDatasetId,
        @RequestParam(required = false) String format) {
        // Declared as ResponseEntity<StreamingResponseBody>: only then does Spring stream the body. A ResponseEntity<?>
        // hands it to the message converters instead ("{}" for JSON, a 500 "No converter" for CSV -- live, run 7405).
        // So a refusal is streamed too, as its JSON envelope.
        StepTimelineService.Download download = this.service.download(runDatasetId, format);
        if (download.refusal != null) {
            ResponseDto refusal = download.refusal;
            return ResponseEntity.status(download.status).contentType(MediaType.APPLICATION_JSON)
                .body(out -> REFUSALS.writeValue(out, refusal));
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

    @RequestMapping(value = "/sourceJob.json/stepLogs", method = RequestMethod.GET)
    public ResponseEntity<?> stepLogs(@RequestParam Long stepExecutionId) {
        return new ResponseEntity<>(this.service.log(stepExecutionId), HttpStatus.OK);
    }
}
