package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import process.model.service.DashboardApiService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 */
@Service
public class DashboardApiServiceImpl implements DashboardApiService {

    private Logger logger = LoggerFactory.getLogger(DashboardApiServiceImpl.class);

    @Autowired
    private QueryService queryService;
    @Autowired
    private SourceJobRepository sourceJobRepository;
    @Autowired
    private SchedulerRepository schedulerRepository;
    @Autowired
    private LookupDataRepository lookupDataRepository;

    @Override
    public ResponseDto jobStatusStatistics() throws Exception {
        ResponseDto responseDto;
        List<Object[]> result = this.queryService.executeQuery(this.queryService.jobStatusStatistics());
        if (!isNull(result) && result.size() > 0) {
            List<JobStatusStatisticDto> jobStatusStatistic = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                jobStatusStatistic.add(new JobStatusStatisticDto(String.valueOf(obj[index]), Integer.valueOf(obj[++index].toString())));
            }
            responseDto = new ResponseDto(SUCCESS, "Data found for jobStatusStatistics.", jobStatusStatistic);
        } else {
            responseDto = new ResponseDto(SUCCESS, "No Data found for jobStatusStatistics.", new ArrayList<>());
        }
        return responseDto;
    }

    @Override
    public ResponseDto jobRunningStatistics() throws Exception {
        ResponseDto responseDto;
        List<Object[]> result = this.queryService.executeQuery(this.queryService.jobRunningStatistics());
        if (!isNull(result) && result.size() > 0) {
            List<JobStatusStatisticDto> jobStatusStatistic = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                jobStatusStatistic.add(new JobStatusStatisticDto(String.valueOf(obj[index]), Integer.valueOf(obj[++index].toString())));
            }
            responseDto = new ResponseDto(SUCCESS, "Data found for jobRunningStatistics.", jobStatusStatistic);
        } else {
            responseDto = new ResponseDto(SUCCESS, "No Data found for jobRunningStatistics.", new ArrayList<>());
        }
        return responseDto;
    }

    @Override
    public ResponseDto weeklyRunningJobStatistics(String startDate, String endDate) throws Exception {
        ResponseDto responseDto;
        List<Object[]> result = this.queryService.executeQuery(this.queryService.weeklyRunningJobStatistics(startDate, endDate));
        if (!isNull(result) && result.size() > 0) {
            List<JobStatusStatisticDto> jobStatusStatistic = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                jobStatusStatistic.add(new JobStatusStatisticDto(obj[index].toString().trim(), Integer.valueOf(obj[++index].toString())));
            }
            responseDto = new ResponseDto(SUCCESS, "Data found for weeklyRunningJobStatistics.", jobStatusStatistic);
        } else {
            responseDto = new ResponseDto(SUCCESS, "No Data found for weeklyRunningJobStatistics.", new ArrayList<>());
        }
        return responseDto;
    }

    @Override
    public ResponseDto weeklyHrsRunningJobStatistics(String startDate, String endDate) throws Exception {
        ResponseDto responseDto;
        List<Object[]> result = this.queryService.executeQuery(this.queryService.weeklyHrsRunningJobStatistics(startDate, endDate));
        if (!isNull(result) && result.size() > 0) {
            List<WeeklyJobStatisticsDto> weeklyJobStatistics = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                weeklyJobStatistics.add(new WeeklyJobStatisticsDto(obj[index].toString().trim(),
                    Double.valueOf(obj[++index].toString()).longValue(), obj[++index].toString().trim(),
                    Double.valueOf(obj[++index].toString()).longValue()));
            }
            responseDto = new ResponseDto(SUCCESS, "Data found for weeklyHrsRunningJobStatistics.", weeklyJobStatistics);
        } else {
            responseDto = new ResponseDto(SUCCESS, "No Data found for weeklyHrsRunningJobStatistics.", new ArrayList<>());
        }
        return responseDto;
    }

    @Override
    public ResponseDto weeklyHrRunningStatisticsDimension(String targetDate, Long targetHr) throws Exception {
        ResponseDto responseDto;
        List<Object[]> result = this.queryService.executeQuery(this.queryService.weeklyHrRunningStatisticsDimension(targetDate, targetHr));
        if (!isNull(result) && result.size() > 0) {
            List<WeeklyHrJobDimensionStatisticsDto> weeklyJobStatistics = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                weeklyJobStatistics.add(new WeeklyHrJobDimensionStatisticsDto(
                    obj[index] != null ? Long.valueOf(obj[index].toString()) : null, String.valueOf(obj[++index]),
                    Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()),
                    Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()),
                    Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()),
                    Long.valueOf(obj[++index].toString()),Long.valueOf(obj[++index].toString())));
            }
            responseDto = new ResponseDto(SUCCESS, "Data found for weeklyHrRunningStatisticsDimensionData.", weeklyJobStatistics);
        } else {
            responseDto = new ResponseDto(SUCCESS, "No Data found for weeklyHrRunningStatisticsDimensionData.", new ArrayList<>());
        }
        return responseDto;
    }

    @Override
    public ResponseDto weeklyHrRunningStatisticsDimensionDetail(String targetDate, Long targetHr,
        String jobStatus, Long jobId) throws Exception {
        ResponseDto responseDto;
        Map<String, Object> objectDetail = new HashMap<>();
        List<Object[]> result = this.queryService.executeQuery(this.queryService
            .weeklyHrRunningStatisticsDimensionDetail(targetDate, targetHr, jobStatus, jobId));
        if (!isNull(result) && result.size() > 0) {
            List<SourceJobQueueDto> sourceJobQueues = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                SourceJobQueueDto sourceJobQueueDto = new SourceJobQueueDto();
                if (!isNull(obj[index])) {
                    sourceJobQueueDto.setJobQueueId(Long.valueOf(String.valueOf(obj[index])));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueueDto.setDateCreated(Timestamp.valueOf(String.valueOf(obj[index])));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueueDto.setEndTime(LocalDateTime.parse(String.valueOf(obj[index]).substring(0,19), formatter));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueueDto.setJobId(Long.valueOf(String.valueOf(obj[index])));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueueDto.setJobSend(Boolean.valueOf(obj[index].toString()));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueueDto.setJobStatus(JobStatus.valueOf(String.valueOf(obj[index])));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueueDto.setJobStatusMessage(String.valueOf(obj[index]));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueueDto.setRunManual(Boolean.valueOf(obj[index].toString()));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueueDto.setSkipManual(Boolean.valueOf(obj[index].toString()));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueueDto.setSkipTime(LocalDateTime.parse(String.valueOf(obj[index]).substring(0,19), formatter));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueueDto.setStartTime(LocalDateTime.parse(String.valueOf(obj[index]).substring(0,19), formatter));
                }

                sourceJobQueues.add(sourceJobQueueDto);
            }
            objectDetail.put("sourceJobQueues", sourceJobQueues);
            if (!isNull(jobId)) {
                Optional<SourceJob> sourceJob = this.sourceJobRepository.findById(jobId);
                if (sourceJob.isPresent()) {
                    SourceJobDto sourceJobDto = new SourceJobDto();
                    sourceJobDto.setJobId(sourceJob.get().getJobId());
                    sourceJobDto.setJobStatus(sourceJob.get().getJobStatus());
                    sourceJobDto.setLastJobRun(sourceJob.get().getLastJobRun());
                    sourceJobDto.setJobName(sourceJob.get().getJobName());
                    sourceJobDto.setDateCreated(sourceJob.get().getDateCreated());
                    sourceJobDto.setPriority(sourceJob.get().getPriority());
                    sourceJobDto.setJobRunningStatus(sourceJob.get().getJobRunningStatus());
                    sourceJobDto.setExecution(sourceJob.get().getExecution());
                    sourceJobDto.setCompleteJob(sourceJob.get().isCompleteJob());
                    sourceJobDto.setFailJob(sourceJob.get().isFailJob());
                    sourceJobDto.setPdfReportUrl(sourceJob.get().getJobId().toString());
                    sourceJobDto.setSkipJob(sourceJob.get().isSkipJob());
                    if (sourceJob.get().getTaskDetail() != null) {
                        SourceTask sourceTask = sourceJob.get().getTaskDetail();
                        SourceTaskDto sourceTaskDto = new SourceTaskDto();
                        sourceTaskDto.setTaskDetailId(sourceTask.getTaskDetailId());
                        sourceTaskDto.setTaskName(sourceTask.getTaskName());
                        sourceTaskDto.setTaskStatus(sourceTask.getTaskStatus());
                        sourceTaskDto.setTaskPayload(sourceTask.getTaskPayload());
                        if (!isNull(sourceTask.getHomePageId())) {
                            String homePage = this.lookupDataRepository.findById(
                                    Long.valueOf(sourceTask.getHomePageId())).get().getLookupValue();
                            homePage = homePage.replace("{jobId}", String.valueOf(sourceJobDto.getJobId()));
                            homePage = homePage.replace("{taskId}", String.valueOf(sourceTaskDto.getTaskDetailId()));
                            if (!isNull(sourceTask.getPipelineId())) {
                                homePage = homePage.replace("{pipeline}", sourceTask.getPipelineId());
                            }
                            sourceTaskDto.setHomePageId(homePage);
                        }
                        if (!isNull(sourceTask.getPipelineId())) {
                            sourceTaskDto.setPipelineId(this.lookupDataRepository.findById(
                                Long.valueOf(sourceTask.getPipelineId())).get().getLookupValue());
                        }
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
                    objectDetail.put("sourceJob", sourceJobDto);
                    result = this.queryService.executeQuery(this.queryService.statisticsBySourceJobId(jobId));
                    for(Object[] obj : result) {
                        int index = 0;
                        objectDetail.put("sourceJobStatistics", new WeeklyHrJobDimensionStatisticsDto(
                            Long.valueOf(obj[index].toString()), Long.valueOf(obj[++index].toString()),
                            Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()),
                            Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()),
                            Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString())));
                    }
                }
            }
            responseDto = new ResponseDto(SUCCESS, "Data found for weeklyHrRunningStatisticsDimensionDetail.", objectDetail);
        } else {
            responseDto = new ResponseDto(SUCCESS, "No Data found for weeklyHrRunningStatisticsDimensionDetail.");
        }
        return responseDto;
    }

}
