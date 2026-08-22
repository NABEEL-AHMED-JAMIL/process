package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.service.NotifyService;
import process.util.ProcessUtil;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.EnumSet;

/**
 * Callback endpoints the ETL workers use to report job progress back. These are outside the
 * JWT chain (SecurityConfig permits them) because the workers are batch processes with no
 * user session -- so they authenticate instead with a shared secret in X-Worker-Token, without
 * which anyone able to reach this port could drive any job's status and audit log by id.
 */
@RestController
@CrossOrigin(origins = "*")
public class NotifyResetApi {

    private static final String WORKER_TOKEN_HEADER = "X-Worker-Token";

    private Logger logger = LoggerFactory.getLogger(NotifyResetApi.class);

    private final NotifyService notifyService;

    @Value("${worker.callback.token:}")
    private String workerCallbackToken;

    public NotifyResetApi(NotifyService notifyService) {
        this.notifyService = notifyService;
    }

    @PostConstruct
    public void warnIfUnsecured() {
        if (ProcessUtil.isNull(this.workerCallbackToken) || this.workerCallbackToken.trim().isEmpty()) {
            this.logger.warn("WORKER_CALLBACK_TOKEN is not set -- /changeState and /addLogs accept "
                + "unauthenticated requests. Set it here and on the ETL workers to close that off.");
        }
    }

    /**
     * Returns null when the caller is allowed through. Comparison is constant-time so that a
     * wrong token can't be recovered a character at a time from response timing.
     */
    private ResponseEntity<?> rejectIfUntrusted(String presentedToken) {
        if (ProcessUtil.isNull(this.workerCallbackToken) || this.workerCallbackToken.trim().isEmpty()) {
            return null;
        }
        byte[] expected = this.workerCallbackToken.trim().getBytes(StandardCharsets.UTF_8);
        byte[] presented = presentedToken == null
            ? new byte[0]
            : presentedToken.trim().getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(expected, presented)) {
            return null;
        }
        this.logger.warn("Rejected worker callback with a missing or invalid {}.", WORKER_TOKEN_HEADER);
        return new ResponseEntity<>(
            new ResponseDto(ProcessUtil.ERROR_MESSAGE, "Unauthorized worker callback."), HttpStatus.UNAUTHORIZED);
    }

    @RequestMapping(value = "/changeState/jobId/{jobId}/jobQueueId/{jobQueueId}/jobStatus/{jobStatus}", method = RequestMethod.POST)
    public ResponseEntity<?> changeState(
        @PathVariable("jobId") Long jobId,
        @PathVariable("jobQueueId") Long jobQueueId,
        @PathVariable("jobStatus") JobStatus jobStatus,
        @RequestHeader(value = WORKER_TOKEN_HEADER, required = false) String workerToken,
        @RequestBody SourceJobQueueDto jobQueue) {
        try {
            ResponseEntity<?> rejected = this.rejectIfUntrusted(workerToken);
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
            return new ResponseEntity<>(this.notifyService.changeState(jobQueue), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while changeState ", ex);
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
            ResponseEntity<?> rejected = this.rejectIfUntrusted(workerToken);
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
