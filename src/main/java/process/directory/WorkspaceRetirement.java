package process.directory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What Core does when Identity deletes a workspace (MIG-166): a status update, never a cascade.
 *
 * The workspace's active jobs are made Inactive -- source_job's trigger then takes their schedules out of
 * dispatch (V84) -- so nothing keeps running for a workspace that no longer exists, and nothing is removed:
 * a workspace restored in Identity has every job, task and run it had, to switch back on. Idempotent: the
 * same deletion heard twice, or re-applied by the orphan audit, changes nothing the second time.
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
