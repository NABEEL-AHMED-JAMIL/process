package process.api;

import process.util.BusinessTime;
import org.barco.platform.correlation.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import process.callback.CallbackKeys;
import process.callback.ReplayedResponse;
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
import java.util.Optional;
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
        return this.refused(jobId, jobQueueId, this.runCallbackTokens.verify(jobId, jobQueueId, presentedToken));
    }

    private ResponseEntity<?> refused(Long jobId, Long jobQueueId, Optional<RunCallbackTokens.Refusal> refusal) {
        return refusal
            .map(why -> {
                this.logger.warn("Rejected worker callback for job {} run {}: {}.", jobId, jobQueueId, why);
                return new ResponseEntity<>(
                    new ResponseDto(ProcessUtil.ERROR_MESSAGE, "Unauthorized worker callback."), HttpStatus.UNAUTHORIZED);
            })
            .map(r -> (ResponseEntity<?>) r)
            .orElse(null);
    }

    /**
     * The token check, and -- for a run that is over -- the one way past it (MIG-18).
     *
     * RUN_OVER is only reported for the run's own token (RunCallbackTokens checks the token first), so
     * a worker re-sending the last callback of a finished run, whose answer it never received, is
     * answered from the receipt that callback left: exactly what it was told the first time. Anything
     * without a receipt is the same 401 as ever. Null means go ahead.
     */
    private ResponseEntity<?> admit(Long jobId, Long jobQueueId, String presentedToken,
        JobStatus jobStatus, String request, String idempotencyKey, String echoedCorrelationId) {
        Optional<RunCallbackTokens.Refusal> refusal = this.runCallbackTokens.verify(jobId, jobQueueId, presentedToken);
        if (!refusal.isPresent() || refusal.get() == RunCallbackTokens.Refusal.RUN_OVER
            || refusal.get() == RunCallbackTokens.Refusal.EXPIRED) {
            this.bindCorrelation(jobId, jobQueueId, request, echoedCorrelationId);
        }
        if (refusal.isPresent() && refusal.get() == RunCallbackTokens.Refusal.RUN_OVER
            && (idempotencyKey == null || CallbackKeys.isAcceptable(idempotencyKey))) {
            Optional<ResponseDto> answered = this.notifyService.replay(jobQueueId, jobStatus, request, idempotencyKey);
            if (answered.isPresent()) {
                this.logger.info("Answered a redelivered {} for finished run {} of job {} from its receipt.",
                    request, jobQueueId, jobId);
                return new ResponseEntity<>(answered.get(), HttpStatus.OK);
            }
        }
        if (refusal.isPresent() && refusal.get() == RunCallbackTokens.Refusal.EXPIRED) {
            // Still refused -- reconciliation is separate from acceptance (MIG-63). But EXPIRED is only
            // said to the run's own token, so this is its worker, alive and unable to be heard: noted on
            // the run for the stall sweep to close, instead of the run blocking its job for hours.
            try {
                this.notifyService.noteRefusedCallback(jobQueueId, jobStatus);
            } catch (RuntimeException failed) {
                this.logger.warn("Could not note the refused callback on run {}: {}", jobQueueId, failed.getMessage());
            }
        }
        ResponseEntity<?> rejected = this.refused(jobId, jobQueueId, refusal);
        if (rejected != null) {
            return rejected;
        }
        if (idempotencyKey != null && !CallbackKeys.isAcceptable(idempotencyKey)) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                CallbackKeys.HEADER + " must be 8 to 128 letters, digits, '.', '_', ':' or '-'."), HttpStatus.BAD_REQUEST);
        }
        return null;
    }

    /**
     * Logs this callback under its run's correlation id (MIG-95, MIG-94), and answers with it.
     *
     * A verified callback is about its run, so the run's own id (job_queue.correlation_id) is the one it is logged
     * under, whatever id it arrived with. MIG-95 first kept an arriving id and resolved the run's only when none
     * came; but behind the gateway one always comes -- the gateway mints an id for a worker that sends none, as
     * job-search's Python workers do -- so live, every callback was logged under a fresh id of its own. The id it
     * arrived under, when different, is named on the callback's line, so the gateway's access line still leads
     * here. Only for a token that has been found to be the run's own: the id is not an input to anything, and a
     * caller who merely knows a run id is not told it.
     */
    private void bindCorrelation(Long jobId, Long jobQueueId, String request, String arrivedWith) {
        Optional<RunCallbackTokens.RunCorrelation> run = this.runCallbackTokens.correlationOf(jobQueueId);
        String arrivedAs = CorrelationId.isAcceptable(arrivedWith) ? arrivedWith : CorrelationId.current();
        Optional<String> resolved = run.map(found -> found.correlationId).filter(CorrelationId::isAcceptable);
        resolved.ifPresent(id -> {
            CorrelationId.set(id);
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes && ((ServletRequestAttributes) attributes).getResponse() != null) {
                ((ServletRequestAttributes) attributes).getResponse().setHeader(CorrelationId.HEADER, id);
            }
        });
        boolean renamed = resolved.isPresent() && arrivedAs != null && !resolved.get().equals(arrivedAs);
        this.logger.info("Worker callback {} for job {} run {} of tenant {}{}.", request, jobId, jobQueueId,
            run.map(found -> found.tenantId).orElse(null), renamed ? " (arrived as " + arrivedAs + ")" : "");
    }

    @RequestMapping(value = "/changeState/jobId/{jobId}/jobQueueId/{jobQueueId}/jobStatus/{jobStatus}", method = RequestMethod.POST)
    public ResponseEntity<?> changeState(
        @PathVariable("jobId") Long jobId,
        @PathVariable("jobQueueId") Long jobQueueId,
        @PathVariable("jobStatus") JobStatus jobStatus,
        @RequestHeader(value = WORKER_TOKEN_HEADER, required = false) String workerToken,
        @RequestHeader(value = CallbackKeys.HEADER, required = false) String idempotencyKey,
        @RequestHeader(value = CorrelationId.HEADER, required = false) String correlationId,
        @RequestBody SourceJobQueueDto jobQueue) {
        try {
            ResponseEntity<?> rejected = this.admit(jobId, jobQueueId, workerToken, jobStatus,
                CallbackKeys.changeState(jobStatus), idempotencyKey, correlationId);
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
                jobQueue.setEndTime(BusinessTime.now());
            }
            ResponseDto outcome = this.notifyService.changeState(jobQueue, idempotencyKey);
            // The run is over: its token is spent. A retry, should one be scheduled, is a new
            // dispatch and mints its own. Only on an accepted change -- a refused transition
            // leaves the run, and its token, as they were -- and only the first time: a
            // redelivery answered from its receipt changed nothing, so it spends nothing either.
            if (EnumSet.of(JobStatus.Failed, JobStatus.Completed).contains(jobStatus)
                && !ProcessUtil.ERROR.equals(outcome.getStatus()) && !(outcome instanceof ReplayedResponse)) {
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
            @RequestHeader(value = CallbackKeys.HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = CorrelationId.HEADER, required = false) String correlationId,
            @RequestBody Map<String, List<String>> body) {
        try {
            ResponseEntity<?> rejected = this.admit(jobId, jobQueueId, workerToken, null,
                CallbackKeys.ADD_LOGS_BATCH, idempotencyKey, correlationId);
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
                this.notifyService.addLogsBatch(jobId, jobQueueId, messages, idempotencyKey), HttpStatus.OK);
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
            @RequestHeader(value = CallbackKeys.HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = CorrelationId.HEADER, required = false) String correlationId,
            @RequestBody SourceJobQueueDto jobQueue) {
        try {
            ResponseEntity<?> rejected = this.admit(jobId, jobQueueId, workerToken, null,
                CallbackKeys.ADD_LOGS, idempotencyKey, correlationId);
            if (rejected != null) {
                return rejected;
            }
            jobQueue.setJobId(jobId);
            jobQueue.setJobQueueId(jobQueueId);

            if (ProcessUtil.isNull(jobQueue.getJobStatusMessage())) {
                return new ResponseEntity<>(new ResponseDto(ProcessUtil.JOB_STATUS_MESSAGE_REQUIRED, ProcessUtil.BAD_REQUEST_400), HttpStatus.BAD_REQUEST);
            }
            return new ResponseEntity<>(this.notifyService.addLogs(jobQueue, idempotencyKey), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addLogs ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
