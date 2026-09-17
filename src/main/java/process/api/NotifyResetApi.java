package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.service.NotifyService;
import process.security.RunCallbackTokens;
import process.util.ProcessUtil;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.EnumSet;

/**
 * Callback endpoints the ETL workers use to report job progress back. These are outside the
 * JWT chain (SecurityConfig permits them) because the workers are batch processes with no
 * user session -- so they authenticate instead with the run's own token in X-Worker-Token, without
 * which anyone able to reach this port could drive any job's status and audit log by id.
 *
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
public class NotifyResetApi {

    private static final String WORKER_TOKEN_HEADER = "X-Worker-Token";

    private Logger logger = LoggerFactory.getLogger(NotifyResetApi.class);

    private final NotifyService notifyService;
    private final RunCallbackTokens runCallbackTokens;

    public NotifyResetApi(NotifyService notifyService, RunCallbackTokens runCallbackTokens) {
        this.notifyService = notifyService;
        this.runCallbackTokens = runCallbackTokens;
    }

    /**
     * Whether this callback may act on this run. Null means yes.
     *
     * The proof is the run's own token (RunCallbackTokens), issued at dispatch and echoed back
     * here, so a caller can only ever touch the run it was handed; the shared secret is honoured
     * only for a run dispatched before tokens existed. Every refusal is the same 401 with the
     * same words: which check failed is logged, never returned.
     */
    ResponseEntity<?> rejectIfUntrusted(Long jobId, Long jobQueueId, String presentedToken) {
        return this.runCallbackTokens.verify(jobId, jobQueueId, presentedToken)
            .map(refusal -> {
                this.logger.warn("Rejected worker callback for job {} run {}: {}.", jobId, jobQueueId, refusal);
                return new ResponseEntity<>(
                    new ResponseDto(ProcessUtil.ERROR_MESSAGE, "Unauthorized worker callback."), HttpStatus.UNAUTHORIZED);
            })
            .map(r -> (ResponseEntity<?>) r)
            .orElse(null);
    }

    @RequestMapping(value = "/changeState/jobId/{jobId}/jobQueueId/{jobQueueId}/jobStatus/{jobStatus}", method = RequestMethod.POST)
    public ResponseEntity<?> changeState(
        @PathVariable("jobId") Long jobId,
        @PathVariable("jobQueueId") Long jobQueueId,
        @PathVariable("jobStatus") JobStatus jobStatus,
        @RequestHeader(value = WORKER_TOKEN_HEADER, required = false) String workerToken,
        @RequestBody SourceJobQueueDto jobQueue) {
        try {
            ResponseEntity<?> rejected = this.rejectIfUntrusted(jobId, jobQueueId, workerToken);
            if (rejected != null) {
                return rejected;
            }
            jobQueue.setJobId(jobId);
            jobQueue.setJobQueueId(jobQueueId);
            jobQueue.setJobStatus(jobStatus);

            if (!EnumSet.of(JobStatus.Running, JobStatus.Failed, JobStatus.Completed).contains(jobStatus)) {
                return new ResponseEntity<>(new ResponseDto(ProcessUtil.JOB_STATUS_INVALID, ProcessUtil.BAD_REQUEST_400), HttpStatus.BAD_REQUEST);
            } else if (ProcessUtil.isNull(jobQueue.getJobStatusMessage())) {
                return new ResponseEntity<>(new ResponseDto(ProcessUtil.JOB_STATUS_MESSAGE_REQUIRED, ProcessUtil.BAD_REQUEST_400), HttpStatus.BAD_REQUEST);
            }

            if (EnumSet.of(JobStatus.Failed, JobStatus.Completed).contains(jobStatus)) {
                jobQueue.setEndTime(LocalDateTime.now());
            }
            ResponseDto outcome = this.notifyService.changeState(jobQueue);
            // The run is over: its token is spent. A retry, should one be scheduled, is a new
            // dispatch and mints its own. Only on an accepted change -- a refused transition
            // leaves the run, and its token, as they were.
            if (EnumSet.of(JobStatus.Failed, JobStatus.Completed).contains(jobStatus)
                && !ProcessUtil.ERROR.equals(outcome.getStatus())) {
                this.runCallbackTokens.retire(jobQueueId);
            }
            return new ResponseEntity<>(outcome, HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while changeState ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Many log lines in one request, for workers that buffer instead of posting per line.
     *
     * A run producing fifty lines was making fifty round trips, each repeating the same job
     * lookup. Measured on a 500-job run, that path accounted for roughly 97% of every run's
     * elapsed time under concurrency.
     */
    @RequestMapping(value = "/addLogsBatch/jobId/{jobId}/jobQueueId/{jobQueueId}", method = RequestMethod.POST)
    public ResponseEntity<?> addLogsBatch(
            @PathVariable("jobId") Long jobId,
            @PathVariable("jobQueueId") Long jobQueueId,
            @RequestHeader(value = WORKER_TOKEN_HEADER, required = false) String workerToken,
            @RequestBody Map<String, List<String>> body) {
        try {
            ResponseEntity<?> rejected = this.rejectIfUntrusted(jobId, jobQueueId, workerToken);
            if (rejected != null) {
                return rejected;
            }
            List<String> messages = body == null ? null : body.get("messages");
            if (messages == null || messages.isEmpty()) {
                return new ResponseEntity<>(
                    new ResponseDto(ProcessUtil.ERROR_MESSAGE, "messages must not be empty."),
                    HttpStatus.BAD_REQUEST);
            }
            return new ResponseEntity<>(
                this.notifyService.addLogsBatch(jobId, jobQueueId, messages), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addLogsBatch ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/addLogs/jobId/{jobId}/jobQueueId/{jobQueueId}", method = RequestMethod.POST)
    public ResponseEntity<?> addLogs(
            @PathVariable("jobId") Long jobId,
            @PathVariable("jobQueueId") Long jobQueueId,
            @RequestHeader(value = WORKER_TOKEN_HEADER, required = false) String workerToken,
            @RequestBody SourceJobQueueDto jobQueue) {
        try {
            ResponseEntity<?> rejected = this.rejectIfUntrusted(jobId, jobQueueId, workerToken);
            if (rejected != null) {
                return rejected;
            }
            jobQueue.setJobId(jobId);
            jobQueue.setJobQueueId(jobQueueId);

            if (ProcessUtil.isNull(jobQueue.getJobStatusMessage())) {
                return new ResponseEntity<>(new ResponseDto(ProcessUtil.JOB_STATUS_MESSAGE_REQUIRED, ProcessUtil.BAD_REQUEST_400), HttpStatus.BAD_REQUEST);
            }
            return new ResponseEntity<>(this.notifyService.addLogs(jobQueue), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addLogs ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
