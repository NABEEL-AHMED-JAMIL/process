package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import process.model.dto.*;
import process.model.enums.Status;
import process.model.pojo.Scheduler;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTaskType;
import process.model.pojo.TaskDetail;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.TaskDetailRepository;
import process.model.service.SourceJobApiService;
import process.util.ProcessTimeUtil;
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
    private TaskDetailRepository taskDetailRepository;

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
        sourceJob.setTaskDetail(this.taskDetailRepository
            .findById(tempSourceJob.getTaskDetail().getTaskDetailId()).get());
        sourceJob.setJobStatus(Status.Active);
        sourceJob.setExecution(tempSourceJob.getExecution());
        sourceJob.setPriority(tempSourceJob.getPriority());
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
            sourceJob.get().setTaskDetail(this.taskDetailRepository
                .findById(tempSourceJob.getTaskDetail().getTaskDetailId()).get());
            if (!isNull(tempSourceJob.getJobStatus())) {
                sourceJob.get().setJobStatus(tempSourceJob.getJobStatus());
            }
            if (!isNull(tempSourceJob.getExecution())) {
                sourceJob.get().setExecution(tempSourceJob.getExecution());
            }
            if (!isNull(tempSourceJob.getPriority())) {
                sourceJob.get().setPriority(tempSourceJob.getPriority());
            }
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
    public ResponseDto fetchSourceJobDetailWithSourceJobId(Long jobDetailId) {
        Optional<SourceJob> sourceJob = this.sourceJobRepository.findById(jobDetailId);
        if (sourceJob.isPresent()) {
            SourceJobDto sourceJobDto = new SourceJobDto();
            sourceJobDto.setJobId(sourceJob.get().getJobId());
            sourceJobDto.setJobStatus(sourceJob.get().getJobStatus());
            sourceJobDto.setLastJobRun(sourceJob.get().getLastJobRun());
            sourceJobDto.setJobName(sourceJob.get().getJobName());
            sourceJobDto.setDateCreated(sourceJob.get().getDateCreated());
            sourceJobDto.setPriority(sourceJob.get().getPriority());
            sourceJobDto.setExecution(sourceJob.get().getExecution());
            if (sourceJob.get().getTaskDetail() != null) {
                TaskDetail taskDetail = sourceJob.get().getTaskDetail();
                TaskDetailDto taskDetailDto = new TaskDetailDto();
                taskDetailDto.setTaskDetailId(taskDetail.getTaskDetailId());
                taskDetailDto.setTaskName(taskDetail.getTaskName());
                taskDetailDto.setTaskStatus(taskDetail.getTaskStatus());
                taskDetailDto.setTaskPayload(taskDetail.getTaskPayload());
                if (taskDetail.getSourceTaskType() != null) {
                    SourceTaskType sourceTaskType = taskDetail.getSourceTaskType();
                    SourceTaskTypeDto sourceTaskTypeDto = new SourceTaskTypeDto();
                    sourceTaskTypeDto.setSourceTaskTypeId(sourceTaskType.getSourceTaskTypeId());
                    sourceTaskTypeDto.setServiceName(sourceTaskType.getServiceName());
                    sourceTaskTypeDto.setQueueTopicPartition(sourceTaskType.getQueueTopicPartition());
                    sourceTaskTypeDto.setDescription(sourceTaskType.getDescription());
                    taskDetailDto.setSourceTaskType(sourceTaskTypeDto);
                }
                sourceJobDto.setTaskDetail(taskDetailDto);
            }
            Optional<Scheduler> scheduler = this.schedulerRepository.findSchedulerByJobId(jobDetailId);
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
            return new ResponseDto(SUCCESS, String.format("SourceJob found with %d.", jobDetailId), sourceJobDto);
        }
        return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", jobDetailId));
    }

    @Override
    public ResponseDto listSourceJob() throws Exception {
        List<SourceJobDto> sourceJobDtoList = new ArrayList<>();
        this.sourceJobRepository.findAll()
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
                TaskDetail taskDetail = sourceJob.getTaskDetail();
                TaskDetailDto taskDetailDto = new TaskDetailDto();
                taskDetailDto.setTaskDetailId(taskDetail.getTaskDetailId());
                taskDetailDto.setTaskName(taskDetail.getTaskName());
                taskDetailDto.setTaskStatus(taskDetail.getTaskStatus());
                taskDetailDto.setTaskPayload(taskDetail.getTaskPayload());
                if (taskDetail.getSourceTaskType() != null) {
                    SourceTaskType sourceTaskType = taskDetail.getSourceTaskType();
                    SourceTaskTypeDto sourceTaskTypeDto = new SourceTaskTypeDto();
                    sourceTaskTypeDto.setSourceTaskTypeId(sourceTaskType.getSourceTaskTypeId());
                    sourceTaskTypeDto.setServiceName(sourceTaskType.getServiceName());
                    sourceTaskTypeDto.setQueueTopicPartition(sourceTaskType.getQueueTopicPartition());
                    sourceTaskTypeDto.setDescription(sourceTaskType.getDescription());
                    sourceTaskTypeDto.setSchemaPayload(sourceTaskType.getSchemaPayload());
                    sourceTaskTypeDto.setSchemaRegister(sourceTaskType.isSchemaRegister());
                    taskDetailDto.setSourceTaskType(sourceTaskTypeDto);
                }
                sourceJobDto.setTaskDetail(taskDetailDto);
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

    @Override
    public ResponseDto downloadListSourceJob(Long appUserId, String startDate, String endDate,
         String columnName, String order, Pageable paging, SearchTextDto searchTextDto) throws Exception {
        return null;
    }
}
