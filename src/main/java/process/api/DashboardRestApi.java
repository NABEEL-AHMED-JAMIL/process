package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ResponseDto;
import process.model.service.DashboardService;
import process.util.ProcessUtil;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/dashboard.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class DashboardRestApi {

    private Logger logger = LoggerFactory.getLogger(DashboardRestApi.class);

    private final DashboardService dashboardService;

    public DashboardRestApi(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @RequestMapping(value = "/jobStatusStatistics", method = RequestMethod.GET)
    public ResponseEntity<?> jobStatusStatistics(
        @RequestParam(name = "startDate", required = false) String startDate,
        @RequestParam(name = "endDate", required = false) String endDate) {
        try {
            return new ResponseEntity<>(this.dashboardService.jobStatusStatistics(startDate, endDate), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while jobStatusStatistics ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/jobRunningStatistics", method = RequestMethod.GET)
    public ResponseEntity<?> jobRunningStatistics(
        @RequestParam(name = "startDate", required = false) String startDate,
        @RequestParam(name = "endDate", required = false) String endDate) {
        try {
            return new ResponseEntity<>(this.dashboardService.jobRunningStatistics(startDate, endDate), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while jobRunningStatistics ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/weeklyRunningJobStatistics", method = RequestMethod.GET)
    public ResponseEntity<?> weeklyRunningJobStatistics(
        @RequestParam(name = "startDate") String startDate,
        @RequestParam(name = "endDate") String endDate) {
        try {
            return new ResponseEntity<>(this.dashboardService.weeklyRunningJobStatistics(startDate, endDate), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while weeklyJobRunningStatistics ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/weeklyHrsRunningJobStatistics", method = RequestMethod.GET)
    public ResponseEntity<?> weeklyHrsRunningJobStatistics(
        @RequestParam(name = "startDate") String startDate,
        @RequestParam(name = "endDate") String endDate) {
        try {
            return new ResponseEntity<>(this.dashboardService.weeklyHrsRunningJobStatistics(startDate, endDate), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while weeklyHrsRunningJobStatistics ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/weeklyHrRunningStatisticsDimension", method = RequestMethod.GET)
    public ResponseEntity<?> weeklyHrRunningStatisticsDimension(
        @RequestParam(name = "targetDate") String targetDate,
        @RequestParam(name = "targetHr") Long targetHr) {
        try {
            return new ResponseEntity<>(this.dashboardService.weeklyHrRunningStatisticsDimension(targetDate, targetHr), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while weeklyHrRunningStatisticsDimension ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/weeklyHrRunningStatisticsDimensionDetail", method = RequestMethod.GET)
    public ResponseEntity<?> weeklyHrRunningStatisticsDimensionDetail(
        @RequestParam(name = "targetDate", required = false) String targetDate,
        @RequestParam(name = "targetHr", required = false) Long targetHr,
        @RequestParam(name = "jobStatus", required = false) String jobStatus,
        @RequestParam(name = "jobId", required = false) Long jobId) {
        try {
            return new ResponseEntity<>(this.dashboardService.weeklyHrRunningStatisticsDimensionDetail(targetDate, targetHr, jobStatus, jobId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while weeklyHrRunningStatisticsDimensionDetail ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

}