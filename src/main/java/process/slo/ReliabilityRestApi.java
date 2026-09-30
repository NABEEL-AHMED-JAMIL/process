package process.slo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.model.dto.ResponseDto;
import process.util.ProcessUtil;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The console's Reliability page (MIG-196), platform administrators only. The definitions are etl-platform
 * docs/SLO.md's; in short:
 *
 * <p><b>Pipeline execution rate</b> = numerator / denominator over a window [from, to) of when runs ENDED
 * (coalesce(end_time, skip_time) of job_queue):
 * <ul>
 *   <li>One operation is a run: one job_queue row. A retry reuses the row and writes no terminal status (C7c), so a run
 *       is counted once, by the status its last attempt ended in.</li>
 *   <li>Numerator: runs that ended Completed.</li>
 *   <li>Denominator: runs that ended Completed, Failed or Interrupt, less the excluded. Bad: a worker's Failed after its
 *       retries, a decline (Start -> Failed, MIG-201: not billed, counted a failed run), a dispatch out of retries, and
 *       the stall sweep's Interrupt (it writes Interrupt, never Failed -- C4).</li>
 *   <li>Excluded: Skip and Missed (never attempted), a configuration refusal before dispatch, a pipeline AI step's
 *       refusal or failure before dispatch (no call made, nothing billed), and a run a person failed or interrupted.</li>
 * </ul>
 * Each terminal write records why in job_queue.end_reason (RunEnd); RunSlo is the one classification, used by the live
 * counter process.runs.ended and by this report alike.
 *
 * <p><b>Billing rate</b> is Billing's (GET /billing.json/reliability): accepted usage events priced by their day's
 * rollup over accepted events, past a five-minute grace.
 *
 * <p>Target 99.99% for both; the window is a rolling 28 days (four of each weekday); error budget = 0.01% of the
 * window's counted operations; errorBudgetRemaining = 1 - bad / budget.
 *
 * <ul>
 *   <li>GET /reliability.json/pipelineRuns?from=&amp;to= -- the window's figures from stored rows, by (status, reason),
 *       and day by day (UTC). Instants with their offset; "to" defaults to now and "from" to 28 days before it. Any past
 *       window can be asked: the answer depends only on the rows.</li>
 *   <li>GET /reliability.json/burnRates -- both SLOs under the four multiwindow burn-rate rules, now (SloBurnRates);
 *       the rules SloBurnAlerts notifies platform administrators on.</li>
 * </ul>
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/reliability.json")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class ReliabilityRestApi {

    /** A longer question is a batch job's, not a request's (InternalSloRestApi's limit). */
    static final Duration LONGEST = InternalSloRestApi.LONGEST;

    private final RunSloReport report;
    private final SloBurnRates burnRates;
    private final Clock clock;

    @Autowired
    public ReliabilityRestApi(RunSloReport report, SloBurnRates burnRates) {
        this(report, burnRates, Clock.systemUTC());
    }

    ReliabilityRestApi(RunSloReport report, SloBurnRates burnRates, Clock clock) {
        this.report = report;
        this.burnRates = burnRates;
        this.clock = clock;
    }

    @GetMapping(value = "/pipelineRuns")
    public ResponseEntity<ResponseDto> pipelineRuns(@RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to) {
        Instant end;
        Instant start;
        try {
            end = blank(to) ? this.clock.instant() : Instant.parse(to.trim());
            start = blank(from) ? end.minus(InternalSloRestApi.DEFAULT_WINDOW) : Instant.parse(from.trim());
        } catch (DateTimeParseException unreadable) {
            return refused("from and to are instants with an offset, e.g. 2026-09-01T00:00:00Z.");
        }
        if (!start.isBefore(end)) {
            return refused("from must be before to.");
        }
        if (Duration.between(start, end).compareTo(LONGEST) > 0) {
            return refused("Ask for at most " + LONGEST.toDays() + " days at a time.");
        }
        Map<String, Object> figures = this.report.measure(start, end).toMap();
        List<Map<String, Object>> daily = new ArrayList<>();
        for (RunSloReport.Day day : this.report.measureDaily(start, end)) {
            daily.add(day.toMap());
        }
        figures.put("daily", daily);
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Pipeline execution SLO.", figures), HttpStatus.OK);
    }

    @GetMapping(value = "/burnRates")
    public ResponseEntity<ResponseDto> burnRates() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SloBurnRates.Evaluation evaluation : this.burnRates.evaluate()) {
            rows.add(evaluation.toMap());
        }
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "SLO burn rates.", rows), HttpStatus.OK);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static ResponseEntity<ResponseDto> refused(String message) {
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, message), HttpStatus.BAD_REQUEST);
    }
}
