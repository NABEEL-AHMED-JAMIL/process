package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.notifications.JobMail;
import process.engine.BulkAction;
import process.model.dto.*;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.MessageQService;
import process.security.TenantContext;
import process.util.BusinessTime;
import process.util.EnumUtils;
import process.util.ProcessUtil;
import java.sql.Timestamp;
import java.util.*;
import java.util.Set;
import static process.util.ProcessUtil.*;
import static process.util.ProcessUtil.SUCCESS;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class MessageQServiceImpl implements MessageQService {

    private Logger logger = LoggerFactory.getLogger(MessageQServiceImpl.class);

    private final String SOURCE_JOB_QUEUES = "sourceJobQueues";
    private final String JOB_STATUS_STATISTICS = "jobStatusStatistic";
    private final String AUDIT_LOG = "AUDIT_LOG";
    private final String QUEUE_DETAIL = "QUEUE_DETAIL";

    /**
     * The statuses a run can be forced out of by hand.
     *
     * These are the three the platform treats as occupying the queue: it is the set
     * getCountForInQueueJobByJobId counts when deciding a job is already busy, the set
     * findStalledRuns sweeps, and the set the queue screen's own inFlight() enables its Actions
     * menu for. failJobLogs accepted only Queue, so 'Mark as failed' -- which the screen offers on
     * all three -- came back "Only 'In Queue' Job can be fail." for precisely the runs an operator
     * needs it for: the ones sitting in Start or Running behind a worker that is never going to
     * report. 'Mark as interrupted' beside it, which has no status check at all, worked, so the
     * two neighbouring buttons disagreed about the same row.
     *
     * Forcing an in-flight run to Failed is a deliberate, confirmed operator action and is not the
     * judgement reconcileStalledRuns declines to make: that sweep runs unattended and cannot know
     * what a silent worker managed, which is why it settles for Interrupt. A person who has looked
     * at the run can say it failed, and the screen offers both words so they can say which.
     *
     * Terminal statuses stay refused -- re-failing a Completed or Skipped run would rewrite
     * history that something already recorded correctly.
     */
    private static final Set<JobStatus> IN_FLIGHT_STATUSES = JobStatus.IN_FLIGHT;

    private final BulkAction bulkAction;
    private final QueryService queryService;
    private final JobQueueRepository jobQueueRepository;
    private final SourceJobRepository sourceJobRepository;
    private final JobMail jobMail;

    public MessageQServiceImpl(BulkAction bulkAction,
        QueryService queryService,
        JobQueueRepository jobQueueRepository,
        SourceJobRepository sourceJobRepository,
        JobMail jobMail) {
        this.bulkAction = bulkAction;
        this.queryService = queryService;
        this.jobQueueRepository = jobQueueRepository;
        this.sourceJobRepository = sourceJobRepository;
        this.jobMail = jobMail;
    }

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
                    sourceJobQueue.setDateCreated((Timestamp) obj[index]);
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueue.setEndTime(BusinessTime.wallClockOf(obj[index]).withNano(0));
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
                    sourceJobQueue.setSkipTime(BusinessTime.wallClockOf(obj[index]).withNano(0));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueue.setStartTime(BusinessTime.wallClockOf(obj[index]).withNano(0));
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

    private boolean isJobOwnedByCaller(Long jobId) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return this.sourceJobRepository.findById(jobId)
            .map(job -> Objects.equals(job.getTenantId(), TenantContext.getTenantId()))
            .orElse(false);
    }

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
            if (!IN_FLIGHT_STATUSES.contains(jobQueue.get().getJobStatus())) {
                return new ResponseDto(ERROR, "Only a run still in flight ('Queue', 'Start', 'Running') can be failed.", jobQId);
            }
            String failMessage = String.format("Job %s fail by manual.", jobQueue.get().getJobId());
            this.bulkAction.changeJobStatus(jobQueue.get().getJobId(), JobStatus.Failed);
            this.bulkAction.changeJobQueueStatus(jobQueue.get().getJobQueueId(), JobStatus.Failed, failMessage);
            this.bulkAction.saveJobAuditLogs(jobQueue.get().getJobQueueId(), failMessage);
            this.bulkAction.changeJobQueueEndDate(jobQueue.get().getJobQueueId(), BusinessTime.now());

            Optional<SourceJob> sourceJob = this.sourceJobRepository.findById(jobQueue.get().getJobId());
            // Gated on the job's Failed preference. This read isSkipJob(), so whether a run marked
            // Failed sent its failure mail was decided by the "email me when a run is skipped" box
            // -- a job with fail mail on and skip mail off got nothing, and one with the opposite
            // pair got a failure mail it had not asked for. Both other places that send this same
            // Failed mail (ProducerBulkEngine.changeStatusForLastJob and changeJobStatus below)
            // read isFailJob().
            if (sourceJob.isPresent() && sourceJob.get().isFailJob()) {
                this.jobMail.send(SourceJobQueueDto.forEmailNotification(jobQueue.get()),JobStatus.Failed);
            }
            return new ResponseDto(SUCCESS, "JobQueue successfully updated.", jobQId);
        }
        return new ResponseDto(ERROR, "JobQueue not found");
    }

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
            String interruptMessage = String.format("Job %s interrupted.", jobQueue.get().getJobId());
            this.bulkAction.changeJobStatus(jobQueue.get().getJobId(), JobStatus.Interrupt);
            this.bulkAction.changeJobQueueStatus(jobQueue.get().getJobQueueId(), JobStatus.Interrupt, interruptMessage);
            this.bulkAction.saveJobAuditLogs(jobQueue.get().getJobQueueId(), interruptMessage);
            this.bulkAction.changeJobQueueEndDate(jobQueue.get().getJobQueueId(), BusinessTime.now());
            return new ResponseDto(SUCCESS, "JobQueue successfully updated.", jobQId);
        }
        return new ResponseDto(ERROR, "JobQueue not found");
    }

    @Override
    public ResponseDto changeJobStatus(QueueMessageStatusDto queueMessageStatus) {
        if (isNull(queueMessageStatus.getMessageType())) {
            return new ResponseDto(ERROR, "Message Type required for transaction.");
        }

        if (isNull(queueMessageStatus.getJobQueueId())) {
            return new ResponseDto(ERROR, "JobQueueId required for transaction.");
        }
        // Every write below lands on this queue row, so ownership is settled from the row and
        // not from the optional jobId in the body -- leaving jobId out used to skip the check
        // altogether. A jobId that is supplied has to agree with the row, or the request is
        // pointing at one job while writing to another's run.
        Optional<JobQueue> jobQueue = this.jobQueueRepository.findById(queueMessageStatus.getJobQueueId());
        if (!jobQueue.isPresent() || !this.isJobOwnedByCaller(jobQueue.get().getJobId())) {
            return new ResponseDto(ERROR, "JobQueue not found.");
        }
        if (!isNull(queueMessageStatus.getJobId())
            && !Objects.equals(jobQueue.get().getJobId(), queueMessageStatus.getJobId())) {
            return new ResponseDto(ERROR, "JobQueue not found.");
        }
        Long jobId = jobQueue.get().getJobId();
        if (queueMessageStatus.getMessageType().equals(AUDIT_LOG)) {
            this.bulkAction.saveJobAuditLogs(jobQueue.get().getJobQueueId(), queueMessageStatus.getLogsDetail());
        } else if (queueMessageStatus.getMessageType().equals(QUEUE_DETAIL)) {
            // The worker saying a run failed is the failure retry exists for: everything the
            // dispatcher can go wrong at is infrastructure, whereas this is the task itself
            // reporting that it could not finish -- a source that was briefly unreachable, an
            // object store that refused one connection, a database that dropped the session.
            //
            // Offered before any of the writes below, because those are what a failure IS as far
            // as the rest of the platform is concerned: the job's status, the run's status, the
            // end time and the fail mail. Making them and then retrying would tell everyone the
            // run had failed moments before trying it again. scheduleRetry writes the worker's own
            // explanation into the audit log, so nothing it reported is lost by returning early.
            if (JobStatus.Failed.equals(queueMessageStatus.getJobStatus())
                && this.bulkAction.scheduleRetry(jobQueue.get(), queueMessageStatus.getLogsDetail())) {
                return new ResponseDto(SUCCESS, "Run failed and has been queued for another attempt.");
            }
            this.bulkAction.changeJobStatus(jobId, queueMessageStatus.getJobStatus());
            this.bulkAction.changeJobQueueStatus(jobQueue.get().getJobQueueId(), queueMessageStatus.getJobStatus(), queueMessageStatus.getLogsDetail());
            this.bulkAction.saveJobAuditLogs(jobQueue.get().getJobQueueId(), queueMessageStatus.getLogsDetail());
            if (!isNull(queueMessageStatus.getEndTime())) {
                this.bulkAction.changeJobQueueEndDate(jobQueue.get().getJobQueueId(), queueMessageStatus.getEndTime());
            }

            Optional<SourceJob> sourceJob = this.sourceJobRepository.findById(jobId);
            // Re-read the queue row so the mail carries the status and message just written.
            Optional<JobQueue> jobQueueForMail = this.jobQueueRepository.findById(jobQueue.get().getJobQueueId());
            JobStatus status = queueMessageStatus.getJobStatus();
            boolean shouldSend = sourceJob.isPresent() && jobQueueForMail.isPresent() && !isNull(status) &&
                ((sourceJob.get().isSkipJob() && status.equals(JobStatus.Skip)) ||
                (sourceJob.get().isCompleteJob() && status.equals(JobStatus.Completed)) ||
                (sourceJob.get().isFailJob() && status.equals(JobStatus.Failed)));
            if (shouldSend) {
                this.jobMail.send(SourceJobQueueDto.forEmailNotification(jobQueueForMail.get()), status);
            }
        }
        return new ResponseDto(SUCCESS, "QueueMessage successfully updated.");
    }

}