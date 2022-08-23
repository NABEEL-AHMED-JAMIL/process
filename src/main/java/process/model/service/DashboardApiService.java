package process.model.service;

import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 */
public interface DashboardApiService {

    public ResponseDto jobStatusStatistics() throws Exception;

    public ResponseDto jobRunningStatistics() throws Exception;

    public ResponseDto weeklyRunningJobStatistics(String startDate, String endDate) throws Exception;

    public ResponseDto viewRunningJobDateByTargetClickJobStatistics(String startDate, String endDate, String targetClick) throws Exception;

}
