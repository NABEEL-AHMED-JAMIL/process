package process.engine;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.emailer.EmailMessagesFactory;
import process.engine.dto.JobPayloadDTO;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.*;
import process.model.service.impl.TransactionServiceImpl;
import process.util.KafkaTopicPartitionUtil;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import java.time.LocalDateTime;
import java.util.*;
import static java.util.Objects.isNull;

@Component
public class ProducerBulkEngine {

    public Logger logger = LogManager.getLogger(ProducerBulkEngine.class);

    private final BulkAction bulkAction;
    private final TransactionServiceImpl transactionService;
    private final EmailMessagesFactory emailMessagesFactory;
    private final KafkaTemplateProvider kafkaTemplateProvider;
    private final KafkaConnectionResolver kafkaConnectionResolver;

    public ProducerBulkEngine(BulkAction bulkAction,
        TransactionServiceImpl transactionService,
        EmailMessagesFactory emailMessagesFactory,
        KafkaTemplateProvider kafkaTemplateProvider,
        KafkaConnectionResolver kafkaConnectionResolver) {
        this.bulkAction = bulkAction;
        this.transactionService = transactionService;
        this.emailMessagesFactory = emailMessagesFactory;
        this.kafkaTemplateProvider = kafkaTemplateProvider;
        this.kafkaConnectionResolver = kafkaConnectionResolver;
    }

    public void addManualJobInQueue(SourceJob sourceJob) {
        this.bulkAction.changeJobStatus(sourceJob.getJobId(), JobStatus.Queue);
        JobQueue jobQueue = this.bulkAction.createJobQueueV1(sourceJob.getJobId(),
            LocalDateTime.now(), JobStatus.Queue, "Job %s now in the queue.", false);
        this.bulkAction.changeJobLastJobRun(sourceJob.getJobId(), jobQueue.getStartTime());
        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s now in the queue.", sourceJob.getJobId()));
        this.bulkAction.sendJobStatusNotification(sourceJob.getJobId());
    }

    public void skipManualJobInQueue(Scheduler scheduler) {

        JobQueue jobQueue = this.bulkAction.createJobQueueV1(scheduler.getJobId(),
            scheduler.getNextRunAt(), JobStatus.Skip, "Job %s skip, by user action.", true);
        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s skip, by user action.", scheduler.getJobId()));

        this.bulkAction.sendJobStatusNotification(jobQueue.getJobId());
        Optional<SourceJob> sourceJobForSkipMail = this.transactionService.findByJobId(jobQueue.getJobId());
        if (sourceJobForSkipMail.isPresent() && sourceJobForSkipMail.get().isSkipJob()) {
            this.emailMessagesFactory.sendSourceJobEmail(SourceJobQueueDto.forEmailNotification(jobQueue), JobStatus.Skip);
        }
    }

    public void addJobInQueue() {
        try {
            logger.info("addJobInQueue --> FETCH due schedulers STARTED ");
            LocalDateTime now = LocalDateTime.now();
            List<Scheduler> dueSchedulers = this.transactionService.findDueSchedulers(now);
            logger.info("addJobInQueue --> FETCHED due schedulers: size {} ", dueSchedulers.size());
            if (!dueSchedulers.isEmpty()) {

                dueSchedulers.stream()
                    .forEach(scheduler -> {
                        try {
                            Thread.sleep(50);
                            JobQueue jobQueue;
                            if (this.bulkAction.getCountForInQueueJobByJobId(scheduler.getJobId()) > 0) {

                                jobQueue = this.bulkAction.createJobQueue(scheduler.getJobId(), LocalDateTime.now(), JobStatus.Skip, "Job %s skip, already in queue.", true);
                                this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s skip, already in queue.", scheduler.getJobId()));
                                Optional<SourceJob> sourceJobForSkipMail = this.transactionService.findByJobId(scheduler.getJobId());
                                if (sourceJobForSkipMail.isPresent() && sourceJobForSkipMail.get().isSkipJob()) {
                                    this.emailMessagesFactory.sendSourceJobEmail(SourceJobQueueDto.forEmailNotification(jobQueue), JobStatus.Skip);
                                }
                            } else {
                                this.bulkAction.changeJobStatus(scheduler.getJobId(), JobStatus.Queue);
                                jobQueue = this.bulkAction.createJobQueue(scheduler.getJobId(), LocalDateTime.now(), JobStatus.Queue, "Job %s now in the queue.", false);
                                this.bulkAction.changeJobLastJobRun(scheduler.getJobId(), jobQueue.getStartTime());
                                this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s now in the queue.", scheduler.getJobId()));
                            }

                            this.bulkAction.updateNextScheduler(scheduler);
                            this.bulkAction.sendJobStatusNotification(scheduler.getJobId());
                        } catch (Exception ex) {
                            logger.error("Error in addJobInQueue: {}.", ExceptionUtil.getRootCauseMessage(ex));
                        }
                });
                return;
            }
            logger.info("addJobInQueue --> NO scheduler is due at this timestamp");
        } catch (Exception ex) {
            logger.error("Error in addJobInQueue: {}.", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    public void startJobInCurrentTimeSlot() {
        try {
            logger.info("runJobInCurrentTimeSlot --> FETCH JobQueue of current day STARTED ");
            LookupData lookupData = this.transactionService.findByLookupType(ProcessUtil.QUEUE_FETCH_LIMIT);
            List<JobQueue> jobQueues = this.transactionService.findAllJobForTodayWithLimit(Long.valueOf(lookupData.getLookupValue()));
            logger.info("runJobInCurrentTimeSlot --> FETCHED JobQueue of current day: size {} ", jobQueues.size());
            if (!jobQueues.isEmpty()) {
                jobQueues.forEach(jobQueue -> {
                    Optional<SourceJob> sourceJob = this.transactionService.findByJobIdAndJobStatus(jobQueue.getJobId(), Status.Active);
                    try {
                        Thread.sleep(100);
                        if (sourceJob.isPresent()) {
                            this.pushMessageToQueue(sourceJob.get(), jobQueue);
                        } else {
                            this.changeStatusForLastJob(jobQueue, "Job %s failed in the queue because the main job is deleted or inactive.");
                        }
                    } catch (Exception ex) {
                        logger.error("Error in runJobInCurrentTimeSlot: {}.", ExceptionUtil.getRootCauseMessage(ex));
                    }
                });
                return;
            }
            logger.info("runJobInCurrentTimeSlot --> NO scheduler is set for this timestamp");
        } catch (Exception ex) {
            logger.error("Error in runJobInCurrentTimeSlot: {}.", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    private void pushMessageToQueue(SourceJob sourceJob, JobQueue jobQueue) throws Exception {
        SourceTask sourceTask = sourceJob.getTaskDetail();
        if (!isNull(sourceTask.getSourceTaskType())) {
            try {
                SourceTaskType sourceTaskType = sourceTask.getSourceTaskType();
                if (sourceTaskType.getStatus().equals(Status.Active)) {
                    String queueTopicPartition = sourceTaskType.getQueueTopicPartition();
                    Optional<KafkaTopicPartitionUtil.Parsed> parsed = KafkaTopicPartitionUtil.parse(queueTopicPartition);
                    if (parsed.isPresent()) {
                        String topic = parsed.get().getTopic();
                        String partition = parsed.get().getPartition();

                        String key = UUID.randomUUID().toString();
                        String payload = this.getSourceJobDetail(sourceJob, jobQueue);
                        try {

                            KafkaTemplate<String, String> template = this.kafkaTemplateProvider.getTemplate(
                                this.kafkaConnectionResolver.resolve(sourceJob.getTenantId(), sourceTaskType.getSourceTaskTypeId()));
                            if (partition.contains(ProcessUtil.START)) {
                                template.send(topic, key, payload)
                                    .addCallback(
                                        result -> this.handleSendSuccess(result, payload, sourceJob, jobQueue),
                                        ex -> this.handleSendFailure(ex, payload, sourceJob, jobQueue)
                                    );
                            } else {
                                template.send(topic, Integer.valueOf(partition), key, payload)
                                    .addCallback(
                                        result -> this.handleSendSuccess(result, payload, sourceJob, jobQueue),
                                        ex -> this.handleSendFailure(ex, payload, sourceJob, jobQueue)
                                    );
                            }
                        } catch (Exception ex) {
                            logger.error("Unexpected exception while sending message=[{}]: {}", payload, ex.getMessage());
                            handleSendFailure(ex, payload, sourceJob, jobQueue);
                        }
                        return;
                    }
                    logger.error("Regex does not match.");
                    this.changeStatusForLastJob(jobQueue, "Broker configuration is invalid for job %s: " + queueTopicPartition);
                    return;
                }
                this.changeStatusForLastJob(jobQueue, "Broker is not active for job %s.");
            } catch (Exception ex) {
                this.changeStatusForLastJob(jobQueue, "Broker is not active for job %s.");
                logger.error("Error in pushMessageToQueue: {}.", ExceptionUtil.getRootCauseMessage(ex));
            }
        }
    }

    private void handleSendSuccess(SendResult<String, String> result, String payload, SourceJob sourceJob, JobQueue jobQueue) {
        long offset = result.getRecordMetadata().offset();
        logger.info("Sent message=[{}] with offset=[{}]", payload, offset);

        jobQueue.setJobSend(true);
        jobQueue.setJobStatus(JobStatus.Start);
        jobQueue.setJobStatusMessage("Sent message=[" + payload + "] with offset=[" + offset + "]");
        this.transactionService.updateJobQueue(jobQueue);
        this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Start);

        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s sent message=[%s] with offset=[%s]", sourceJob.getJobId(), payload, offset));
        this.bulkAction.sendJobStatusNotification(jobQueue.getJobId());
    }

    private void handleSendFailure(Throwable ex, String payload, SourceJob sourceJob, JobQueue jobQueue) {
        logger.error("Unable to send message=[{}] due to: {}", payload, ex.getMessage());

        jobQueue.setJobSend(false);
        jobQueue.setJobStatusMessage("Unable to send message=[" + payload + "] due to: " + ex.getMessage());

        this.changeStatusForLastJob(jobQueue, String.format("Job %s unable to send message=[%s] due to: %s", sourceJob.getJobId(), payload, ex.getMessage()));
    }

    private void changeStatusForLastJob(JobQueue jobQueue, String message) {
        String formattedMessage = String.format(message, jobQueue.getJobId());
        this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Failed);
        this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), JobStatus.Failed, formattedMessage);
        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), formattedMessage);
        this.bulkAction.changeJobQueueEndDate(jobQueue.getJobQueueId(), LocalDateTime.now());
        this.bulkAction.sendJobStatusNotification(jobQueue.getJobId());
        Optional<SourceJob> sourceJobForFailMail = this.transactionService.findByJobId(jobQueue.getJobId());
        if (sourceJobForFailMail.isPresent() && sourceJobForFailMail.get().isFailJob()) {
            this.emailMessagesFactory.sendSourceJobEmail(SourceJobQueueDto.forEmailNotification(jobQueue), JobStatus.Failed);
        }
    }

    private String getSourceJobDetail(SourceJob sourceJob, JobQueue jobQueue) {
        JobPayloadDTO dto = new JobPayloadDTO();
        dto.setJobQueueId(jobQueue.getJobQueueId());
        dto.setJobId(jobQueue.getJobId());
        if (!ProcessUtil.isNull(sourceJob.getTaskDetail())) {
            Long homePageLookupId = ProcessUtil.parseLongOrNull(sourceJob.getTaskDetail().getHomePageId());
            if (homePageLookupId != null) {
                dto.setHomePageId(this.transactionService.findLookupValueByLookupId(homePageLookupId));
            }
            Long pipelineLookupId = ProcessUtil.parseLongOrNull(sourceJob.getTaskDetail().getPipelineId());
            if (pipelineLookupId != null) {
                dto.setPipelineId(this.transactionService.findLookupValueByLookupId(pipelineLookupId));
            }
            dto.setTaskPayload(sourceJob.getTaskDetail().getTaskPayload());
        }
        dto.setPriority(sourceJob.getPriority());
        return dto.toString();
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}