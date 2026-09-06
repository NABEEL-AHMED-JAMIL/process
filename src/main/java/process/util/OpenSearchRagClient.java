package process.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The RAG chunk store: one document per chunk of one file, at one version of that file.
 *
 * Mirrors {@link OpenSearchAuditLogClient}'s shape deliberately -- same plain-RestTemplate
 * approach (no OpenSearch Java client dependency for one more index), same {@code isEnabled()}
 * gate so a deployment with no {@code opensearch.url} degrades to "RAG unavailable" rather than
 * failing file chat outright.
 *
 * Retrieval here is always scoped to one file (bucket + key + etag), which is the shape this
 * feature actually needs -- "what does THIS file say" -- not a cross-corpus search. A single
 * file's chunk count is small (tens to a few hundred), so rather than reach for OpenSearch's
 * k-NN filtered-query DSL (version-sensitive, and overkill at this scale), matching chunks are
 * fetched by an exact term filter and ranked by cosine similarity in application code. The
 * vectors are still stored in a real {@code knn_vector} field with a proper k-NN mapping, so
 * the index is ready for a cross-file search built later without a reindex.
 *
 * @author Nabeel Ahmed
 * */
@Component
public class OpenSearchRagClient {

    private static final Logger logger = LoggerFactory.getLogger(OpenSearchRagClient.class);
    private static final String INDEX_NAME = "file-rag-chunks";

    /** A single file is never expected to produce more chunks than this; see class javadoc. */
    private static final int MAX_CHUNKS_PER_FILE = 2000;

    @Value("${opensearch.url:}")
    private String baseUrl;

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final RestTemplate restTemplate = buildRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile boolean indexEnsured = false;

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    public boolean isEnabled() {
        return this.baseUrl != null && !this.baseUrl.trim().isEmpty();
    }

    /**
     * Creates the index with its k-NN mapping if it does not already exist. Called lazily on
     * first real use rather than at startup, so a deployment that never opens a RAG-eligible
     * file never pays for it, and so this class does not need to be wired into application
     * startup at all -- one fewer thing that can fail a boot for an optional feature.
     *
     * {@code index.knn} has to be set at index-creation time; it cannot be added after. A PUT
     * against an index that already exists is a 400 from OpenSearch either way, which is
     * indistinguishable here from "already ensured" -- both leave the index usable, so this
     * treats any outcome of the creation call as "the index exists now" rather than retrying.
     */
    private void ensureIndex(int dimensions) {
        if (this.indexEnsured || !this.isEnabled()) {
            return;
        }
        synchronized (this) {
            if (this.indexEnsured) {
                return;
            }
            if (this.indexExists()) {
                this.indexEnsured = true;
                return;
            }
            try {
                Map<String, Object> settings = new HashMap<>();
                settings.put("index.knn", true);

                Map<String, Object> embeddingField = new HashMap<>();
                embeddingField.put("type", "knn_vector");
                embeddingField.put("dimension", dimensions);

                Map<String, Object> properties = new HashMap<>();
                properties.put("tenantId", typeField("long"));
                properties.put("bucket", typeField("keyword"));
                properties.put("key", typeField("keyword"));
                properties.put("etag", typeField("keyword"));
                properties.put("chunkIndex", typeField("integer"));
                properties.put("chunkText", typeField("text"));
                properties.put("dateCreated", typeField("date"));
                properties.put("embedding", embeddingField);
                // Which model produced this vector -- a document written before this field
                // existed simply omits it, which is itself informative (pre-dates the field, so
                // predates any deliberate multi-model tracking; treat as "unknown, reindex to be
                // sure" rather than assuming it matches the current model).
                properties.put("embeddingModel", typeField("keyword"));

                Map<String, Object> body = new HashMap<>();
                body.put("settings", settings);
                body.put("mappings", Collections.singletonMap("properties", properties));

                this.restTemplate.exchange(this.baseUrl + "/" + INDEX_NAME, HttpMethod.PUT,
                    this.jsonEntity(body), String.class);
                logger.info("Created the {} index (knn_vector dimension={}).", INDEX_NAME, dimensions);
            } catch (Exception ex) {
                logger.warn("Could not create the {} index (it may already exist): {}", INDEX_NAME, ex.getMessage());
            }
            this.indexEnsured = true;
        }
    }

    private boolean indexExists() {
        try {
            return this.restTemplate.getForEntity(this.baseUrl + "/" + INDEX_NAME, String.class)
                .getStatusCode().is2xxSuccessful();
        } catch (HttpClientErrorException.NotFound notFound) {
            return false;
        } catch (Exception ex) {
            logger.warn("Could not check for the {} index; will attempt to create it: {}", INDEX_NAME, ex.getMessage());
            return false;
        }
    }

    private static Map<String, Object> typeField(String type) {
        return Collections.singletonMap("type", type);
    }

    /**
     * Whether this exact version of this file has already been chunked and embedded.
     *
     * The etag is the whole mechanism: a different upload of the same key is a different etag,
     * so this correctly says "no" for it and the caller re-indexes, while a repeat question
     * against an unchanged file gets "yes" and skips straight to retrieval -- no re-extraction,
     * no re-chunking, no re-embedding.
     */
    public boolean isIndexed(String bucket, String key, String etag) {
        if (!this.isEnabled()) {
            return false;
        }
        try {
            Map<String, Object> query = new HashMap<>();
            query.put("size", 0);
            query.put("query", this.termsFilter(bucket, key, etag));
            String response = this.restTemplate.postForObject(
                this.baseUrl + "/" + INDEX_NAME + "/_search", this.jsonEntity(query), String.class);
            JsonNode root = this.objectMapper.readTree(response);
            return root.path("hits").path("total").path("value").asLong(0) > 0;
        } catch (HttpClientErrorException.NotFound notFound) {
            // The index has never been created, so nothing is indexed -- not an error.
            return false;
        } catch (Exception ex) {
            // A RAG lookup that cannot be answered must not read as "yes, skip indexing" -- that
            // would leave the caller retrieving from chunks that do not exist. False is the safe
            // default: worst case is a redundant re-index, never a silent empty answer.
            logger.warn("Could not check whether {}/{} (etag {}) is indexed; assuming it is not: {}",
                bucket, key, etag, ex.getMessage());
            return false;
        }
    }

    /**
     * Replaces this file's chunks: deletes every previous version's chunks for this bucket/key
     * (any etag), then writes the new ones. Deleting by key alone rather than by key+etag is
     * deliberate -- only the current version is ever queried, so an old version's chunks are
     * pure dead weight once a new one lands, not a history worth keeping.
     */
    public void indexChunks(Long tenantId, String bucket, String key, String etag,
        List<String> chunkTexts, List<float[]> embeddings, String embeddingModel) {
        if (!this.isEnabled() || chunkTexts.isEmpty()) {
            return;
        }
        if (chunkTexts.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunkTexts and embeddings must be the same length.");
        }
        this.ensureIndex(embeddings.get(0).length);

        int count = Math.min(chunkTexts.size(), MAX_CHUNKS_PER_FILE);
        if (chunkTexts.size() > MAX_CHUNKS_PER_FILE) {
            logger.warn("{}/{} produced {} chunks; keeping the first {} rather than all of them.",
                bucket, key, chunkTexts.size(), MAX_CHUNKS_PER_FILE);
        }

        this.deleteChunksForFile(bucket, key);

        String dateCreated = Instant.now().toString();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < count; i++) {
            body.append("{\"index\":{\"_index\":\"").append(INDEX_NAME)
                .append("\",\"_id\":\"").append(chunkId(bucket, key, etag, i)).append("\"}}\n");
            Map<String, Object> doc = new HashMap<>();
            doc.put("tenantId", tenantId);
            doc.put("bucket", bucket);
            doc.put("key", key);
            doc.put("etag", etag);
            doc.put("chunkIndex", i);
            doc.put("chunkText", chunkTexts.get(i));
            doc.put("embedding", toList(embeddings.get(i)));
            doc.put("embeddingModel", embeddingModel);
            doc.put("dateCreated", dateCreated);
            body.append(this.writeJson(doc)).append("\n");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/x-ndjson"));
            ResponseEntity<String> response = this.restTemplate.exchange(
                this.baseUrl + "/_bulk?refresh=true", HttpMethod.POST,
                new HttpEntity<>(body.toString(), headers), String.class);
            this.logBulkErrors(response.getBody(), bucket, key, count);
        } catch (Exception ex) {
            logger.error("Failed to index {} chunks for {}/{}", count, bucket, key, ex);
        }
    }

    /**
     * The relevant chunks for one question against one file, best match first, plus whether that
     * was every chunk the file has.
     *
     * RAG now runs for every file the embedding model and OpenSearch can reach, not only ones
     * too large to send whole -- a short file often chunks into one or two pieces, and {@code
     * topK} then returns all of them. The caller needs to know that happened: "these are excerpts,
     * there may be more" is true of a genuine partial retrieval and false, misleadingly, of a
     * complete one.
     */
    public static final class RetrievalResult {
        public final List<String> chunks;
        /** True when nothing was left out by topK -- every chunk the file has came back. */
        public final boolean complete;

        public RetrievalResult(List<String> chunks, boolean complete) {
            this.chunks = chunks;
            this.complete = complete;
        }
    }

    /**
     * Cosine similarity in application code, not an OpenSearch k-NN query -- see class javadoc
     * for why that is the right call at this scale. {@code topK} is a ceiling, not a guarantee;
     * a short file may have fewer chunks than that, which is also how the caller learns a
     * retrieval was complete rather than partial.
     */
    public RetrievalResult searchRelevantChunks(String bucket, String key, String etag,
        float[] queryEmbedding, int topK) {
        if (!this.isEnabled()) {
            return new RetrievalResult(Collections.emptyList(), true);
        }
        try {
            Map<String, Object> query = new HashMap<>();
            query.put("size", MAX_CHUNKS_PER_FILE);
            query.put("query", this.termsFilter(bucket, key, etag));
            query.put("_source", Arrays.asList("chunkIndex", "chunkText", "embedding"));
            String response = this.restTemplate.postForObject(
                this.baseUrl + "/" + INDEX_NAME + "/_search", this.jsonEntity(query), String.class);
            JsonNode hits = this.objectMapper.readTree(response).path("hits").path("hits");

            List<ScoredChunk> scored = new ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                String text = source.path("chunkText").asText("");
                if (text.isEmpty()) {
                    continue;
                }
                float[] vector = toFloatArray(source.path("embedding"));
                scored.add(new ScoredChunk(source.path("chunkIndex").asInt(0), text,
                    cosineSimilarity(queryEmbedding, vector)));
            }
            // Best matches first to select the top K, then re-sorted into document order -- a
            // "what does section 3 say, and how does it relate to section 5" question reads far
            // better when its top matches come back in the order they appear in the source
            // document than sorted purely by relevance.
            List<String> top = scored.stream()
                .sorted(Comparator.comparingDouble((ScoredChunk c) -> c.score).reversed())
                .limit(Math.max(topK, 0))
                .sorted(Comparator.comparingInt(c -> c.chunkIndex))
                .map(c -> c.text)
                .collect(Collectors.toList());
            return new RetrievalResult(top, scored.size() <= topK);
        } catch (HttpClientErrorException.NotFound notFound) {
            return new RetrievalResult(Collections.emptyList(), true);
        } catch (Exception ex) {
            logger.warn("RAG retrieval failed for {}/{}, returning no chunks: {}", bucket, key, ex.getMessage());
            return new RetrievalResult(Collections.emptyList(), true);
        }
    }

    /** Called before indexing a new version of a file; see indexChunks. */
    private void deleteChunksForFile(String bucket, String key) {
        try {
            Map<String, Object> bool = new HashMap<>();
            bool.put("must", Arrays.asList(termQuery("bucket", bucket), termQuery("key", key)));
            Map<String, Object> body = new HashMap<>();
            body.put("query", Collections.singletonMap("bool", bool));
            this.restTemplate.postForObject(
                this.baseUrl + "/" + INDEX_NAME + "/_delete_by_query?conflicts=proceed",
                this.jsonEntity(body), String.class);
        } catch (HttpClientErrorException.NotFound notFound) {
            // Nothing to delete -- the index does not exist yet.
        } catch (Exception ex) {
            logger.warn("Could not clear previous chunks for {}/{} before reindexing: {}", bucket, key, ex.getMessage());
        }
    }

    private Map<String, Object> termsFilter(String bucket, String key, String etag) {
        Map<String, Object> bool = new HashMap<>();
        bool.put("must", Arrays.asList(termQuery("bucket", bucket), termQuery("key", key), termQuery("etag", etag)));
        return Collections.singletonMap("bool", bool);
    }

    private static Map<String, Object> termQuery(String field, String value) {
        return Collections.singletonMap("term", Collections.singletonMap(field, value));
    }

    /**
     * OpenSearch doc IDs tolerate slashes and unicode in practice, but hashing sidesteps every
     * edge case a real object key can carry, and keeps re-indexing the same chunk of the same
     * file version idempotent (same input, same _id, an overwrite not a duplicate) rather than
     * accumulating extra documents on a retry.
     */
    private static String chunkId(String bucket, String key, String etag, int chunkIndex) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest((bucket + "|" + key + "|" + etag + "|" + chunkIndex)
                .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            // SHA-256 is guaranteed present on any JVM this project runs on; this branch exists
            // only so the compiler does not require a throws clause here.
            throw new IllegalStateException(ex);
        }
    }

    private static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return -1f;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return -1f;
        }
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    private static List<Float> toList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float f : vector) {
            list.add(f);
        }
        return list;
    }

    private static float[] toFloatArray(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return new float[0];
        }
        float[] result = new float[arrayNode.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = (float) arrayNode.get(i).asDouble();
        }
        return result;
    }

    private HttpEntity<String> jsonEntity(Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return new HttpEntity<>(this.objectMapper.writeValueAsString(body), headers);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not serialise a RAG request body.", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return this.objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not serialise a RAG chunk document.", ex);
        }
    }

    private void logBulkErrors(String responseBody, String bucket, String key, int count) {
        if (responseBody == null) {
            logger.warn("OpenSearch returned no body indexing {} chunks for {}/{}", count, bucket, key);
            return;
        }
        try {
            JsonNode root = this.objectMapper.readTree(responseBody);
            if (root.path("errors").asBoolean(false)) {
                logger.warn("OpenSearch rejected some chunks while indexing {}/{} ({} attempted)", bucket, key, count);
            }
        } catch (Exception ignored) {
            // The bulk write itself already succeeded at the HTTP level if execution reached
            // here; a response body this class cannot parse is not worth failing the caller over.
        }
    }

    private static final class ScoredChunk {
        final int chunkIndex;
        final String text;
        final double score;

        ScoredChunk(int chunkIndex, String text, double score) {
            this.chunkIndex = chunkIndex;
            this.text = text;
            this.score = score;
        }
    }
}
