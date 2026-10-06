package process.model.service;

import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 * */
public interface DashboardService {

    ResponseDto jobStatusStatistics(String startDate, String endDate) throws Exception;

    ResponseDto jobRunningStatistics(String startDate, String endDate) throws Exception;

    ResponseDto userStatistics(String startDate, String endDate) throws Exception;

    ResponseDto weeklyRunningJobStatistics(String startDate, String endDate) throws Exception;

    ResponseDto weeklyHrsRunningJobStatistics(String startDate, String endDate) throws Exception;

    ResponseDto weeklyHrRunningStatisticsDimension(String targetDate, Long targetHr) throws Exception;

    ResponseDto weeklyHrRunningStatisticsDimensionDetail(String targetDate, Long targetHr, String jobStatus, Long jobId) throws Exception;

}
