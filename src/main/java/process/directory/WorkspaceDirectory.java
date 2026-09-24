package process.directory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * workspace_directory: Core's local view of each workspace's status in Identity (owner decision 2026-09-24,
 * MIG-166 follow-up).
 *
 * A workspace that is neither Active nor deleted -- Suspended, Inactive -- has its scheduled jobs PAUSED: the
 * enqueuer records each due slot as a Skip and moves the schedule on, and the jobs resume by themselves when
 * the workspace is Active again. A deleted one is not paused: WorkspaceRetirement makes its jobs Inactive.
 *
 * Fed by platform.identity.tenant.v1 through IdentityEventsListener, and read on the enqueue path -- which is
 * why it is a table and not a call: the enqueuer holds a slot FOR UPDATE SKIP LOCKED while it decides, and
 * must never wait on Identity. A workspace the view has not heard of is treated as Active, today's behaviour,
 * so an empty or lagging view can delay a pause but never stops a schedule by itself.
 *
 * Each row carries the instant its state was true at Identity; a write that is not newer changes nothing, so
 * events redelivered late or out of order never roll a status back. status_since is when the current status
 * began: one pause, one audit line per job (scheduler.paused_since).
 *
 * @author Nabeel Ahmed
 */
@Component
public class WorkspaceDirectory {

    public static final String ACTIVE = "Active";
    public static final String DELETED = "Delete";

    /** A paused workspace: its status in Identity and when that status began. */
    public static final class Pause {
        private final long tenantId;
        private final String status;
        private final Timestamp since;

        public Pause(long tenantId, String status, Timestamp since) {
            this.tenantId = tenantId;
            this.status = Objects.requireNonNull(status, "status");
            this.since = Objects.requireNonNull(since, "since");
        }

        public long getTenantId() { return this.tenantId; }
        public String getStatus() { return this.status; }
        public Timestamp getSince() { return this.since; }
    }

    private final JdbcTemplate jdbc;

    public WorkspaceDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether a workspace in this status has its schedules paused: anything but Active, and not deleted. */
    public static boolean pauses(String status) {
        return status != null && !ACTIVE.equals(status) && !DELETED.equals(status);
    }

    /** A status the view took: the one it replaced (null for a workspace new to the view) and the new one. */
    public static final class Change {
        private final String before;
        private final String after;

        Change(String before, String after) {
            this.before = before;
            this.after = after;
        }

        public String getBefore() { return this.before; }
        public String getAfter() { return this.after; }
        /** The workspace's schedules were running and are paused from now on. */
        public boolean pausedNow() { return !pauses(this.before) && pauses(this.after); }
        /** The workspace's schedules were paused and run again from now on. */
        public boolean resumedNow() { return pauses(this.before) && !pauses(this.after); }
    }

    /**
     * Stores Identity's status for the workspace unless the view already holds a state at least as new;
     * answers what changed, or empty when the event was not newer and nothing was written.
     */
    public Optional<Change> apply(long tenantId, String status, Instant updatedAt) {
        Timestamp at = Timestamp.from(Objects.requireNonNull(updatedAt, "updatedAt"));
        List<Change> written = this.jdbc.query("WITH before AS (SELECT status FROM workspace_directory WHERE tenant_id = ?) "
                + "INSERT INTO workspace_directory AS w (tenant_id, status, status_since, updated_at) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (tenant_id) DO UPDATE SET status = EXCLUDED.status, "
                + "status_since = CASE WHEN w.status = EXCLUDED.status THEN w.status_since ELSE EXCLUDED.status_since END, "
                + "updated_at = EXCLUDED.updated_at WHERE w.updated_at < EXCLUDED.updated_at "
                + "RETURNING (SELECT status FROM before), w.status",
            (rs, i) -> new Change(rs.getString(1), rs.getString(2)), tenantId, tenantId,
            Objects.requireNonNull(status, "status"), at, at);
        return written.stream().findFirst();
    }

    /** The workspace's pause, if it is paused; empty when Active, deleted, or unknown to the view. */
    public Optional<Pause> pauseOf(long tenantId) {
        return this.first(this.jdbc.query("SELECT tenant_id, status, status_since FROM workspace_directory WHERE tenant_id = ?",
            (rs, i) -> new Pause(rs.getLong(1), rs.getString(2), rs.getTimestamp(3)), tenantId));
    }

    /** The pause of the workspace a job belongs to, in one local query -- what the enqueuer asks per slot. */
    public Optional<Pause> pauseOfJob(long jobId) {
        return this.first(this.jdbc.query("SELECT w.tenant_id, w.status, w.status_since FROM source_job j "
                + "JOIN workspace_directory w ON w.tenant_id = j.tenant_id WHERE j.job_id = ?",
            (rs, i) -> new Pause(rs.getLong(1), rs.getString(2), rs.getTimestamp(3)), jobId));
    }

    private Optional<Pause> first(List<Pause> rows) {
        return rows.stream().filter(pause -> pauses(pause.getStatus())).findFirst();
    }
}
