package process.settings;

/**
 * The two resume points in orchestration_setting (MIG-167). Each has exactly one writer -- the enqueuer's start-up
 * for the scheduler's, AuditLogSyncCron for the audit sync's -- and the settings API writes neither (owner's D4,
 * WatermarkWritersTest).
 */
public enum Watermark {
    SCHEDULER_LAST_RUN_TIME("Where the enqueuer resumes from after a restart. Written only by the enqueuer."),
    AUDIT_LOG_SYNC_LAST_RUN_TIME("Where the OpenSearch audit-log sync resumes from. Written only by AuditLogSyncCron.");

    final String description;

    Watermark(String description) {
        this.description = description;
    }

    static boolean isWatermark(String key) {
        for (Watermark watermark : values()) {
            if (watermark.name().equals(key)) {
                return true;
            }
        }
        return false;
    }
}
