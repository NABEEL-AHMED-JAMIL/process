package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.emailer.EmailMessagesFactory;
import process.engine.BulkAction;
import process.model.dto.*;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.MessageQService;
import process.security.TenantContext;
import process.util.EnumUtils;
import process.util.ProcessUtil;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import static process.util.ProcessUtil.*;
import static process.util.ProcessUtil.SUCCESS;

/**
 * @author Nabeel Ahmed
 */
@Service
public class MessageQServiceImpl implements MessageQService {

    private Logger logger = LoggerFactory.getLogger(MessageQServiceImpl.class);

    private final String SOURCE_JOB_QUEUES = "sourceJobQueues";
    private final String JOB_STATUS_STATISTICS = "jobStatusStatistic";
    private final String AUDIT_LOG = "AUDIT_LOG";
    private final String QUEUE_DETAIL = "QUEUE_DETAIL";

    private final BulkAction bulkAction;
    private final QueryService queryService;
    private final JobQueueRepository jobQueueRepository;
    private final SourceJobRepository sourceJobRepository;
    private final EmailMessagesFactory emailMessagesFactory;

    public MessageQServiceImpl(BulkAction bulkAction,
        QueryService queryService,
        JobQueueRepository jobQueueRepository,
        SourceJobRepository sourceJobRepository,
        EmailMessagesFactory emailMessagesFactory) {
        this.bulkAction = bulkAction;
        this.queryService = queryService;
        this.jobQueueRepository = jobQueueRepository;
        this.sourceJobRepository = sourceJobRepository;
        this.emailMessagesFactory = emailMessagesFactory;
    }

    /**
     * Method use to fetch the logs
     * @param messageQSearch
     * @return ResponseDto
     * */
    @Override
    public ResponseDto fetchLogs(MessageQSearchDto messageQSearch) {
        ResponseDto responseDto = new ResponseDto(SUCCESS, "No Data found.", new ArrayList<>());
        if (ProcessUtil.isNull(messageQSearch.getFromDate())) {
            return new ResponseDto(ERROR, "FromDate missing.");
        } else if (ProcessUtil.isNull(messageQSearch.getToDate())) {
            return new ResponseDto(ERROR, "ToDate missing.");
        }
        Map<String, Object> objectMap = new HashMap<>();
        List<Object[]> result = this.queryService.executeQuery(this.queryService.fetchJobQLog(messageQSearch, false));
        if (!ProcessUtil.isNull(result) && !result.isEmpty()) {
            List<SourceJobQueueDto> sourceJobQueues = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                SourceJobQueueDto sourceJobQueue = new SourceJobQueueDto();
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueue.setJobQueueId(Long.valueOf(obj[index].toString()));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueue.setDateCreated(Timestamp.valueOf(String.valueOf(obj[index])));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueue.setEndTime(LocalDateTime.parse(String.valueOf(obj[index]).substring(0,19), formatter));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueue.setJobId(Long.valueOf(String.valueOf(obj[index])));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueue.setJobSend(Boolean.parseBoolean(obj[index].toString()));
                }
                index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueue.setJobStatus(EnumUtils.parseEnum(JobStatus.class, String.valueOf(obj[index])));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueue.setJobStatusMessage(String.valueOf(obj[index]));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueue.setRunManual(Boolean.valueOf(obj[index].toString()));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueue.setSkipManual(Boolean.valueOf(obj[index].toString()));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueue.setSkipTime(LocalDateTime.parse(String.valueOf(obj[index]).substring(0,19), formatter));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueue.setStartTime(LocalDateTime.parse(String.valueOf(obj[index]).substring(0,19), formatter));
                }
                sourceJobQueues.add(sourceJobQueue);
            }
            objectMap.put(SOURCE_JOB_QUEUES, sourceJobQueues);
            result = this.queryService.executeQuery(this.queryService.fetchJobQLog(messageQSearch, true));
            if (!ProcessUtil.isNull(result) && !result.isEmpty()) {
                List<JobStatusStatisticDto> jobStatusStatistic = new ArrayList<>();
                for(Object[] obj : result) {
                    int index = 0;
                    jobStatusStatistic.add(new JobStatusStatisticDto(String.valueOf(obj[index]), Integer.valueOf(obj[++index].toString())));
                }
                objectMap.put(JOB_STATUS_STATISTICS, jobStatusStatistic);
            }
            responseDto = new ResponseDto(SUCCESS, "MessageQ successfully ", objectMap);
        }
        return responseDto;
    }

    /**
     * Method use to check whether the SourceJob a job_queue row belongs to is owned by the
     * caller (PLATFORM_ADMIN, or the tenant that owns the job) -- JobQueue has no tenantId of
     * its own, so ownership is only knowable through its parent SourceJob. Without this,
     * failJobLogs/interruptJobLogs/changeJobStatus below would let any authenticated tenant
     * user fail, interrupt, or rewrite the audit trail of ANY tenant's running job just by
     * guessing/incrementing a jobQueueId -- a write-side IDOR, not just a read leak.
     * @param jobId
     * @return boolean
     * */
    private boolean isJobOwnedByCaller(Long jobId) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return this.sourceJobRepository.findById(jobId)
            .map(job -> Objects.equals(job.getTenantId(), TenantContext.getTenantId()))
            .orElse(false);
    }

    /**
     * Method use to fail the job
     * @param jobQId
     * @return ResponseDto
     * */
    @Override
    public ResponseDto failJobLogs(Long jobQId) {
        if (isNull(jobQId)) {
            return new ResponseDto(ERROR, "JobQId missing.");
        }
        Optional<JobQueue> jobQueue = this.jobQueueRepository.findById(jobQId);
        if (jobQueue.isPresent() && !this.isJobOwnedByCaller(jobQueue.get().getJobId())) {
            return new ResponseDto(ERROR, "JobQueue not found");
        }
        if (jobQueue.isPresent()) {
            if (!jobQueue.get().getJobStatus().equals(JobStatus.Queue)) {
                return new ResponseDto(ERROR, "Only 'In Queue' Job can be fail.", jobQId);
            }
            this.bulkAction.changeJobStatus(jobQueue.get().getJobId(), JobStatus.Failed);
            this.bulkAction.changeJobQueueStatus(jobQueue.get().getJobQueueId(), JobStatus.Failed);
            this.bulkAction.saveJobAuditLogs(jobQueue.get().getJobQueueId(), String.format("Job %s fail by manual.", jobQueue.get().getJobId()));
            this.bulkAction.changeJobQueueEndDate(jobQueue.get().getJobQueueId(), LocalDateTime.now());
            // the status change above already succeeded -- the job may have been deleted
            // concurrently since, in which case there's simply no notification email to send
            Optional<SourceJob> sourceJob = this.sourceJobRepository.findById(jobQueue.get().getJobId());
            if (sourceJob.isPresent() && sourceJob.get().isSkipJob()) {
                this.emailMessagesFactory.sendSourceJobEmail(SourceJobQueueDto.forEmailNotification(jobQueue.get()),JobStatus.Failed);
            }
            return new ResponseDto(SUCCESS, "JobQueue successfully update.", jobQId);
        }
        return new ResponseDto(ERROR, "JobQueue not found");
    }

    /**
     * Method use to interrupt the job
     * @param jobQId
     * @return ResponseDto
     * */
    @Override
    public ResponseDto interruptJobLogs(Long jobQId) {
        if (isNull(jobQId)) {
            return new ResponseDto(ERROR, "JobQId missing.");
        }
        Optional<JobQueue> jobQueue = this.jobQueueRepository.findById(jobQId);
        if (jobQueue.isPresent() && !this.isJobOwnedByCaller(jobQueue.get().getJobId())) {
            return new ResponseDto(ERROR, "JobQueue not found");
        }
        if (jobQueue.isPresent()) {
            this.bulkAction.changeJobStatus(jobQueue.get().getJobId(), JobStatus.Interrupt);
            this.bulkAction.changeJobQueueStatus(jobQueue.get().getJobQueueId(), JobStatus.Interrupt);
            this.bulkAction.saveJobAuditLogs(jobQueue.get().getJobQueueId(), String.format("Job %s interrupted.", jobQueue.get().getJobId()));
            this.bulkAction.changeJobQueueEndDate(jobQueue.get().getJobQueueId(), LocalDateTime.now());
            return new ResponseDto(SUCCESS, "JobQueue successfully update.", jobQId);
        }
        return new ResponseDto(ERROR, "JobQueue not found");
    }

    /**
     * Method use to method use to change the job status
     * @param queueMessageStatus
     * @return ResponseDto
     * */
    @Override
    public ResponseDto changeJobStatus(QueueMessageStatusDto queueMessageStatus) {
        if (isNull(queueMessageStatus.getMessageType())) {
            return new ResponseDto(ERROR, "Message Type required for transaction.");
        }
        // TENANT_USER+ can hit this endpoint for any jobId/jobQueueId it names -- without this,
        // a tenant user could write audit-log entries or flip another tenant's job status/queue
        // just by supplying that job's id (same class of write-IDOR as failJobLogs above).
        // Gate on jobId whenever the request carries one, regardless of messageType.
        if (!isNull(queueMessageStatus.getJobId()) && !this.isJobOwnedByCaller(queueMessageStatus.getJobId())) {
            return new ResponseDto(ERROR, "SourceJob not found.");
        }
        if (queueMessageStatus.getMessageType().equals(AUDIT_LOG)) {
            this.bulkAction.saveJobAuditLogs(queueMessageStatus.getJobQueueId(), queueMessageStatus.getLogsDetail());
        } else if (queueMessageStatus.getMessageType().equals(QUEUE_DETAIL)) {
            this.bulkAction.changeJobStatus(queueMessageStatus.getJobId(), queueMessageStatus.getJobStatus());
            this.bulkAction.changeJobQueueStatus(queueMessageStatus.getJobQueueId(), queueMessageStatus.getJobStatus());
            this.bulkAction.saveJobAuditLogs(queueMessageStatus.getJobQueueId(), queueMessageStatus.getLogsDetail());
            if (!isNull(queueMessageStatus.getEndTime())) {
                this.bulkAction.changeJobQueueEndDate(queueMessageStatus.getJobQueueId(), queueMessageStatus.getEndTime());
            }
            // if the user configure then send email -- the status change above already
            // succeeded, so a missing job/queue here just means no notification email goes out
            Optional<SourceJob> sourceJob = this.sourceJobRepository.findById(queueMessageStatus.getJobId());
            Optional<JobQueue> jobQueueForMail = this.jobQueueRepository.findById(queueMessageStatus.getJobQueueId());
            JobStatus status = queueMessageStatus.getJobStatus();
            boolean shouldSend = sourceJob.isPresent() && jobQueueForMail.isPresent() &&
                ((sourceJob.get().isSkipJob() && status.equals(JobStatus.Skip)) ||
                (sourceJob.get().isCompleteJob() && status.equals(JobStatus.Completed)) ||
                (sourceJob.get().isFailJob() && status.equals(JobStatus.Failed)));
            if (shouldSend) {
                this.emailMessagesFactory.sendSourceJobEmail(SourceJobQueueDto.forEmailNotification(jobQueueForMail.get()), status);
            }
        }
        return new ResponseDto(SUCCESS, "QueueMessage successfully update.");
    }

}