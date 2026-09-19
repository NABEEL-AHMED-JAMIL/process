package process.analytics;

import org.junit.jupiter.api.Test;
import process.analytics.canvas.AnalysisRequest;
import process.analytics.canvas.AnalysisService;
import process.analytics.canvas.DatasetOverviewService;
import process.analytics.canvas.dto.AnalysisResultDto;
import process.analytics.canvas.dto.DatasetOverviewDto;
import process.analytics.dto.ColumnDistributionDto;
import process.analytics.dto.ColumnProfileDto;
import process.analytics.dto.DatasetProfileDto;
import process.security.TenantContext;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The overview draws what the columns deserve and nothing else: a date gives rows over time at
 * a grain that fits its span, a categorical column its top values, a numeric measure its spread,
 * a key or a constant nothing; a chart that fails reports on its own tile while the rest draw.
 */
class DatasetOverviewServiceTest {

    private static ColumnProfileDto column(String name, String type, long distinct, String min, String max, boolean keyLike, double nullPct) {
        ColumnProfileDto c = new ColumnProfileDto();
        c.setName(name); c.setType(type); c.setApproxDistinct(distinct); c.setMin(min); c.setMax(max); c.setKeyLike(keyLike);
        c.setNullPercentage(BigDecimal.valueOf(nullPct)); c.setCompleteness(BigDecimal.valueOf(100 - nullPct));
        return c;
    }

    private static DatasetRef dataset() {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(9L); connection.setTenantId(2905L); connection.setProvider(StorageProvider.S3);
        connection.setAlias("worker-store"); connection.setBucketName("etl-bucket"); connection.setStatus(Status.Active);
        return new DatasetRef(connection, "etl-bucket", "sales/in/orders.csv", DatasetRef.Format.CSV);
    }

    private static DatasetProfileDto orders() {
        DatasetProfileDto p = new DatasetProfileDto();
        p.setTotalRows(60);
        p.setColumns(Arrays.asList(
            column("order_id", "BIGINT", 55, "1000", "1059", true, 0),
            column("region", "VARCHAR", 4, "east", "west", false, 0),
            column("product", "VARCHAR", 4, "bp-cuff", "thermometer", false, 0),
            column("qty", "BIGINT", 10, "1", "9", false, 0),
            column("amount", "DOUBLE", 47, "48.19", "865.27", false, 12.5),
            column("order_date", "DATE", 13, "2026-09-01", "2026-09-18", false, 0),
            column("customer_email", "VARCHAR", 58, "a", "z", true, 0),
            column("note", "VARCHAR", 1, "n/a", "n/a", false, 0)));
        p.getColumns().get(7).setConstant(true);
        return p;
    }

    @Test
    void decidesFromTheColumns() {
        List<ColumnProfileDto> cols = orders().getColumns();
        assertThat(DatasetOverviewService.firstDate(cols).getName()).isEqualTo("order_date");
        assertThat(DatasetOverviewService.isCategorical(cols.get(1))).isTrue();                // region
        assertThat(DatasetOverviewService.isCategorical(cols.get(6))).isFalse();               // an email per row is a key
        assertThat(DatasetOverviewService.isCategorical(cols.get(7))).isFalse();               // a constant says nothing
        assertThat(DatasetOverviewService.isMeasureLike(cols.get(0), 60)).isFalse();           // the id
        assertThat(DatasetOverviewService.isMeasureLike(cols.get(4), 60)).isTrue();            // amount
        assertThat(DatasetOverviewService.isMeasureLike(column("seq", "BIGINT", 58, "1", "60", false, 0), 60)).isFalse();   // distinct on nearly every row
        assertThat(DatasetOverviewService.isMeasureLike(column("customer_no", "BIGINT", 20, "1", "60", false, 0), 60)).isFalse();
        assertThat(DatasetOverviewService.grainFor(cols.get(5))).isEqualTo(AnalysisRequest.Grain.DAY);
        assertThat(DatasetOverviewService.grainFor(column("d", "DATE", 9, "2026-01-01", "2026-09-01", false, 0))).isEqualTo(AnalysisRequest.Grain.WEEK);
        assertThat(DatasetOverviewService.grainFor(column("d", "DATE", 9, "2023-01-01", "2026-09-01", false, 0))).isEqualTo(AnalysisRequest.Grain.MONTH);
        assertThat(DatasetOverviewService.grainFor(column("d", "DATE", 9, "garbage", null, false, 0))).isEqualTo(AnalysisRequest.Grain.MONTH);
    }

    @Test
    void composesTheChartsAndKeepsAFailureToItsTile() throws Exception {
        AnalyticsQueryService queries = mock(AnalyticsQueryService.class);
        AnalysisService analyses = mock(AnalysisService.class);
        DatasetRef dataset = dataset();
        when(queries.profileOf(dataset)).thenReturn(orders());
        AnalysisResultDto ok = new AnalysisResultDto(); ok.setRows(new ArrayList<>()); ok.setRowCount(0);
        when(analyses.analyze(any())).thenAnswer(inv -> {
            AnalysisRequest r = inv.getArgument(0);
            if ("product".equals(r.getDimensions().get(0))) throw new AnalyticsException("Refused for the test.");
            return ok;
        });
        when(queries.distributionOf(eq(dataset), eq("qty"))).thenReturn(new ColumnDistributionDto());
        when(queries.distributionOf(eq(dataset), eq("amount"))).thenThrow(new RuntimeException("boom"));

        DatasetOverviewDto out = new DatasetOverviewService(queries, analyses).overviewOf(dataset, "worker-store", "sales/in/orders.csv");
        List<String> titles = new ArrayList<>();
        for (DatasetOverviewDto.Chart c : out.getCharts()) titles.add(c.getKind() + ":" + c.getColumn());
        assertThat(titles).containsExactly("rowsOverTime:order_date", "topValues:region", "topValues:product", "spread:amount", "spread:qty", "completeness:null");
        DatasetOverviewDto.Chart over = out.getCharts().get(0);
        assertThat(over.getRequest().getGrains()).containsExactly(AnalysisRequest.Grain.DAY);
        assertThat(over.getRequest().getMeasure().getAggregation()).isEqualTo(AnalysisRequest.Aggregation.COUNT_ROWS);
        assertThat(out.getCharts().get(1).getRequest().getTopN().getLimit()).isEqualTo(DatasetOverviewService.TOP_VALUES_LIMIT);
        assertThat(out.getCharts().get(2).getError()).isEqualTo("Refused for the test.");
        assertThat(out.getCharts().get(2).getResult()).isNull();
        assertThat(out.getCharts().get(3).getError()).contains("could not measure");
        assertThat(out.getCharts().get(5).getResult().getRows()).containsExactly(Arrays.asList("amount", "12.5"));
        assertThat(out.getProfile().getTotalRows()).isEqualTo(60);
    }

    @Test
    void chartsRunOnWorkerThreadsThatAreStillTheCaller() throws Exception {
        // The resolver decides who may read a connection from the thread it runs on. A chart drawn
        // on a pool thread with no tenant would be refused -- or, if the check were looser, would
        // read as nobody -- so every worker has to carry the caller's tenant and drop it after.
        AnalyticsQueryService queries = mock(AnalyticsQueryService.class);
        AnalysisService analyses = mock(AnalysisService.class);
        DatasetRef dataset = dataset();
        when(queries.profileOf(dataset)).thenReturn(orders());
        java.util.Set<String> threads = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        java.util.Set<Long> tenants = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        when(analyses.analyze(any())).thenAnswer(inv -> {
            threads.add(Thread.currentThread().getName()); tenants.add(TenantContext.getTenantId());
            AnalysisResultDto ok = new AnalysisResultDto(); ok.setRows(new ArrayList<>()); return ok;
        });
        when(queries.distributionOf(eq(dataset), any())).thenAnswer(inv -> { tenants.add(TenantContext.getTenantId()); return new ColumnDistributionDto(); });

        TenantContext.set(2905L, "TENANT_ADMIN", 4385L, "emily");
        try {
            DatasetOverviewDto out = new DatasetOverviewService(queries, analyses).overviewOf(dataset, "worker-store", "sales/in/orders.csv");
            assertThat(out.getCharts()).hasSize(6);
            assertThat(tenants).containsExactly(2905L);
            assertThat(threads).doesNotContain(Thread.currentThread().getName());
            assertThat(threads.size()).isBetween(1, DatasetOverviewService.PARALLEL_CHARTS);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void anEmptyDatasetGetsNoChartsButItsProfile() throws Exception {
        AnalyticsQueryService queries = mock(AnalyticsQueryService.class);
        DatasetProfileDto empty = new DatasetProfileDto(); empty.setTotalRows(0); empty.setColumns(Collections.singletonList(column("a", "VARCHAR", 0, null, null, false, 0)));
        DatasetRef dataset = dataset();
        when(queries.profileOf(dataset)).thenReturn(empty);
        DatasetOverviewDto out = new DatasetOverviewService(queries, mock(AnalysisService.class)).overviewOf(dataset, "c", "p");
        assertThat(out.getCharts()).isEmpty();
        assertThat(out.getProfile()).isSameAs(empty);
    }
}
