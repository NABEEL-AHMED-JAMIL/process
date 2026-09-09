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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class AuditLogSyncCron {

    private static final Duration OVERLAP = Duration.ofMinutes(10);

    /** Enough missing runs to tell the operator which ones they are without printing hundreds of ids. */
    private static final int SKIPPED_ID_SAMPLE = 5;

    private static final Logger logger = LogManager.getLogger(AuditLogSyncCron.class);

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
            Set<Long> liveJobQueueIds = this.liveJobQueueIds(hits);
            int upserted = 0;
            int skipped = 0;
            Set<Long> missingJobQueueIds = new LinkedHashSet<>();
            boolean hadParseFailure = false;
            Instant maxSeen = lastRun;
            for (OpenSearchJobAuditLogProjection hit : hits) {
                // The catch stays per hit and not around the loop: upsertFromOpenSearch is
                // @Transactional and nothing here holds an outer transaction, so each row commits
                // or rolls back on its own and a refusal never took the rows after it down with
                // it. Everything below is about not provoking the refusal in the first place.
                try {
                    Instant dateCreated = Instant.parse(hit.getDateCreated());
                    if (hit.getJobQueueId() == null || !liveJobQueueIds.contains(hit.getJobQueueId())) {
                        skipped++;
                        missingJobQueueIds.add(hit.getJobQueueId());
                    } else {
                        upserted += this.jobAuditLogRepository.upsertFromOpenSearch(
                            hit.getExternalId(), hit.getJobQueueId(), hit.getLogsDetail(), Timestamp.from(dateCreated));
                    }
                    // A skipped hit has still been examined, so it carries the watermark with the
                    // rest of the batch. Its run has been purged from job_queue and is not coming
                    // back, so holding the watermark behind it only made the next scan re-read and
                    // re-skip the same dead rows, and a window with nothing but dead rows at its
                    // head would never move at all.
                    if (dateCreated.isAfter(maxSeen)) {
                        maxSeen = dateCreated;
                    }
                } catch (Exception ex) {
                    hadParseFailure = true;
                    logger.error("Failed to sync one audit log hit externalId={}: {}", hit.getExternalId(), ex.getMessage(), ex);
                }
            }
            if (skipped > 0) {
                // One line an operator can act on, in place of the stack trace per row this used
                // to produce. It is a warn rather than an error because a purged run is expected:
                // nothing here can recover those lines, and nothing should page about them.
                logger.warn("Skipped {} audit log hits belonging to {} runs that are no longer in job_queue, for example jobQueueId={}",
                    skipped, missingJobQueueIds.size(),
                    missingJobQueueIds.stream().limit(SKIPPED_ID_SAMPLE).collect(Collectors.toList()));
            }

            LookupData newBookmark = bookmark == null ? new LookupData() : bookmark;
            newBookmark.setLookupType(ProcessUtil.AUDIT_LOG_SYNC_LAST_RUN_TIME);
            Instant newBookmarkValue = hadParseFailure ? lastRun : (hits.isEmpty() ? Instant.now().minus(OVERLAP) : maxSeen);
            newBookmark.setLookupValue(newBookmarkValue.toString());
            if (bookmark == null) {
                newBookmark.setDescription("Watermark for the OpenSearch -> job_audit_logs sync cron (AuditLogSyncCron).");
            }
            this.transactionService.updateLookupDate(newBookmark);

            logger.info("~~~~~~~~~~~~~~~~~~~~~~~~End-SyncAuditLogsFromOpenSearch scanned={} upserted={} skipped={}~~~~~~~~~~~~~~~~~~~~~~~~",
                hits.size(), upserted, skipped);
        } catch (Exception e) {
            logger.error("Error in syncAuditLogsFromOpenSearch scheduler: {}", e.getMessage(), e);
        }
    }

    /**
     * Of the runs this batch of hits refers to, the ones job_queue still has.
     *
     * OpenSearch keeps audit lines long after the run they describe has been dropped from
     * job_queue, so most of a scan can point at parents that are gone: 262 of 477 hits in a
     * measured run, every one of them handed to the insert and refused by
     * fk_job_audit_logs_job_queue. That turned an expected and permanent condition into a
     * constraint violation and a stack trace per row -- over a thousand an hour -- while the
     * run still reported itself as a success.
     *
     * Asked once for the whole batch rather than per row, so the guard costs one query however
     * many hits came back. It is a snapshot and not a lock: a run deleted between this query and
     * the insert still raises the violation, which is why the loop keeps its per-hit catch.
     */
    private Set<Long> liveJobQueueIds(List<OpenSearchJobAuditLogProjection> hits) {
        Set<Long> referenced = new HashSet<>();
        for (OpenSearchJobAuditLogProjection hit : hits) {
            if (hit.getJobQueueId() != null) {
                referenced.add(hit.getJobQueueId());
            }
        }
        if (referenced.isEmpty()) {
            // "in ()" is not valid SQL, so an empty scan must not reach the query at all.
            return Collections.emptySet();
        }
        Set<Long> live = new HashSet<>();
        for (Number jobQueueId : this.jobAuditLogRepository.findExistingJobQueueIds(new ArrayList<>(referenced))) {
            live.add(jobQueueId.longValue());
        }
        return live;
    }

}
