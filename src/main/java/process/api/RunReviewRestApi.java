package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.engine.OneRunInFlight;
import process.model.dto.ResponseDto;
import process.pipeline.review.RunReviewRequest;
import process.pipeline.review.RunReviewService;
import process.util.ProcessUtil;

/**
 * Two-party result review of a run (MIG-237), the console's half, under the prefix the gateway already sends to Core
 * and gates as the jobs page -- as the run reads beside it (stepExecutions, runOutputs), so no gateway route or page
 * key is new:
 *
 * <ul>
 *   <li>GET sourceJob.json/review?jobQueueId=: the run's review status, required parties, decisions, and whether the
 *       caller may decide now -- any tenant user who can see the run;</li>
 *   <li>POST sourceJob.json/review/decide {jobQueueId, decision, comment, reason, rerun}: the internal review, by a
 *       tenant administrator. A request for the customer's review is refused here.</li>
 * </ul>
 *
 * The customer's half, POST /v1/executions/{id}/review, is deferred with the customer integration (MIG-234): it will
 * call RunReviewService.decide as the CUSTOMER party. A run that is not the caller's is the envelope's "not found".
 */
@RestController
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('TENANT_USER')")
public class RunReviewRestApi {

    private final Logger logger = LoggerFactory.getLogger(RunReviewRestApi.class);

    private final RunReviewService service;

    public RunReviewRestApi(RunReviewService service) {
        this.service = service;
    }

    @RequestMapping(value = "/sourceJob.json/review", method = RequestMethod.GET)
    public ResponseEntity<?> review(@RequestParam Long jobQueueId) {
        return new ResponseEntity<>(this.service.review(jobQueueId), HttpStatus.OK);
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/sourceJob.json/review/decide", method = RequestMethod.POST)
    public ResponseEntity<?> decide(@RequestBody RunReviewRequest request) {
        try {
            return new ResponseEntity<>(this.service.consoleDecide(request), HttpStatus.OK);
        } catch (RuntimeException ex) {
            if (OneRunInFlight.isViolation(ex)) {
                // As runSourceJob answers it: another Run or the enqueuer took the slot first; nothing was recorded.
                return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "The review was not recorded: the job can't be run "
                    + "again while its last run is still in flight ('Queue', 'Start', 'Running')."), HttpStatus.OK);
            }
            this.logger.error("An error occurred while deciding a run review.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500),
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
