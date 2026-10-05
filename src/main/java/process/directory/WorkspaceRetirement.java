package process.directory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * What Core does when Identity deletes a workspace (MIG-166): a status update, never a cascade.
 *
 * The workspace's active jobs are made Inactive -- source_job's trigger then takes their schedules out of
 * dispatch (V84) -- so nothing keeps running for a workspace that no longer exists, and nothing is removed:
 * a workspace restored in Identity has every job, task and run it had, to switch back on. Idempotent: the
 * same deletion heard twice, or re-applied by the orphan audit, changes nothing the second time.
 *
 * A deletion older than what Core already knows of the workspace is not acted on (event audit E8): the topic
 * is compacted and read from the beginning, so a replay -- a new consumer group, a reset offset -- hands over
 * tenant.deleted events long since followed by a restore, and switching the restored workspace's jobs off
 * again would be wrong. workspace_directory (written by its own listener) holds the newest state seen.
 *
 * @author Nabeel Ahmed
 */
@Component
public class WorkspaceRetirement {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceRetirement.class);

    private final JdbcTemplate jdbc;

    public WorkspaceRetirement(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The deletion Identity announced at deletedAt (its event's updatedAt); answers how many jobs were switched off -- none
     * when workspace_directory already holds a newer state of the workspace (a restore after this deletion). A null
     * deletedAt, or a workspace the directory has not seen, is acted on.
     */
    public int retire(long tenantId, Instant deletedAt) {
        if (deletedAt != null) {
            List<Timestamp> known = this.jdbc.queryForList("SELECT updated_at FROM workspace_directory WHERE tenant_id = ?", Timestamp.class,
                tenantId);
            if (!known.isEmpty() && known.get(0) != null && known.get(0).toInstant().isAfter(deletedAt)) {
                logger.info("Workspace {}'s deletion of {} is older than its state of {} in the directory (restored since): its jobs are"
                    + " left as they are.", tenantId, deletedAt, known.get(0).toInstant());
                return 0;
            }
        }
        return this.retire(tenantId);
    }

    /** Answers how many jobs were switched off. */
    public int retire(long tenantId) {
        int stopped = this.jdbc.update("UPDATE source_job SET job_status = 'Inactive' WHERE tenant_id = ? AND job_status = 'Active'",
            tenantId);
        if (stopped > 0) {
            logger.info("Workspace {} was deleted in Identity: {} active job(s) made Inactive; nothing removed.", tenantId, stopped);
        }
        return stopped;
    }
}
