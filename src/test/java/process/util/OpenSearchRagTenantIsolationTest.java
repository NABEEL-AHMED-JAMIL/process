package process.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import process.security.TenantContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * MIG-10 (DEF-009): every query against file-rag-chunks is scoped to the caller's tenant.
 *
 * tenantId has been written onto every chunk since the index existed, and no query read it back:
 * isolation lived entirely in FileChatServiceImpl.validateBucketAccess plus the bucket term clause,
 * a check in a different subsystem. The moment bucket names stop being effectively tenant-scoped,
 * or RAG is extracted, that barrier leaves with the caller. The tenant now comes from TenantContext
 * -- never from an argument a caller could get wrong -- and sits in the non-scoring filter context
 * of every read and of the delete-by-query, so relevance is untouched. validateBucketAccess and the
 * bucket clause stay: this is defence in depth, not a replacement.
 *
 * The cluster here is a stub that holds real documents and applies whatever term clauses a query
 * carries, so the attack is answered the way OpenSearch would answer it.
 */
class OpenSearchRagTenantIsolationTest {

    private static final long TENANT_A = 2905L;
    private static final long TENANT_B = 1000L;
    private static final String B_BUCKET = "tenant-b-private";

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final OpenSearchRagClient client = new OpenSearchRagClient();
    private final ObjectMapper json = new ObjectMapper();
    private final List<ObjectNode> index = new ArrayList<>();
    private final List<String> searches = new ArrayList<>();
    private final List<String> deletes = new ArrayList<>();
    private ListAppender<ILoggingEvent> log;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(this.client, "baseUrl", "http://opensearch.test:9200");
        ReflectionTestUtils.setField(this.client, "configuredEmbeddingModel", "nomic-embed-text");
        ReflectionTestUtils.setField(this.client, "restTemplate", this.restTemplate);
        ReflectionTestUtils.setField(this.client, "indexEnsured", true);
        for (int i = 0; i < 3; i++) this.store(TENANT_B, B_BUCKET, "payroll.pdf", "etag-b", i, "tenant B salary line " + i);
        for (int i = 0; i < 3; i++) this.store(TENANT_A, "tenant-a-docs", "manual.pdf", "etag-a", i, "tenant A manual line " + i);
        doAnswer(inv -> this.answer((String) inv.getArgument(0), String.valueOf((Object) ((HttpEntity<?>) inv.getArgument(1)).getBody())))
            .when(this.restTemplate).postForObject(anyString(), any(), eq(String.class));
        doReturn(new ResponseEntity<String>("{\"errors\":false,\"items\":[{\"index\":{\"status\":201}}]}", HttpStatus.OK))
            .when(this.restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        this.log = new ListAppender<>();
        this.log.start();
        ((Logger) LoggerFactory.getLogger(OpenSearchRagClient.class)).addAppender(this.log);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        ((Logger) LoggerFactory.getLogger(OpenSearchRagClient.class)).detachAppender(this.log);
    }

    private void store(long tenant, String bucket, String key, String etag, int chunkIndex, String text) {
        ObjectNode doc = this.json.createObjectNode();
        doc.put("tenantId", tenant); doc.put("bucket", bucket); doc.put("key", key); doc.put("etag", etag);
        doc.put("embeddingModel", "nomic-embed-text"); doc.put("chunkIndex", chunkIndex); doc.put("chunkText", text);
        doc.putArray("embedding").add(1f).add(0f).add(0f);
        this.index.add(doc);
    }

    // ---- a stub cluster that applies term clauses ----------------------------------------------

    private String answer(String url, String body) throws Exception {
        JsonNode request = this.json.readTree(body);
        if (url.contains("_delete_by_query")) {
            this.deletes.add(body);
            return "{\"deleted\":0}";
        }
        this.searches.add(body);
        JsonNode query = request.path("query");
        if (query.has("knn")) query = query.path("knn").path("embedding").path("filter");
        List<ObjectNode> hits = new ArrayList<>();
        for (ObjectNode doc : this.index) if (matches(query, doc)) hits.add(doc);
        StringBuilder out = new StringBuilder("{\"hits\":{\"total\":{\"value\":" + hits.size() + ",\"relation\":\"eq\"},\"hits\":[");
        if (request.path("size").asInt(10) > 0) {
            out.append(hits.stream().map(d -> "{\"_source\":" + d.toString() + "}").collect(Collectors.joining(",")));
        }
        return out.append("]}}").toString();
    }

    /** term, match (exact, as on a keyword), bool.must / bool.filter (all), bool.should (any, minimum 1), bool.must_not, match_all. */
    private static boolean matches(JsonNode clause, JsonNode doc) {
        if (clause.isMissingNode() || clause.has("match_all")) return true;
        String leaf = clause.has("term") ? "term" : clause.has("match") ? "match" : null;
        if (leaf != null) {
            String field = clause.path(leaf).fieldNames().next();
            String wanted = clause.path(leaf).path(field).asText();
            String field0 = field.endsWith(".keyword") ? field.substring(0, field.length() - 8) : field;
            return doc.has(field0) && doc.path(field0).asText().equals(wanted);
        }
        JsonNode bool = clause.path("bool");
        for (String all : new String[] {"must", "filter"}) {
            for (JsonNode c : asList(bool.path(all))) if (!matches(c, doc)) return false;
        }
        for (JsonNode c : asList(bool.path("must_not"))) if (matches(c, doc)) return false;
        List<JsonNode> should = asList(bool.path("should"));
        return should.isEmpty() || should.stream().anyMatch(c -> matches(c, doc));
    }

    private static List<JsonNode> asList(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node.isArray()) node.forEach(out::add);
        else if (node.isObject()) out.add(node);
        return out;
    }

    private int hitsFor(Object query) throws Exception {
        String body = this.json.writeValueAsString(query);
        return this.json.readTree(this.answer("http://opensearch.test:9200/file-rag-chunks/_search", body))
            .path("hits").path("total").path("value").asInt();
    }

    private static Long tenantFilterOf(JsonNode boolQuery) {
        for (JsonNode c : asList(boolQuery.path("bool").path("filter"))) {
            if (c.path("term").has("tenantId")) return c.path("term").path("tenantId").asLong();
        }
        return null;
    }

    // ---- the attack ------------------------------------------------------------------------------

    /**
     * Tenant A asks for chunks with a bucket name that belongs to tenant B -- the case
     * validateBucketAccess exists to stop, reached here as if that check were missing or had moved
     * to another service. Before MIG-10 the bucket/key/etag clauses matched B's chunks and A read
     * B's payroll. Now the tenant filter excludes them: zero chunks, however the path is taken.
     */
    @Test
    void tenantARetrievingWithTenantBsBucketGetsZeroChunks() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "a");

        OpenSearchRagClient.RetrievalResult small = this.client.searchRelevantChunks(B_BUCKET, "payroll.pdf", "etag-b", new float[] {1f, 0f, 0f}, 8);
        OpenSearchRagClient.RetrievalResult ranked = this.client.searchRelevantChunks(B_BUCKET, "payroll.pdf", "etag-b", new float[] {1f, 0f, 0f}, 1);
        ReflectionTestUtils.setField(this.client, "knnQuerySupported", false);
        OpenSearchRagClient.RetrievalResult fallback = this.client.searchRelevantChunks(B_BUCKET, "payroll.pdf", "etag-b", new float[] {1f, 0f, 0f}, 1);

        // The count says zero, so neither ranking query is even sent -- and each, sent as it is
        // built, would match nothing of B's anyway.
        assertThat(this.hitsFor(this.client.knnQuery(B_BUCKET, "payroll.pdf", "etag-b", new float[] {1f, 0f, 0f}, 1))).isZero();
        assertThat(this.hitsFor(this.client.retrievalQuery(B_BUCKET, "payroll.pdf", "etag-b", true))).isZero();
        assertThat(small.chunks).isEmpty();
        assertThat(ranked.chunks).isEmpty();
        assertThat(fallback.chunks).isEmpty();
        assertThat(this.client.indexStateOf(B_BUCKET, "payroll.pdf", "etag-b")).isEqualTo(OpenSearchRagClient.IndexState.NOT_INDEXED);
    }

    /** And tenant B, asking for its own file, still gets all of it -- the filter is not a wall for everyone. */
    @Test
    void theOwnerStillRetrievesItsOwnChunks() {
        TenantContext.set(TENANT_B, "TENANT_USER", 2L, "b");

        OpenSearchRagClient.RetrievalResult own = this.client.searchRelevantChunks(B_BUCKET, "payroll.pdf", "etag-b", new float[] {1f, 0f, 0f}, 8);

        assertThat(own.chunks).containsExactly("tenant B salary line 0", "tenant B salary line 1", "tenant B salary line 2");
        assertThat(own.complete).isTrue();
    }

    // ---- every query carries the tenant ----------------------------------------------------------

    /**
     * The count, the document-order fetch, the k-NN query, the application-code fallback and the
     * delete-by-query before a re-index: each carries tenantId = TenantContext's tenant, in the
     * bool's filter clause. None of them is sent without one.
     */
    @Test
    void everyQueryAgainstTheIndexCarriesTheCallersTenant() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "a");

        this.client.searchRelevantChunks("tenant-a-docs", "manual.pdf", "etag-a", new float[] {1f, 0f, 0f}, 8);
        this.client.searchRelevantChunks("tenant-a-docs", "manual.pdf", "etag-a", new float[] {1f, 0f, 0f}, 1);
        ReflectionTestUtils.setField(this.client, "knnQuerySupported", false);
        this.client.searchRelevantChunks("tenant-a-docs", "manual.pdf", "etag-a", new float[] {1f, 0f, 0f}, 1);
        this.client.indexChunks(TENANT_A, "tenant-a-docs", "manual.pdf", "etag-a2",
            Collections.singletonList("new text"), Collections.singletonList(new float[] {1f, 0f, 0f}), "nomic-embed-text");

        assertThat(this.searches).as("count+fetch, count+knn, count+fallback fetch").hasSize(6);
        assertThat(this.deletes).hasSize(1);
        for (String body : this.searches) {
            JsonNode query = this.json.readTree(body).path("query");
            JsonNode scoped = query.has("knn") ? query.path("knn").path("embedding").path("filter") : query;
            assertThat(tenantFilterOf(scoped)).as(body).isEqualTo(TENANT_A);
        }
        assertThat(tenantFilterOf(this.json.readTree(this.deletes.get(0)).path("query"))).isEqualTo(TENANT_A);
    }

    /**
     * The tenant is the context's, not an argument's. The write path is the only one that is handed
     * a tenant, and one that disagrees with the caller's context is refused, not written: nothing is
     * deleted and nothing is bulk-indexed under somebody else's id.
     */
    @Test
    void aWriteForAnotherTenantThanTheCallersIsRefused() {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "a");

        OpenSearchRagClient.IndexOutcome outcome = this.client.indexChunks(TENANT_B, B_BUCKET, "payroll.pdf", "etag-b2",
            Collections.singletonList("overwrite"), Collections.singletonList(new float[] {1f, 0f, 0f}), "nomic-embed-text");

        assertThat(outcome.getStored()).isZero();
        assertThat(outcome.isComplete()).isFalse();
        assertThat(this.deletes).isEmpty();
        assertThat(this.log.list).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
    }

    // ---- no tenant, no hits ----------------------------------------------------------------------

    /**
     * No resolvable tenant -- a scheduler thread, a platform admin without a workspace -- is zero
     * hits and a log line, never an unfiltered query. Retrieval reports itself unavailable rather
     * than empty, so FileChatServiceImpl answers from the raw file instead of re-indexing it under
     * no tenant; the index state is UNKNOWN for the same reason; and no request leaves at all.
     */
    @Test
    void noResolvableTenantIsZeroHitsAndALogLineAndNoQueryIsSent() {
        TenantContext.clear();

        OpenSearchRagClient.RetrievalResult result = this.client.searchRelevantChunks(B_BUCKET, "payroll.pdf", "etag-b", new float[] {1f, 0f, 0f}, 8);
        OpenSearchRagClient.IndexState state = this.client.indexStateOf(B_BUCKET, "payroll.pdf", "etag-b");
        OpenSearchRagClient.IndexOutcome written = this.client.indexChunks(null, B_BUCKET, "payroll.pdf", "etag-b",
            Collections.singletonList("text"), Collections.singletonList(new float[] {1f, 0f, 0f}), "nomic-embed-text");

        assertThat(result.chunks).isEmpty();
        assertThat(result.failed).isTrue();
        assertThat(state).isEqualTo(OpenSearchRagClient.IndexState.UNKNOWN);
        assertThat(written.getStored()).isZero();
        verifyNoInteractions(this.restTemplate);
        assertThat(this.log.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
            .map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList()))
            .anySatisfy(m -> assertThat(m).contains("no resolvable tenant"));
    }

    /**
     * termsFilter itself fails closed: built without a tenant it matches nothing, so a future read
     * path that forgets the guard still returns zero hits rather than every tenant's chunks.
     */
    @Test
    void theFilterBuiltWithoutATenantMatchesNothing() throws Exception {
        TenantContext.clear();

        JsonNode filter = this.json.valueToTree(this.client.termsFilter(B_BUCKET, "payroll.pdf", "etag-b"));

        assertThat(this.index).noneMatch(doc -> matches(filter, doc));
    }

    // ---- relevance is untouched ------------------------------------------------------------------

    /**
     * For a single tenant the queries are what they were, plus one clause in the non-scoring filter
     * context alongside the other four scoping clauses: k, size and _source are unchanged, and
     * nothing in a filter scores, so ordering cannot move.
     */
    @Test
    void theTenantClauseIsNonScoringAndLeavesTheRestOfTheQueryAsItWas() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "a");
        JsonNode knn = this.json.valueToTree(this.client.knnQuery("tenant-a-docs", "manual.pdf", "etag-a", new float[] {1f, 0f, 0f}, 8));
        JsonNode fetch = this.json.valueToTree(this.client.retrievalQuery("tenant-a-docs", "manual.pdf", "etag-a", true));

        JsonNode knnFilter = knn.path("query").path("knn").path("embedding").path("filter");
        assertThat(knnFilter.path("bool").path("filter")).hasSize(5);
        assertThat(tenantFilterOf(knnFilter)).isEqualTo(TENANT_A);
        assertThat(knn.path("size").asInt()).isEqualTo(8);
        assertThat(knn.path("query").path("knn").path("embedding").path("k").asInt()).isEqualTo(8);
        assertThat(knn.path("_source").toString()).isEqualTo("[\"chunkIndex\",\"chunkText\"]");
        assertThat(fetch.path("query").path("bool").path("filter")).hasSize(5);
        assertThat(fetch.path("_source").toString()).isEqualTo("[\"chunkIndex\",\"chunkText\",\"embedding\"]");
        assertThat(Arrays.asList(knn.toString(), fetch.toString())).noneMatch(s -> s.contains("\"should\":[{\"term\":{\"tenantId\""));
    }
}
