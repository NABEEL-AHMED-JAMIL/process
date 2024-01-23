package process.model.service;

import process.payload.response.AppResponse;

/**
 * @author Nabeel Ahmed
 */
public interface DashboardApiService {

    public AppResponse jobStatusStatistics() throws Exception;

    public AppResponse jobRunningStatistics() throws Exception;

    public AppResponse weeklyRunningJobStatistics(String startDate, String endDate) throws Exception;

    public AppResponse weeklyHrsRunningJobStatistics(String startDate, String endDate) throws Exception;

    public AppResponse weeklyHrRunningStatisticsDimension(String targetDate, Long targetHr) throws Exception;

    public AppResponse weeklyHrRunningStatisticsDimensionDetail(String targetDate, Long targetHr, String jobStatus, Long jobId) throws Exception;

}