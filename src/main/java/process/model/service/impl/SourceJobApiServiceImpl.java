package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import process.engine.ProducerBulkEngine;
import process.model.dto.*;
import process.model.enums.Execution;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.Scheduler;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTaskType;
import process.model.pojo.SourceTask;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.service.SourceJobApiService;
import process.util.ProcessTimeUtil;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 */
@Service
public class SourceJobApiServiceImpl implements SourceJobApiService {

    private Logger logger = LoggerFactory.getLogger(SourceJobApiServiceImpl.class);

    @Autowired
    private SourceJobRepository sourceJobRepository;
    @Autowired
    private SchedulerRepository schedulerRepository;
    @Autowired
    private SourceTaskRepository sourceTaskRepository;
    @Autowired
    private ProducerBulkEngine producerBulkEngine;

    @Override
    public ResponseDto addSourceJob(SourceJobDto tempSourceJob) throws Exception {
        if (isNull(tempSourceJob.getJobName())) {
            return new ResponseDto(ERROR, "SourceJob jobName missing.");
        } else if (isNull(tempSourceJob.getTaskDetail())) {
            return new ResponseDto(ERROR, "SourceJob taskDetail missing.");
        } else if (isNull(tempSourceJob.getTaskDetail().getTaskDetailId())) {
            return new ResponseDto(ERROR, "SourceJob taskDetailId missing.");
        }
        // validation for scheduler list -> if any missing then
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobName(tempSourceJob.getJobName());
        sourceJob.setTaskDetail(this.sourceTaskRepository
            .findById(tempSourceJob.getTaskDetail().getTaskDetailId()).get());
        sourceJob.setJobStatus(Status.Active);
        sourceJob.setExecution(tempSourceJob.getExecution());
        sourceJob.setPriority(tempSourceJob.getPriority());
        sourceJob.setCompleteJob(tempSourceJob.isCompleteJob());
        sourceJob.setFailJob(tempSourceJob.isFailJob());
        sourceJob.setSkipJob(tempSourceJob.isSkipJob());
        this.sourceJobRepository.saveAndFlush(sourceJob);
        if (tempSourceJob.getSchedulers() != null && tempSourceJob.getSchedulers().size() > 0) {
            tempSourceJob.getSchedulers().stream()
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

    @Override
    public ResponseDto updateSourceJob(SourceJobDto tempSourceJob) throws Exception {
        if (isNull(tempSourceJob.getJobId())) {
            return new ResponseDto(ERROR, "SourceJob job-id missing.");
        } else if (isNull(tempSourceJob.getJobName())) {
            return new ResponseDto(ERROR, "SourceJob jobName missing.");
        } else if (isNull(tempSourceJob.getTaskDetail())) {
            return new ResponseDto(ERROR, "SourceJob taskDetail missing.");
        } else if (isNull(tempSourceJob.getTaskDetail().getTaskDetailId())) {
            return new ResponseDto(ERROR, "SourceJob taskDetailId missing.");
        }
        Optional<SourceJob> sourceJob = this.sourceJobRepository.findById(tempSourceJob.getJobId());
        if (sourceJob.isPresent()) {
            sourceJob.get().setJobName(tempSourceJob.getJobName());
            // check source active then allow to link
            Optional<SourceTask> sourceTask = this.sourceTaskRepository.findByTaskDetailIdAndTaskStatus(
                tempSourceJob.getTaskDetail().getTaskDetailId(), Status.Active);
            if (sourceTask.isPresent()) {
                sourceJob.get().setTaskDetail(sourceTask.get());
            } else {
                return new ResponseDto(ERROR, "Selected sourceTask not active.");
            }
            if (!isNull(tempSourceJob.getJobStatus())) {
                sourceJob.get().setJobStatus(tempSourceJob.getJobStatus());
            }
            if (!isNull(tempSourceJob.getExecution())) {
                sourceJob.get().setExecution(tempSourceJob.getExecution());
            }
            if (!isNull(tempSourceJob.getPriority())) {
                sourceJob.get().setPriority(tempSourceJob.getPriority());
            }
            sourceJob.get().setCompleteJob(tempSourceJob.isCompleteJob());
            sourceJob.get().setFailJob(tempSourceJob.isFailJob());
            sourceJob.get().setSkipJob(tempSourceJob.isSkipJob());
            this.sourceJobRepository.saveAndFlush(sourceJob.get());
            if (!isNull(tempSourceJob.getSchedulers()) && tempSourceJob.getSchedulers().size() > 0) {
                tempSourceJob.getSchedulers().stream()
                    .forEach(schedulerDto -> {
                        Optional<Scheduler> scheduler = this.schedulerRepository.findSchedulerByJobId(tempSourceJob.getJobId());
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
            return new ResponseDto(SUCCESS, String.format("Job save with jobId %d.", tempSourceJob.getJobId()));
        }
        return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", tempSourceJob.getJobId()));
    }

    @Override
    public ResponseDto deleteSourceJob(SourceJobDto tempSourceJob) throws Exception {
        if (isNull(tempSourceJob.getJobId())) {
            return new ResponseDto(ERROR, "SourceJob jobId missing.");
        }
        Optional<SourceJob> sourceJob = this.sourceJobRepository.findById(tempSourceJob.getJobId());
        if (sourceJob.isPresent()) {
            sourceJob.get().setJobStatus(Status.Delete);
            this.sourceJobRepository.save(sourceJob.get());
            return new ResponseDto(SUCCESS, String.format("SourceJob successfully update with %d.",
                tempSourceJob.getJobId()));
        }
        return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", tempSourceJob.getJobId()));
    }

    @Override
    public ResponseDto runSourceJob(SourceJobDto tempSourceJob) throws Exception {
        if (isNull(tempSourceJob.getJobId())) {
            return new ResponseDto(ERROR, "SourceJob jobId missing.");
        }
        Optional<SourceJob> sourceJob = this.sourceJobRepository.findByJobIdAndJobStatus(tempSourceJob.getJobId(), Status.Active);
        if (!sourceJob.isPresent()) {
            return new ResponseDto(ERROR, "SourceJob not found with jobId.");
        } else if (!isNull(sourceJob.get().getJobRunningStatus()) && (sourceJob.get().getJobRunningStatus().equals(JobStatus.Queue) ||
            sourceJob.get().getJobRunningStatus().equals(JobStatus.Running))) {
            return new ResponseDto(ERROR, "SourceJob can't be run if its in ('Queue', 'Running') state.");
        }
        this.producerBulkEngine.addManualJobInQueue(sourceJob.get());
        return new ResponseDto(ERROR, "SourceJob job successfully added into queue.", tempSourceJob);
    }

    @Override
    public ResponseDto skipNextSourceJob(SourceJobDto tempSourceJob) throws Exception {
        if (isNull(tempSourceJob.getJobId())) {
            return new ResponseDto(ERROR, "SourceJob jobId missing.");
        }
        Optional<SourceJob> sourceJob = this.sourceJobRepository.findByJobIdAndJobStatus(tempSourceJob.getJobId(), Status.Active);
        if (!sourceJob.isPresent()) {
            return new ResponseDto(ERROR, "SourceJob not found with jobId.");
        } else if (!isNull(sourceJob.get().getJobRunningStatus()) && (sourceJob.get().getJobRunningStatus().equals(JobStatus.Queue) ||
            sourceJob.get().getJobRunningStatus().equals(JobStatus.Running))) {
            return new ResponseDto(ERROR, "SourceJob can't be run if its in ('Queue', 'Running') state.");
        } else if (!sourceJob.get().getExecution().equals(Execution.Auto)) {
            // check is the job type is auto or not
            return new ResponseDto(ERROR, "SourceJob skip only work with 'auto' source job.");
        }
        return null;
    }

    @Override
    public ResponseDto fetchSourceJobDetailWithSourceJobId(Long jobId) {
        Optional<SourceJob> sourceJob = this.sourceJobRepository.findById(jobId);
        if (sourceJob.isPresent()) {
            SourceJobDto sourceJobDto = new SourceJobDto();
            sourceJobDto.setJobId(sourceJob.get().getJobId());
            sourceJobDto.setJobStatus(sourceJob.get().getJobStatus());
            sourceJobDto.setLastJobRun(sourceJob.get().getLastJobRun());
            sourceJobDto.setJobName(sourceJob.get().getJobName());
            sourceJobDto.setDateCreated(sourceJob.get().getDateCreated());
            sourceJobDto.setPriority(sourceJob.get().getPriority());
            sourceJobDto.setExecution(sourceJob.get().getExecution());
            sourceJobDto.setCompleteJob(sourceJob.get().isCompleteJob());
            sourceJobDto.setFailJob(sourceJob.get().isFailJob());
            sourceJobDto.setSkipJob(sourceJob.get().isSkipJob());
            if (sourceJob.get().getTaskDetail() != null) {
                SourceTask sourceTask = sourceJob.get().getTaskDetail();
                SourceTaskDto sourceTaskDto = new SourceTaskDto();
                sourceTaskDto.setTaskDetailId(sourceTask.getTaskDetailId());
                sourceTaskDto.setTaskName(sourceTask.getTaskName());
                sourceTaskDto.setTaskStatus(sourceTask.getTaskStatus());
                sourceTaskDto.setTaskPayload(sourceTask.getTaskPayload());
                if (sourceTask.getSourceTaskType() != null) {
                    SourceTaskType sourceTaskType = sourceTask.getSourceTaskType();
                    SourceTaskTypeDto sourceTaskTypeDto = new SourceTaskTypeDto();
                    sourceTaskTypeDto.setSourceTaskTypeId(sourceTaskType.getSourceTaskTypeId());
                    sourceTaskTypeDto.setServiceName(sourceTaskType.getServiceName());
                    sourceTaskTypeDto.setQueueTopicPartition(sourceTaskType.getQueueTopicPartition());
                    sourceTaskTypeDto.setDescription(sourceTaskType.getDescription());
                    sourceTaskDto.setSourceTaskType(sourceTaskTypeDto);
                }
                sourceJobDto.setTaskDetail(sourceTaskDto);
            }
            Optional<Scheduler> scheduler = this.schedulerRepository.findSchedulerByJobId(jobId);
            if (scheduler.isPresent()) {
                SchedulerDto schedulerDto = new SchedulerDto();
                schedulerDto.setSchedulerId(scheduler.get().getSchedulerId());
                schedulerDto.setStartDate(scheduler.get().getStartDate());
                schedulerDto.setEndDate(scheduler.get().getEndDate());
                schedulerDto.setStartTime(scheduler.get().getStartTime());
                schedulerDto.setFrequency(scheduler.get().getFrequency());
                schedulerDto.setRecurrence(scheduler.get().getRecurrence());
                schedulerDto.setRecurrenceTime(scheduler.get().getRecurrenceTime());
                sourceJobDto.setScheduler(schedulerDto);
            }
            return new ResponseDto(SUCCESS, String.format("SourceJob found with %d.", jobId), sourceJobDto);
        }
        return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", jobId));
    }

    @Override
    public ResponseDto listSourceJob() throws Exception {
        List<SourceJobDto> sourceJobDtoList = new ArrayList<>();
        this.sourceJobRepository.findAll(Sort.by(Sort.Direction.ASC, "jobId"))
        .stream().forEach(sourceJob -> {
            SourceJobDto sourceJobDto = new SourceJobDto();
            sourceJobDto.setJobId(sourceJob.getJobId());
            sourceJobDto.setJobStatus(sourceJob.getJobStatus());
            sourceJobDto.setJobName(sourceJob.getJobName());
            sourceJobDto.setDateCreated(sourceJob.getDateCreated());
            sourceJobDto.setPriority(sourceJob.getPriority());
            sourceJobDto.setExecution(sourceJob.getExecution());
            sourceJobDto.setJobRunningStatus(sourceJob.getJobRunningStatus());
            sourceJobDto.setLastJobRun(sourceJob.getLastJobRun());
            if (sourceJob.getTaskDetail() != null) {
                SourceTask sourceTask = sourceJob.getTaskDetail();
                SourceTaskDto sourceTaskDto = new SourceTaskDto();
                sourceTaskDto.setTaskDetailId(sourceTask.getTaskDetailId());
                sourceTaskDto.setTaskName(sourceTask.getTaskName());
                sourceTaskDto.setTaskStatus(sourceTask.getTaskStatus());
                sourceTaskDto.setTaskPayload(sourceTask.getTaskPayload());
                if (sourceTask.getSourceTaskType() != null) {
                    SourceTaskType sourceTaskType = sourceTask.getSourceTaskType();
                    SourceTaskTypeDto sourceTaskTypeDto = new SourceTaskTypeDto();
                    sourceTaskTypeDto.setSourceTaskTypeId(sourceTaskType.getSourceTaskTypeId());
                    sourceTaskTypeDto.setServiceName(sourceTaskType.getServiceName());
                    sourceTaskTypeDto.setQueueTopicPartition(sourceTaskType.getQueueTopicPartition());
                    sourceTaskTypeDto.setDescription(sourceTaskType.getDescription());
                    sourceTaskTypeDto.setSchemaPayload(sourceTaskType.getSchemaPayload());
                    sourceTaskTypeDto.setSchemaRegister(sourceTaskType.isSchemaRegister());
                    sourceTaskDto.setSourceTaskType(sourceTaskTypeDto);
                }
                sourceJobDto.setTaskDetail(sourceTaskDto);
            }
            Optional<Scheduler> scheduler = this.schedulerRepository.findSchedulerByJobId(sourceJob.getJobId());
            if (scheduler.isPresent()) {
                SchedulerDto schedulerDto = new SchedulerDto();
                schedulerDto.setSchedulerId(scheduler.get().getSchedulerId());
                schedulerDto.setStartDate(scheduler.get().getStartDate());
                schedulerDto.setEndDate(scheduler.get().getEndDate());
                schedulerDto.setStartTime(scheduler.get().getStartTime());
                schedulerDto.setFrequency(scheduler.get().getFrequency());
                schedulerDto.setRecurrence(scheduler.get().getRecurrence());
                schedulerDto.setRecurrenceTime(scheduler.get().getRecurrenceTime());
                sourceJobDto.setScheduler(schedulerDto);
            }
            sourceJobDtoList.add(sourceJobDto);
        });
        return new ResponseDto(SUCCESS, String.format("Fetch Source Jobs."), sourceJobDtoList);
    }

    @Override
    public ResponseDto fetchRunningJobEvent(SourceJobDto tempSourceJob) throws Exception {
        return new ResponseDto(SUCCESS, String.format("Fetch Source Jobs."),
            this.sourceJobRepository.fetchRunningJobEvent(tempSourceJob.getJobIds()));
    }

}
