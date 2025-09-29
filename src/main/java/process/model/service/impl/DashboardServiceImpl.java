package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.model.dto.*;
import process.model.enums.JobStatus;
import process.model.pojo.Scheduler;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.repository.LookupDataRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.DashboardService;
import process.util.ProcessUtil;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 */
@Service
public class DashboardServiceImpl implements DashboardService {

    private Logger logger = LoggerFactory.getLogger(DashboardServiceImpl.class);

    private final QueryService queryService;
    private final SourceJobRepository sourceJobRepository;
    private final SchedulerRepository schedulerRepository;
    private final LookupDataRepository lookupDataRepository;

    public DashboardServiceImpl(QueryService queryService,
        SourceJobRepository sourceJobRepository,
        SchedulerRepository schedulerRepository,
        LookupDataRepository lookupDataRepository) {
        this.queryService = queryService;
        this.sourceJobRepository = sourceJobRepository;
        this.schedulerRepository = schedulerRepository;
        this.lookupDataRepository = lookupDataRepository;
    }

    /**
     * Method use to for get job status statistics
     * @return ResponseDto
     * */
    @Override
    public ResponseDto jobStatusStatistics() throws Exception {
        ResponseDto responseDto = new ResponseDto(SUCCESS, "No data found.", new ArrayList<>());
        List<Object[]> result = this.queryService.executeQuery(this.queryService.jobStatusStatistics());
        if (!ProcessUtil.isNull(result) && !result.isEmpty()) {
            List<JobStatusStatisticDto> jobStatusStatistic = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                jobStatusStatistic.add(new JobStatusStatisticDto(String.valueOf(obj[index]), Integer.valueOf(obj[++index].toString())));
            }
            responseDto = new ResponseDto(SUCCESS, "Data found.", jobStatusStatistic);
        }
        return responseDto;
    }

    /**
     * Method use to for get job running statistics
     * @return ResponseDto
     * */
    @Override
    public ResponseDto jobRunningStatistics() throws Exception {
        ResponseDto responseDto = new ResponseDto(SUCCESS, "No data found.", new ArrayList<>());
        List<Object[]> result = this.queryService.executeQuery(this.queryService.jobRunningStatistics());
        if (!ProcessUtil.isNull(result) && !result.isEmpty()) {
            List<JobStatusStatisticDto> jobStatusStatistic = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                jobStatusStatistic.add(new JobStatusStatisticDto(String.valueOf(obj[index]), Integer.valueOf(obj[++index].toString())));
            }
            responseDto = new ResponseDto(SUCCESS, "Data found.", jobStatusStatistic);
        }
        return responseDto;
    }

    /**
     * Method use to for get weekly running job statistics
     * @param startDate
     * @param endDate
     * @return ResponseDto
     * */
    @Override
    public ResponseDto weeklyRunningJobStatistics(String startDate, String endDate) throws Exception {
        ResponseDto responseDto = new ResponseDto(SUCCESS, "No data found.", new ArrayList<>());
        List<Object[]> result = this.queryService.executeQuery(this.queryService.weeklyRunningJobStatistics(startDate, endDate));
        if (!ProcessUtil.isNull(result) && !result.isEmpty()) {
            List<JobStatusStatisticDto> jobStatusStatistic = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                jobStatusStatistic.add(new JobStatusStatisticDto(obj[index].toString().trim(), Integer.valueOf(obj[++index].toString())));
            }
            responseDto = new ResponseDto(SUCCESS, "Data found.", jobStatusStatistic);
        }
        return responseDto;
    }

    /**
     * Method use to run weekly rs running job statistics
     * @param startDate
     * @param endDate
     * @return ResponseDto
     * */
    @Override
    public ResponseDto weeklyHrsRunningJobStatistics(String startDate, String endDate) throws Exception {
        ResponseDto responseDto = new ResponseDto(SUCCESS, "No data found.", new ArrayList<>());
        List<Object[]> result = this.queryService.executeQuery(this.queryService.weeklyHrsRunningJobStatistics(startDate, endDate));
        if (!ProcessUtil.isNull(result) && !result.isEmpty()) {
            List<WeeklyJobStatisticsDto> weeklyJobStatistics = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                weeklyJobStatistics.add(new WeeklyJobStatisticsDto(obj[index].toString().trim(),
                    Double.valueOf(obj[++index].toString()).longValue(), obj[++index].toString().trim(),
                    Double.valueOf(obj[++index].toString()).longValue()));
            }
            responseDto = new ResponseDto(SUCCESS, "Data found.", weeklyJobStatistics);
        }
        return responseDto;
    }

    /**
     * Method use to get weekly hr running statistics dimension
     * @param targetHr
     * @param targetDate
     * @return ResponseDto
     * */
    @Override
    public ResponseDto weeklyHrRunningStatisticsDimension(String targetDate, Long targetHr) throws Exception {
        ResponseDto responseDto = new ResponseDto(SUCCESS, "No Data found.", new ArrayList<>());
        List<Object[]> result = this.queryService.executeQuery(this.queryService.weeklyHrRunningStatisticsDimension(targetDate, targetHr));
        if (!ProcessUtil.isNull(result) && !result.isEmpty()) {
            List<WeeklyHrJobDimensionStatisticsDto> weeklyJobStatistics = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                weeklyJobStatistics.add(new WeeklyHrJobDimensionStatisticsDto(
                    obj[index] != null ? Long.valueOf(obj[index].toString()) : null, String.valueOf(obj[++index]),
                    Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()),
                    Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()),
                    Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()),Long.valueOf(obj[++index].toString())));
            }
            responseDto = new ResponseDto(SUCCESS, "Data found.", weeklyJobStatistics);
        }
        return responseDto;
    }

    /**
     * Method use to get weekly hr running statistics dimension
     * @param targetHr
     * @param targetDate
     * @param jobStatus
     * @param jobId
     * @return ResponseDto
     * */
    @Override
    public ResponseDto weeklyHrRunningStatisticsDimensionDetail(String targetDate, Long targetHr, String jobStatus, Long jobId) throws Exception {
        ResponseDto responseDto = new ResponseDto(SUCCESS, "No data found.");
        Map<String, Object> objectDetail = new HashMap<>();
        List<Object[]> result = this.queryService.executeQuery(this.queryService.weeklyHrRunningStatisticsDimensionDetail(targetDate, targetHr, jobStatus, jobId));
        if (!ProcessUtil.isNull(result) && !result.isEmpty()) {
            List<SourceJobQueueDto> sourceJobQueues = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                SourceJobQueueDto sourceJobQueueDto = new SourceJobQueueDto();
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueueDto.setJobQueueId(Long.valueOf(String.valueOf(obj[index])));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueueDto.setDateCreated(Timestamp.valueOf(String.valueOf(obj[index])));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueueDto.setEndTime(LocalDateTime.parse(String.valueOf(obj[index]).substring(0,19), formatter));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueueDto.setJobId(Long.valueOf(String.valueOf(obj[index])));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueueDto.setJobSend(Boolean.parseBoolean(obj[index].toString()));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueueDto.setJobStatus(JobStatus.valueOf(String.valueOf(obj[index])));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueueDto.setJobStatusMessage(String.valueOf(obj[index]));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueueDto.setRunManual(Boolean.valueOf(obj[index].toString()));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueueDto.setSkipManual(Boolean.valueOf(obj[index].toString()));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueueDto.setSkipTime(LocalDateTime.parse(String.valueOf(obj[index]).substring(0,19), formatter));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueueDto.setStartTime(LocalDateTime.parse(String.valueOf(obj[index]).substring(0,19), formatter));
                }

                sourceJobQueues.add(sourceJobQueueDto);
            }
            objectDetail.put("sourceJobQueues", sourceJobQueues);
            if (!ProcessUtil.isNull(jobId)) {
                Optional<SourceJob> sourceJob = this.sourceJobRepository.findById(jobId);
                if (sourceJob.isPresent()) {
                    SourceJobDto sourceJobDto = getSourceJobDto(sourceJob.get());
                    if (!ProcessUtil.isNull(sourceJob.get().getTaskDetail())) {
                        SourceTaskDto sourceTaskDto = getSourceTaskDto(sourceJob.get().getTaskDetail());
                        sourceJobDto.setTaskDetail(sourceTaskDto);
                    }
                    Optional<Scheduler> scheduler = this.schedulerRepository.findSchedulerByJobId(jobId);
                    scheduler.ifPresent(value -> sourceJobDto.setScheduler(getSchedulerDto(value)));
                    objectDetail.put("sourceJob", sourceJobDto);
                    result = this.queryService.executeQuery(this.queryService.statisticsBySourceJobId(jobId));
                    for(Object[] obj : result) {
                        int index = 0;
                        objectDetail.put("sourceJobStatistics", new WeeklyHrJobDimensionStatisticsDto(
                            Long.valueOf(obj[index].toString()), Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()),
                            Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()),
                            Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString())));
                    }
                }
            }
            responseDto = new ResponseDto(SUCCESS, "Data found.", objectDetail);
        }
        return responseDto;
    }

    /**
     * Method use get source job
     * @param sourceJob
     * @return SourceJobDto
     * */
    private SourceJobDto getSourceJobDto(SourceJob sourceJob) {
        SourceJobDto sourceJobDto = new SourceJobDto();
        sourceJobDto.setJobId(sourceJob.getJobId());
        sourceJobDto.setJobStatus(sourceJob.getJobStatus());
        sourceJobDto.setJobRunningStatus(sourceJob.getJobRunningStatus());
        sourceJobDto.setLastJobRun(sourceJob.getLastJobRun());
        sourceJobDto.setJobName(sourceJob.getJobName());
        sourceJobDto.setDateCreated(sourceJob.getDateCreated());
        sourceJobDto.setPriority(sourceJob.getPriority());
        sourceJobDto.setExecution(sourceJob.getExecution());
        sourceJobDto.setCompleteJob(sourceJob.isCompleteJob());
        sourceJobDto.setFailJob(sourceJob.isFailJob());
        sourceJobDto.setSkipJob(sourceJob.isSkipJob());
        return sourceJobDto;
    }

    /**
     * Method use get source task
     * @param sourceTask
     * @return SourceTaskDto
     * */
    private SourceTaskDto getSourceTaskDto(SourceTask sourceTask) {
        SourceTaskDto sourceTaskDto = new SourceTaskDto();
        sourceTaskDto.setTaskDetailId(sourceTask.getTaskDetailId());
        sourceTaskDto.setTaskName(sourceTask.getTaskName());
        sourceTaskDto.setTaskStatus(sourceTask.getTaskStatus());
        sourceTaskDto.setTaskPayload(sourceTask.getTaskPayload());
        if (!ProcessUtil.isNull(sourceTask.getHomePageId())) {
            sourceTaskDto.setHomePageId(this.lookupDataRepository.findById(
                Long.valueOf(sourceTask.getHomePageId())).get().getLookupValue());
        }
        if (!ProcessUtil.isNull(sourceTask.getPipelineId())) {
            sourceTaskDto.setPipelineId(this.lookupDataRepository.findById(
                Long.valueOf(sourceTask.getPipelineId())).get().getLookupValue());
        }
        if (!ProcessUtil.isNull(sourceTask.getSourceTaskType())) {
            sourceTaskDto.setSourceTaskType(getSourceTaskTypeDto(sourceTask));
        }
        return sourceTaskDto;
    }

    /**
     * Method use get scheduler
     * @param scheduler
     * @return SchedulerDto
     * */
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
     * Method use get source task type
     * @param sourceTask
     * @return SourceTaskTypeDto
     * */
    private SourceTaskTypeDto getSourceTaskTypeDto(SourceTask sourceTask) {
        SourceTaskType sourceTaskType = sourceTask.getSourceTaskType();
        SourceTaskTypeDto sourceTaskTypeDto = new SourceTaskTypeDto();
        sourceTaskTypeDto.setSourceTaskTypeId(sourceTaskType.getSourceTaskTypeId());
        sourceTaskTypeDto.setServiceName(sourceTaskType.getServiceName());
        sourceTaskTypeDto.setQueueTopicPartition(sourceTaskType.getQueueTopicPartition());
        sourceTaskTypeDto.setDescription(sourceTaskType.getDescription());
        return sourceTaskTypeDto;
    }

}
