package process.model.service;

import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 */
public interface DashboardApiService {

    public ResponseDto jobStatusStatistics() throws Exception;

    public ResponseDto jobRunningStatistics() throws Exception;

    public ResponseDto weeklyRunningJobStatistics(String startDate, String endDate) throws Exception;

    public ResponseDto weeklyHrsRunningJobStatistics(String startDate, String endDate) throws Exception;

    public ResponseDto weeklyHrRunningStatisticsDimension(String targetDate, Long targetHr) throws Exception;

    public ResponseDto weeklyHrRunningStatisticsDimensionDetail(String targetDate, Long targetHr, String jobStatus, Long jobId) throws Exception;

}
