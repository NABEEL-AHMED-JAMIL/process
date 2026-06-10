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
import process.socket.GlobalProperties;
import process.socket.Message;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.EnumSet;

/**
 * Api use to perform crud operation on dashboard
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
public class NotifyResetApi {

    private Logger logger = LoggerFactory.getLogger(NotifyResetApi.class);

    private final NotifyService notifyService;
    private final GlobalProperties globalProperties;

    public NotifyResetApi(NotifyService notifyService,
        GlobalProperties globalProperties) {
        this.notifyService = notifyService;
        this.globalProperties = globalProperties;
    }

    /**
     * Process message for register the session
     * @param message
     * @param headerAccessor
     * */
    @MessageMapping("/register")
    public void register(@Payload Message message,
        SimpMessageHeaderAccessor headerAccessor) throws Exception {
        logger.info("register sessionID" + message);
        this.globalProperties.addTransactionAndSession(message.getSessionId(), message.getTransactionId());
    }

    /**
     * Process message for unregister the session
     * @param message
     * @param headerAccessor
     * */
    @MessageMapping("/unregister")
    public void unregister(@Payload Message message,
        SimpMessageHeaderAccessor headerAccessor) throws Exception {
        logger.info("unregister sessionID" + message);
        this.globalProperties.removeTransactionAndSession(message.getSessionId());
    }

    /**
     * Job status notify
     * @return ResponseEntity
     * */
    @RequestMapping(value = "/changeState/jobId/{jobId}/jobQueueId/{jobQueueId}/jobStatus/{jobStatus}", method = RequestMethod.POST)
    public ResponseEntity<?> changeState(
        @PathVariable("jobId") Long jobId,
        @PathVariable("jobQueueId") Long jobQueueId,
        @PathVariable("jobStatus") JobStatus jobStatus,
        @RequestBody SourceJobQueueDto jobQueue) {
        try {
            jobQueue.setJobId(jobId);
            jobQueue.setJobQueueId(jobQueueId);
            jobQueue.setJobStatus(jobStatus);
            // Validate job status and job status message
            if (!EnumSet.of(JobStatus.Running, JobStatus.Failed, JobStatus.Completed).contains(jobStatus)) {
                return new ResponseEntity<>(new ResponseDto(ProcessUtil.JOB_STATUS_INVALID, ProcessUtil.BAD_REQUEST_400), HttpStatus.BAD_REQUEST);
            } else if (ProcessUtil.isNull(jobQueue.getJobStatusMessage())) { // Job status message
                return new ResponseEntity<>(new ResponseDto(ProcessUtil.JOB_STATUS_MESSAGE_REQUIRED, ProcessUtil.BAD_REQUEST_400), HttpStatus.BAD_REQUEST);
            }
            // Set end time when job status is Failed or Completed
            if (EnumSet.of(JobStatus.Failed, JobStatus.Completed).contains(jobStatus)) {
                jobQueue.setEndTime(LocalDateTime.now());
            }
            return new ResponseEntity<>(this.notifyService.changeState(jobQueue), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while changeState ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Job logs notify
     * @return ResponseEntity
     * */
    @RequestMapping(value = "/addLogs/jobId/{jobId}/jobQueueId/{jobQueueId}", method = RequestMethod.POST)
    public ResponseEntity<?> addLogs(
            @PathVariable("jobId") Long jobId,
            @PathVariable("jobQueueId") Long jobQueueId,
            @RequestBody SourceJobQueueDto jobQueue) {
        try {
            jobQueue.setJobId(jobId);
            jobQueue.setJobQueueId(jobQueueId);
            // Validate job status and job status message
            if (ProcessUtil.isNull(jobQueue.getJobStatusMessage())) {
                return new ResponseEntity<>(new ResponseDto(ProcessUtil.JOB_STATUS_MESSAGE_REQUIRED, ProcessUtil.BAD_REQUEST_400), HttpStatus.BAD_REQUEST);
            }
            return new ResponseEntity<>(this.notifyService.addLogs(jobQueue), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addLogs ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

}
