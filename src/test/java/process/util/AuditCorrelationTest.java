package process.util;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.barco.platform.correlation.CorrelationId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import process.engine.cron.AuditLogSyncCron;
import process.model.pojo.JobAuditLogs;
import process.model.projection.OpenSearchJobAuditLogProjection;
import process.model.repository.JobAuditLogRepository;
import process.settings.OrchestrationSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-94 acceptance 5: both audit stores carry the correlation id of the work that wrote the line, so a run's
 * audit trail and every service's logs are joined by one string. OpenSearch documents gain correlationId,
 * mapped as a keyword (exact match, and a ULID sorts chronologically); job_audit_logs gains correlation_id
 * (V160); the sync from OpenSearch carries it across (OpenSearchJobAuditLogProjection, 5 fields). And the
 * Postgres fallback row now keeps its externalId, so the two stores stay reconcilable.
 */
class AuditCorrelationTest {

    private static final String ID = "01J8ZK3V4N6T8W0Y2A4C6E8G0J";

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final OpenSearchAuditLogClient client = new OpenSearchAuditLogClient();

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(this.client, "baseUrl", "http://opensearch.test:9200");
        ReflectionTestUtils.setField(this.client, "restTemplate", this.restTemplate);
        when(this.restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"errors\":false,\"items\":[{\"index\":{\"status\":201}}]}"));
        CorrelationId.set(ID);
    }

    @AfterEach
    void tidy() {
        CorrelationId.clear();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<HttpEntity> sent(String url, HttpMethod method) {
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(this.restTemplate, atLeastOnce()).exchange(eq(url), eq(method), entity.capture(), eq(String.class));
        return entity.getAllValues();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLineIndexedDuringAPieceOfWorkCarriesItsId() {
        this.client.index("ext-1", 91422L, "run started", new Timestamp(0L));

        Map<String, Object> doc = (Map<String, Object>) this.sent("http://opensearch.test:9200/job-audit-logs/_doc/ext-1",
            HttpMethod.PUT).get(0).getBody();
        assertThat(doc).containsEntry("correlationId", ID);
    }

    @Test
    void everyLineOfABulkCarriesTheId() {
        List<Object[]> entries = new ArrayList<>();
        entries.add(new Object[] {"ext-1", 91422L, "one", new Timestamp(0L)});
        this.client.indexAllReturningFailures(entries);

        String body = (String) this.sent("http://opensearch.test:9200/_bulk", HttpMethod.POST).get(0).getBody();
        assertThat(body).contains("\"correlationId\":\"" + ID + "\"");
    }

    /** Keyword-mapped once per process, before the first document: a dynamic mapping would make it text. */
    @Test
    void theIdIsMappedAsAKeywordOnceBeforeTheFirstLine() {
        this.client.index("ext-1", 91422L, "one", new Timestamp(0L));
        this.client.index("ext-2", 91422L, "two", new Timestamp(0L));

        @SuppressWarnings("rawtypes")
        List<HttpEntity> mappings = this.sent("http://opensearch.test:9200/job-audit-logs/_mapping", HttpMethod.PUT);
        assertThat(mappings).hasSize(1);
        assertThat(String.valueOf(mappings.get(0).getBody())).contains("correlationId").contains("keyword");
    }

    @Test
    void aSearchHitCarriesItsIdToTheProjection() {
        when(this.restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn(
            "{\"hits\":{\"hits\":[{\"_id\":\"ext-1\",\"_source\":{\"jobQueueId\":91422,\"logDetail\":\"one\","
                + "\"dateCreated\":\"2026-09-24T18:00:00Z\",\"correlationId\":\"" + ID + "\"}}]}}");

        List<OpenSearchJobAuditLogProjection> hits = this.client.searchByJobQueueId(91422L);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getCorrelationId()).isEqualTo(ID);
    }

    @Test
    void theSyncCopiesTheIdIntoJobAuditLogs() {
        OpenSearchAuditLogClient openSearch = mock(OpenSearchAuditLogClient.class);
        JobAuditLogRepository repository = mock(JobAuditLogRepository.class);
        OrchestrationSettings settings = mock(OrchestrationSettings.class);
        when(openSearch.isEnabled()).thenReturn(true);
        when(settings.value(anyString())).thenReturn(Optional.empty());
        when(openSearch.searchSince(any(Instant.class))).thenReturn(Collections.singletonList(
            new OpenSearchJobAuditLogProjection("ext-1", 91422L, "one", "2026-09-24T18:00:00Z", ID)));
        when(repository.findExistingJobQueueIds(anyList())).thenReturn(Collections.<Number>singletonList(91422L));

        new AuditLogSyncCron(openSearch, repository, settings).syncAuditLogsFromOpenSearch();

        verify(repository).upsertFromOpenSearch(eq("ext-1"), eq(91422L), eq("one"), any(Timestamp.class), eq(ID));
    }

    /** A row written to Postgres carries the id of the work that wrote it. */
    @Test
    void aDatabaseRowIsStampedWithTheIdOfTheWorkThatWroteIt() {
        JobAuditLogs row = new JobAuditLogs();
        row.setJobQueueId(91422L);
        row.setLogsDetail("one");

        ReflectionTestUtils.invokeMethod(row, "onCreate");

        assertThat(row.getCorrelationId()).isEqualTo(ID);
    }
}
