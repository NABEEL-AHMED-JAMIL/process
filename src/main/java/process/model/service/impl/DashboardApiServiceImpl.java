package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.model.dto.ResponseDto;
import process.model.service.DashboardApiService;

/**
 * @author Nabeel Ahmed
 */
@Service
public class DashboardApiServiceImpl implements DashboardApiService {

    private Logger logger = LoggerFactory.getLogger(DashboardApiServiceImpl.class);

    @Autowired
    private QueryService queryService;

    @Override
    public ResponseDto jobStatusStatistics() throws Exception {
        return null;
    }

    @Override
    public ResponseDto jobRunningStatistics() throws Exception {
        return null;
    }

    @Override
    public ResponseDto weeklyRunningJobStatistics(String startDate, String endDate) throws Exception {
        return null;
    }

    @Override
    public ResponseDto viewRunningJobDateByTargetClickJobStatistics(String startDate, String endDate, String targetClick) throws Exception {
        return null;
    }
}
