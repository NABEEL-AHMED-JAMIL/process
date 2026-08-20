package process.engine.cron;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import process.model.pojo.LookupData;
import process.model.projection.OpenSearchJobAuditLogProjection;
import process.model.repository.JobAuditLogRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.util.OpenSearchAuditLogClient;
import process.util.ProcessUtil;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class AuditLogSyncCron {

    private static final Duration OVERLAP = Duration.ofMinutes(10);

    public Logger logger = LogManager.getLogger(AuditLogSyncCron.class);

    @Value("${audit.log.sync.initial-lookback-days:30}")
    private int initialLookbackDays;

    private final OpenSearchAuditLogClient openSearchAuditLogClient;
    private final JobAuditLogRepository jobAuditLogRepository;
    private final TransactionServiceImpl transactionService;

    public AuditLogSyncCron(OpenSearchAuditLogClient openSearchAuditLogClient,
        JobAuditLogRepository jobAuditLogRepository,
        TransactionServiceImpl transactionService) {
        this.openSearchAuditLogClient = openSearchAuditLogClient;
        this.jobAuditLogRepository = jobAuditLogRepository;
        this.transactionService = transactionService;
    }

    @Scheduled(initialDelay = 15000, fixedDelay = 4 * 60 * 60 * 1000)
    @SchedulerLock(name = "syncAuditLogsFromOpenSearch", lockAtLeastFor = "5S", lockAtMostFor = "5M")
    public void syncAuditLogsFromOpenSearch() {
        if (!this.openSearchAuditLogClient.isEnabled()) {
            return;
        }
        try {
            logger.info("~~~~~~~~~~~~~~~~~~~~~~~~Start-SyncAuditLogsFromOpenSearch~~~~~~~~~~~~~~~~~~~~~~~~");
            LookupData bookmark = this.transactionService.findByLookupType(ProcessUtil.AUDIT_LOG_SYNC_LAST_RUN_TIME);
            Instant lastRun = bookmark == null
                ? Instant.now().minus(Duration.ofDays(this.initialLookbackDays))
                : Instant.parse(bookmark.getLookupValue());
            Instant scanFrom = lastRun.minus(OVERLAP);

            List<OpenSearchJobAuditLogProjection> hits = this.openSearchAuditLogClient.searchSince(scanFrom);
            int upserted = 0;
            boolean hadParseFailure = false;
            Instant maxSeen = lastRun;
            for (OpenSearchJobAuditLogProjection hit : hits) {
                try {
                    Instant dateCreated = Instant.parse(hit.getDateCreated());
                    int rows = this.jobAuditLogRepository.upsertFromOpenSearch(
                        hit.getExternalId(), hit.getJobQueueId(), hit.getLogsDetail(), Timestamp.from(dateCreated));
                    upserted += rows;
                    if (dateCreated.isAfter(maxSeen)) {
                        maxSeen = dateCreated;
                    }
                } catch (Exception ex) {
                    hadParseFailure = true;
                    logger.error("Failed to sync one audit log hit externalId={}: {}", hit.getExternalId(), ex.getMessage(), ex);
                }
            }

            LookupData newBookmark = bookmark == null ? new LookupData() : bookmark;
            newBookmark.setLookupType(ProcessUtil.AUDIT_LOG_SYNC_LAST_RUN_TIME);
            Instant newBookmarkValue = hadParseFailure ? lastRun : (hits.isEmpty() ? Instant.now().minus(OVERLAP) : maxSeen);
            newBookmark.setLookupValue(newBookmarkValue.toString());
            if (bookmark == null) {
                newBookmark.setDescription("Watermark for the OpenSearch -> job_audit_logs sync cron (AuditLogSyncCron).");
            }
            this.transactionService.updateLookupDate(newBookmark);

            logger.info("~~~~~~~~~~~~~~~~~~~~~~~~End-SyncAuditLogsFromOpenSearch scanned={} upserted={}~~~~~~~~~~~~~~~~~~~~~~~~",
                hits.size(), upserted);
        } catch (Exception e) {
            logger.error("Error in syncAuditLogsFromOpenSearch scheduler: {}", e.getMessage(), e);
        }
    }

}
