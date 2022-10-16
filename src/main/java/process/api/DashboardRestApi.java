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

    /**
     * Integration Status :- done
     * Api use to fetch the donat chart statistics
     * @return ResponseEntity<?>
     * */
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

    /**
     * Integration Status :- done
     * Api use to fetch the circle chart statistics
     * @return ResponseEntity<?>
     * */
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

    /**
     * Integration Status :- done
     * Api use to fetch the line chart statistics
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/weeklyRunningJobStatistics", method = RequestMethod.GET)
    public ResponseEntity<?> weeklyRunningJobStatistics(@RequestParam(name = "startDate") String startDate,
        @RequestParam(name = "endDate") String endDate) {
        try {
            return new ResponseEntity<>(this.dashboardApiService.weeklyRunningJobStatistics(startDate, endDate), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while weeklyJobRunningStatistics ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to fetch the heat-map chart statistics
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/weeklyHrsRunningJobStatistics", method = RequestMethod.GET)
    public ResponseEntity<?> weeklyHrsRunningJobStatistics(@RequestParam(name = "startDate") String startDate,
        @RequestParam(name = "endDate") String endDate) {
        try {
            return new ResponseEntity<>(this.dashboardApiService.weeklyHrsRunningJobStatistics(startDate, endDate), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while weeklyHrsRunningJobStatistics ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to fetch the weekly-hr-job dimension statistics
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/weeklyHrRunningStatisticsDimension", method = RequestMethod.GET)
    public ResponseEntity<?> weeklyHrRunningStatisticsDimension(@RequestParam(name = "targetDate") String targetDate,
        @RequestParam(name = "targetHr") Long targetHr) {
        try {
            return new ResponseEntity<>(this.dashboardApiService.weeklyHrRunningStatisticsDimension(targetDate, targetHr), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while weeklyHrRunningStatisticsDimension ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- pending
     * Api use to fetch the weekly-hr-job history detail
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/viewRunningJobDateByTargetClickJobStatistics", method = RequestMethod.GET)
    public ResponseEntity<?> viewRunningJobDateByTargetClickJobStatistics(@RequestParam(name = "targetDate") String targetDate,
        @RequestParam(name = "targetHr") Long targetHr) {
        try {
            return new ResponseEntity<>(this.dashboardApiService.viewRunningJobDateByTargetClickJobStatistics(targetDate, targetHr), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while viewRunningJobDateByTargetClickJobStatistics ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

}