package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import process.model.dto.ResponseDto;
import process.pipeline.review.ReviewPolicy;
import process.pipeline.review.RunReviewBulk;
import process.pipeline.review.RunReviewBulkRequest;
import process.util.ProcessUtil;

import java.util.Map;

/**
 * Bulk review (2026-10-12), beside the run review (RunReviewRestApi) under the prefix the gateway already sends to Core:
 *
 * <ul>
 *   <li>POST sourceJob.json/review/bulkDecide {jobQueueIds, decision, comment, bulkId, dryRun}: a tenant administrator
 *       approves or rejects many runs' internal review, each run decided on its own by a single decision's rules
 *       (RunReviewBulk); answered per run -- done, skipped with a reason, failed. A request that is not valid is a 400, and
 *       a valid one past 20 bulk requests a minute a 429; nothing is done then.</li>
 *   <li>GET sourceJob.json/review/policy: the workspace's "Allow bulk approve" for pipeline runs, to any member;</li>
 *   <li>POST sourceJob.json/review/policy {bulkApproveRuns}: a tenant administrator sets it.</li>
 * </ul>
 * Every refusal is a 4xx with its sentence in the envelope -- 400 for what is not valid (an account with no workspace
 * included), 403 for who may not (the @PreAuthorize), 429 for too many -- as the other services answer theirs.
 */
@RestController
@PreAuthorize("hasRole('TENANT_USER')")
public class RunReviewBulkRestApi {

    private final Logger logger = LoggerFactory.getLogger(RunReviewBulkRestApi.class);

    private final RunReviewBulk bulk;
    private final ReviewPolicy policy;

    public RunReviewBulkRestApi(RunReviewBulk bulk, ReviewPolicy policy) {
        this.bulk = bulk;
        this.policy = policy;
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/sourceJob.json/review/bulkDecide", method = RequestMethod.POST)
    public ResponseEntity<?> bulkDecide(@RequestBody(required = false) RunReviewBulkRequest request) {
        try {
            return new ResponseEntity<>(this.bulk.decide(request), HttpStatus.OK);
        } catch (RunReviewBulk.Refused refused) {
            return refusal(refused);
        } catch (RuntimeException ex) {
            this.logger.error("An error occurred while deciding runs' reviews in bulk.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500),
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/sourceJob.json/review/policy", method = RequestMethod.GET)
    public ResponseEntity<?> policy() {
        try {
            return new ResponseEntity<>(this.policy.read(), HttpStatus.OK);
        } catch (RunReviewBulk.Refused refused) {
            return refusal(refused);
        }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/sourceJob.json/review/policy", method = RequestMethod.POST)
    public ResponseEntity<?> savePolicy(@RequestBody(required = false) Map<String, Object> body) {
        Object on = body == null ? null : body.get("bulkApproveRuns");
        try {
            return new ResponseEntity<>(this.policy.save(on instanceof Boolean ? (Boolean) on : null), HttpStatus.OK);
        } catch (RunReviewBulk.Refused refused) {
            return refusal(refused);
        }
    }

    private static ResponseEntity<ResponseDto> refusal(RunReviewBulk.Refused refused) {
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, refused.getMessage()), refused.getStatus());
    }
}
