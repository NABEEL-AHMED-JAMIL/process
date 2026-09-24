package process.model.service.impl;

import java.util.stream.Collectors;
import java.util.Map;
import java.util.Locale;
import java.util.HashMap;
import java.util.Comparator;
import java.text.Collator;
import process.identity.IdentityPort;
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
import process.model.repository.TaskReferenceRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.DashboardService;
import process.security.TenantContext;
import process.util.BusinessTime;
import process.util.ProcessTimeUtil;
import process.util.ProcessUtil;
import java.sql.Timestamp;
import java.util.*;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class DashboardServiceImpl implements DashboardService {

    private Logger logger = LoggerFactory.getLogger(DashboardServiceImpl.class);

    private final QueryService queryService;
    private final SourceJobRepository sourceJobRepository;
    private final JobQueueRepository jobQueueRepository;
    private final SchedulerRepository schedulerRepository;
    private final TaskReferenceRepository taskReferenceRepository;

    private final IdentityPort identity;

    public DashboardServiceImpl(QueryService queryService,
        SourceJobRepository sourceJobRepository,
        JobQueueRepository jobQueueRepository,
        SchedulerRepository schedulerRepository,
        TaskReferenceRepository taskReferenceRepository, IdentityPort identity) {
        this.identity = identity;
        this.queryService = queryService;
        this.sourceJobRepository = sourceJobRepository;
        this.jobQueueRepository = jobQueueRepository;
        this.schedulerRepository = schedulerRepository;
        this.taskReferenceRepository = taskReferenceRepository;
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
            jobStatusStatistic.forEach(tile -> {
                tile.setTenantId(scopeTenant());
                tile.setAllWorkspaces(TenantContext.isPlatformAdmin());
            });
            responseDto = new ResponseDto(SUCCESS, "Data found.", jobStatusStatistic);
        }
        return responseDto;
    }

    /**
     * The people list with each person's work (MIG-107). Who is listed is Identity's answer for the caller's
     * scope -- a workspace's people, every workspace's for a platform administrator, nobody for a caller
     * scoped to nothing -- and a tenant user's view is their own row (MIG-46, DEF-128): the list names every
     * colleague with their failed-run counts, and the lowest role has no business profiling colleagues. No
     * user id is nobody. The counts are Core's, one query for everyone listed; a person with no work is
     * listed at zero. Most jobs first, then by name, as the query ordered it.
     */
    @Override
    public ResponseDto userStatistics(String startDate, String endDate) throws Exception {
        List<IdentityPort.Person> people = new ArrayList<>(this.identity.members(TenantContext.scope()));
        if ("TENANT_USER".equals(TenantContext.getUserRole())) {
            Long me = TenantContext.getAppUserId();
            people.removeIf(person -> me == null || !me.equals(person.getAppUserId()));
        }
        List<Long> ids = people.stream().map(IdentityPort.Person::getAppUserId).collect(Collectors.toList());
        // Built even for nobody: a malformed range is refused in words whoever is listed (MIG-103).
        String counts = this.queryService.userStatistics(startDate, endDate, ids);
        if (people.isEmpty()) {
            return new ResponseDto(SUCCESS, "No data found.", new ArrayList<>());
        }
        Map<Long, Object[]> work = new HashMap<>();
        List<Object[]> result = this.queryService.executeQuery(counts);
        if (!ProcessUtil.isNull(result)) {
            for (Object[] row : result) {
                work.put(asLong(row[0]), row);
            }
        }
        List<UserStatisticDto> stats = new ArrayList<>();
        for (IdentityPort.Person person : people) {
            Object[] row = work.getOrDefault(person.getAppUserId(), new Object[7]);
            int index = 0;
            UserStatisticDto dto = new UserStatisticDto();
            dto.setAppUserId(person.getAppUserId());
            dto.setUsername(person.getUsername());
            dto.setFullName(person.getFullName());
            dto.setUserRole(person.getUserRole());
            dto.setStatus(person.getStatus());
            dto.setAvatarBucket(person.getAvatarBucket());
            dto.setAvatarKey(person.getAvatarKey());
            dto.setJobCount(asInt(row[++index]));
            dto.setActiveJobs(asInt(row[++index]));
            dto.setTaskCount(asInt(row[++index]));
            dto.setRunCount(asInt(row[++index]));
            dto.setCompletedCount(asInt(row[++index]));
            dto.setFailedCount(asInt(row[++index]));
            dto.setTenantId(person.getTenantId());
            stats.add(dto);
        }
        Collator byName = Collator.getInstance(Locale.US);
        stats.sort(Comparator.comparing(UserStatisticDto::getJobCount, Comparator.reverseOrder())
            .thenComparing(UserStatisticDto::getFullName, Comparator.nullsLast(byName)));
        return new ResponseDto(SUCCESS, "Data found.", stats);
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

    /**
     * Whose numbers a total is (MIG-46, DEF-128): the caller's workspace, or none at all for a
     * platform administrator, whose totals span every workspace and say so with allWorkspaces.
     */
    private static Long scopeTenant() {
        return TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId();
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
            jobStatusStatistic.forEach(tile -> {
                tile.setTenantId(scopeTenant());
                tile.setAllWorkspaces(TenantContext.isPlatformAdmin());
            });
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
            jobStatusStatistic.forEach(tile -> {
                tile.setTenantId(scopeTenant());
                tile.setAllWorkspaces(TenantContext.isPlatformAdmin());
            });
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
            weeklyJobStatistics.forEach(cell -> {
                cell.setTenantId(scopeTenant());
                cell.setAllWorkspaces(TenantContext.isPlatformAdmin());
            });
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
                Long tenantId = obj.length > ++i && obj[i] != null ? Long.valueOf(obj[i].toString()) : null;
                WeeklyHrJobDimensionStatisticsDto row = new WeeklyHrJobDimensionStatisticsDto(jobId, jobName,
                    queue, start, running, failed, completed, stop, skip, interrupt, missed, total);
                // A job names its own workspace. The TOTAL row adds up the caller's scope: one
                // workspace, or every workspace for a platform administrator.
                boolean totalRow = jobId == null;
                row.setTenantId(totalRow ? scopeTenant() : tenantId);
                row.setAllWorkspaces(totalRow && TenantContext.isPlatformAdmin());
                weeklyJobStatistics.add(row);
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
                    sourceJobQueueDto.setDateCreated((Timestamp) obj[index]);
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueueDto.setEndTime(BusinessTime.wallClockOf(obj[index]).withNano(0));
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
                    sourceJobQueueDto.setSkipTime(BusinessTime.wallClockOf(obj[index]).withNano(0));
                }
                index++;
                if (!ProcessUtil.isNull(obj[index])) {
                    sourceJobQueueDto.setStartTime(BusinessTime.wallClockOf(obj[index]).withNano(0));
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
        Long homePageId = sourceTask.getHomePageId();
        if (homePageId != null) {
            this.taskReferenceRepository.findById(homePageId)
                .ifPresent(homePage -> sourceTaskDto.setHomePageId(homePage.getName()));
        }
        // pipeline_id is the raw id the worker routes on ("F768930"); the PIPELINE_IDS lookup family it once named
        // was dropped by V28, and lookup_data is retired (MIG-167), so it is shown as it is stored.
        sourceTaskDto.setPipelineId(sourceTask.getPipelineId());
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
