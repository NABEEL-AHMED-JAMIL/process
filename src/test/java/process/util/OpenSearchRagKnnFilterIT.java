package process.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import process.security.TenantContext;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The filtered k-NN retrieval query, sent to a real OpenSearch -- the only place its defect could
 * be seen.
 *
 * On the live 2.10 cluster every k-NN retrieval came back HTTP 400 "failed to create query:
 * Rewrite first", {@code knnQuerySupported} latched false, and every question from then on paid
 * for the application-code fallback. The cause was the {@code term} on {@code embeddingModel.keyword}
 * inside the knn {@code filter}: that sub-field does not exist on the index this class creates
 * (there {@code embeddingModel} is a plain keyword), OpenSearch's term query can only handle an
 * unmapped field after a rewrite, and the k-NN plugin builds its filter without rewriting it. A
 * stubbed RestTemplate accepts any JSON, so no unit test could see this.
 *
 * Nothing here touches {@code file-rag-chunks}. Each test builds its own throwaway index under a
 * random name and deletes it afterwards, so this is safe to run against the cluster production
 * reads from. Skipped when OpenSearch is not reachable; not picked up by {@code mvn test} (an *IT),
 * run it with {@code mvn -o test -Dtest=OpenSearchRagKnnFilterIT} or under {@code -Pit}.
 *
 * Vectors are deterministic, seeded, 768-dimension unit vectors rather than Ollama embeddings: the
 * question is whether the filter is accepted and whether k-NN ranks as the fallback does, and both
 * are sharper with vectors whose true ordering this test can compute itself.
 */
public class OpenSearchRagKnnFilterIT {

    private static final String OPENSEARCH_URL = System.getProperty("opensearch.url", "http://localhost:9200");
    private static final String MODEL = "nomic-embed-text";
    private static final long TENANT = 2905L;
    private static final long OTHER_TENANT = 1000L;
    private static final int DIMENSIONS = 768;
    private static final int CHUNKS = 30;
    private static final int TOP_K = 8;
    private static final String BUCKET = "worker-store";
    private static final String KEY = "hurricanes/out/hurricane_data_2020.csv";
    private static final String ETAG = "86b974b1ccfb7e1eb7b3a15d7149b1dd";

    private static boolean openSearchReachable;

    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper json = new ObjectMapper();
    private final OpenSearchRagClient client = new OpenSearchRagClient();
    private String index;
    private List<float[]> vectors;
    private float[] question;

    @BeforeAll
    static void probe() {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(OPENSEARCH_URL).openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            openSearchReachable = connection.getResponseCode() < 500;
        } catch (Exception ex) {
            openSearchReachable = false;
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(openSearchReachable, "OpenSearch is not reachable at " + OPENSEARCH_URL);
        this.index = "rag-knn-it-" + UUID.randomUUID();
        ReflectionTestUtils.setField(this.client, "baseUrl", OPENSEARCH_URL);
        ReflectionTestUtils.setField(this.client, "configuredEmbeddingModel", MODEL);
        ReflectionTestUtils.setField(this.client, "indexName", this.index);
        TenantContext.set(TENANT, "TENANT_USER", 1L, "rag-knn-it");

        // Each chunk sits at a chosen cosine from the question -- 30 distinct values 0.03 apart,
        // shuffled across chunk indexes -- so the true ranking has no near-ties for float rounding
        // to flip, and best-first order is nothing like document order.
        Random random = new Random(20260924L);
        this.question = unit(randomVector(random));
        List<Double> cosines = IntStream.range(0, CHUNKS).mapToObj(i -> 0.95 - 0.03 * i).collect(Collectors.toList());
        Collections.shuffle(cosines, random);
        this.vectors = new ArrayList<>();
        for (int i = 0; i < CHUNKS; i++) {
            this.vectors.add(atCosine(this.question, cosines.get(i), random));
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (this.index != null && openSearchReachable) {
            try {
                this.http.delete(OPENSEARCH_URL + "/" + this.index);
            } catch (Exception ignored) {
                // Never created -- the test failed before its first write.
            }
        }
    }

    /**
     * The index exactly as this class creates it: embeddingModel a keyword, no .keyword sub-field.
     * This is the live cluster's shape and the one that answered 400.
     */
    @Test
    void theFilteredKnnQueryIsAcceptedOnTheIndexThisClassCreates() throws Exception {
        List<String> texts = IntStream.range(0, CHUNKS).mapToObj(i -> "target chunk " + i).collect(Collectors.toList());
        OpenSearchRagClient.IndexOutcome outcome = this.client.indexChunks(TENANT, BUCKET, KEY, ETAG, texts, this.vectors, MODEL);
        assertThat(outcome.isComplete()).as(String.valueOf(outcome.getFailureSummary())).isTrue();
        assertThat(this.mapping().path("embeddingModel").path("type").asText()).isEqualTo("keyword");
        assertThat(this.mapping().path("embeddingModel").has("fields")).as("no .keyword sub-field here").isFalse();
        this.writeDecoys();

        this.assertKnnAcceptedAndRanksLikeTheFallback();
    }

    /**
     * An index whose embeddingModel was dynamically mapped (text plus a .keyword sub-field) -- the
     * shape the .keyword alternative in the model clause exists for. It must keep working too.
     */
    @Test
    void theFilteredKnnQueryIsAcceptedOnAnIndexWithADynamicallyMappedModelField() throws Exception {
        Map<String, Object> knnVector = new HashMap<>();
        knnVector.put("type", "knn_vector");
        knnVector.put("dimension", DIMENSIONS);
        Map<String, Object> method = new HashMap<>();
        method.put("name", "hnsw");
        method.put("engine", "lucene");
        method.put("space_type", "cosinesimil");
        knnVector.put("method", method);
        Map<String, Object> properties = new HashMap<>();
        properties.put("embedding", knnVector);
        properties.put("tenantId", typed("long"));
        properties.put("bucket", typed("keyword"));
        properties.put("key", typed("keyword"));
        properties.put("etag", typed("keyword"));
        properties.put("chunkIndex", typed("integer"));
        Map<String, Object> body = new HashMap<>();
        body.put("settings", map("index.knn", true));
        body.put("mappings", map("properties", properties));
        this.http.exchange(OPENSEARCH_URL + "/" + this.index, HttpMethod.PUT, this.jsonEntity(body), String.class);

        StringBuilder bulk = new StringBuilder();
        for (int i = 0; i < CHUNKS; i++) {
            bulk.append(this.bulkLine(TENANT, BUCKET, KEY, ETAG, MODEL, i, "target chunk " + i, this.vectors.get(i)));
        }
        this.bulk(bulk.toString());
        assertThat(this.mapping().path("embeddingModel").path("type").asText()).isEqualTo("text");
        assertThat(this.mapping().path("embeddingModel").path("fields").path("keyword").path("type").asText()).isEqualTo("keyword");
        this.writeDecoys();

        this.assertKnnAcceptedAndRanksLikeTheFallback();
    }

    // ---------------------------------------------------------------------------------------------

    private void assertKnnAcceptedAndRanksLikeTheFallback() throws Exception {
        // The exact body the client sends, straight to the cluster: accepted, not a 400.
        String raw = this.http.postForObject(OPENSEARCH_URL + "/" + this.index + "/_search",
            this.jsonEntity(this.client.knnQuery(BUCKET, KEY, ETAG, this.question, TOP_K)), String.class);
        JsonNode hits = this.json.readTree(raw).path("hits").path("hits");
        assertThat(this.json.readTree(raw).path("_shards").path("failed").asInt()).isZero();

        // The truth, computed here: cosine against the target file's own chunks only.
        List<Integer> byCosine = IntStream.range(0, CHUNKS).boxed()
            .sorted(Comparator.comparingDouble((Integer i) -> OpenSearchRagClient.cosineSimilarity(this.question, this.vectors.get(i))).reversed())
            .collect(Collectors.toList());
        List<Integer> expectedTop = byCosine.subList(0, TOP_K);

        List<Integer> knnOrder = new ArrayList<>();
        for (JsonNode hit : hits) {
            assertThat(hit.path("_source").path("chunkText").asText())
                .as("a decoy -- another tenant, model, version or file -- got past the filter")
                .startsWith("target chunk ");
            int chunkIndex = hit.path("_source").path("chunkIndex").asInt();
            knnOrder.add(chunkIndex);
            // lucene cosinesimil scores (1 + cos) / 2: the same ordering, and the same number, as the fallback's cosine.
            double cosine = OpenSearchRagClient.cosineSimilarity(this.question, this.vectors.get(chunkIndex));
            assertThat(hit.path("_score").asDouble()).isCloseTo((1 + cosine) / 2, within(1e-4));
        }
        assertThat(knnOrder).as("k-NN best-first order must be the fallback's cosine order").isEqualTo(expectedTop);

        // Through the public path: k-NN answers, and the fast path stays on.
        OpenSearchRagClient.RetrievalResult viaKnn = this.client.searchRelevantChunks(BUCKET, KEY, ETAG, this.question, TOP_K);
        assertThat(ReflectionTestUtils.getField(this.client, "knnQuerySupported"))
            .as("a well-formed query against a lucene index must not latch the fallback").isEqualTo(Boolean.TRUE);
        List<String> expectedTexts = expectedTop.stream().sorted().map(i -> "target chunk " + i).collect(Collectors.toList());
        assertThat(viaKnn.failed).isFalse();
        assertThat(viaKnn.chunks).isEqualTo(expectedTexts);
        assertThat(viaKnn.complete).isFalse();

        // And the fallback, on the same data, agrees.
        ReflectionTestUtils.setField(this.client, "knnQuerySupported", false);
        OpenSearchRagClient.RetrievalResult viaFallback = this.client.searchRelevantChunks(BUCKET, KEY, ETAG, this.question, TOP_K);
        assertThat(viaFallback.chunks).isEqualTo(viaKnn.chunks);
    }

    /**
     * Chunks the filter must exclude, each a near-copy of the question so a filter that leaked --
     * or a post-filter instead of a filter -- would put them at the top.
     */
    private void writeDecoys() {
        StringBuilder bulk = new StringBuilder();
        bulk.append(this.bulkLine(OTHER_TENANT, BUCKET, KEY, ETAG, MODEL, 0, "decoy other tenant", this.question));
        bulk.append(this.bulkLine(TENANT, BUCKET, KEY, ETAG, "other-embed-model", 0, "decoy other model", this.question));
        bulk.append(this.bulkLine(TENANT, BUCKET, KEY, "older-etag", MODEL, 0, "decoy other version", this.question));
        bulk.append(this.bulkLine(TENANT, BUCKET, "other/file.csv", ETAG, MODEL, 0, "decoy other file", this.question));
        bulk.append(this.bulkLine(TENANT, "other-bucket", KEY, ETAG, MODEL, 0, "decoy other bucket", this.question));
        this.bulk(bulk.toString());
    }

    private String bulkLine(long tenant, String bucket, String key, String etag, String model, int chunkIndex,
        String text, float[] vector) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("tenantId", tenant);
        doc.put("bucket", bucket);
        doc.put("key", key);
        doc.put("etag", etag);
        doc.put("embeddingModel", model);
        doc.put("chunkIndex", chunkIndex);
        doc.put("chunkText", text);
        List<Float> list = new ArrayList<>();
        for (float f : vector) {
            list.add(f);
        }
        doc.put("embedding", list);
        try {
            return "{\"index\":{\"_index\":\"" + this.index + "\"}}\n" + this.json.writeValueAsString(doc) + "\n";
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void bulk(String ndjson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/x-ndjson;charset=UTF-8"));
        String response = this.http.postForObject(OPENSEARCH_URL + "/_bulk?refresh=true",
            new HttpEntity<>(ndjson, headers), String.class);
        assertThat(response).contains("\"errors\":false");
    }

    private JsonNode mapping() throws Exception {
        String body = this.http.getForObject(OPENSEARCH_URL + "/" + this.index + "/_mapping", String.class);
        return this.json.readTree(body).path(this.index).path("mappings").path("properties");
    }

    private HttpEntity<String> jsonEntity(Object body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(this.json.writeValueAsString(body), headers);
    }

    private static Map<String, Object> typed(String type) {
        return map("type", type);
    }

    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> out = new HashMap<>();
        out.put(key, value);
        return out;
    }

    private static float[] randomVector(Random random) {
        float[] v = new float[DIMENSIONS];
        for (int d = 0; d < DIMENSIONS; d++) {
            v[d] = (float) random.nextGaussian();
        }
        return v;
    }

    /** A unit vector whose cosine with the unit vector {@code q} is {@code cosine}. */
    private static float[] atCosine(float[] q, double cosine, Random random) {
        float[] u = randomVector(random);
        double along = 0;
        for (int d = 0; d < DIMENSIONS; d++) {
            along += u[d] * q[d];
        }
        for (int d = 0; d < DIMENSIONS; d++) {
            u[d] -= (float) (along * q[d]);
        }
        u = unit(u);
        double across = Math.sqrt(1 - cosine * cosine);
        float[] out = new float[DIMENSIONS];
        for (int d = 0; d < DIMENSIONS; d++) {
            out[d] = (float) (cosine * q[d] + across * u[d]);
        }
        return unit(out);
    }

    private static float[] unit(float[] v) {
        double norm = 0;
        for (float f : v) {
            norm += f * f;
        }
        float[] out = new float[v.length];
        for (int d = 0; d < v.length; d++) {
            out[d] = (float) (v[d] / Math.sqrt(norm));
        }
        return out;
    }
}
