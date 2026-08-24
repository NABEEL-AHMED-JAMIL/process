package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import process.emailer.EmailMessagesFactory;
import process.engine.BulkAction;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.SourceJob;
import process.model.service.NotifyService;
import java.util.Optional;
import static process.util.ProcessUtil.ERROR;
import process.socket.JobEventPublisher;

@Service
@Transactional
public class NotifyServiceImpl implements NotifyService {

    private Logger logger = LoggerFactory.getLogger(NotifyServiceImpl.class);

    private final BulkAction bulkAction;
    private final EmailMessagesFactory emailMessagesFactory;
    private final TransactionServiceImpl transactionService;
    private final JobEventPublisher jobEventPublisher;

    public NotifyServiceImpl(
        BulkAction bulkAction,
        EmailMessagesFactory emailMessagesFactory,
        TransactionServiceImpl transactionService,
        JobEventPublisher jobEventPublisher) {
        this.bulkAction = bulkAction;
        this.emailMessagesFactory = emailMessagesFactory;
        this.transactionService = transactionService;
        this.jobEventPublisher = jobEventPublisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResponseDto changeState(SourceJobQueueDto jobQueue) {
        logger.info("Received request to change job {} queue {} status to {}", jobQueue.getJobId(), jobQueue.getJobQueueId(), jobQueue.getJobStatus());
        Optional<SourceJob> job = this.transactionService.findByJobIdAndJobStatus(jobQueue.getJobId(), Status.Active);
        if (!job.isPresent()) {
            logger.warn("Job {} not found or not active", jobQueue.getJobId());
            return new ResponseDto(ERROR, String.format("Job with id %s not found or not active", jobQueue.getJobId()), jobQueue);
        }
        JobStatus currentStatus = job.get().getJobRunningStatus();
        JobStatus newStatus = jobQueue.getJobStatus();
        logger.info("Job {} current status: {}, requested status: {}", jobQueue.getJobId(), currentStatus, newStatus);
        if (!this.isValidStatusTransition(currentStatus, newStatus)) {
            logger.warn("Invalid status transition for job {} from {} to {}", jobQueue.getJobId(), currentStatus, newStatus);
            return new ResponseDto(ERROR, String.format("Invalid status transition from %s to %s", currentStatus, newStatus), jobQueue);
        }
        logger.info("Updating status for job {} to {}", jobQueue.getJobId(), newStatus);
        this.bulkAction.changeJobStatus(jobQueue.getJobId(), newStatus);
        this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), newStatus, jobQueue.getJobStatusMessage());
        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), jobQueue.getJobStatusMessage());
        this.bulkAction.sendJobStatusNotification(jobQueue.getJobId(), currentStatus != newStatus);
        // Push the new state to anyone watching this tenant's job list, so a running job
        // updates in place instead of waiting for someone to hit Refresh.
        this.jobEventPublisher.publishStatus(job.get().getTenantId(), jobQueue.getJobId(),
            jobQueue.getJobQueueId(), newStatus.name(), jobQueue.getJobStatusMessage());
        if (newStatus == JobStatus.Failed || newStatus == JobStatus.Completed) {
            logger.info("Setting end date for job {}", jobQueue.getJobId());
            this.bulkAction.changeJobQueueEndDate(jobQueue.getJobQueueId(), jobQueue.getEndTime());
        }
        switch (newStatus) {
            case Failed:
                logger.info("Job {} marked as Failed", jobQueue.getJobId());
                if (job.get().isCompleteJob()) {
                    logger.info("Sending failure notification email for job {}", jobQueue.getJobId());
                    this.emailMessagesFactory.sendSourceJobEmail(jobQueue, newStatus);
                }
                break;
            case Completed:
                logger.info("Job {} marked as Completed", jobQueue.getJobId());
                if (job.get().isFailJob()) {
                    logger.info("Sending completion notification email for job {}", jobQueue.getJobId());
                    this.emailMessagesFactory.sendSourceJobEmail(jobQueue, newStatus);
                }
                break;
            default:
                break;
        }
        logger.info("Successfully changed job {} status to {}", jobQueue.getJobId(), newStatus);
        return new ResponseDto(String.format("Job %s status changed to %s", jobQueue.getJobId(), newStatus), jobQueue);
    }

    public ResponseDto addLogs(SourceJobQueueDto jobQueue) {
        logger.info("Received request to add logs for job {} queue {}", jobQueue.getJobId(), jobQueue.getJobQueueId());
        Optional<SourceJob> job = this.transactionService.findByJobIdAndJobStatus(jobQueue.getJobId(), Status.Active);
        if (!job.isPresent()) {
            logger.warn("Job {} not found or not active", jobQueue.getJobId());
            return new ResponseDto(ERROR, String.format("Job with id %s not found or not active", jobQueue.getJobId()), jobQueue);
        }
        logger.info("Adding logs for job {} queue {}", jobQueue.getJobId(), jobQueue.getJobQueueId());
        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), jobQueue.getJobStatusMessage());
        // The same pipeline callback that writes the line announces it, so an open run-logs
        // screen appends it instead of polling every five seconds for one that may not come.
        this.jobEventPublisher.publishLog(job.get().getTenantId(), jobQueue.getJobId(),
            jobQueue.getJobQueueId(), jobQueue.getJobStatusMessage());
        logger.info("Successfully added logs for job {} queue {}", jobQueue.getJobId(), jobQueue.getJobQueueId());
        return new ResponseDto(String.format("Logs added for job %s queue %s", jobQueue.getJobId(), jobQueue.getJobQueueId()), jobQueue);
    }

    private boolean isValidStatusTransition(JobStatus currentStatus, JobStatus newStatus) {
        switch (currentStatus) {
            case Queue:

                return newStatus == JobStatus.Start;

            case Start:
                return newStatus == JobStatus.Start
                        || newStatus == JobStatus.Running;

            case Running:
                return newStatus == JobStatus.Running
                        || newStatus == JobStatus.Failed
                        || newStatus == JobStatus.Completed;

            case Failed:
            case Completed:
                return currentStatus == newStatus;

            default:
                return false;
        }
    }

}
