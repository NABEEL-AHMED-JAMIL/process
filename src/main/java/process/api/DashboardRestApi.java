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

/**
 * @author Nabeel Ahmed
 * */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/dashboard.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class DashboardRestApi {

    // No try/catch per endpoint (MIG-103): each one caught everything and answered 500, so a
    // malformed date looked exactly like a dead connection pool. GlobalExceptionHandler decides --
    // a RequestRefused is a 200 carrying ERROR and its sentence, anything else the fixed 500.

    private Logger logger = LoggerFactory.getLogger(DashboardRestApi.class);

    private final DashboardService dashboardService;

    public DashboardRestApi(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @RequestMapping(value = "/jobStatusStatistics", method = RequestMethod.GET)
    public ResponseEntity<?> jobStatusStatistics(
        @RequestParam(name = "startDate", required = false) String startDate,
        @RequestParam(name = "endDate", required = false) String endDate) throws Exception {
        return new ResponseEntity<>(this.dashboardService.jobStatusStatistics(startDate, endDate), HttpStatus.OK);
    }

    /** Per-user totals for the people dashboard: what each user owns and how it has gone. */
    @RequestMapping(value = "/userStatistics", method = RequestMethod.GET)
    public ResponseEntity<?> userStatistics(
        @RequestParam(name = "startDate", required = false) String startDate,
        @RequestParam(name = "endDate", required = false) String endDate) throws Exception {
        return new ResponseEntity<>(this.dashboardService.userStatistics(startDate, endDate), HttpStatus.OK);
    }

    @RequestMapping(value = "/jobRunningStatistics", method = RequestMethod.GET)
    public ResponseEntity<?> jobRunningStatistics(
        @RequestParam(name = "startDate", required = false) String startDate,
        @RequestParam(name = "endDate", required = false) String endDate) throws Exception {
        return new ResponseEntity<>(this.dashboardService.jobRunningStatistics(startDate, endDate), HttpStatus.OK);
    }

    @RequestMapping(value = "/weeklyRunningJobStatistics", method = RequestMethod.GET)
    public ResponseEntity<?> weeklyRunningJobStatistics(
        @RequestParam(name = "startDate") String startDate,
        @RequestParam(name = "endDate") String endDate) throws Exception {
        return new ResponseEntity<>(this.dashboardService.weeklyRunningJobStatistics(startDate, endDate), HttpStatus.OK);
    }

    @RequestMapping(value = "/weeklyHrsRunningJobStatistics", method = RequestMethod.GET)
    public ResponseEntity<?> weeklyHrsRunningJobStatistics(
        @RequestParam(name = "startDate") String startDate,
        @RequestParam(name = "endDate") String endDate) throws Exception {
        return new ResponseEntity<>(this.dashboardService.weeklyHrsRunningJobStatistics(startDate, endDate), HttpStatus.OK);
    }

    @RequestMapping(value = "/weeklyHrRunningStatisticsDimension", method = RequestMethod.GET)
    public ResponseEntity<?> weeklyHrRunningStatisticsDimension(
        @RequestParam(name = "targetDate") String targetDate,
        @RequestParam(name = "targetHr") Long targetHr) throws Exception {
        return new ResponseEntity<>(this.dashboardService.weeklyHrRunningStatisticsDimension(targetDate, targetHr), HttpStatus.OK);
    }

    @RequestMapping(value = "/weeklyHrRunningStatisticsDimensionDetail", method = RequestMethod.GET)
    public ResponseEntity<?> weeklyHrRunningStatisticsDimensionDetail(
        @RequestParam(name = "targetDate", required = false) String targetDate,
        @RequestParam(name = "targetHr", required = false) Long targetHr,
        @RequestParam(name = "jobStatus", required = false) String jobStatus,
        @RequestParam(name = "jobId", required = false) Long jobId) throws Exception {
        return new ResponseEntity<>(this.dashboardService.weeklyHrRunningStatisticsDimensionDetail(targetDate, targetHr, jobStatus, jobId), HttpStatus.OK);
    }

}