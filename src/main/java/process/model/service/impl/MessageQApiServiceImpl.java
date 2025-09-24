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
import process.model.service.MessageQApiService;
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
public class MessageQApiServiceImpl implements MessageQApiService {

    private Logger logger = LoggerFactory.getLogger(MessageQApiServiceImpl.class);

    private final String SOURCE_JOB_QUEUES = "sourceJobQueues";
    private final String JOB_STATUS_STATISTICS = "jobStatusStatistic";
    private final String AUDIT_LOG = "AUDIT_LOG";
    private final String QUEUE_DETAIL = "QUEUE_DETAIL";

    private final BulkAction bulkAction;
    private final QueryService queryService;
    private final JobQueueRepository jobQueueRepository;
    private final SourceJobRepository sourceJobRepository;
    private final EmailMessagesFactory emailMessagesFactory;

    public MessageQApiServiceImpl(BulkAction bulkAction,
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
                    sourceJobQueue.setJobStatus(JobStatus.valueOf(String.valueOf(obj[index])));
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
        if (jobQueue.isPresent()) {
            if (!jobQueue.get().getJobStatus().equals(JobStatus.Queue)) {
                return new ResponseDto(ERROR, "Only 'In Queue' Job can be fail.", jobQId);
            }
            this.bulkAction.changeJobStatus(jobQueue.get().getJobId(), JobStatus.Failed);
            this.bulkAction.changeJobQueueStatus(jobQueue.get().getJobQueueId(), JobStatus.Failed);
            this.bulkAction.saveJobAuditLogs(jobQueue.get().getJobQueueId(), String.format("Job %s fail by manual.", jobQueue.get().getJobId()));
            this.bulkAction.changeJobQueueEndDate(jobQueue.get().getJobQueueId(), LocalDateTime.now());
            SourceJob sourceJob = this.sourceJobRepository.findById(jobQueue.get().getJobId()).get();
            if (sourceJob.isSkipJob()) {
                this.emailMessagesFactory.sendSourceJobEmail(EmailMessagesFactory.getSourceJobQueueDto(jobQueue.get()),JobStatus.Failed);
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
        if (queueMessageStatus.getMessageType().equals(AUDIT_LOG)) {
            this.bulkAction.saveJobAuditLogs(queueMessageStatus.getJobQueueId(), queueMessageStatus.getLogsDetail());
        } else if (queueMessageStatus.getMessageType().equals(QUEUE_DETAIL)) {
            this.bulkAction.changeJobStatus(queueMessageStatus.getJobId(), queueMessageStatus.getJobStatus());
            this.bulkAction.changeJobQueueStatus(queueMessageStatus.getJobQueueId(), queueMessageStatus.getJobStatus());
            this.bulkAction.saveJobAuditLogs(queueMessageStatus.getJobQueueId(), queueMessageStatus.getLogsDetail());
            if (!isNull(queueMessageStatus.getEndTime())) {
                this.bulkAction.changeJobQueueEndDate(queueMessageStatus.getJobQueueId(), queueMessageStatus.getEndTime());
            }
            // if the user configure then send email
            SourceJob sourceJob = this.sourceJobRepository.findById(queueMessageStatus.getJobId()).get();
            if (sourceJob.isSkipJob() && queueMessageStatus.getJobStatus().equals(JobStatus.Skip)) {
                this.emailMessagesFactory.sendSourceJobEmail(EmailMessagesFactory.getSourceJobQueueDto(this.jobQueueRepository.findById(queueMessageStatus.getJobQueueId()).get()),queueMessageStatus.getJobStatus());
            } else if (sourceJob.isCompleteJob() && queueMessageStatus.getJobStatus().equals(JobStatus.Completed)) {
                this.emailMessagesFactory.sendSourceJobEmail(EmailMessagesFactory.getSourceJobQueueDto(this.jobQueueRepository.findById(queueMessageStatus.getJobQueueId()).get()),queueMessageStatus.getJobStatus());
            } else if (sourceJob.isFailJob() && queueMessageStatus.getJobStatus().equals(JobStatus.Failed)) {
                this.emailMessagesFactory.sendSourceJobEmail(EmailMessagesFactory.getSourceJobQueueDto(this.jobQueueRepository.findById(queueMessageStatus.getJobQueueId()).get()),queueMessageStatus.getJobStatus());
            }
        }
        return new ResponseDto(SUCCESS, "QueueMessage successfully update.");
    }

}