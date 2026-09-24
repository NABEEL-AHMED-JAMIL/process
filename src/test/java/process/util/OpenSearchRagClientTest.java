package process.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What the RAG chunk store asks OpenSearch for, and what it does with the answer.
 *
 * {@link OpenSearchRagClientIT} already drives this class against a real cluster and a real
 * embedding model, and it is the right place to prove that cosine ranking actually surfaces the
 * semantically closest chunk. It is the wrong place to prove any of what is asserted here,
 * because all of this is about requests that are never sent and results that are never returned
 * -- a vector payload that must not be fetched, a chunk that must be dropped rather than
 * returned, a term clause that must be present, a flag that must not stay latched, and a ranking
 * query that must reach the vector index instead of pulling every vector back here to rank it.
 * None of that is visible from the outside of a healthy stack -- a term-filter retrieval and a
 * k-NN retrieval both return perfectly plausible chunks -- which is how each of these survived as
 * long as it did. A stubbed RestTemplate makes every one of them an ordinary assertion.
 *
 * @author Nabeel Ahmed
 */
public class OpenSearchRagClientTest {

    private static final String BASE_URL = "http://opensearch.test:9200";

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final OpenSearchRagClient client = new OpenSearchRagClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Every request body this class posted, in order -- the count query first, then the fetch. */
    private final List<String> postedBodies = new ArrayList<>();
    private final List<String> postedUrls = new ArrayList<>();

    @BeforeEach
    void wireUpAStubbedCluster() {
        ReflectionTestUtils.setField(this.client, "baseUrl", BASE_URL);
        ReflectionTestUtils.setField(this.client, "configuredEmbeddingModel", "nomic-embed-text");
        ReflectionTestUtils.setField(this.client, "restTemplate", this.restTemplate);
        // Every query is scoped to the caller's tenant (MIG-10); these tests are about everything else.
        TenantContext.set(1L, "TENANT_USER", 1L, "rag-test");
    }

    @AfterEach
    void clearTheCaller() {
        TenantContext.clear();
    }

    /**
     * Answers each {@code _search} / {@code _delete_by_query} POST with the next canned response,
     * repeating the last one once they run out, and records what was actually sent.
     */
    private void stubSearches(final String... responses) {
        this.stubSearches(false, responses);
    }

    /**
     * The same, except that a {@code knn} query is answered with the 400 an OpenSearch that cannot
     * serve one gives back -- too old for filtered k-NN, {@code index.knn} off, or an
     * {@code embedding} field that a {@code _bulk} auto-created as a plain float array. A rejected
     * call does not consume a canned response, so the array stays "count, then fetch" whichever
     * path the client ends up taking.
     */
    private void stubSearches(final boolean rejectKnn, final String... responses) {
        final int[] call = { 0 };
        doAnswer(invocation -> {
            Object[] arguments = invocation.getArguments();
            String body = String.valueOf(((HttpEntity<?>) arguments[1]).getBody());
            this.postedUrls.add(String.valueOf(arguments[0]));
            this.postedBodies.add(body);
            if (rejectKnn && isKnnQuery(body)) {
                throw badRequest("{\"error\":{\"type\":\"search_phase_execution_exception\","
                    + "\"reason\":\"[knn] requires index.knn to be set to true\"}}");
            }
            int index = Math.min(call[0]++, responses.length - 1);
            return responses[index];
        }).when(this.restTemplate).postForObject(anyString(), any(), eq(String.class));
    }

    private static boolean isKnnQuery(String body) {
        return body.contains("\"knn\"");
    }

    private String lastPostedBody() {
        return this.postedBodies.get(this.postedBodies.size() - 1);
    }

    private long knnQueriesSent() {
        return this.postedBodies.stream().filter(OpenSearchRagClientTest::isKnnQuery).count();
    }

    private static HttpClientErrorException notFound() {
        return HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
            new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
    }

    private static HttpClientErrorException badRequest(String body) {
        return HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
            new HttpHeaders(), body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /** A {@code size: 0} response: OpenSearch reports the total and returns no documents. */
    private static String countResponse(int total) {
        return "{\"took\":1,\"hits\":{\"total\":{\"value\":" + total + ",\"relation\":\"eq\"},\"hits\":[]}}";
    }

    /** One hit. Passing no floats is a chunk whose {@code embedding} was never fetched or stored. */
    private static String hit(int chunkIndex, String text, float... vector) {
        StringBuilder json = new StringBuilder("{\"_index\":\"file-rag-chunks\",\"_score\":1.0,\"_source\":{")
            .append("\"chunkIndex\":").append(chunkIndex)
            .append(",\"chunkText\":\"").append(text).append("\"");
        if (vector.length > 0) {
            json.append(",\"embedding\":[");
            for (int i = 0; i < vector.length; i++) {
                if (i > 0) {
                    json.append(",");
                }
                json.append(vector[i]);
            }
            json.append("]");
        }
        return json.append("}}").toString();
    }

    private static String searchResponse(String... hits) {
        return "{\"took\":3,\"hits\":{\"total\":{\"value\":" + hits.length + ",\"relation\":\"eq\"},\"hits\":["
            + String.join(",", hits) + "]}}";
    }

    private JsonNode mustClausesOf(String body) throws Exception {
        return this.objectMapper.readTree(body).path("query").path("bool").path("must");
    }

    // ---------------------------------------------------------------------------------------
    // The ranking that could not rank anything.
    // ---------------------------------------------------------------------------------------

    /**
     * A file with no more chunks than topK. The score sort is undone by the document-order sort
     * that follows it and limit(topK) discards nothing, so the vectors cannot change the answer --
     * and so they must not be fetched. Before the count query in front of this, every one of them
     * crossed the wire and was run through a cosine loop whose output was then thrown away.
     */
    @Test
    void aFileWithNoMoreChunksThanTopKNeverFetchesItsVectors() {
        stubSearches(countResponse(3), searchResponse(
            hit(2, "third", 9f, 9f, 9f),
            hit(0, "first", 1f, 0f, 0f),
            hit(1, "second", 0f, 1f, 0f)));

        OpenSearchRagClient.RetrievalResult result = this.client.searchRelevantChunks(
            "etl-bucket", "resume.pdf", "etag-1", new float[] { 1f, 0f, 0f }, 8);

        assertEquals(Arrays.asList("first", "second", "third"), result.chunks,
            "document order, which is what the old ranking produced here too -- at the cost of the vectors");
        assertTrue(result.complete, "nothing was left out");
        assertEquals(2, this.postedBodies.size(), "a size:0 count, then one fetch");
        assertTrue(this.postedUrls.get(0).endsWith("/file-rag-chunks/_search"));
        assertTrue(this.postedBodies.get(0).contains("\"size\":0"), "the first call is the count");
        assertTrue(this.postedBodies.get(1).contains("\"_source\":[\"chunkIndex\",\"chunkText\"]"),
            "ranking cannot discriminate here, so the stored vectors must stay in OpenSearch");
        assertFalse(this.postedBodies.get(1).contains("\"_source\":[\"chunkIndex\",\"chunkText\",\"embedding\"]"),
            "a file's worth of 768-float arrays fetched for a ranking that changes nothing");
    }

    // ---------------------------------------------------------------------------------------
    // The vector index nothing ever queried as one.
    // ---------------------------------------------------------------------------------------

    /**
     * The moment topK can actually discard something, ranking has to happen -- and it happens in
     * OpenSearch now, on the HNSW graph the mapping has been paying for since the index was
     * created.
     *
     * What this replaced: a constant_score bool/must of terms, size 2000, every matching chunk's
     * full 768 floats dragged back over HTTP so a Java loop could rank them. Measured on the live
     * cluster, one question about a 20-chunk PDF came back as a 229,285-byte response in which
     * every hit carried the identical _score. Every assertion here is about a request, because
     * that is the only place the difference is visible -- both shapes return plausible chunks.
     */
    @Test
    void theRankingPathAsksOpenSearchForTheNearestVectorsInsteadOfFetchingThemAll() throws Exception {
        stubSearches(countResponse(40), searchResponse(
            hit(7, "gamma"),
            hit(1, "alpha")));

        OpenSearchRagClient.RetrievalResult result = this.client.searchRelevantChunks(
            "etl-bucket", "manual.pdf", "etag-1", new float[] { 1f, 0f, 0f }, 2);

        assertEquals(Arrays.asList("alpha", "gamma"), result.chunks,
            "OpenSearch returns them best-match-first; the caller reads them in document order");
        assertFalse(result.complete, "two of forty is not the whole file");

        JsonNode body = this.objectMapper.readTree(this.lastPostedBody());
        JsonNode knn = body.path("query").path("knn").path("embedding");
        assertFalse(knn.isMissingNode(), "the ranking query must be a knn query on the embedding field");
        assertEquals(2, knn.path("k").asInt(), "k is topK -- what the caller actually wants back");
        assertEquals(2, body.path("size").asInt(), "and size must match it, or k neighbours get truncated");
        assertEquals(1.0d, knn.path("vector").get(0).asDouble(), 1e-6d, "the question vector goes to the graph");

        assertEquals("[\"chunkIndex\",\"chunkText\"]", body.path("_source").toString(),
            "the embedding must NOT be fetched -- that payload is the whole reason this query exists");

        JsonNode must = knn.path("filter").path("bool").path("must");
        assertEquals(4, must.size(), "bucket, key, etag and the vector space, applied during the graph walk");
        assertEquals("etl-bucket", must.get(0).path("term").path("bucket").asText());
        assertEquals("manual.pdf", must.get(1).path("term").path("key").asText());
        assertEquals("etag-1", must.get(2).path("term").path("etag").asText());
        assertEquals("nomic-embed-text",
            must.get(3).path("bool").path("should").get(0).path("term").path("embeddingModel").asText());
    }

    /**
     * A cluster that will not serve the knn query still has to answer the question. The Java
     * cosine path is kept for exactly this -- an OpenSearch older than 2.4, index.knn disabled, or
     * an index auto-created by a _bulk after a wipe with embedding as a plain float array -- and
     * it is the path this same test asserted before k-NN existed, unchanged.
     */
    @Test
    void aClusterThatRejectsTheKnnQueryFallsBackToRankingInJava() {
        stubSearches(true, countResponse(4), searchResponse(
            hit(0, "alpha", 1f, 0f, 0f),
            hit(1, "beta", 0f, 1f, 0f),
            hit(2, "gamma", 0.9f, 0.1f, 0f),
            hit(3, "delta", 0f, 0f, 1f)));

        OpenSearchRagClient.RetrievalResult result = this.client.searchRelevantChunks(
            "etl-bucket", "manual.pdf", "etag-1", new float[] { 1f, 0f, 0f }, 2);

        assertEquals(Arrays.asList("alpha", "gamma"), result.chunks,
            "the two closest to the question, handed back in document order");
        assertFalse(result.complete, "two of four is not the whole file");
        assertTrue(this.lastPostedBody().contains("\"_source\":[\"chunkIndex\",\"chunkText\",\"embedding\"]"),
            "on the fallback path the vectors are the whole point");
    }

    /**
     * And the rejection is learned once. A cluster cannot start supporting filtered k-NN without a
     * restart or a reindex, so re-asking on every question would spend a rejected round trip per
     * message to re-discover a fact that cannot have changed.
     */
    @Test
    void theKnnRejectionIsLearnedOnceRatherThanRetriedOnEveryQuestion() {
        stubSearches(true,
            countResponse(4), searchResponse(hit(0, "alpha", 1f, 0f, 0f), hit(1, "beta", 0f, 1f, 0f)),
            countResponse(4), searchResponse(hit(0, "alpha", 1f, 0f, 0f), hit(1, "beta", 0f, 1f, 0f)));

        this.client.searchRelevantChunks("etl-bucket", "manual.pdf", "etag-1", new float[] { 1f, 0f, 0f }, 2);
        this.client.searchRelevantChunks("etl-bucket", "manual.pdf", "etag-1", new float[] { 1f, 0f, 0f }, 2);

        assertEquals(1L, this.knnQueriesSent(),
            "the second question must go straight to the fallback rather than pay for the same 400 again");
    }

    /**
     * OpenSearch reports a shard-level failure INSIDE a 200 body. Read only as "no hits", a
     * cluster that could not execute the query on any shard is indistinguishable from a file with
     * no chunks -- and that reading is the one that sends FileChatServiceImpl off to re-extract,
     * re-chunk and re-embed the whole file on every message.
     */
    @Test
    void aKnnAnswerWhoseShardsAllFailedIsARefusalRatherThanAnEmptyFile() {
        stubSearches(countResponse(4),
            "{\"took\":2,\"_shards\":{\"total\":1,\"successful\":0,\"failed\":1,\"failures\":[{\"shard\":0,"
                + "\"reason\":{\"type\":\"query_shard_exception\",\"reason\":\"[knn] unknown query\"}}]},"
                + "\"hits\":{\"total\":{\"value\":0,\"relation\":\"eq\"},\"hits\":[]}}",
            searchResponse(hit(0, "alpha", 1f, 0f, 0f), hit(1, "beta", 0f, 1f, 0f)));

        OpenSearchRagClient.RetrievalResult result = this.client.searchRelevantChunks(
            "etl-bucket", "manual.pdf", "etag-1", new float[] { 1f, 0f, 0f }, 1);

        assertEquals(Collections.singletonList("alpha"), result.chunks,
            "the fallback ran and answered, instead of the file reading as empty and being re-embedded");
        assertFalse(result.failed);
    }

    /**
     * The other half of that distinction, and the reason the latch is narrow: a timeout is an
     * outage, not a statement about k-NN support. Treating it as a refusal would permanently
     * disable the fast path for the life of the process because the cluster hiccuped once, and
     * would hide the outage behind a fallback query that is about to fail in exactly the same way.
     */
    @Test
    void aKnnQueryThatTimesOutIsAnOutageAndDoesNotDisableTheFastPath() {
        doThrow(new RuntimeException("Read timed out")).when(this.restTemplate)
            .postForObject(anyString(), any(), eq(String.class));

        OpenSearchRagClient.RetrievalResult result = this.client.searchRelevantChunks(
            "etl-bucket", "manual.pdf", "etag-1", new float[] { 1f, 0f, 0f }, 2);

        assertTrue(result.failed, "an outage must surface as one, not as an empty or a downgraded answer");
        assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(this.client, "knnQuerySupported"),
            "a cluster that is merely down has said nothing about whether it can serve a knn query");
    }

    // ---------------------------------------------------------------------------------------
    // The sentinel nothing looked at.
    // ---------------------------------------------------------------------------------------

    /**
     * The day an operator swaps embedding.model for a model of a different size. Every stored
     * vector mismatches the question vector's dimension, so nothing in the index can answer the
     * question. This used to score all of them exactly -1f, tie the descending sort completely,
     * and hand the model the first eight chunks OpenSearch returned -- deterministically the
     * opening of the document -- labelled "RELEVANT EXCERPTS FROM THE FILE".
     *
     * Driven through the k-NN rejection, which is not a contrivance: a knn query carrying a
     * 4-dimension vector against a field mapped for 768 is itself a 400 from a live cluster, so a
     * dimension swap lands on this exact path.
     */
    @Test
    void everyVectorMismatchingTheQuestionReturnsNothingRatherThanTheHeadOfTheFile() {
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            hits.add(hit(i, "chunk-" + i, 1f, 0f, 0f));
        }
        stubSearches(true, countResponse(12), searchResponse(hits.toArray(new String[0])));

        OpenSearchRagClient.RetrievalResult result = this.client.searchRelevantChunks(
            "etl-bucket", "big.pdf", "etag-1", new float[] { 1f, 0f, 0f, 0f }, 8);

        assertTrue(result.chunks.isEmpty(),
            "an empty result is a state the caller already recovers from; eight confidently mislabelled "
                + "chunks is not");
        assertFalse(result.complete);
    }

    /**
     * Unreadable vectors among good ones are dropped rather than padding the result out to topK.
     * Four chunks, two of them unreadable, and room for three: -1f sorted them to the bottom but
     * still inside the limit, so a chunk nothing could score was handed over as the third best
     * match in the file. Dropping them also means this is no longer the whole file.
     *
     * On the fallback path, which is the only one that scores vectors in application code at all.
     */
    @Test
    void unreadableVectorsAreDroppedRatherThanPaddingOutTheResult() {
        stubSearches(true, countResponse(4), searchResponse(
            hit(0, "alpha", 1f, 0f, 0f),
            hit(1, "broken-one"),
            hit(2, "broken-two"),
            hit(3, "gamma", 0f, 1f, 0f)));

        OpenSearchRagClient.RetrievalResult result = this.client.searchRelevantChunks(
            "etl-bucket", "manual.pdf", "etag-1", new float[] { 1f, 0f, 0f }, 3);

        assertEquals(Arrays.asList("alpha", "gamma"), result.chunks,
            "two chunks could be scored, so two chunks come back -- not two plus filler");
        assertFalse(result.complete,
            "chunks were discarded, so telling the caller it has the whole file would be a lie");
    }

    /**
     * -1 is a perfectly ordinary cosine score for two opposite vectors, which is exactly why it
     * could not also mean "I could not compare these".
     */
    @Test
    void incomparableVectorsAnswerNaNWhileMinusOneStaysARealScore() {
        assertTrue(Double.isNaN(OpenSearchRagClient.cosineSimilarity(
            new float[] { 1f, 0f }, new float[] { 1f, 0f, 0f })), "different dimensions");
        assertTrue(Double.isNaN(OpenSearchRagClient.cosineSimilarity(new float[] { 1f, 0f }, null)),
            "no vector at all");
        assertTrue(Double.isNaN(OpenSearchRagClient.cosineSimilarity(new float[0], new float[0])),
            "two empty vectors");
        assertTrue(Double.isNaN(OpenSearchRagClient.cosineSimilarity(
            new float[] { 0f, 0f }, new float[] { 1f, 0f })), "a zero vector has no direction");
        assertEquals(-1.0d, OpenSearchRagClient.cosineSimilarity(
            new float[] { 1f, 0f }, new float[] { -1f, 0f }), 1e-6d, "a genuine opposite");
    }

    // ---------------------------------------------------------------------------------------
    // The model tag that was written and never read.
    // ---------------------------------------------------------------------------------------

    /**
     * Two 768-dimension models produce vectors that compare perfectly well and mean nothing to
     * each other. The embeddingModel term clause is the only thing that can tell them apart,
     * because the arithmetic cannot.
     */
    @Test
    void retrievalIsScopedToTheConfiguredEmbeddingModel() throws Exception {
        stubSearches(countResponse(0));

        this.client.searchRelevantChunks("etl-bucket", "a.pdf", "etag-1", new float[] { 1f, 0f }, 8);

        JsonNode must = this.mustClausesOf(this.postedBodies.get(0));
        assertEquals(4, must.size(), "bucket, key, etag -- and the vector space they were embedded into");
        // The model clause is a should over both field paths now, because an index that predates
        // embeddingModel in the mapping has it as analyzed text and a bare term matched 0 of 222
        // chunks on the live cluster. Either path is enough; the scoping it exists for is intact.
        JsonNode either = must.get(3).path("bool").path("should");
        assertEquals(2, either.size(), "one clause for a keyword mapping, one for a dynamic one");
        assertEquals("nomic-embed-text", either.get(0).path("term").path("embeddingModel").asText(),
            "a superseded model's chunks have to stop matching, not silently rank as noise");
        assertEquals("nomic-embed-text", either.get(1).path("term").path("embeddingModel.keyword").asText(),
            "and the same has to hold on an index whose embeddingModel was dynamically mapped");
    }

    /** The same clause guards the readiness check, or the swap heals on one path and not the other. */
    @Test
    void theIsIndexedCheckAsksAboutTheCurrentModelToo() throws Exception {
        stubSearches(countResponse(0));

        assertFalse(this.client.isIndexed("etl-bucket", "a.pdf", "etag-1"));

        JsonNode must = this.mustClausesOf(this.postedBodies.get(0));
        assertEquals(4, must.size());
        JsonNode either = must.get(3).path("bool").path("should");
        assertEquals("nomic-embed-text", either.get(0).path("term").path("embeddingModel").asText());
        assertEquals("nomic-embed-text", either.get(1).path("term").path("embeddingModel.keyword").asText());
    }

    /** A blank embedding.model keeps the old unfiltered scope rather than retrieving nothing at all. */
    @Test
    void anUnsetEmbeddingModelFallsBackToTheOldUnfilteredScope() throws Exception {
        ReflectionTestUtils.setField(this.client, "configuredEmbeddingModel", "   ");
        stubSearches(countResponse(0));

        this.client.searchRelevantChunks("etl-bucket", "a.pdf", "etag-1", new float[] { 1f, 0f }, 8);

        assertEquals(3, this.mustClausesOf(this.postedBodies.get(0)).size(),
            "unfiltered is bad; matching nothing forever because a property was cleared is worse");
    }

    // ---------------------------------------------------------------------------------------
    // The latch that outlived its index.
    // ---------------------------------------------------------------------------------------

    /** The clean-slate procedure wipes OpenSearch while the backend keeps running. */
    @Test
    void aMissingIndexClearsTheEnsuredLatch() {
        ReflectionTestUtils.setField(this.client, "indexEnsured", true);
        doThrow(notFound()).when(this.restTemplate).postForObject(anyString(), any(), eq(String.class));

        OpenSearchRagClient.RetrievalResult result = this.client.searchRelevantChunks(
            "etl-bucket", "a.pdf", "etag-1", new float[] { 1f, 0f }, 8);

        assertTrue(result.chunks.isEmpty());
        assertFalse(result.failed,
            "a 404 is an answer: there is no index, so there are genuinely no chunks and indexing "
                + "is the right next step -- this is the one empty result the caller should act on");
        assertEquals(Boolean.FALSE, ReflectionTestUtils.getField(this.client, "indexEnsured"),
            "an index that is gone must not stay latched as 'already created for this process'");
    }

    /**
     * The consequence that actually mattered: with the latch stuck, the next write went straight
     * to _bulk against an index that no longer existed, and OpenSearch auto-created it from the
     * payload with a dynamic mapping -- no knn_vector, no index.knn, and bucket/key/etag as
     * analyzed text, which this deployment's hyphenated bucket names can never term-match again.
     * The re-creating PUT has to happen first, which is also why deleteChunksForFile now runs
     * before ensureIndex: its 404 is the earliest signal available.
     */
    @Test
    void afterAWipeTheNextWriteRecreatesTheIndexBeforeBulkCanAutoCreateOne() {
        ReflectionTestUtils.setField(this.client, "indexEnsured", true);
        doThrow(notFound()).when(this.restTemplate).postForObject(anyString(), any(), eq(String.class));
        doThrow(notFound()).when(this.restTemplate).getForEntity(anyString(), eq(String.class));
        doReturn(new ResponseEntity<String>("{\"acknowledged\":true}", HttpStatus.OK))
            .when(this.restTemplate).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class),
                eq(String.class));

        this.client.indexChunks(1L, "etl-bucket", "a.pdf", "etag-1",
            Collections.singletonList("only chunk"),
            Collections.singletonList(new float[] { 1f, 0f, 0f }), "nomic-embed-text");

        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpMethod> methods = ArgumentCaptor.forClass(HttpMethod.class);
        verify(this.restTemplate, times(2)).exchange(urls.capture(), methods.capture(),
            any(HttpEntity.class), eq(String.class));

        assertEquals(HttpMethod.PUT, methods.getAllValues().get(0), "the index is re-created first");
        assertTrue(urls.getAllValues().get(0).endsWith("/file-rag-chunks"));
        assertEquals(HttpMethod.POST, methods.getAllValues().get(1), "and only then is the bulk written");
        assertTrue(urls.getAllValues().get(1).contains("/_bulk"));
    }

    /**
     * A new index has to be created as something a FILTERED knn query can be served from, which is
     * not the same as being a k-NN index.
     *
     * Without an explicit method block the embedding field gets the cluster's default engine, and
     * on the versions this deploys against that engine cannot apply a filter during the graph walk
     * at all -- filtering is a Lucene-engine (2.4+) and faiss (2.9+) capability. The knn query is
     * rejected on such an index and every retrieval falls back to dragging the whole file's
     * vectors over HTTP, which is the entire cost the k-NN path exists to remove. The space type
     * is the other half: the fallback ranks by cosine, so a graph built for the default l2 would
     * rank by a different metric than the code that has to agree with it.
     */
    @Test
    void aNewIndexIsCreatedWithAMethodThatFilteredKnnCanBeServedFrom() throws Exception {
        doThrow(notFound()).when(this.restTemplate).getForEntity(anyString(), eq(String.class));
        doReturn(new ResponseEntity<String>("{\"acknowledged\":true}", HttpStatus.OK))
            .when(this.restTemplate).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class),
                eq(String.class));

        this.client.indexChunks(1L, "etl-bucket", "a.pdf", "etag-1",
            Collections.singletonList("only chunk"),
            Collections.singletonList(new float[] { 1f, 0f, 0f }), "nomic-embed-text");

        ArgumentCaptor<HttpEntity> sent = ArgumentCaptor.forClass(HttpEntity.class);
        verify(this.restTemplate, times(2)).exchange(anyString(), any(HttpMethod.class), sent.capture(),
            eq(String.class));

        JsonNode embedding = this.objectMapper.readTree(String.valueOf(sent.getAllValues().get(0).getBody()))
            .path("mappings").path("properties").path("embedding");
        assertEquals("knn_vector", embedding.path("type").asText());
        assertEquals("hnsw", embedding.path("method").path("name").asText());
        assertEquals("lucene", embedding.path("method").path("engine").asText(),
            "the default engine rejects a knn query that carries a filter, which is every query this "
                + "class issues");
        assertEquals("cosinesimil", embedding.path("method").path("space_type").asText(),
            "the application-code fallback ranks by cosine, so the graph must not be built for l2");
    }

    // ---------------------------------------------------------------------------------------
    // "The index exists" was never the same thing as "the index is usable".
    // ---------------------------------------------------------------------------------------

    @Test
    void anAutoCreatedDynamicMappingIsRecognisedAsBroken() throws Exception {
        JsonNode dynamic = this.objectMapper.readTree("{\"mappings\":{\"properties\":{"
            + "\"bucket\":{\"type\":\"text\"},\"embedding\":{\"type\":\"float\"}}},"
            + "\"settings\":{\"index\":{\"number_of_shards\":\"1\"}}}");

        String complaint = OpenSearchRagClient.mappingComplaint(dynamic, 768);

        assertNotNull(complaint, "a float array is not a vector index");
        assertTrue(complaint.contains("knn_vector"));
    }

    @Test
    void anIndexBuiltForADifferentDimensionIsRecognisedAsBroken() throws Exception {
        JsonNode existing = this.objectMapper.readTree(healthyIndex(768));

        assertNotNull(OpenSearchRagClient.mappingComplaint(existing, 1024),
            "every chunk written from here would be rejected, one warn line at a time");
        assertNull(OpenSearchRagClient.mappingComplaint(existing, 768),
            "the mapping this class itself writes must not trip the check");
    }

    /**
     * The one index shape that could turn the new embeddingModel term clause into a permanent
     * re-index loop: an index old enough to predate that field in the mapping, where the first
     * write dynamically maps it as analyzed text and the standard analyzer takes a model name
     * like nomic-embed-text apart at the hyphens so no term query can ever match it again.
     */
    @Test
    void anEmbeddingModelFieldThatIsNotAKeywordIsRecognisedAsBroken() throws Exception {
        JsonNode legacy = this.objectMapper.readTree("{\"mappings\":{\"properties\":{"
            + "\"embedding\":{\"type\":\"knn_vector\",\"dimension\":768},"
            + "\"embeddingModel\":{\"type\":\"text\"}}},"
            + "\"settings\":{\"index\":{\"knn\":\"true\"}}}");

        String complaint = OpenSearchRagClient.mappingComplaint(legacy, 768);

        assertNotNull(complaint);
        assertTrue(complaint.contains("keyword"));
    }

    /**
     * The shape the live index was actually in, which retrieval must work against.
     *
     * A dynamically mapped string is analyzed text WITH the .keyword sub-field OpenSearch adds by
     * default. The chunks and their vectors are perfectly good; only the exact-term clause could
     * not reach them. Measured on the running cluster: the bare term matched 0 of 222 chunks and
     * the same term against embeddingModel.keyword matched 219. Reindexing to fix a query is the
     * wrong trade, so this shape must retrieve rather than complain.
     */
    @Test
    void aDynamicallyMappedEmbeddingModelIsRetrievableAndSoIsNotAComplaint() throws Exception {
        JsonNode dynamicString = this.objectMapper.readTree("{\"mappings\":{\"properties\":{"
            + "\"embedding\":{\"type\":\"knn_vector\",\"dimension\":768},"
            + "\"embeddingModel\":{\"type\":\"text\",\"fields\":{"
            + "\"keyword\":{\"type\":\"keyword\",\"ignore_above\":256}}}}},"
            + "\"settings\":{\"index\":{\"knn\":\"true\"}}}");

        assertNull(OpenSearchRagClient.mappingComplaint(dynamicString, 768),
            "a text mapping WITH a keyword sub-field retrieves correctly and must not be reported broken");
    }

    /**
     * The model clause has to match an index this class created AND one that predates the field.
     *
     * This is the defect that made RAG dead on the live cluster: every question read as "not
     * indexed yet", re-embedded the whole file, searched again, still found nothing, and answered
     * from truncated raw text -- on every single message.
     */
    @Test
    void theModelClauseMatchesTheFieldWhicheverWayItIsMapped() {
        String rendered = String.valueOf(OpenSearchRagClient.modelClause("nomic-embed-text"));
        assertTrue(rendered.contains("embeddingModel.keyword"),
            "the clause must reach an index whose embeddingModel was dynamically mapped as text");
        assertTrue(rendered.contains("embeddingModel="),
            "and must still reach one this class created, where it is a keyword");
        assertTrue(rendered.contains("minimum_should_match"),
            "either path matching is enough; requiring both would match nothing anywhere");
    }

    // ---------------------------------------------------------------------------------------
    // "The cluster has nothing for this file" and "the cluster did not answer" are not the
    // same sentence, and collapsing them cost a full re-embed per message.
    // ---------------------------------------------------------------------------------------

    /**
     * A timeout, a 5xx or an unparseable body. All three used to come back as a plain empty list,
     * which FileChatServiceImpl.resolveContext reads as "nothing indexed for this file version
     * yet" -- so it took the index lock, re-extracted the file, re-chunked it, re-embedded every
     * chunk and wrote them into the cluster that was already failing, on every single message for
     * the length of the outage.
     */
    @Test
    void aClusterThatCannotAnswerIsAFailureRatherThanAnEmptyIndex() {
        doThrow(new RuntimeException("connect timed out")).when(this.restTemplate)
            .postForObject(anyString(), any(), eq(String.class));

        OpenSearchRagClient.RetrievalResult result = this.client.searchRelevantChunks(
            "etl-bucket", "a.pdf", "etag-1", new float[] { 1f, 0f }, 8);

        assertTrue(result.chunks.isEmpty());
        assertTrue(result.failed,
            "an outage must not read as 'this file has no chunks' -- that answer triggers a full "
                + "re-chunk and re-embed of the file on every message");
        assertFalse(result.complete,
            "claiming the whole file came back when nothing did is the confusion this flag ends");
    }

    /** The other half of the distinction, which has to keep working or nothing ever indexes. */
    @Test
    void aClusterReportingZeroChunksIsNotAFailure() {
        stubSearches(countResponse(0));

        OpenSearchRagClient.RetrievalResult result = this.client.searchRelevantChunks(
            "etl-bucket", "a.pdf", "etag-1", new float[] { 1f, 0f }, 8);

        assertTrue(result.chunks.isEmpty());
        assertFalse(result.failed,
            "a cluster that answered 'zero' really has nothing for this file version, and that is "
                + "exactly the case the caller is supposed to respond to by indexing it");
    }

    /**
     * A question vector that could not be produced is the same class of non-answer: the file's
     * chunks were never consulted, so nothing here says anything about whether it is indexed.
     */
    @Test
    void anEmptyQuestionVectorIsAFailureRatherThanAnEmptyIndex() {
        OpenSearchRagClient.RetrievalResult result = this.client.searchRelevantChunks(
            "etl-bucket", "a.pdf", "etag-1", new float[0], 8);

        assertTrue(result.chunks.isEmpty());
        assertTrue(result.failed,
            "re-indexing on the strength of a retrieval that never ran acts on a fact this call "
                + "did not establish");
    }

    /** The same three-way distinction on the readiness side, which drives the chat panel's banner. */
    @Test
    void aCountThatCannotBeAnsweredIsUnknownRatherThanNotIndexed() {
        doThrow(new RuntimeException("connect timed out")).when(this.restTemplate)
            .postForObject(anyString(), any(), eq(String.class));

        assertEquals(OpenSearchRagClient.IndexState.UNKNOWN,
            this.client.indexStateOf("etl-bucket", "a.pdf", "etag-1"),
            "the cluster said nothing, so nothing was learned about this file either way");
        assertFalse(this.client.isIndexed("etl-bucket", "a.pdf", "etag-1"),
            "the boolean shape still has to collapse UNKNOWN to false -- a redundant re-index is "
                + "recoverable, retrieving from chunks that are not there is not");
    }

    @Test
    void aClusterReportingChunksIsIndexed() {
        stubSearches(countResponse(12));

        assertEquals(OpenSearchRagClient.IndexState.INDEXED,
            this.client.indexStateOf("etl-bucket", "a.pdf", "etag-1"));
    }

    /** The mapping ensureIndex itself writes, which must never be reported as broken. */
    private static String healthyIndex(int dimension) {
        return "{\"mappings\":{\"properties\":{"
            + "\"bucket\":{\"type\":\"keyword\"},\"key\":{\"type\":\"keyword\"},"
            + "\"etag\":{\"type\":\"keyword\"},\"chunkIndex\":{\"type\":\"integer\"},"
            + "\"chunkText\":{\"type\":\"text\"},"
            + "\"embedding\":{\"type\":\"knn_vector\",\"dimension\":" + dimension + "},"
            + "\"embeddingModel\":{\"type\":\"keyword\"}}},"
            + "\"settings\":{\"index\":{\"knn\":\"true\",\"number_of_shards\":\"1\"}}}";
    }

}
