package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.model.dto.*;
import process.model.enums.JobStatus;
import process.model.service.DashboardApiService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 */
@Service
public class DashboardApiServiceImpl implements DashboardApiService {

    private Logger logger = LoggerFactory.getLogger(DashboardApiServiceImpl.class);

    private DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private QueryService queryService;

    @Override
    public ResponseDto jobStatusStatistics() throws Exception {
        ResponseDto responseDto = null;
        List<Object[]> result = this.queryService.executeQuery(this.queryService.jobStatusStatistics());
        if (!isNull(result) && result.size() > 0) {
            List<JobStatusStatisticDto> jobStatusStatistic = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                jobStatusStatistic.add(new JobStatusStatisticDto(String.valueOf(obj[index]), Integer.valueOf(obj[++index].toString())));
            }
            responseDto = new ResponseDto(SUCCESS, "Data found for jobStatusStatistics.", jobStatusStatistic);
        } else {
            responseDto = new ResponseDto(ERROR, "No Data found for jobStatusStatistics.", new ArrayList<>());
        }
        return responseDto;
    }

    @Override
    public ResponseDto jobRunningStatistics() throws Exception {
        ResponseDto responseDto = null;
        List<Object[]> result = this.queryService.executeQuery(this.queryService.jobRunningStatistics());
        if (!isNull(result) && result.size() > 0) {
            List<JobStatusStatisticDto> jobStatusStatistic = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                jobStatusStatistic.add(new JobStatusStatisticDto(String.valueOf(obj[index]), Integer.valueOf(obj[++index].toString())));
            }
            responseDto = new ResponseDto(SUCCESS, "Data found for jobRunningStatistics.", jobStatusStatistic);
        } else {
            responseDto = new ResponseDto(ERROR, "No Data found for jobRunningStatistics.", new ArrayList<>());
        }
        return responseDto;
    }

    @Override
    public ResponseDto weeklyRunningJobStatistics(String startDate, String endDate) throws Exception {
        ResponseDto responseDto = null;
        List<Object[]> result = this.queryService.executeQuery(
            this.queryService.weeklyRunningJobStatistics(startDate, endDate));
        if (!isNull(result) && result.size() > 0) {
            List<JobStatusStatisticDto> jobStatusStatistic = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                jobStatusStatistic.add(new JobStatusStatisticDto(
                    String.valueOf(Double.valueOf(obj[index].toString()).longValue()), Integer.valueOf(obj[++index].toString())));
            }
            responseDto = new ResponseDto(SUCCESS, "Data found for weeklyRunningJobStatistics.", jobStatusStatistic);
        } else {
            responseDto = new ResponseDto(ERROR, "No Data found for weeklyRunningJobStatistics.", new ArrayList<>());
        }
        return responseDto;
    }

    @Override
    public ResponseDto weeklyHrsRunningJobStatistics(String startDate, String endDate) throws Exception {
        ResponseDto responseDto = null;
        List<Object[]> result = this.queryService.executeQuery(
            this.queryService.weeklyHrsRunningJobStatistics(startDate, endDate));
        if (!isNull(result) && result.size() > 0) {
            List<WeeklyJobStatisticsDto> weeklyJobStatistics = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                weeklyJobStatistics.add(new WeeklyJobStatisticsDto(
                    Double.valueOf(obj[index].toString()).longValue(),
                    Double.valueOf(obj[++index].toString()).longValue(), obj[++index].toString(),
                    Double.valueOf(obj[++index].toString()).longValue()));
            }
            responseDto = new ResponseDto(SUCCESS, "Data found for weeklyHrsRunningJobStatistics.", weeklyJobStatistics);
        } else {
            responseDto = new ResponseDto(ERROR, "No Data found for weeklyHrsRunningJobStatistics.", new ArrayList<>());
        }
        return responseDto;
    }

    @Override
    public ResponseDto weeklyHrRunningStatisticsDimension(String targetDate, Long targetHr) throws Exception {
        ResponseDto responseDto = null;
        List<Object[]> result = this.queryService.executeQuery(
            this.queryService.weeklyHrRunningStatisticsDimension(targetDate, targetHr));
        if (!isNull(result) && result.size() > 0) {
            List<WeeklyHrJobDimensionStatisticsDto> weeklyJobStatistics = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                weeklyJobStatistics.add(new WeeklyHrJobDimensionStatisticsDto(Long.valueOf(obj[index].toString()), obj[++index].toString(),
                    Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()),
                    Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()), Long.valueOf(obj[++index].toString()),
                    Long.valueOf(obj[++index].toString())));
            }
            responseDto = new ResponseDto(SUCCESS, "Data found for weeklyHrRunningStatisticsDimensionData.", weeklyJobStatistics);
        } else {
            responseDto = new ResponseDto(ERROR, "No Data found for weeklyHrRunningStatisticsDimensionData.", new ArrayList<>());
        }
        return responseDto;
    }

    @Override
    public ResponseDto viewRunningJobDateByTargetClickJobStatistics(String targetDate, Long targetHr) throws Exception {
        ResponseDto responseDto = null;
        List<Object[]> result = this.queryService.executeQuery(this.queryService
            .viewRunningJobDateByTargetClickJobStatistics(targetDate, targetHr));
        if (!isNull(result) && result.size() > 0) {
            List<SourceJobQueueDto> sourceJobQueues = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                SourceJobQueueDto sourceJobQueueDto = new SourceJobQueueDto();
                if (!isNull(obj[index])) {
                    sourceJobQueueDto.setJobName(String.valueOf(obj[index]));
                }
                index++;
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
                    sourceJobQueueDto.setJobStatus(JobStatus.valueOf(String.valueOf(obj[index])));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueueDto.setJobStatusMessage(String.valueOf(obj[index]));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueueDto.setStartTime(LocalDateTime.parse(String.valueOf(obj[index]).substring(0,19), formatter));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueueDto.setSkipTime(LocalDateTime.parse(String.valueOf(obj[index]).substring(0,19), formatter));
                }
                sourceJobQueues.add(sourceJobQueueDto);
            }
            responseDto = new ResponseDto(SUCCESS, "Data found for viewRunningJobDateByTargetClickJobStatistics.",
                sourceJobQueues);
        } else {
            responseDto = new ResponseDto(ERROR, "No Data found for viewRunningJobDateByTargetClickJobStatistics.",
                new ArrayList<>());
        }
        return responseDto;
    }
}
