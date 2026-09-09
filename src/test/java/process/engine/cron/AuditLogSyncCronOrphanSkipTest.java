package process.engine.cron;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.pojo.LookupData;
import process.model.projection.OpenSearchJobAuditLogProjection;
import process.model.repository.JobAuditLogRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.util.OpenSearchAuditLogClient;
import process.util.ProcessUtil;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpenSearch keeps audit lines for runs the database has long since dropped, and
 * job_audit_logs.job_queue_id is a not-null foreign key onto job_queue. The sync cron handed
 * every hit to the insert regardless, so a scan of 477 hits produced 262
 * fk_job_audit_logs_job_queue violations and 262 stack traces while still logging itself as a
 * success -- and because any failure pinned the watermark, the very same dead rows came back and
 * exploded again on every later run.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class AuditLogSyncCronOrphanSkipTest {

    private static final long LIVE_QUEUE_ID = 5342L;
    private static final long ANOTHER_LIVE_QUEUE_ID = 5343L;
    private static final long PURGED_QUEUE_ID = 5074L;

    @Mock private OpenSearchAuditLogClient openSearchAuditLogClient;
    @Mock private JobAuditLogRepository jobAuditLogRepository;
    @Mock private TransactionServiceImpl transactionService;

    private AuditLogSyncCron cron;
    private Instant lastRun;

    @BeforeEach
    void setUp() {
        this.cron = new AuditLogSyncCron(this.openSearchAuditLogClient,
            this.jobAuditLogRepository, this.transactionService);
        ReflectionTestUtils.setField(this.cron, "initialLookbackDays", 30);
        this.lastRun = Instant.now().minus(Duration.ofHours(6));
        when(this.openSearchAuditLogClient.isEnabled()).thenReturn(true);
        when(this.transactionService.findByLookupType(ProcessUtil.AUDIT_LOG_SYNC_LAST_RUN_TIME))
            .thenReturn(this.bookmarkAt(this.lastRun));
    }

    @Test
    void hitsWhoseRunIsGoneAreNeverOfferedToTheInsert() {
        OpenSearchJobAuditLogProjection purged = hit("a-1", PURGED_QUEUE_ID, this.lastRun.plusSeconds(60));
        OpenSearchJobAuditLogProjection live = hit("a-2", LIVE_QUEUE_ID, this.lastRun.plusSeconds(120));
        OpenSearchJobAuditLogProjection anotherLive = hit("a-3", ANOTHER_LIVE_QUEUE_ID, this.lastRun.plusSeconds(180));
        when(this.openSearchAuditLogClient.searchSince(any(Instant.class)))
            .thenReturn(Arrays.asList(purged, live, anotherLive));
        // BigInteger, not Long: that is what a bigint column comes back as through a native query.
        when(this.jobAuditLogRepository.findExistingJobQueueIds(anyList()))
            .thenReturn(Arrays.<Number>asList(BigInteger.valueOf(LIVE_QUEUE_ID), BigInteger.valueOf(ANOTHER_LIVE_QUEUE_ID)));
        when(this.jobAuditLogRepository.upsertFromOpenSearch(anyString(), any(), anyString(), any(Timestamp.class)))
            .thenReturn(1);

        this.cron.syncAuditLogsFromOpenSearch();

        verify(this.jobAuditLogRepository, never())
            .upsertFromOpenSearch(anyString(), eq(PURGED_QUEUE_ID), anyString(), any(Timestamp.class));
        // The dead row came first: the two live rows behind it must still be written, which is the
        // half that would break if one refusal were allowed to take the rest of the batch with it.
        verify(this.jobAuditLogRepository)
            .upsertFromOpenSearch(eq("a-2"), eq(LIVE_QUEUE_ID), anyString(), any(Timestamp.class));
        verify(this.jobAuditLogRepository)
            .upsertFromOpenSearch(eq("a-3"), eq(ANOTHER_LIVE_QUEUE_ID), anyString(), any(Timestamp.class));
        verify(this.jobAuditLogRepository, times(2))
            .upsertFromOpenSearch(anyString(), any(), anyString(), any(Timestamp.class));
    }

    @Test
    void aBatchOfNothingButPurgedRunsStillMovesTheWatermark() {
        Instant newest = this.lastRun.plusSeconds(300);
        when(this.openSearchAuditLogClient.searchSince(any(Instant.class))).thenReturn(Arrays.asList(
            hit("b-1", PURGED_QUEUE_ID, this.lastRun.plusSeconds(60)),
            hit("b-2", PURGED_QUEUE_ID, newest)));
        when(this.jobAuditLogRepository.findExistingJobQueueIds(anyList()))
            .thenReturn(Collections.<Number>emptyList());

        this.cron.syncAuditLogsFromOpenSearch();

        verify(this.jobAuditLogRepository, never())
            .upsertFromOpenSearch(anyString(), any(), anyString(), any(Timestamp.class));
        // Skipping is a decision, not a failure. Left as a failure the watermark stayed where it
        // was, so the next scan re-read the same purged rows for ever and the window only grew.
        assertThat(this.savedBookmarkValue()).isEqualTo(newest.toString());
    }

    @Test
    void anEmptyScanNeverAsksWhichRunsAreStillThere() {
        when(this.openSearchAuditLogClient.searchSince(any(Instant.class)))
            .thenReturn(Collections.emptyList());

        this.cron.syncAuditLogsFromOpenSearch();

        // "in ()" is not valid SQL, so an empty batch must not reach the lookup at all.
        verify(this.jobAuditLogRepository, never()).findExistingJobQueueIds(anyList());
    }

    private String savedBookmarkValue() {
        ArgumentCaptor<LookupData> captor = ArgumentCaptor.forClass(LookupData.class);
        verify(this.transactionService).updateLookupDate(captor.capture());
        return captor.getValue().getLookupValue();
    }

    private LookupData bookmarkAt(Instant value) {
        LookupData bookmark = new LookupData();
        bookmark.setLookupType(ProcessUtil.AUDIT_LOG_SYNC_LAST_RUN_TIME);
        bookmark.setLookupValue(value.toString());
        return bookmark;
    }

    private static OpenSearchJobAuditLogProjection hit(String externalId, Long jobQueueId, Instant dateCreated) {
        return new OpenSearchJobAuditLogProjection(externalId, jobQueueId, "a log line", dateCreated.toString());
    }

}
