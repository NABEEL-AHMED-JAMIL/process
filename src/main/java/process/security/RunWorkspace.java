package process.security;

import org.barco.platform.tenancy.AcrossTenants;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The workspace a worker's callback works in (MIG-258). A callback carries no person's token -- only its run's id
 * and the run's own callback token -- so nothing has set a workspace on the thread, and under row-level security a
 * session with none sees no run at all. The run's workspace is read here, across workspaces, by the id the callback
 * names; everything after -- the token check included -- runs as that one workspace
 * ({@code RowSecurity.forTenant(runWorkspace.of(id), ...)}), so a callback can reach no other workspace's rows
 * whatever its code asks for. A run that does not exist is workspace 0, which is none: its token check then finds
 * nothing and refuses it exactly as before.
 *
 * @author Nabeel Ahmed
 */
@Component
public class RunWorkspace {

    private final JdbcTemplate jdbc;

    public RunWorkspace(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The run's workspace, or 0 (no workspace) for a run that does not exist. */
    @AcrossTenants("a worker's callback names only its run: the run's workspace is read by its id, before anything else")
    public long of(Long jobQueueId) {
        if (jobQueueId == null) {
            return 0L;
        }
        List<Long> found = this.jdbc.queryForList("SELECT tenant_id FROM job_queue WHERE job_queue_id = ?", Long.class, jobQueueId);
        return found.isEmpty() || found.get(0) == null ? 0L : found.get(0);
    }
}
