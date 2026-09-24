package process.model.service.impl;

import org.barco.platform.meter.Meter;
import org.barco.platform.meter.MeterReporter;
import org.barco.platform.meter.UsageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import process.analytics.DatasetBytes;
import process.model.pojo.AnalyticsQueryRun;
import process.model.repository.AnalyticsDashboardWidgetRepository;
import process.model.repository.AnalyticsQueryRepository;
import process.model.repository.AnalyticsQueryRunRepository;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.UserNameResolver;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-104 (the owner's decision, 2026-09-23): analytics.gb_scanned has a producer. A successful read
 * that scans a dataset -- a statement, a profile, a distribution, an overview -- bills the GB it read,
 * both datasets of a join; a schema or a preview page samples rather than scans and bills none. The
 * card prices the meter per GB (unit GB, per 1), so the quantity is bytes / 2^30. The key is the run
 * row's own id, as analytics.queries' is.
 */
class AnalyticsGbScannedTest {

    private static final long GIB = 1024L * 1024 * 1024;

    private final AnalyticsQueryRunRepository runs = mock(AnalyticsQueryRunRepository.class);
    private final MeterReporter meter = mock(MeterReporter.class);
    private final DatasetBytes sizes = mock(DatasetBytes.class);
    private AnalyticsQueryLibraryServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new AnalyticsQueryLibraryServiceImpl(mock(AnalyticsQueryRepository.class), this.runs,
            mock(AnalyticsDashboardWidgetRepository.class), mock(TenantFilterHelper.class), mock(UserNameResolver.class));
        ReflectionTestUtils.setField(this.service, "meter", this.meter);
        ReflectionTestUtils.setField(this.service, "datasetBytes", this.sizes);
        when(this.runs.save(any())).thenAnswer(inv -> {
            AnalyticsQueryRun r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "analyticsQueryRunId", 7001L);
            return r;
        });
        when(this.sizes.of("sales", "q3/orders.parquet")).thenReturn(Optional.of(new DatasetBytes.Size(3 * GIB, 1, true)));
        when(this.sizes.of("crm", "accounts.csv")).thenReturn(Optional.of(new DatasetBytes.Size(GIB / 2, 1, true)));
        TenantContext.set(2905L, "TENANT_USER", 4385L, "user");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static AnalyticsQueryRun run(String queryText, String status) {
        AnalyticsQueryRun run = new AnalyticsQueryRun();
        run.setConnectionAlias("sales");
        run.setDatasetPath("q3/orders.parquet");
        run.setQueryText(queryText);
        run.setRunStatus(status);
        return run;
    }

    private List<UsageEvent> scanned() {
        ArgumentCaptor<UsageEvent> captor = ArgumentCaptor.forClass(UsageEvent.class);
        verify(this.meter, atLeast(0)).report(captor.capture());
        return captor.getAllValues().stream().filter(e -> Meter.ANALYTICS_GB_SCANNED.key().equals(e.meter)).collect(Collectors.toList());
    }

    @Test
    void aStatementBillsTheGbItsDatasetHolds() {
        this.service.recordRun(run("select count(*) from dataset", AnalyticsQueryRun.STATUS_SUCCESS));

        List<UsageEvent> events = this.scanned();
        assertThat(events).hasSize(1);
        UsageEvent e = events.get(0);
        assertThat(e.quantity).isEqualTo(3.0);
        assertThat(e.unit).isEqualTo("GB");
        assertThat(e.tenantId).isEqualTo(2905L);
        assertThat(e.dedupeKey).isEqualTo("analytics-run#7001#gb");
        assertThat(e.subjectId).isEqualTo("q3/orders.parquet");
    }

    @Test
    void aJoinBillsBothDatasets() {
        AnalyticsQueryRun join = run("select * from dataset join dataset2 using (id)", AnalyticsQueryRun.STATUS_SUCCESS);
        join.setSecondConnectionAlias("crm");
        join.setSecondDatasetPath("accounts.csv");

        AnalyticsQueryRun saved = this.service.recordRun(join);

        assertThat(this.scanned()).extracting(e -> e.quantity).containsExactly(3.5);
        // The history names both files: the copy into the saved row used to drop the second.
        assertThat(saved.getSecondConnectionAlias()).isEqualTo("crm");
        assertThat(saved.getSecondDatasetPath()).isEqualTo("accounts.csv");
    }

    @Test
    void fullScanReadsBillAndSamplingReadsDoNot() {
        for (String scan : new String[] {"-- profile", "-- distribution amount", "-- overview"}) {
            this.service.recordRun(run(scan, AnalyticsQueryRun.STATUS_SUCCESS));
        }
        for (String sample : new String[] {"-- schema", "-- preview page=3 size=50"}) {
            this.service.recordRun(run(sample, AnalyticsQueryRun.STATUS_SUCCESS));
        }
        assertThat(this.scanned()).hasSize(3);
    }

    @Test
    void aFailedOrRefusedRunBillsNoScan() {
        this.service.recordRun(run("select 1", AnalyticsQueryRun.STATUS_FAILED));
        this.service.recordRun(run("select 1", AnalyticsQueryRun.STATUS_REFUSED));
        assertThat(this.scanned()).isEmpty();
    }

    /** A size Storage cannot give is not guessed at. */
    @Test
    void anUnknownSizeBillsNothing() {
        when(this.sizes.of("sales", "q3/orders.parquet")).thenReturn(Optional.empty());
        this.service.recordRun(run("select 1", AnalyticsQueryRun.STATUS_SUCCESS));
        assertThat(this.scanned()).isEmpty();
    }

    @Test
    void aPartialGlobSizeSaysSoOnTheEvent() {
        when(this.sizes.of("sales", "q3/orders.parquet")).thenReturn(Optional.of(new DatasetBytes.Size(GIB, 10_000, false)));
        this.service.recordRun(run("select 1", AnalyticsQueryRun.STATUS_SUCCESS));
        assertThat(this.scanned().get(0).note).contains("partial");
    }

    @Test
    void aPlatformAdminsReadBillsNoWorkspace() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "admin");
        this.service.recordRun(run("select 1", AnalyticsQueryRun.STATUS_SUCCESS));
        assertThat(this.scanned()).isEmpty();
    }
}
