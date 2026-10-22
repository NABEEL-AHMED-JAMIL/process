package process.pipeline.items;

import process.security.TenantContext;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.model.pojo.JobQueue;

/**
 * MIG-420: every item file opened or downloaded, every zip of an item's files and every signed link made for one -- served or
 * refused -- in file_access_log (V190, kinds run_item_file and run_item_zip since V233), as run dataset downloads are.
 */
@Component
public class ItemAccessLog {

    private static final Logger logger = LoggerFactory.getLogger(ItemAccessLog.class);

    public static final String DOWNLOAD = "download";
    public static final String PREVIEW = "preview";
    public static final String LINK = "link";
    public static final String ITEM_FILE = "run_item_file";
    public static final String ITEM_ZIP = "run_item_zip";

    private final JdbcTemplate jdbc;

    public ItemAccessLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One access of a run's item files, in the run's workspace. */
    public void log(JobQueue run, String kind, String action, String fileName, boolean served, int status, String reason) {
        Long userId = TenantContext.getAppUserId();
        Long tenant = run == null ? TenantContext.getTenantId() : run.getTenantId();
        logger.info("File access: {} {} of run {} by user {} in workspace {}: {} {}", kind, action, run == null ? null : run.getJobQueueId(),
            userId, tenant, served ? "served" : "refused", status);
        if (tenant == null || tenant < 1) {
            return;
        }
        try {
            RowSecurity.forTenant(tenant, () -> this.jdbc.update("INSERT INTO file_access_log (tenant_id, user_id, action, kind, job_queue_id, "
                    + "file_name, outcome, http_status, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", tenant, userId, action, kind,
                run == null ? null : run.getJobQueueId(), cut(fileName), served ? "served" : "refused", status, cut(reason)));
        } catch (RuntimeException ex) {
            logger.warn("The item file access of run {} by user {} could not be recorded: {}", run == null ? null : run.getJobQueueId(), userId,
                ex.getMessage());
        }
    }

    /** A refusal before the run was found: written in the caller's own workspace, naming the run asked for. */
    public void refusedRun(Long jobQueueId, String kind, String action, int status, String reason) {
        Long userId = TenantContext.getAppUserId();
        Long tenant = TenantContext.getTenantId();
        logger.info("File access: {} {} of run {} by user {} in workspace {}: refused {}", kind, action, jobQueueId, userId, tenant, status);
        if (tenant == null || tenant < 1) {
            return;
        }
        try {
            RowSecurity.forTenant(tenant, () -> this.jdbc.update("INSERT INTO file_access_log (tenant_id, user_id, action, kind, job_queue_id, "
                + "outcome, http_status, reason) VALUES (?, ?, ?, ?, NULL, 'refused', ?, ?)", tenant, userId, action, kind, status,
                cut(reason + (jobQueueId == null ? "" : " (run " + jobQueueId + ")"))));
        } catch (RuntimeException ex) {
            logger.warn("A refused item file access by user {} could not be recorded: {}", userId, ex.getMessage());
        }
    }

    private static String cut(String text) {
        return text == null || text.length() <= 512 ? text : text.substring(0, 512);
    }
}
