package process.pipeline;

import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.security.TenantContext;

/**
 * Every download of a run dataset through the console, served or refused (MIG-243, file_access_log): who, which dataset
 * of which run, and the answer. Written in the dataset's workspace when the dataset is the caller's; a refusal of one
 * that is not (404 -- another workspace's, or none) is written in the caller's own workspace, without the run. A caller
 * with no workspace of their own (a platform administrator, or a sign-in naming none) asking for a dataset that is not
 * theirs leaves only the application log line.
 *
 * Its own statement, outside the download's read-only transaction; a record that cannot be written never stops the
 * download, and says so in the log.
 */
@Component
public class FileAccessLog {

    public static final String DOWNLOAD = "download";
    public static final String RUN_DATASET = "run_dataset";

    private static final Logger logger = LoggerFactory.getLogger(FileAccessLog.class);

    private final JdbcTemplate jdbc;

    public FileAccessLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One download of a run dataset, as the service answered it. */
    public void runDataset(Long runDatasetId, String format, StepTimelineService.Download answer) {
        Long userId = TenantContext.getAppUserId();
        Long tenant = answer.tenantId != null ? answer.tenantId : TenantContext.getTenantId();
        boolean served = answer.refusal == null;
        String reason = served ? null : answer.refusal.getMessage();
        logger.info("File access: run dataset {} ({}) by user {} in workspace {}: {} {}", runDatasetId, format, userId, tenant,
            served ? "served" : "refused", answer.status);
        if (tenant == null || tenant < 1) {
            return;
        }
        try {
            RowSecurity.forTenant(tenant, () -> this.jdbc.update("INSERT INTO file_access_log (tenant_id, user_id, action, kind, run_dataset_id, "
                    + "job_queue_id, file_name, format, outcome, http_status, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", tenant, userId,
                DOWNLOAD, RUN_DATASET, runDatasetId, answer.jobQueueId, answer.fileName, format, served ? "served" : "refused", answer.status,
                reason == null || reason.length() <= 512 ? reason : reason.substring(0, 512)));
        } catch (RuntimeException ex) {
            logger.warn("The file access of run dataset {} by user {} could not be recorded: {}", runDatasetId, userId, ex.getMessage());
        }
    }
}
