package process.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.billing.WorkspaceZones;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.util.BusinessTime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;

/**
 * MIG-347: what a run reads as {{param.*}} and {{run.*}} -- worked out once per attempt, when the step engine starts it.
 *
 * <ul>
 *   <li>Parameters: the values the run was given (job_queue.run_params: Run with..., a backfill), else each parameter's
 *   default, checked again against the definition the run follows; a required parameter with neither fails the run.</li>
 *   <li>run.*: a backfill run's own (written with it); else a scheduled run's slot (run_slot_at) -- its day in the
 *   workspace's billing zone, or its hour for a minute or hourly schedule; else today there (a run started by hand, a
 *   file, a form, a document or the API).</li>
 * </ul>
 */
@Component
public class RunValuesResolver {

    private final WorkspaceZones zones;
    /** A job's schedule frequency (scheduler.frequency), or null: by job id. */
    private final Function<Long, String> frequencies;

    @Autowired
    public RunValuesResolver(WorkspaceZones zones, JdbcTemplate jdbc) {
        this(zones, jobId -> {
            List<String> found = jdbc.queryForList("SELECT frequency FROM scheduler WHERE job_id = ? ORDER BY scheduler_id DESC LIMIT 1",
                String.class, jobId);
            return found.isEmpty() ? null : found.get(0);
        });
    }

    RunValuesResolver(WorkspaceZones zones, Function<Long, String> frequencies) {
        this.zones = zones;
        this.frequencies = frequencies;
    }

    /** For an engine without one wired (tests): the business zone, a slot read as a day. */
    static RunValuesResolver plain() {
        return new RunValuesResolver(null, jobId -> null);
    }

    /** The run's values, or why it cannot run: a parameter problem. */
    public static final class Outcome {
        public final RunParameters.RunValues values;
        public final String refusal;

        Outcome(RunParameters.RunValues values, String refusal) {
            this.values = values;
            this.refusal = refusal;
        }
    }

    public Outcome resolve(SourceJob job, JobQueue run, PipelineDefinition definition) {
        RunParameters.Stored stored = RunParameters.read(run.getRunParams());
        RunParameters.Resolved resolved = RunParameters.resolve(definition == null ? null : definition.effectiveParameters(), stored.params);
        if (!resolved.ok()) {
            return new Outcome(null, "Its parameters do not stand: " + resolved.sentence());
        }
        if (stored.run != null) {
            return new Outcome(new RunParameters.RunValues(resolved.values, stored.run.date, stored.run.windowStart, stored.run.windowEnd,
                stored.run.zone), null);
        }
        ZoneId zone = this.zones == null ? BusinessTime.ZONE : this.zones.zoneOf(job == null ? run.getTenantId() : job.getTenantId());
        if (run.getRunSlotAt() != null) {
            Instant slot = BusinessTime.instantOf(run.getRunSlotAt());
            String frequency = run.getJobId() == null ? null : this.frequencies.apply(run.getJobId());
            if ("Mint".equals(frequency) || "Hr".equals(frequency)) {
                return new Outcome(RunParameters.RunValues.ofHour(resolved.values, slot, zone), null);
            }
            return new Outcome(RunParameters.RunValues.ofDay(resolved.values, LocalDate.ofInstant(slot, zone), zone), null);
        }
        Instant now = BusinessTime.instantOf(BusinessTime.now());
        return new Outcome(RunParameters.RunValues.ofDay(resolved.values, LocalDate.ofInstant(now, zone), zone), null);
    }
}
