package process.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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
 *   <li>GET sourceJob.json/stepLogs?stepExecutionId=: one step's log lines.</li>
 * </ul>
 *
 * A missing id is Spring's 400; a run or step that is not the caller's is the envelope's "not found".
 */
@RestController
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('TENANT_USER')")
public class StepTimelineRestApi {

    private final StepTimelineService service;

    public StepTimelineRestApi(StepTimelineService service) {
        this.service = service;
    }

    @RequestMapping(value = "/sourceJob.json/stepExecutions", method = RequestMethod.GET)
    public ResponseEntity<?> stepExecutions(@RequestParam Long jobQueueId, @RequestParam(required = false) Integer attempt) {
        return new ResponseEntity<>(this.service.timeline(jobQueueId, attempt), HttpStatus.OK);
    }

    @RequestMapping(value = "/sourceJob.json/stepLogs", method = RequestMethod.GET)
    public ResponseEntity<?> stepLogs(@RequestParam Long stepExecutionId) {
        return new ResponseEntity<>(this.service.log(stepExecutionId), HttpStatus.OK);
    }
}
