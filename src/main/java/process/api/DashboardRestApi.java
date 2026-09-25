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
 * The job-statistics read surface: the Operations dashboard's tiles and heatmap, its drill-down into
 * runs, and the people dashboard's totals. Every figure is counted from Core's own tables (source_job,
 * job_queue), so Core owns it (MIG-82, 2026-09-24): Analytics has no job statistics and reads none of
 * these tables, and nothing but the console calls this.
 *
 * <b>Callers.</b> The console only (scheduler1/next: features/dashboard, features/jobs/history,
 * features/admin/users), through the gateway's dashboard.json route. No other service calls it.
 * etl-platform/api-check pins all seven: 200 for every signed-in role, including a tenant user
 * holding no page (the surface is deliberately not page-gated), 401 without a token.
 *
 * <b>Scope.</b> hasRole('TENANT_USER') on the class, and no endpoint may carry its own rule
 * (DashboardScopeTest). Every query adds QueryService.tenantClause: a caller sees their workspace
 * only, a platform admin every workspace (audited), a caller with no workspace nothing; each
 * aggregate names the workspace it covers (tenantId, or allWorkspaces for a platform admin). Pinned against the real
 * schema by DashboardTenantScopePostgresTest and UserStatisticsPostgresTest.
 *
 * <b>Dates</b> are yyyy-MM-dd, read as America/Chicago calendar days; a malformed one is refused
 * (ERROR and its sentence), never answered with an empty chart. Every response is a ResponseDto.
 *
 * <ul>
 *   <li>{@code GET jobStatusStatistics?startDate&endDate} -- jobs by Active / Inactive, plus All.</li>
 *   <li>{@code GET jobRunningStatistics?startDate&endDate} -- jobs by their last run's state.</li>
 *   <li>{@code GET weeklyRunningJobStatistics?startDate&endDate} -- runs per day.</li>
 *   <li>{@code GET weeklyHrsRunningJobStatistics?startDate&endDate} -- runs per day and hour (the heatmap).</li>
 *   <li>{@code GET weeklyHrRunningStatisticsDimension?targetDate&targetHr} -- one heatmap cell: runs
 *       per job and state, and a TOTAL row.</li>
 *   <li>{@code GET weeklyHrRunningStatisticsDimensionDetail?targetDate&targetHr&jobStatus&jobId} --
 *       the runs behind a cell; with jobId, also that job and its all-time run counts
 *       (QueryService.statisticsBySourceJobId).</li>
 *   <li>{@code GET userStatistics?startDate&endDate} -- per person: jobs, active jobs, tasks, runs,
 *       completed and failed; a tenant user gets their own row.</li>
 * </ul>
 *
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