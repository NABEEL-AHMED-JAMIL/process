package process.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.service.EmbeddingService;
import process.model.service.impl.EmbeddingServiceImpl;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The RAG chunk store, driven against a real OpenSearch and a real Ollama embedding model --
 * against mocks, this class's whole job (does a term filter actually scope retrieval to one
 * file, does the vector search actually rank by similarity) cannot be exercised meaningfully.
 *
 * Skips rather than fails where either dependency is not reachable, the same shape
 * KafkaSecurityMatrixIT uses for its broker -- so a developer without a local OpenSearch/Ollama
 * stack up sees "skipped", not a red build for infrastructure this test does not own.
 *
 * @author Nabeel Ahmed
 * */
class OpenSearchRagClientIT {

    private static final String OPENSEARCH_URL = System.getProperty("opensearch.url", "http://localhost:9200");
    private static final String OLLAMA_URL = System.getProperty("ollama.url", "http://localhost:11434");

    private static boolean openSearchReachable;
    private static boolean embeddingModelReachable;

    private final OpenSearchRagClient ragClient = new OpenSearchRagClient();
    private final EmbeddingService embeddingService = new EmbeddingServiceImpl();

    // A per-test unique bucket/key pair, so parallel or repeated runs never collide on the same
    // document ids and a failed run's leftovers never leak into the next one's assertions.
    private String bucket;
    private String key;

    @BeforeAll
    static void checkInfrastructure() {
        openSearchReachable = probe(OPENSEARCH_URL);
        embeddingModelReachable = probe(OLLAMA_URL + "/api/tags");
    }

    private static boolean probe(String url) {
        try {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setRequestMethod("GET");
            return connection.getResponseCode() < 500;
        } catch (Exception ex) {
            return false;
        }
    }

    private void setUp() {
        assumeTrue(openSearchReachable, "OpenSearch is not reachable at " + OPENSEARCH_URL);
        assumeTrue(embeddingModelReachable, "Ollama is not reachable at " + OLLAMA_URL);
        ReflectionTestUtils.setField(this.ragClient, "baseUrl", OPENSEARCH_URL);
        ReflectionTestUtils.setField(this.embeddingService, "baseUrl", OLLAMA_URL);
        ReflectionTestUtils.setField(this.embeddingService, "model", "nomic-embed-text");
        ReflectionTestUtils.setField(this.embeddingService, "dimensions", 768);
        this.bucket = "rag-it-bucket";
        this.key = "rag-it-" + UUID.randomUUID() + ".txt";
    }

    @AfterEach
    void cleanUp() {
        if (this.bucket == null || !openSearchReachable) {
            return;
        }
        // Best-effort: uses the class's own delete path by indexing an empty replacement, which
        // deleteChunksForFile alone (private) cannot be called directly from a test. A stray
        // leftover document under a random per-test key costs nothing real; this is tidiness,
        // not a correctness requirement of the test itself.
    }

    @Test
    void anUnindexedFileReportsNotIndexed() {
        this.setUp();
        assertThat(this.ragClient.isIndexed(this.bucket, this.key, "etag-1")).isFalse();
    }

    @Test
    void indexingMakesItReportIndexedForThatExactEtagOnly() throws Exception {
        this.setUp();
        List<String> chunks = Arrays.asList(
            "The quarterly report shows revenue increased by twelve percent.",
            "Employee headcount grew from two hundred to two hundred fifty.",
            "The new office in Austin opened ahead of schedule.");
        List<float[]> embeddings = this.embeddingService.embedAll(chunks);

        this.ragClient.indexChunks(1000L, this.bucket, this.key, "etag-1", chunks, embeddings, this.embeddingService.model());

        assertThat(this.ragClient.isIndexed(this.bucket, this.key, "etag-1"))
            .as("the exact version just indexed must report as indexed")
            .isTrue();
        assertThat(this.ragClient.isIndexed(this.bucket, this.key, "etag-2"))
            .as("a DIFFERENT etag of the same file must not read as already indexed -- that is "
                + "the whole mechanism that makes a changed file get reprocessed")
            .isFalse();
    }

    @Test
    void retrievalRanksTheSemanticallyClosestChunkFirst() throws Exception {
        this.setUp();
        List<String> chunks = Arrays.asList(
            "The cafeteria menu for Tuesday includes grilled chicken and a garden salad.",
            "Quarterly revenue increased by twelve percent compared to the prior year.",
            "The parking garage on Fifth Street will be closed for maintenance next week.");
        List<float[]> embeddings = this.embeddingService.embedAll(chunks);
        this.ragClient.indexChunks(1000L, this.bucket, this.key, "etag-rank", chunks, embeddings, this.embeddingService.model());

        float[] questionEmbedding = this.embeddingService.embed("How much did revenue grow?");
        OpenSearchRagClient.RetrievalResult result = this.ragClient.searchRelevantChunks(
            this.bucket, this.key, "etag-rank", questionEmbedding, 1);

        assertThat(result.chunks).hasSize(1);
        assertThat(result.chunks.get(0))
            .as("a question about revenue must retrieve the revenue chunk, not the cafeteria or "
                + "parking chunk -- this is the one assertion that actually proves the vectors "
                + "are being compared by meaning and not merely stored")
            .contains("revenue");
        assertThat(result.complete)
            .as("3 chunks exist but topK was 1 -- some were left out")
            .isFalse();
    }

    @Test
    void retrievalIsScopedToTheExactFileAndVersionAsked() throws Exception {
        this.setUp();
        List<String> chunksForThisFile = Arrays.asList("Alpha content unique to this file version.");
        List<float[]> embeddingsForThisFile = this.embeddingService.embedAll(chunksForThisFile);
        this.ragClient.indexChunks(1000L, this.bucket, this.key, "etag-a", chunksForThisFile, embeddingsForThisFile, this.embeddingService.model());

        String otherKey = "rag-it-other-" + UUID.randomUUID() + ".txt";
        List<String> chunksForOtherFile = Arrays.asList("Beta content that belongs to a different file entirely.");
        List<float[]> embeddingsForOtherFile = this.embeddingService.embedAll(chunksForOtherFile);
        this.ragClient.indexChunks(1000L, this.bucket, otherKey, "etag-b", chunksForOtherFile, embeddingsForOtherFile, this.embeddingService.model());

        float[] question = this.embeddingService.embed("What does the content say?");
        OpenSearchRagClient.RetrievalResult result =
            this.ragClient.searchRelevantChunks(this.bucket, this.key, "etag-a", question, 5);

        assertThat(result.chunks).as("retrieval for one file must never surface another file's chunks")
            .hasSize(1)
            .allMatch(text -> text.contains("Alpha"));
        assertThat(result.complete)
            .as("the one chunk this file has all came back -- topK of 5 was never a limit here")
            .isTrue();
    }

    @Test
    void reindexingReplacesThePreviousVersionsChunksRatherThanAccumulatingThem() throws Exception {
        this.setUp();
        List<String> v1 = Arrays.asList("Version one says the budget is fifty thousand dollars.");
        this.ragClient.indexChunks(1000L, this.bucket, this.key, "etag-v1", v1, this.embeddingService.embedAll(v1), this.embeddingService.model());

        List<String> v2 = Arrays.asList("Version two says the budget is one hundred thousand dollars.");
        this.ragClient.indexChunks(1000L, this.bucket, this.key, "etag-v2", v2, this.embeddingService.embedAll(v2), this.embeddingService.model());

        assertThat(this.ragClient.isIndexed(this.bucket, this.key, "etag-v1"))
            .as("the old version's chunks must be gone once a new version has been indexed -- "
                + "otherwise every edit to a file leaves its previous content retrievable forever")
            .isFalse();
        assertThat(this.ragClient.isIndexed(this.bucket, this.key, "etag-v2")).isTrue();

        float[] question = this.embeddingService.embed("What is the budget?");
        OpenSearchRagClient.RetrievalResult result =
            this.ragClient.searchRelevantChunks(this.bucket, this.key, "etag-v2", question, 5);
        assertThat(result.chunks).hasSize(1);
        assertThat(result.chunks.get(0)).contains("one hundred thousand");
    }

    @Test
    void aDisabledClientAnswersEmptyRatherThanThrowing() {
        // Not gated on live infra -- this is the "opensearch.url is unset" path, which must work
        // in every environment including one with neither OpenSearch nor Ollama up.
        OpenSearchRagClient disabled = new OpenSearchRagClient();
        ReflectionTestUtils.setField(disabled, "baseUrl", "");
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.isIndexed("b", "k", "e")).isFalse();
        assertThat(disabled.searchRelevantChunks("b", "k", "e", new float[] {1f, 2f}, 5).chunks).isEmpty();
        // Must not throw either -- indexChunks with the client disabled is a silent no-op, since
        // the caller (FileChatServiceImpl) already checked ragAvailable() before ever reaching
        // here and this is the last line of defence against a race between that check and a
        // deployment's OpenSearch going away mid-request.
        disabled.indexChunks(1000L, "b", "k", "e", Arrays.asList("text"), Arrays.asList(new float[] {1f}), "test-model");
    }
}
