package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.model.dto.UserStatisticDto;
import process.model.dto.*;
import process.model.enums.JobStatus;
import process.model.pojo.Scheduler;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.repository.JobQueueRepository;
import process.model.repository.LookupDataRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.DashboardService;
import process.security.TenantContext;
import process.util.ProcessTimeUtil;
import process.util.ProcessUtil;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import static process.util.ProcessUtil.*;

@Service
public class DashboardServiceImpl implements DashboardService {

    private Logger logger = LoggerFactory.getLogger(DashboardServiceImpl.class);

    private final QueryService queryService;
    private final SourceJobRepository sourceJobRepository;
    private final JobQueueRepository jobQueueRepository;
    private final SchedulerRepository schedulerRepository;
    private final LookupDataRepository lookupDataRepository;

    public DashboardServiceImpl(QueryService queryService,
        SourceJobRepository sourceJobRepository,
        JobQueueRepository jobQueueRepository,
        SchedulerRepository schedulerRepository,
        LookupDataRepository lookupDataRepository) {
        this.queryService = queryService;
        this.sourceJobRepository = sourceJobRepository;
        this.jobQueueRepository = jobQueueRepository;
        this.schedulerRepository = schedulerRepository;
        this.lookupDataRepository = lookupDataRepository;
    }

    @Override
    public ResponseDto jobStatusStatistics(String startDate, String endDate) throws Exception {
        ResponseDto responseDto = new ResponseDto(SUCCESS, "No data found.", new ArrayList<>());
        List<Object[]> result = this.queryService.executeQuery(this.queryService.jobStatusStatistics(startDate, endDate));
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

    @Override
    public ResponseDto userStatistics(String startDate, String endDate) throws Exception {
        ResponseDto responseDto = new ResponseDto(SUCCESS, "No data found.", new ArrayList<>());
        List<Object[]> result = this.queryService.executeQuery(this.queryService.userStatistics(startDate, endDate));
        if (!ProcessUtil.isNull(result) && !result.isEmpty()) {
            List<UserStatisticDto> stats = new ArrayList<>();
            for (Object[] obj : result) {
                int index = 0;
                UserStatisticDto dto = new UserStatisticDto();
                dto.setAppUserId(asLong(obj[index]));
                dto.setUsername(asText(obj[++index]));
                dto.setFullName(asText(obj[++index]));
                dto.setUserRole(asText(obj[++index]));
                dto.setStatus(asText(obj[++index]));
                dto.setAvatarBucket(asText(obj[++index]));
                dto.setAvatarKey(asText(obj[++index]));
                dto.setJobCount(asInt(obj[++index]));
                dto.setActiveJobs(asInt(obj[++index]));
                dto.setTaskCount(asInt(obj[++index]));
                dto.setRunCount(asInt(obj[++index]));
                dto.setCompletedCount(asInt(obj[++index]));
                dto.setFailedCount(asInt(obj[++index]));
                stats.add(dto);
            }
            responseDto = new ResponseDto(SUCCESS, "Data found.", stats);
        }
        return responseDto;
    }

    /* Null-tolerant readers: avatar columns and any count can come back null, and
     * String.valueOf(null) yields the four-character string "null" rather than nothing. */
    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInt(Object value) {
        return value == null ? 0 : Integer.valueOf(value.toString());
    }

    private static Long asLong(Object value) {
        return value == null ? null : Long.valueOf(value.toString());
    }

    @Override
    public ResponseDto jobRunningStatistics(String startDate, String endDate) throws Exception {
        ResponseDto responseDto = new ResponseDto(SUCCESS, "No data found.", new ArrayList<>());
        List<Object[]> result = this.queryService.executeQuery(this.queryService.jobRunningStatistics(startDate, endDate));
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

    @Override
    public ResponseDto weeklyHrRunningStatisticsDimension(String targetDate, Long targetHr) throws Exception {
        ResponseDto responseDto = new ResponseDto(SUCCESS, "No Data found.", new ArrayList<>());
        List<Object[]> result = this.queryService.executeQuery(this.queryService.weeklyHrRunningStatisticsDimension(targetDate, targetHr));
        if (!ProcessUtil.isNull(result) && !result.isEmpty()) {
            List<WeeklyHrJobDimensionStatisticsDto> weeklyJobStatistics = new ArrayList<>();
            for (Object[] obj : result) {
                int i = 0;
                Long jobId = obj[i] != null ? Long.valueOf(obj[i].toString()) : null;
                String jobName = obj[++i] != null ? obj[i].toString() : null;
                Long queue = obj[++i] != null ? Long.valueOf(obj[i].toString()) : 0L;
                Long start = obj[++i] != null ? Long.valueOf(obj[i].toString()) : 0L;
                Long running = obj[++i] != null ? Long.valueOf(obj[i].toString()) : 0L;
                Long failed = obj[++i] != null ? Long.valueOf(obj[i].toString()) : 0L;
                Long completed = obj[++i] != null ? Long.valueOf(obj[i].toString()) : 0L;
                Long stop = obj[++i] != null ? Long.valueOf(obj[i].toString()) : 0L;
                Long skip = obj[++i] != null ? Long.valueOf(obj[i].toString()) : 0L;
                Long interrupt = obj[++i] != null ? Long.valueOf(obj[i].toString()) : 0L;
                Long missed = obj[++i] != null ? Long.valueOf(obj[i].toString()) : 0L;
                Long total = obj[++i] != null ? Long.valueOf(obj[i].toString()) : 0L;
                weeklyJobStatistics.add(
                    new WeeklyHrJobDimensionStatisticsDto(jobId, jobName,
                        queue, start, running, failed, completed, stop, skip, interrupt, missed, total)
                );
            }
            responseDto = new ResponseDto(SUCCESS, "Data found.", weeklyJobStatistics);
        }
        return responseDto;
    }

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

                Optional<SourceJob> sourceJob = this.sourceJobRepository.findById(jobId)
                    .filter(job -> TenantContext.isPlatformAdmin() || Objects.equals(job.getTenantId(), TenantContext.getTenantId()));
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
                            Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()),
                            Long.valueOf(obj[++index].toString())));
                    }
                }
            }
            responseDto = new ResponseDto(SUCCESS, "Data found.", objectDetail);
        }
        return responseDto;
    }

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

    private SourceTaskDto getSourceTaskDto(SourceTask sourceTask) {
        SourceTaskDto sourceTaskDto = new SourceTaskDto();
        sourceTaskDto.setTaskDetailId(sourceTask.getTaskDetailId());
        sourceTaskDto.setTaskName(sourceTask.getTaskName());
        sourceTaskDto.setTaskStatus(sourceTask.getTaskStatus());
        sourceTaskDto.setTaskPayload(sourceTask.getTaskPayload());
        sourceTaskDto.setBucket(sourceTask.getBucket());
        sourceTaskDto.setInputFolder(sourceTask.getInputFolder());
        sourceTaskDto.setOutputFolder(sourceTask.getOutputFolder());
        Long homePageLookupId = ProcessUtil.parseLongOrNull(sourceTask.getHomePageId());
        if (homePageLookupId != null) {
            this.lookupDataRepository.findById(homePageLookupId)
                .ifPresent(lookupData -> sourceTaskDto.setHomePageId(lookupData.getLookupType()));
        }
        Long pipelineLookupId = ProcessUtil.parseLongOrNull(sourceTask.getPipelineId());
        if (pipelineLookupId != null) {
            this.lookupDataRepository.findById(pipelineLookupId)
                .ifPresent(lookupData -> sourceTaskDto.setPipelineId(lookupData.getLookupType()));
        }
        if (!ProcessUtil.isNull(sourceTask.getSourceTaskType())) {
            sourceTaskDto.setSourceTaskType(getSourceTaskTypeDto(sourceTask));
        }
        return sourceTaskDto;
    }

    private SchedulerDto getSchedulerDto(Scheduler scheduler) {
        SchedulerDto schedulerDto = new SchedulerDto();
        schedulerDto.setSchedulerId(scheduler.getSchedulerId());
        schedulerDto.setStartDate(scheduler.getStartDate());
        schedulerDto.setEndDate(scheduler.getEndDate());
        schedulerDto.setStartTime(scheduler.getStartTime());
        schedulerDto.setFrequency(scheduler.getFrequency());
        schedulerDto.setIntervalValue(scheduler.getIntervalValue());
        schedulerDto.setDaysOfWeek(scheduler.getDaysOfWeek());
        schedulerDto.setDayOfMonth(scheduler.getDayOfMonth());
        schedulerDto.setNextRunAt(scheduler.getNextRunAt());
        schedulerDto.setExpired(scheduler.isExpired());
        schedulerDto.setLastFlight(!scheduler.isExpired() && ProcessTimeUtil.isLastFlight(scheduler));
        return schedulerDto;
    }

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
