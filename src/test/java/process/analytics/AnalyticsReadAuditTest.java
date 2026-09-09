package process.analytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.api.AnalyticsRestApi;
import process.analytics.dto.ColumnDto;
import process.analytics.dto.DatasetPreviewDto;
import process.analytics.dto.DatasetSchemaDto;
import process.model.pojo.AnalyticsQueryRun;
import process.model.service.AnalyticsQueryLibraryService;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every read of a dataset leaves a row behind, not just the ones somebody typed SQL for.
 *
 * <b>Document 15's audit-completeness row.</b> analytics_query_run held query attempts including
 * refusals, and the other nine tabs -- which is how the file is actually read -- wrote a log line
 * and nothing else. "Who has read this file" could be answered for the SQL console alone.
 *
 * The descriptor is deliberately a SQL COMMENT. It lands in query_text, which everywhere else
 * holds a statement as the caller submitted it, and a bare word there would eventually be read
 * back as something somebody ran.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsReadAuditTest {

    private static final String ALIAS = "store";
    private static final String PATH = "etl-demo/sales.csv";

    @Mock private DatasetResolver datasetResolver;
    @Mock private AnalyticsQueryService analyticsQueryService;
    @Mock private AnalyticsQueryLibraryService library;

    private AnalyticsRestApi api() {
        return new AnalyticsRestApi(this.datasetResolver, this.analyticsQueryService,
            this.library, null, new AnalyticsLimits());
    }

    private DatasetRef datasetRef() {
        StorageConnection connection = new StorageConnection();
        connection.setTenantId(1001L);
        connection.setProvider(StorageProvider.MINIO);
        connection.setAlias(ALIAS);
        connection.setBucketName("etl-bucket");
        connection.setStatus(Status.Active);
        return new DatasetRef(connection, "etl-bucket", PATH, DatasetRef.Format.CSV);
    }

    private AnalyticsQueryRun recorded() {
        ArgumentCaptor<AnalyticsQueryRun> captor = ArgumentCaptor.forClass(AnalyticsQueryRun.class);
        verify(this.library, times(1)).recordRun(captor.capture());
        return captor.getValue();
    }

    @Test
    void aSchemaReadIsRecorded() throws Exception {
        when(this.datasetResolver.resolve(anyString(), anyString())).thenReturn(datasetRef());
        when(this.analyticsQueryService.schemaOf(any())).thenReturn(new DatasetSchemaDto(
            "etl-bucket", PATH, "CSV", false,
            Collections.singletonList(new ColumnDto("amount", "DECIMAL(10,2)"))));

        api().schema(ALIAS, PATH);

        AnalyticsQueryRun run = recorded();
        assertEquals(ALIAS, run.getConnectionAlias());
        assertEquals(PATH, run.getDatasetPath());
        assertEquals(AnalyticsQueryRun.STATUS_SUCCESS, run.getRunStatus());
        assertEquals("-- schema", run.getQueryText());
        // The column count, so the row says how much was read and not merely that something was.
        assertEquals(Long.valueOf(1L), run.getRowCount());
        assertNotNull(run.getDurationMs());
    }

    @Test
    void aRefusedReadIsRecordedToo() throws Exception {
        // The single most interesting row this table can hold: somebody asked for a dataset they
        // were not given. Before this, a refusal on nine of the ten tabs left nothing at all.
        when(this.datasetResolver.resolve(anyString(), anyString()))
            .thenThrow(new AnalyticsException("That connection is not yours."));

        api().schema(ALIAS, PATH);

        AnalyticsQueryRun run = recorded();
        assertEquals(AnalyticsQueryRun.STATUS_REFUSED, run.getRunStatus());
        assertEquals("That connection is not yours.", run.getErrorMessage());
    }

    @Test
    void aPreviewRecordsWhichPageAndThatItWasNarrowed() throws Exception {
        when(this.datasetResolver.resolve(anyString(), anyString())).thenReturn(datasetRef());
        when(this.analyticsQueryService.preview(any(), anyInt(), any(), any(), any()))
            .thenReturn(new DatasetPreviewDto(Collections.<String>emptyList(),
                Collections.<java.util.List<String>>emptyList(), 0, 100, 150000L, false, true));

        api().preview(ALIAS, PATH, 3, 100, null, "amount", "DESC", "smith", null, false);

        AnalyticsQueryRun run = recorded();
        assertEquals("-- preview page=3 sorted searched", run.getQueryText());
    }

    @Test
    void aPreviewNeverRecordsWhatWasSearchedFOR() throws Exception {
        // "searched", not the search term. The filter values are the reader's own data -- a
        // surname, an account number -- and an audit table is not where those should accumulate.
        when(this.datasetResolver.resolve(anyString(), anyString())).thenReturn(datasetRef());
        when(this.analyticsQueryService.preview(any(), anyInt(), any(), any(), any()))
            .thenReturn(new DatasetPreviewDto(Collections.<String>emptyList(),
                Collections.<java.util.List<String>>emptyList(), 0, 100, 150000L, false, true));

        api().preview(ALIAS, PATH, 0, 100, null, null, null, "Delgado", null, false);

        AnalyticsQueryRun run = recorded();
        assertTrue(run.getQueryText().contains("searched"), "the fact of a search is recorded");
        assertTrue(!run.getQueryText().contains("Delgado"), "the search TERM must not be");
    }

    @Test
    void theDescriptorIsAnInertSqlComment() throws Exception {
        // query_text holds statements as submitted everywhere else. A descriptor that did not
        // announce itself as a comment would eventually be read back as something somebody ran.
        when(this.datasetResolver.resolve(anyString(), anyString())).thenReturn(datasetRef());
        when(this.analyticsQueryService.schemaOf(any())).thenReturn(new DatasetSchemaDto(
            "etl-bucket", PATH, "CSV", false, Collections.<ColumnDto>emptyList()));

        api().schema(ALIAS, PATH);

        assertTrue(recorded().getQueryText().startsWith("-- "),
            "a read descriptor must be distinguishable from a statement");
    }
}
