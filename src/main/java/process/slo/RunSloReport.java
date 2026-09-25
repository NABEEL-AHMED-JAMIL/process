package process.slo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.model.enums.JobStatus;
import process.model.enums.RunEnd;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pipeline execution SLI for any window, from stored rows (MIG-196; etl-platform docs/SLO.md). Each run is one
 * job_queue row -- a retry reuses its row (C7c), so a run retried twice is still one run -- counted in the window its
 * current terminal status was written in: coalesce(end_time, skip_time), which idx_job_queue_ended_at indexes. The
 * row's end_reason says which path closed it and RunSlo decides good, bad or excluded, the same rule the live
 * counter (RunOutcomes) uses.
 *
 * A run that ended before V174 has no end_reason. It is given the one its status line proves -- a person's fail or
 * interrupt from the console, the stall sweep's two messages, a skip, a missed slot -- and is otherwise judged on its
 * status alone ("unattributed"): Completed good, Failed and Interrupt bad. That errs towards bad: a pre-V174
 * configuration refusal or AI step failure counts against the SLI.
 */
@Component
public class RunSloReport {

    /** The reason a pre-V174 row is given when its status line proves none. */
    public static final String UNATTRIBUTED = "UNATTRIBUTED";

    /** [from, to) on when the run ended; one row per (status, reason). The patterns are the writers' own sentences. */
    static final String QUERY = "select q.job_status, coalesce(q.end_reason, case "
        + "when q.job_status = 'Skip' then 'SKIPPED' "
        + "when q.job_status = 'Missed' then 'MISSED' "
        + "when q.job_status = 'Failed' and q.job_status_message ~ '^Job [0-9]+ fail by manual\\.$' then 'OPERATOR' "
        + "when q.job_status = 'Interrupt' and q.job_status_message ~ '^Job [0-9]+ interrupted\\.$' then 'OPERATOR' "
        + "when q.job_status = 'Interrupt' and q.job_status_message like '%callback token had expired%' then 'TOKEN_EXPIRED' "
        + "when q.job_status = 'Interrupt' and q.job_status_message like '%stopped reporting and was closed after%' then 'STALLED' "
        + "else '" + UNATTRIBUTED + "' end) as reason, count(*) as runs "
        + "from job_queue q "
        + "where coalesce(q.end_time, q.skip_time) >= ? and coalesce(q.end_time, q.skip_time) < ? "
        + "and q.job_status in ('Completed', 'Failed', 'Interrupt', 'Skip', 'Missed') "
        + "group by 1, 2 order by 1, 2";

    private final JdbcTemplate sql;

    public RunSloReport(JdbcTemplate sql) {
        this.sql = sql;
    }

    /** One (status, reason) group and how the SLI counts it. */
    public static final class Group {
        public final JobStatus status;
        /** Null for an unattributed pre-V174 run. */
        public final RunEnd reason;
        public final RunSlo slo;
        public final long runs;

        Group(JobStatus status, RunEnd reason, long runs) {
            this.status = status;
            this.reason = reason;
            this.slo = RunSlo.of(status, reason);
            this.runs = runs;
        }
    }

    /** The window's figures. */
    public static final class Window {
        public final Instant from;
        public final Instant to;
        public final List<Group> groups;
        public final long good;
        public final long bad;
        public final long excluded;

        Window(Instant from, Instant to, List<Group> groups) {
            this.from = from;
            this.to = to;
            this.groups = Collections.unmodifiableList(groups);
            long g = 0, b = 0, x = 0;
            for (Group group : groups) {
                if (group.slo == RunSlo.GOOD) {
                    g += group.runs;
                } else if (group.slo == RunSlo.BAD) {
                    b += group.runs;
                } else {
                    x += group.runs;
                }
            }
            this.good = g;
            this.bad = b;
            this.excluded = x;
        }

        /** good / (good + bad); null when no run counted. */
        public Double successRate() {
            long counted = this.good + this.bad;
            return counted == 0 ? null : (double) this.good / counted;
        }

        /** The bad runs the window's volume allows at the target: (good + bad) x (1 - target). */
        public double errorBudget() {
            return (this.good + this.bad) * (1 - RunSlo.TARGET);
        }

        /** bad / budget: 1.0 is the whole budget spent; null when no run counted. */
        public Double budgetSpent() {
            double budget = this.errorBudget();
            return budget == 0 ? null : this.bad / budget;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sli", "pipeline_execution");
            out.put("from", this.from.toString());
            out.put("to", this.to.toString());
            out.put("target", RunSlo.TARGET);
            out.put("good", this.good);
            out.put("bad", this.bad);
            out.put("excluded", this.excluded);
            out.put("successRate", this.successRate());
            out.put("errorBudgetRuns", this.errorBudget());
            out.put("budgetSpent", this.budgetSpent());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Group group : this.groups) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("outcome", group.status.name());
                row.put("reason", group.reason == null ? UNATTRIBUTED : group.reason.name());
                row.put("slo", group.slo.tag());
                row.put("runs", group.runs);
                rows.add(row);
            }
            out.put("groups", rows);
            return out;
        }
    }

    public Window measure(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("The window must start before it ends: " + from + " .. " + to);
        }
        List<Group> groups = this.sql.query(QUERY, (rs, i) -> {
            String reason = rs.getString("reason");
            return new Group(JobStatus.valueOf(rs.getString("job_status")),
                UNATTRIBUTED.equals(reason) ? null : RunEnd.valueOf(reason), rs.getLong("runs"));
        }, Timestamp.from(from), Timestamp.from(to));
        return new Window(from, to, groups);
    }
}
