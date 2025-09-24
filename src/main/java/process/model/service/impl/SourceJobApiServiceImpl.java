package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import process.engine.ProducerBulkEngine;
import process.model.dto.*;
import process.model.enums.Execution;
import process.model.enums.Frequency;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.*;
import process.model.repository.*;
import process.model.service.SourceJobApiService;
import process.util.ProcessTimeUtil;
import process.util.ProcessUtil;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 */
@Service
public class SourceJobApiServiceImpl implements SourceJobApiService {

    private Logger logger = LoggerFactory.getLogger(SourceJobApiServiceImpl.class);

    private SourceJobRepository sourceJobRepository;
    private SchedulerRepository schedulerRepository;
    private SourceTaskRepository sourceTaskRepository;
    private JobAuditLogRepository jobAuditLogRepository;
    private JobQueueRepository jobQueueRepository;
    private LookupDataRepository lookupDataRepository;
    private ProducerBulkEngine producerBulkEngine;

    public SourceJobApiServiceImpl(SourceJobRepository sourceJobRepository,
        SchedulerRepository schedulerRepository,
        SourceTaskRepository sourceTaskRepository,
        JobAuditLogRepository jobAuditLogRepository,
        JobQueueRepository jobQueueRepository,
        LookupDataRepository lookupDataRepository,
        ProducerBulkEngine producerBulkEngine) {
        this.sourceJobRepository = sourceJobRepository;
        this.schedulerRepository = schedulerRepository;
        this.sourceTaskRepository = sourceTaskRepository;
        this.jobAuditLogRepository = jobAuditLogRepository;
        this.jobQueueRepository = jobQueueRepository;
        this.lookupDataRepository = lookupDataRepository;
        this.producerBulkEngine = producerBulkEngine;
    }

    /**
     * Method use to add the source job
     * @param sourceJobDto
     * @return ResponseDto
     * */
    @Override
    public ResponseDto addSourceJob(SourceJobDto sourceJobDto) throws Exception {
        if (ProcessUtil.isNull(sourceJobDto.getJobName())) {
            return new ResponseDto(ERROR, "SourceJob jobName missing.");
        } else if (ProcessUtil.isNull(sourceJobDto.getTaskDetail())) {
            return new ResponseDto(ERROR, "SourceJob taskDetail missing.");
        } else if (ProcessUtil.isNull(sourceJobDto.getTaskDetail().getTaskDetailId())) {
            return new ResponseDto(ERROR, "SourceJob taskDetailId missing.");
        }
        // validation for scheduler list -> if any missing then
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobName(sourceJobDto.getJobName());
        sourceJob.setTaskDetail(this.sourceTaskRepository.findById(
             sourceJobDto.getTaskDetail().getTaskDetailId()).get());
        sourceJob.setJobStatus(Status.Active);
        sourceJob.setExecution(sourceJobDto.getExecution());
        sourceJob.setPriority(sourceJobDto.getPriority());
        sourceJob.setCompleteJob(sourceJobDto.isCompleteJob());
        sourceJob.setFailJob(sourceJobDto.isFailJob());
        sourceJob.setSkipJob(sourceJobDto.isSkipJob());
        this.sourceJobRepository.saveAndFlush(sourceJob);
        if (!ProcessUtil.isNull(sourceJobDto.getSchedulers()) && !sourceJobDto.getSchedulers().isEmpty()) {
            sourceJobDto.getSchedulers()
                .forEach(schedulerDto -> {
                    Scheduler scheduler = new Scheduler();
                    scheduler.setStartDate(schedulerDto.getStartDate());
                    if (!StringUtils.isEmpty(schedulerDto.getEndDate())) {
                        scheduler.setEndDate(schedulerDto.getEndDate());
                    }
                    scheduler.setStartTime(schedulerDto.getStartTime());
                    scheduler.setFrequency(schedulerDto.getFrequency());
                    if (!StringUtils.isEmpty(schedulerDto.getRecurrence())) {
                        scheduler.setRecurrence(schedulerDto.getRecurrence());
                    }
                    scheduler.setRecurrenceTime(ProcessTimeUtil.getRecurrenceTime(
                        schedulerDto.getStartDate(), schedulerDto.getStartTime().toString()));
                    scheduler.setJobId(sourceJob.getJobId());
                    this.schedulerRepository.save(scheduler);
                });
        }
        return new ResponseDto(SUCCESS, String.format("Job save with jobId %d.", sourceJob.getJobId()));
    }

    /**
     * Method use to update the source job
     * @param sourceJobDto
     * @return ResponseDto
     * */
    @Override
    public ResponseDto updateSourceJob(SourceJobDto sourceJobDto) throws Exception {
        if (ProcessUtil.isNull(sourceJobDto.getJobId())) {
            return new ResponseDto(ERROR, "SourceJob job-id missing.");
        } else if (ProcessUtil.isNull(sourceJobDto.getJobName())) {
            return new ResponseDto(ERROR, "SourceJob jobName missing.");
        } else if (ProcessUtil.isNull(sourceJobDto.getTaskDetail())) {
            return new ResponseDto(ERROR, "SourceJob taskDetail missing.");
        } else if (ProcessUtil.isNull(sourceJobDto.getTaskDetail().getTaskDetailId())) {
            return new ResponseDto(ERROR, "SourceJob taskDetailId missing.");
        }
        Optional<SourceJob> sourceJob = this.sourceJobRepository.findById(sourceJobDto.getJobId());
        if (sourceJob.isPresent()) {
            sourceJob.get().setJobName(sourceJobDto.getJobName());
            // check source active then allow to link
            Optional<SourceTask> sourceTask = this.sourceTaskRepository.findByTaskDetailIdAndTaskStatus(
                 sourceJobDto.getTaskDetail().getTaskDetailId(), Status.Active);
            if (sourceTask.isPresent()) {
                sourceJob.get().setTaskDetail(sourceTask.get());
            } else {
                return new ResponseDto(ERROR, "Selected sourceTask not active.");
            }
            if (!ProcessUtil.isNull(sourceJobDto.getJobStatus())) {
                sourceJob.get().setJobStatus(sourceJobDto.getJobStatus());
            }
            if (!ProcessUtil.isNull(sourceJobDto.getExecution())) {
                sourceJob.get().setExecution(sourceJobDto.getExecution());
            }
            if (!ProcessUtil.isNull(sourceJobDto.getPriority())) {
                sourceJob.get().setPriority(sourceJobDto.getPriority());
            }
            sourceJob.get().setCompleteJob(sourceJobDto.isCompleteJob());
            sourceJob.get().setFailJob(sourceJobDto.isFailJob());
            sourceJob.get().setSkipJob(sourceJobDto.isSkipJob());
            this.sourceJobRepository.saveAndFlush(sourceJob.get());
            if (!ProcessUtil.isNull(sourceJobDto.getSchedulers()) && !sourceJobDto.getSchedulers().isEmpty()) {
                sourceJobDto.getSchedulers()
                    .forEach(schedulerDto -> {
                        Optional<Scheduler> scheduler = this.schedulerRepository.findSchedulerByJobId(sourceJobDto.getJobId());
                        if (scheduler.isPresent()) {
                            scheduler.get().setStartDate(schedulerDto.getStartDate());
                            if (!StringUtils.isEmpty(schedulerDto.getEndDate())) {
                                scheduler.get().setEndDate(schedulerDto.getEndDate());
                            }
                            scheduler.get().setStartTime(schedulerDto.getStartTime());
                            scheduler.get().setFrequency(schedulerDto.getFrequency());
                            if (!StringUtils.isEmpty(schedulerDto.getRecurrence())) {
                                scheduler.get().setRecurrence(schedulerDto.getRecurrence());
                            }
                            scheduler.get().setRecurrenceTime(ProcessTimeUtil.getRecurrenceTime(
                                schedulerDto.getStartDate(), schedulerDto.getStartTime().toString()));
                            scheduler.get().setJobId(sourceJob.get().getJobId());
                            this.schedulerRepository.save(scheduler.get());
                        }
                    });
            }
            return new ResponseDto(SUCCESS, String.format("Job save with jobId %d.", sourceJobDto.getJobId()));
        }
        return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", sourceJobDto.getJobId()));
    }

    /**
     * Method use to delete teh source job
     * @param sourceJobDto
     * @return ResponseDto
     * */
    @Override
    public ResponseDto deleteSourceJob(SourceJobDto sourceJobDto) throws Exception {
        if (ProcessUtil.isNull(sourceJobDto.getJobId())) {
            return new ResponseDto(ERROR, "SourceJob jobId missing.");
        }
        Optional<SourceJob> sourceJob = this.sourceJobRepository.findById(sourceJobDto.getJobId());
        if (sourceJob.isPresent()) {
            sourceJob.get().setJobStatus(Status.Delete);
            this.sourceJobRepository.save(sourceJob.get());
            return new ResponseDto(SUCCESS, String.format("SourceJob successfully update with %d.", sourceJobDto.getJobId()));
        }
        return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", sourceJobDto.getJobId()));
    }

    /**
     * Method use to run the source job
     * @param sourceJobDto
     * @return ResponseDto
     * */
    @Override
    public ResponseDto runSourceJob(SourceJobDto sourceJobDto) throws Exception {
        if (ProcessUtil.isNull(sourceJobDto.getJobId())) {
            return new ResponseDto(ERROR, "SourceJob jobId missing.");
        }
        Optional<SourceJob> sourceJob = this.sourceJobRepository.findByJobIdAndJobStatus(sourceJobDto.getJobId(), Status.Active);
        if (!sourceJob.isPresent()) {
            return new ResponseDto(ERROR, "SourceJob not found with jobId.");
        } else if (!ProcessUtil.isNull(sourceJob.get().getJobRunningStatus()) && (sourceJob.get().getJobRunningStatus().equals(JobStatus.Queue) ||
            sourceJob.get().getJobRunningStatus().equals(JobStatus.Running))) {
            return new ResponseDto(ERROR, "SourceJob can't be run if its in ('Queue', 'Running') state.");
        }
        this.producerBulkEngine.addManualJobInQueue(sourceJob.get());
        sourceJob = this.sourceJobRepository.findByJobIdAndJobStatus(sourceJobDto.getJobId(), Status.Active);
        return new ResponseDto(SUCCESS, "SourceJob job successfully added into queue.", sourceJob);
    }

    /**
     * Method use to run the source job
     * @param sourceJobDto
     * @return ResponseDto
     * */
    @Override
    public ResponseDto skipNextSourceJob(SourceJobDto sourceJobDto) throws Exception {
        if (ProcessUtil.isNull(sourceJobDto.getJobId())) {
            return new ResponseDto(ERROR, "SourceJob jobId missing.");
        }
        Optional<SourceJob> sourceJob = this.sourceJobRepository.findByJobIdAndJobStatus(sourceJobDto.getJobId(), Status.Active);
        if (!sourceJob.isPresent()) {
            return new ResponseDto(ERROR, "SourceJob not found with jobId.");
        } else if (!ProcessUtil.isNull(sourceJob.get().getJobRunningStatus()) && (sourceJob.get().getJobRunningStatus().equals(JobStatus.Queue) ||
            sourceJob.get().getJobRunningStatus().equals(JobStatus.Running))) {
            return new ResponseDto(ERROR, "SourceJob can't be run if its in ('Queue', 'Running') state.");
        } else if (!sourceJob.get().getExecution().equals(Execution.Auto)) {
            return new ResponseDto(ERROR, "SourceJob skip only work with 'auto' source job.");
        }
        Scheduler scheduler = this.schedulerRepository.findSchedulerByJobId(sourceJob.get().getJobId()).get();
        LocalDateTime nextJobRun = this.getLocalDateTime(scheduler);
        if (!ProcessUtil.isNull(scheduler.getEndDate())) {
            LocalDateTime schedulerEndDateTime = scheduler.getEndDate().atTime(scheduler.getStartTime());
            if (!ProcessUtil.isNull(nextJobRun) && (schedulerEndDateTime.equals(nextJobRun) || schedulerEndDateTime.isAfter(nextJobRun))) {
                this.producerBulkEngine.skipManualJobInQueue(scheduler);
                scheduler.setRecurrenceTime(nextJobRun);
                this.schedulerRepository.save(scheduler);
                return new ResponseDto(SUCCESS, "SourceJob skip successfully.", scheduler);
            }
        } else if (!ProcessUtil.isNull(nextJobRun)) {
            this.producerBulkEngine.skipManualJobInQueue(scheduler);
            scheduler.setRecurrenceTime(nextJobRun);
            this.schedulerRepository.save(scheduler);
            return new ResponseDto(SUCCESS, "SourceJob skip successfully.", scheduler);
        }
        return new ResponseDto(ERROR, "No more flight skip.");
    }

    /**
     * Method use to find the source job audit log
     * @param jobQueueId
     * @param jobId
     * @return ResponseDto
     * */
    @Override
    public ResponseDto findSourceJobAuditLog(Long jobQueueId, Long jobId) throws Exception {
        if (jobQueueId == null) {
            return new ResponseDto(ERROR, "JobQueueId missing.");
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("auditLogs", jobAuditLogRepository.findAllByJobQueueIdV1(jobQueueId));
        sourceJobRepository.findById(jobId).ifPresent(sourceJob -> {
            SourceJobDto sourceJobDto = mapSourceJobToDto(sourceJob);
            schedulerRepository.findSchedulerByJobId(jobId).ifPresent(s -> sourceJobDto.setScheduler(getSchedulerDto(s)));
            payload.put("sourceJob", sourceJobDto);
        });
        jobQueueRepository.findById(jobQueueId).ifPresent(queue -> payload.put("sourceJobQueue", getSourceJobQueueDto(queue)));
        return new ResponseDto(SUCCESS, "SourceJob skip successfully.", payload);
    }

    /**
     * Method use to fetch source job detail with source job id
     * @param jobId
     * @return ResponseDto
     * */
    @Override
    public ResponseDto fetchSourceJobDetailWithSourceJobId(Long jobId) {
        return sourceJobRepository.findById(jobId)
            .map(sourceJob -> {
                SourceJobDto dto = mapSourceJobToDto(sourceJob);
                schedulerRepository.findSchedulerByJobId(jobId).ifPresent(s -> dto.setScheduler(getSchedulerDto(s)));
                return new ResponseDto(SUCCESS, String.format("SourceJob found with %d.", jobId), dto);
            }).orElseGet(() -> new ResponseDto(ERROR, String.format("SourceJob not found with %d.", jobId)));
    }

    /**
     * Method use the list source job
     * @return ResponseDto
     * */
    @Override
    public ResponseDto listSourceJob() throws Exception {
        List<SourceJobDto> sourceJobDtoList = sourceJobRepository.findAll(Sort.by(Sort.Direction.ASC, "jobId"))
            .stream()
            .map(job -> {
                SourceJobDto dto = mapSourceJobToDto(job);
                schedulerRepository.findSchedulerByJobId(job.getJobId()).ifPresent(s -> dto.setScheduler(getSchedulerDto(s)));
                dto.setTabActive(jobQueueRepository.getCountForJobByJobId(job.getJobId()) > 0);
                return dto;
            }).collect(Collectors.toList());
        return new ResponseDto(SUCCESS, "Fetch source jobs.", sourceJobDtoList);
    }

    /**
     * Method use to map the source job to dto
     * @param sourceJob
     * @return SourceJobDto
     */
    private SourceJobDto mapSourceJobToDto(SourceJob sourceJob) {
        SourceJobDto dto = new SourceJobDto();
        dto.setJobId(sourceJob.getJobId());
        dto.setJobStatus(sourceJob.getJobStatus());
        dto.setJobRunningStatus(sourceJob.getJobRunningStatus());
        dto.setLastJobRun(sourceJob.getLastJobRun());
        dto.setJobName(sourceJob.getJobName());
        dto.setDateCreated(sourceJob.getDateCreated());
        dto.setPriority(sourceJob.getPriority());
        dto.setExecution(sourceJob.getExecution());
        dto.setCompleteJob(sourceJob.isCompleteJob());
        dto.setFailJob(sourceJob.isFailJob());
        dto.setSkipJob(sourceJob.isSkipJob());
        if (!ProcessUtil.isNull(sourceJob.getTaskDetail())) {
            dto.setTaskDetail(mapSourceTaskToDto(sourceJob.getTaskDetail()));
        }
        return dto;
    }

    /**
     * Method use to map the source task to dto
     * @param sourceTask
     * @return SourceTaskDto
     */
    private SourceTaskDto mapSourceTaskToDto(SourceTask sourceTask) {
        SourceTaskDto dto = new SourceTaskDto();
        dto.setTaskDetailId(sourceTask.getTaskDetailId());
        dto.setTaskName(sourceTask.getTaskName());
        dto.setTaskStatus(sourceTask.getTaskStatus());
        dto.setTaskPayload(sourceTask.getTaskPayload());
        if (!ProcessUtil.isNull(sourceTask.getHomePageId())) {
            dto.setHomePageId(lookupDataRepository.findById(Long.valueOf(sourceTask.getHomePageId()))
                .map(ld -> ld.getLookupType()).orElse(null));
        }
        if (!ProcessUtil.isNull(sourceTask.getPipelineId())) {
            dto.setPipelineId(lookupDataRepository.findById(Long.valueOf(sourceTask.getPipelineId()))
               .map(ld -> ld.getLookupType()).orElse(null));
        }
        if (!ProcessUtil.isNull(sourceTask.getSourceTaskType())) {
            dto.setSourceTaskType(getSourceTaskTypeDto(sourceTask.getSourceTaskType()));
        }
        return dto;
    }

    /**
     * Method use to get the source task type dto
     * @param sourceTaskType
     * @return SourceTaskTypeDto
     * */
    private SourceTaskTypeDto getSourceTaskTypeDto(SourceTaskType sourceTaskType) {
        SourceTaskTypeDto sourceTaskTypeDto = new SourceTaskTypeDto();
        sourceTaskTypeDto.setSourceTaskTypeId(sourceTaskType.getSourceTaskTypeId());
        sourceTaskTypeDto.setServiceName(sourceTaskType.getServiceName());
        sourceTaskTypeDto.setQueueTopicPartition(sourceTaskType.getQueueTopicPartition());
        sourceTaskTypeDto.setDescription(sourceTaskType.getDescription());
        sourceTaskTypeDto.setSchemaPayload(sourceTaskType.getSchemaPayload());
        sourceTaskTypeDto.setSchemaRegister(sourceTaskType.isSchemaRegister());
        return sourceTaskTypeDto;
    }

    /***
     * Method use to get the scheduler dto
     * @param scheduler
     * @return SchedulerDto
     */
    private SchedulerDto getSchedulerDto(Scheduler scheduler) {
        SchedulerDto schedulerDto = new SchedulerDto();
        schedulerDto.setSchedulerId(scheduler.getSchedulerId());
        schedulerDto.setStartDate(scheduler.getStartDate());
        schedulerDto.setEndDate(scheduler.getEndDate());
        schedulerDto.setStartTime(scheduler.getStartTime());
        schedulerDto.setFrequency(scheduler.getFrequency());
        schedulerDto.setRecurrence(scheduler.getRecurrence());
        schedulerDto.setRecurrenceTime(scheduler.getRecurrenceTime());
        return schedulerDto;
    }

    /**
     * Method use to get the source job queue dto
     * @param jobQueue
     * @return SourceJobQueueDto
     * */
    private SourceJobQueueDto getSourceJobQueueDto(JobQueue jobQueue) {
        SourceJobQueueDto sourceJobQueueDto = new SourceJobQueueDto();
        sourceJobQueueDto.setJobQueueId(jobQueue.getJobQueueId());
        sourceJobQueueDto.setDateCreated(jobQueue.getDateCreated());
        sourceJobQueueDto.setEndTime(jobQueue.getEndTime());
        sourceJobQueueDto.setJobId(jobQueue.getJobId());
        sourceJobQueueDto.setJobStatus(jobQueue.getJobStatus());
        sourceJobQueueDto.setJobStatusMessage(jobQueue.getJobStatusMessage());
        sourceJobQueueDto.setSkipTime(jobQueue.getSkipTime());
        sourceJobQueueDto.setStartTime(jobQueue.getStartTime());
        return sourceJobQueueDto;
    }

    /**
     * Method use to get the local datetime for next flight
     * @param scheduler
     * @return LocalDateTime
     * */
    private LocalDateTime getLocalDateTime(Scheduler scheduler) {
        LocalDateTime nextJobRun = null;
        if (scheduler.getFrequency().equals(Frequency.Mint.name()) && !ProcessUtil.isNull(scheduler.getRecurrence())) {
            nextJobRun = scheduler.getRecurrenceTime().plusMinutes(Long.parseLong(scheduler.getRecurrence()));
        } else if (scheduler.getFrequency().equals(Frequency.Hr.name()) && !ProcessUtil.isNull(scheduler.getRecurrence())) {
            nextJobRun = scheduler.getRecurrenceTime().plusHours(Long.parseLong(scheduler.getRecurrence()));
        } else if (scheduler.getFrequency().equals(Frequency.Daily.name()) && !ProcessUtil.isNull(scheduler.getRecurrence())) {
            nextJobRun = scheduler.getRecurrenceTime().plusDays(Long.parseLong(scheduler.getRecurrence()));
        } else if (scheduler.getFrequency().equals(Frequency.Weekly.name()) && !ProcessUtil.isNull(scheduler.getRecurrence())) {
            nextJobRun = scheduler.getRecurrenceTime().plusWeeks(Long.parseLong(scheduler.getRecurrence()));
        } else if (scheduler.getFrequency().equals(Frequency.Monthly.name()) && !ProcessUtil.isNull(scheduler.getRecurrence())) {
            nextJobRun = scheduler.getRecurrenceTime().plusMonths(Long.parseLong(scheduler.getRecurrence()));
        }
        return nextJobRun;
    }

}
