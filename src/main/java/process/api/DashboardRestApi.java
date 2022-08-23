package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ResponseDto;
import process.model.service.DashboardApiService;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;

/**
 * Api use to perform crud operation on dashboard
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/dashboard.json")
public class DashboardRestApi {

    private Logger logger = LoggerFactory.getLogger(DashboardRestApi.class);

    @Autowired
    private DashboardApiService dashboardApiService;

    @RequestMapping(value = "/jobStatusStatistics", method = RequestMethod.GET)
    public ResponseEntity<?> jobStatusStatistics() {
        try {
            return new ResponseEntity<>(this.dashboardApiService.jobStatusStatistics(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while jobStatusStatistics ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/jobRunningStatistics", method = RequestMethod.GET)
    public ResponseEntity<?> jobRunningStatistics() {
        try {
            return new ResponseEntity<>(this.dashboardApiService.jobRunningStatistics(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while jobRunningStatistics ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/weeklyRunningJobStatistics", method = RequestMethod.GET)
    public ResponseEntity<?> weeklyRunningJobStatistics(@RequestParam(name = "startDate", required = true) String startDate,
            @RequestParam(name = "endDate", required = true) String endDate) {
        try {
            return new ResponseEntity<>(this.dashboardApiService.weeklyRunningJobStatistics(startDate, endDate), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while weeklyRunningJobStatistics ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/viewRunningJobDateByTargetClickJobStatistics", method = RequestMethod.GET)
    public ResponseEntity<?> viewRunningJobDateByTargetClickJobStatistics(String startDate, String endDate, String targetClick) {
        try {
            return new ResponseEntity<>(this.dashboardApiService.viewRunningJobDateByTargetClickJobStatistics(startDate,
                endDate, targetClick), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while viewRunningJobDateByTargetClickJobStatistics ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

}