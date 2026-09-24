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
import process.security.TenantContext;

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
 * feature actually needs -- "what does THIS file say" -- not a cross-corpus search.
 *
 * <h3>Two retrieval paths, and when each one runs</h3>
 *
 * <ul>
 * <li><b>Document order.</b> When the file has no more chunks than the caller asked for, ranking
 *     provably cannot change the answer, so {@link #fetchInDocumentOrder} pulls the chunks
 *     text-only and hands them back in order. Cheap, and it covers the majority of what users
 *     actually open.</li>
 * <li><b>k-NN.</b> Otherwise {@link #knnQuery} asks OpenSearch to do the search it has been
 *     building an HNSW graph for all along: a {@code knn} query on {@code embedding} carrying the
 *     same bucket/key/etag/embeddingModel clauses as its {@code filter}, {@code k} = topK, and a
 *     {@code _source} that deliberately EXCLUDES {@code embedding}.</li>
 * </ul>
 *
 * The k-NN path is new, and what it replaced is worth writing down, because the mapping made it
 * look like it had been there all along. Every query this class sent used to be a plain
 * {@code term} filter: retrieval matched a file's chunks on bucket/key/etag/embeddingModel, asked
 * for {@code size} 2000, pulled every matching chunk's full 768-float vector back over HTTP and
 * ranked them with the hand-written {@link #cosineSimilarity} loop at the bottom of this file. So
 * the {@code knn_vector} mapping and the {@code index.knn} setting {@link #ensureIndex} creates
 * were written and never queried -- the graph cost indexing time and heap and contributed nothing
 * to any answer this feature produced. Measured on the live cluster before this changed: one
 * question about a 20-chunk PDF came back as a 229,285-byte response in which every hit carried
 * the identical constant {@code _score}, and a 73-chunk CSV was worse. Filtered k-NN has been
 * supported natively since 2.4 for the Lucene engine (2.9 for faiss) and this deploys against
 * 2.10, so nothing was blocking it but this class.
 *
 * <h3>The Java cosine path is still here, and is still reachable</h3>
 *
 * {@link #rankChunks} and {@link #cosineSimilarity} are kept as the fallback for a cluster that
 * refuses the {@code knn} query -- an OpenSearch older than 2.4, {@code index.knn} disabled, or an
 * index whose {@code embedding} field is not a {@code knn_vector} at all because a {@code _bulk}
 * against a wiped cluster auto-created it. The first rejection latches {@link #knnQuerySupported}
 * false, logs one line, and every retrieval for the rest of this process's life takes the old
 * path: correct, just expensive.
 *
 * A timeout or a 5xx is deliberately NOT treated as a rejection. That is an outage, it has to
 * surface as {@link RetrievalResult#failed} (see that field for what mislabelling it cost), and
 * turning the fast path off for the life of the process because the cluster hiccuped once is not a
 * trade worth making.
 *
 * <h3>The index an existing deployment is already sitting on</h3>
 *
 * {@link #createIndex} now pins the {@code embedding} field's method explicitly --
 * {@code hnsw}/{@code lucene}/{@code cosinesimil} -- and that is a creation-time change only, so
 * it decides nothing about an index that already exists. The old mapping omitted {@code method}
 * entirely, which left two things wrong with it that could not be seen while nothing queried the
 * graph:
 *
 * <ul>
 * <li>The graph was built for the default space, {@code l2}, and not the cosine this class ranks
 *     by everywhere else. Survivable, and only because Ollama's {@code /api/embed} returns
 *     normalised vectors: for unit-length vectors the two metrics induce the SAME ordering, since
 *     {@code |a-b|^2 == 2 - 2*cos(a,b)}. A model whose vectors are not unit length would rank
 *     partly by magnitude instead of purely by direction.</li>
 * <li>More to the point here, the default engine on the versions this deploys against does not
 *     support applying a {@code filter} during the graph walk at all -- that is a Lucene-engine
 *     (2.4+) and faiss (2.9+) capability. A filtered {@code knn} query against such an index is
 *     rejected, which means an already-deployed index keeps answering through the application-code
 *     fallback below until it is rebuilt.</li>
 * </ul>
 *
 * Neither is repairable in place: {@code index.knn}, a {@code knn_vector}'s {@code dimension} and
 * its space type are all fixed when the mapping is created. Rebuilding means standing up a new
 * index and rewriting every chunk into it -- which this project's clean-slate procedure does to
 * OpenSearch anyway, so the new mapping arrives on its own rather than needing a migration of its
 * own. Nothing regresses in the meantime; the slow path is the path that was already being taken.
 *
 * An earlier version of this comment claimed the index was "ready for a cross-file search built
 * later without a reindex". That was wrong, and it was load-bearing for the wrong decision: a
 * cross-file k-NN search needs its own mapping decisions and exactly the reindex the sentence
 * promised to have avoided. It has been removed rather than softened.
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

    /**
     * The embedding model this deployment is configured to use right now, read from the very same
     * {@code embedding.model} property EmbeddingServiceImpl reads, with the same default.
     * Retrieval filters on it -- see {@link #termsFilter}.
     *
     * Read from configuration rather than injected from EmbeddingService on purpose: nothing else
     * in {@code process.util} depends on {@code process.model.service}, and opening that direction
     * for one string is not worth it. The hazard that choice buys is drift. If this default and
     * EmbeddingServiceImpl's default ever disagree, retrieval filters on a model name that was
     * never written to a single chunk, every file reads as "not indexed", and FileChatServiceImpl
     * re-chunks and re-embeds the whole file on every message forever. They must be changed
     * together.
     */
    @Value("${embedding.model:nomic-embed-text}")
    private String configuredEmbeddingModel;

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 10000;

    // Not final only so a unit test can substitute a stubbed RestTemplate; nothing in production
    // reassigns it. Without a seam here every assertion about the query bodies this class builds
    // needs a live OpenSearch, which is how the retrieval query went years without one.
    private RestTemplate restTemplate = buildRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Whether this process has already satisfied itself that the index exists with the right
     * mapping.
     *
     * This used to latch true and stay true for the life of the JVM, which was a real bug rather
     * than an optimisation. This project's clean-slate procedure wipes OpenSearch as one of five
     * stores, and OpenSearch runs in a different compose project from the backend, so the index
     * is routinely deleted underneath a running process. Once latched, {@link #ensureIndex}
     * returned at its first line forever, the next {@code _bulk} hit an index that no longer
     * existed, and OpenSearch auto-created {@code file-rag-chunks} from the bulk payload with a
     * DYNAMIC mapping: no {@code knn_vector}, no {@code index.knn}, and -- the part that actually
     * broke the product -- {@code bucket}/{@code key}/{@code etag} as analyzed {@code text}
     * instead of {@code keyword}. Every {@code term} query in this class addresses those fields
     * directly, and the standard analyzer splits this deployment's real bucket names
     * ({@code etl-bucket}, {@code etl-avatar}) on the hyphen, so the filters could never match
     * again: retrieval returned empty forever, every message re-embedded the entire file, and
     * {@code deleteChunksForFile} stopped clearing anything.
     *
     * So the latch is kept -- it is still worth not issuing a GET before every write -- but it is
     * now cleared by {@link #noteIndexMissing} the instant any call observes a missing index.
     */
    private volatile boolean indexEnsured = false;

    /**
     * Whether this cluster will accept the {@code knn} retrieval query, until it proves otherwise.
     *
     * Starts optimistic and only ever goes false, once, for the life of the process. Two reasons
     * it is a latch rather than a per-call attempt: a cluster that cannot do filtered k-NN cannot
     * start doing it without a restart or a reindex (it is an OpenSearch version, an
     * {@code index.knn} setting or a mapping, none of which change under a running process), and
     * paying a rejected round trip on every single question to re-learn that would hand back a
     * good part of what querying the graph was supposed to save.
     *
     * Only a rejection flips it -- a 4xx, or a 200 whose {@code _shards} block says every shard
     * failed. A timeout or a 5xx is an outage and propagates, because a cluster that is merely
     * down would otherwise permanently disable the fast path on its way past.
     */
    private volatile boolean knnQuerySupported = true;

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
     *
     * What changed: "the index exists" is no longer accepted as sufficient. An index can exist and
     * still be unusable -- auto-created with a dynamic mapping after a wipe, or built for a
     * different vector dimension than the one now being written -- and both of those used to be
     * taken as success. The existing mapping is now compared against what is about to be written
     * and any disagreement is logged at ERROR, because neither condition can be repaired in place
     * and an operator who does not hear about it will only find out via wrong answers.
     */
    private void ensureIndex(int dimensions) {
        if (this.indexEnsured || !this.isEnabled()) {
            return;
        }
        synchronized (this) {
            if (this.indexEnsured) {
                return;
            }
            JsonNode existing = this.fetchIndexMetadata();
            if (existing != null) {
                String complaint = mappingComplaint(existing, dimensions);
                if (complaint != null) {
                    logger.error("The {} index already exists but {}. Chunks written now may be rejected "
                        + "outright and retrieval against them is not trustworthy. Neither index.knn nor a "
                        + "knn_vector dimension can be changed in place, so fixing this means reindexing {} "
                        + "from scratch. (A legacy embeddingModel mapping is NOT one of these: termsFilter "
                        + "matches its .keyword sub-field, so that shape retrieves correctly and is not "
                        + "reported here.)", INDEX_NAME, complaint, INDEX_NAME);
                }
                this.indexEnsured = true;
                return;
            }
            try {
                this.createIndex(dimensions, true);
                logger.info("Created the {} index (knn_vector dimension={}, hnsw/lucene/cosinesimil).",
                    INDEX_NAME, dimensions);
            } catch (Exception ex) {
                // Never let an opinion about the method block cost this deployment its index. If a
                // cluster will not take hnsw/lucene/cosinesimil -- too old for the Lucene engine, a
                // dimension above what it allows, a build without it -- the index is created the way
                // it always was instead. Retrieval then falls back to ranking in application code
                // (see rankByVector), which is slow and correct; having no index at all is neither,
                // because the next _bulk auto-creates a dynamic mapping in its place and that
                // breaks every term query in this class permanently.
                logger.warn("Could not create the {} index with an explicit hnsw/lucene/cosinesimil method "
                    + "({}); retrying with this cluster's default method.", INDEX_NAME, ex.getMessage());
                try {
                    this.createIndex(dimensions, false);
                    logger.info("Created the {} index (knn_vector dimension={}, default method). Retrieval on "
                        + "this index may have to rank in application code -- a filtered knn query needs an "
                        + "engine that supports filters.", INDEX_NAME, dimensions);
                } catch (Exception secondAttempt) {
                    logger.warn("Could not create the {} index (it may already exist): {}",
                        INDEX_NAME, secondAttempt.getMessage());
                }
            }
            this.indexEnsured = true;
        }
    }

    /**
     * PUTs the index mapping. {@code withMethod} decides whether the {@code embedding} field pins
     * its k-NN method explicitly, and that flag exists because getting it wrong costs different
     * things in each direction.
     *
     * With it, the field is built as {@code hnsw}/{@code lucene}/{@code cosinesimil}, which buys
     * two things this class actually needs. The first is the metric: without a {@code method} the
     * graph is built for the default space, which is {@code l2}, while every comment in this class
     * -- and {@link #cosineSimilarity}, the fallback that has to agree with it -- talks about
     * cosine. The second matters more. A {@code knn} query carrying a {@code filter} is only
     * served by engines that support filtering during the graph walk (Lucene from 2.4, faiss from
     * 2.9); the default engine on the versions this deploys against does not, and rejects the
     * filtered query outright. An index created without this block is therefore a working index
     * that {@link #rankByVector} can only ever use through its slow fallback.
     *
     * Neither of those can be changed on an index that already exists: {@code index.knn}, the
     * {@code dimension} and the space type are all fixed at creation. So this improves every index
     * created from here on and does nothing for one already in the cluster, which will keep
     * answering through the fallback until it is rebuilt -- this project's clean-slate procedure
     * wipes OpenSearch, so that happens on its own soon enough, and until then nothing regresses.
     */
    private void createIndex(int dimensions, boolean withMethod) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("index.knn", true);

        Map<String, Object> embeddingField = new HashMap<>();
        embeddingField.put("type", "knn_vector");
        embeddingField.put("dimension", dimensions);
        if (withMethod) {
            Map<String, Object> method = new HashMap<>();
            method.put("name", "hnsw");
            method.put("engine", "lucene");
            method.put("space_type", "cosinesimil");
            embeddingField.put("method", method);
        }

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
    }

    /**
     * The live index's own metadata, or null when it does not exist or could not be read.
     *
     * This replaced a bare "did the GET return 2xx" existence check. The GET already returns the
     * full mappings and settings; throwing that away and keeping only the status code is what let
     * a dimension mismatch and a post-wipe dynamic mapping both read as "fine, carry on".
     */
    private JsonNode fetchIndexMetadata() {
        try {
            ResponseEntity<String> response = this.restTemplate.getForEntity(
                this.baseUrl + "/" + INDEX_NAME, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return null;
            }
            // OpenSearch keys the response by index name; tolerate a body that is not wrapped
            // that way rather than reporting a perfectly good index as broken.
            JsonNode root = this.objectMapper.readTree(response.getBody());
            JsonNode index = root.path(INDEX_NAME);
            return index.isMissingNode() ? root : index;
        } catch (HttpClientErrorException.NotFound notFound) {
            return null;
        } catch (Exception ex) {
            logger.warn("Could not check for the {} index; will attempt to create it: {}", INDEX_NAME, ex.getMessage());
            return null;
        }
    }

    /**
     * What is wrong with a live {@code file-rag-chunks} index, phrased to drop straight into a log
     * line, or null when there is nothing wrong with it.
     *
     * Deliberately narrow. It reports only the shapes that are known to happen here and that no
     * amount of retrying can fix: an index auto-created by a {@code _bulk} against a wiped cluster
     * (no {@code knn_vector} at all), an index built for a different vector dimension than the
     * model now in use, an index whose {@code embeddingModel} field is not a {@code keyword} and
     * therefore cannot be term-matched, and {@code index.knn} being present and explicitly false.
     * It stays silent about anything merely absent from the settings, because a false alarm on
     * this path trains people to ignore a log line that only ever fires for something real.
     */
    static String mappingComplaint(JsonNode indexMetadata, int expectedDimensions) {
        JsonNode embedding = indexMetadata.path("mappings").path("properties").path("embedding");
        String mappedType = embedding.path("type").asText("");
        if (!"knn_vector".equals(mappedType)) {
            return "its embedding field is mapped as '" + (mappedType.isEmpty() ? "(absent)" : mappedType)
                + "' rather than knn_vector, so it carries no vector index at all -- the signature of an "
                + "index auto-created by a _bulk write against a cluster whose index had been wiped";
        }
        int mappedDimension = embedding.path("dimension").asInt(0);
        if (mappedDimension != expectedDimensions) {
            return "its knn_vector dimension is " + mappedDimension + " while this deployment now embeds in "
                + expectedDimensions + " dimensions, so OpenSearch will reject every chunk written from here";
        }
        JsonNode modelField = indexMetadata.path("mappings").path("properties").path("embeddingModel");
        String modelType = modelField.path("type").asText("");
        // A dynamically mapped string is analyzed text WITH a .keyword sub-field, and termsFilter
        // matches either path -- so that shape retrieves correctly and is not a complaint. Only a
        // text field with no keyword sub-field is genuinely unreachable, and this stays silent
        // about anything it can actually work with: a warning that fires on a working index is how
        // people learn to scroll past this line.
        boolean hasKeywordSubField = "keyword".equals(modelField.path("fields").path("keyword").path("type").asText(""));
        if (!"keyword".equals(modelType) && !hasKeywordSubField) {
            return "its embeddingModel field is mapped as '" + (modelType.isEmpty() ? "(absent)" : modelType)
                + "' with no keyword sub-field, so neither term clause retrieval scopes by can match it -- "
                + "and the standard analyzer splits a model name like nomic-embed-text on its hyphens. Left "
                + "alone, every file will read as un-indexed and be re-chunked and re-embedded on every "
                + "single message";
        }
        JsonNode indexSettings = indexMetadata.path("settings").path("index");
        if (indexSettings.has("knn") && !indexSettings.path("knn").asBoolean(false)) {
            return "index.knn is explicitly disabled on it, so no HNSW graph is being built for the vectors";
        }
        return null;
    }

    private static Map<String, Object> typeField(String type) {
        return Collections.singletonMap("type", type);
    }

    /**
     * Whether this exact version of this file is definitely indexed -- see {@link #indexStateOf}
     * for what that means and for the third answer this boolean cannot carry.
     *
     * A RAG lookup that cannot be answered must not read as "yes, skip indexing" -- that would
     * leave the caller retrieving from chunks that do not exist. False is the safe default: worst
     * case is a redundant re-index, never a silent empty answer. That is why UNKNOWN collapses to
     * false here, and why a caller whose decision differs between UNKNOWN and NOT_INDEXED has to
     * ask {@link #indexStateOf} instead.
     */
    public boolean isIndexed(String bucket, String key, String etag) {
        return this.indexStateOf(bucket, key, etag) == IndexState.INDEXED;
    }

    /**
     * How many chunks are indexed for this exact file version under the current embedding model,
     * or -1 when OpenSearch could not answer.
     *
     * A {@code size: 0} search: OpenSearch returns the total and no documents, so this costs a
     * round trip and essentially no payload. That is what makes it worth issuing before the real
     * retrieval -- see {@link #searchRelevantChunks}.
     */
    private int countChunks(String bucket, String key, String etag) {
        if (TenantContext.getTenantId() == null) {
            // Not "none": nothing was asked, so nothing is known. UNKNOWN keeps the caller from
            // re-indexing a file under no tenant on the strength of a count it never ran.
            logger.warn("RAG chunk count for {}/{} with no resolvable tenant; not querying, reporting unknown.", bucket, key);
            return -1;
        }
        try {
            Map<String, Object> query = new HashMap<>();
            query.put("size", 0);
            // Explicit rather than relying on the 10,000-hit default accuracy ceiling. A file is
            // capped at MAX_CHUNKS_PER_FILE so the default would in fact be exact, but a count
            // that silently becomes approximate if that cap ever rises is a trap worth closing now.
            query.put("track_total_hits", true);
            query.put("query", this.termsFilter(bucket, key, etag));
            String response = this.restTemplate.postForObject(
                this.baseUrl + "/" + INDEX_NAME + "/_search", this.jsonEntity(query), String.class);
            JsonNode root = this.objectMapper.readTree(response);
            return (int) root.path("hits").path("total").path("value").asLong(0);
        } catch (HttpClientErrorException.NotFound notFound) {
            // The index has never been created, or was wiped out from under this process -- not an
            // error, but the latch must not go on claiming the index is there.
            this.noteIndexMissing("a chunk count");
            return 0;
        } catch (Exception ex) {
            logger.warn("Could not count indexed chunks for {}/{} (etag {}); assuming none: {}",
                bucket, key, etag, ex.getMessage());
            return -1;
        }
    }

    /**
     * Replaces this file's chunks: deletes every previous version's chunks for this bucket/key
     * (any etag, any embedding model), then writes the new ones. Deleting by key alone rather than
     * by key+etag is deliberate -- only the current version is ever queried, so an old version's
     * chunks are pure dead weight once a new one lands, not a history worth keeping. Leaving the
     * embedding model out of that delete is equally deliberate and is what makes a model swap
     * self-healing: the chunks the retrieval filter has stopped matching are the ones this clears.
     *
     * The delete now runs BEFORE {@link #ensureIndex} rather than after it. Order matters after a
     * wipe: {@code _delete_by_query} against a missing index is a clean 404, which clears the
     * ensured latch, which lets {@code ensureIndex} rebuild the proper k-NN mapping before the
     * {@code _bulk} below would otherwise have auto-created a dynamic one in its place. Against an
     * index that does exist the two orders are indistinguishable -- ensureIndex is a no-op and the
     * delete does the same work either way.
     */
    public IndexOutcome indexChunks(Long tenantId, String bucket, String key, String etag,
        List<String> chunkTexts, List<float[]> embeddings, String embeddingModel) {
        if (!this.isEnabled() || chunkTexts.isEmpty()) {
            return new IndexOutcome(0, 0, null);
        }
        if (chunkTexts.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunkTexts and embeddings must be the same length.");
        }
        // The delete below is scoped to the caller's tenant, and the chunks are written under the
        // tenant handed in: a write with no caller tenant, or for a tenant other than the caller's,
        // would delete or write somebody else's chunks. Refused, loudly, before anything is sent.
        Long callerTenant = TenantContext.getTenantId();
        if (callerTenant == null || !callerTenant.equals(tenantId)) {
            logger.error("Refusing to index {}/{}: chunks for tenant {} from a caller whose tenant is {} (no resolvable tenant "
                + "writes nothing, and one tenant never writes another's).", bucket, key, tenantId, callerTenant);
            return new IndexOutcome(chunkTexts.size(), 0, "no resolvable tenant, or not the caller's tenant");
        }

        this.deleteChunksForFile(bucket, key);
        this.ensureIndex(embeddings.get(0).length);

        int count = Math.min(chunkTexts.size(), MAX_CHUNKS_PER_FILE);
        if (chunkTexts.size() > MAX_CHUNKS_PER_FILE) {
            logger.warn("{}/{} produced {} chunks; keeping the first {} rather than all of them.",
                bucket, key, chunkTexts.size(), MAX_CHUNKS_PER_FILE);
        }

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
            /*
             * The charset is NOT decoration, and leaving it off silently destroyed real text.
             *
             * Spring's StringHttpMessageConverter picks the encoding in getContentTypeCharset:
             * the content type's own charset if it has one, else UTF-8 only if the type
             * isCompatibleWith(application/json), else its DEFAULT_CHARSET -- which is
             * ISO-8859-1. "application/x-ndjson" is not compatible with application/json, so
             * every bulk body this class has ever sent went out as Latin-1. Two different
             * losses followed, and both are visible in the live index:
             *
             *  - A character above U+00FF has no Latin-1 byte and was encoded as '?'. Every
             *    curly quote, em-dash and currency symbol in every indexed file became a
             *    question mark: 108 of them across 225 chunks.
             *  - A character in U+0080..U+00FF encoded to a single byte 0x80..0xFF, which is
             *    not valid UTF-8. OpenSearch rejected that whole document, the rejection was
             *    swallowed below, and the chunk vanished mid-file. That is why
             *    "I-94_I-95 Official Website - Get Most Recent Response.pdf" holds chunkIndex
             *    0 and 2 and no 1: the middle chunk carried a U+00A7 SECTION SIGN.
             *
             * The tell that it was never anything else: across 225 chunks of CVs, PDFs, CSVs
             * and Markdown, not one character above U+007F survived.
             */
            headers.setContentType(new MediaType("application", "x-ndjson", StandardCharsets.UTF_8));
            ResponseEntity<String> response = this.restTemplate.exchange(
                this.baseUrl + "/_bulk?refresh=true", HttpMethod.POST,
                new HttpEntity<>(body.toString(), headers), String.class);
            return this.reportBulkOutcome(response.getBody(), bucket, key, count);
        } catch (Exception ex) {
            logger.error("Failed to index {} chunks for {}/{}", count, bucket, key, ex);
            return new IndexOutcome(count, 0, "the bulk request itself failed: " + rootMessage(ex));
        }
    }

    /** What a bulk write actually managed to store, so a partial loss cannot pass for a success. */
    public static final class IndexOutcome {
        private final int attempted;
        private final int stored;
        private final String failureSummary;

        IndexOutcome(int attempted, int stored, String failureSummary) {
            this.attempted = attempted;
            this.stored = stored;
            this.failureSummary = failureSummary;
        }

        public int getAttempted() { return this.attempted; }
        public int getStored() { return this.stored; }
        /** Null when everything was stored. */
        public String getFailureSummary() { return this.failureSummary; }
        public boolean isComplete() { return this.stored == this.attempted; }
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
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
        /**
         * True when OpenSearch could not answer the question at all, as opposed to answering
         * "this file has no chunks".
         *
         * The two used to be the same value, and that cost a full re-embed per message during any
         * outage. A cluster timeout, a 5xx or an unparseable body all came back as an empty list,
         * and FileChatServiceImpl.resolveContext reads an empty list as "nothing is indexed for
         * this file version yet" -- so it took the index lock, re-extracted the file (re-running
         * an audio transcription or a LibreOffice conversion), re-chunked it, re-embedded every
         * chunk, wrote them back into the cluster that was already failing, searched again, failed
         * again, and finally answered from raw truncated text. Every message, for as long as the
         * outage lasted, with nothing in the answer or the panel to say retrieval had stopped
         * working. An empty list now means what it says; this flag carries the failure.
         */
        public final boolean failed;

        /** A retrieval that ran: whatever came back is the truth about this file. */
        public RetrievalResult(List<String> chunks, boolean complete) {
            this(chunks, complete, false);
        }

        private RetrievalResult(List<String> chunks, boolean complete, boolean failed) {
            this.chunks = chunks;
            this.complete = complete;
            this.failed = failed;
        }

        /**
         * OpenSearch could not be asked, or could not answer. Nothing is known about this file
         * from it -- not that it is indexed, and not that it is not.
         *
         * {@code complete} is false rather than true here for the same reason: claiming the whole
         * file came back when nothing did is the confusion this type exists to end.
         */
        public static RetrievalResult unavailable() {
            return new RetrievalResult(Collections.emptyList(), false, true);
        }
    }

    /**
     * A filtered k-NN query against the vector index, with the application-code cosine loop kept
     * behind it as a fallback -- see the class javadoc for which path runs when and why both
     * exist. {@code topK} is a ceiling, not a guarantee; a short file may have fewer chunks than
     * that, which is also how the caller learns a retrieval was complete rather than partial.
     *
     * The cheap {@link #countChunks} call in front is new, and it exists because of a
     * long-standing no-op. When a file has no more chunks than {@code topK}, ranking provably
     * cannot change the result: the score sort is undone by the document-order sort that follows
     * it, and {@code limit(topK)} discards nothing, so the output is every chunk in document
     * order no matter what the scores were. TextChunker advances 850 characters per chunk and the
     * caller asks for 8, so every file under roughly 7,000 extracted characters -- the majority
     * of what users actually open, including the resume-sized PDFs this feature was built for --
     * fell in that band. The code still did the expensive part: OpenSearch shipped back every
     * chunk's full 768-float vector, this class parsed them all and ran a cosine loop over each,
     * and then threw every score away. A count query returns a single number and lets that whole
     * payload be skipped, so the inert case now costs one trivial round trip plus a text-only
     * fetch instead of a vector-sized one. The price is one extra round trip on files where
     * ranking does matter, which is the smaller of the two.
     *
     * Note what this deliberately does NOT do: it does not change what a small file returns. The
     * full short-circuit -- not embedding the question at all when retrieval cannot discriminate
     * -- has to happen in FileChatServiceImpl.resolveContext, which owns the question embedding
     * and the RAG_TOP_K constant. This class cannot see that decision, only avoid paying for it.
     */
    public RetrievalResult searchRelevantChunks(String bucket, String key, String etag,
        float[] queryEmbedding, int topK) {
        if (!this.isEnabled()) {
            return new RetrievalResult(Collections.emptyList(), true);
        }
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            // Nothing stored could be compared with this, so ranking would drop every chunk. This
            // is reported as a failure rather than as an empty index because that is what it is:
            // the file's chunks were never consulted, so nothing here says anything about whether
            // it is indexed, and a caller that re-indexed on the strength of it would be acting on
            // a fact this call never established.
            logger.error("RAG retrieval for {}/{} was handed an empty question vector; returning no chunks.",
                bucket, key);
            return RetrievalResult.unavailable();
        }
        if (TenantContext.getTenantId() == null) {
            // Zero hits, never an unfiltered query. Reported as unavailable rather than empty, so
            // FileChatServiceImpl answers from the raw file instead of re-indexing it under no tenant.
            logger.warn("RAG retrieval for {}/{} with no resolvable tenant; returning no chunks.", bucket, key);
            return RetrievalResult.unavailable();
        }
        int ceiling = Math.max(topK, 0);
        try {
            int chunkCount = this.countChunks(bucket, key, etag);
            if (chunkCount == 0) {
                // A cluster that answered "zero" really has nothing for this file version, so this
                // is the one empty result the caller should act on by indexing it.
                return new RetrievalResult(Collections.emptyList(), true);
            }
            // chunkCount < 0 means the count failed, not that the file is small; fall through to
            // the ranking path, which is what this method did unconditionally before.
            if (chunkCount > 0 && chunkCount <= ceiling) {
                return this.fetchInDocumentOrder(bucket, key, etag);
            }
            return this.rankByVector(bucket, key, etag, queryEmbedding, ceiling, chunkCount);
        } catch (HttpClientErrorException.NotFound notFound) {
            // Not a failure to degrade past: there is no index, so there are genuinely no chunks
            // for this file. Indexing is the right response, and it re-creates the index on the
            // way (see indexChunks, which deletes-then-ensures for exactly this case).
            this.noteIndexMissing("a retrieval query");
            return new RetrievalResult(Collections.emptyList(), true);
        } catch (Exception ex) {
            // A timeout, a 5xx, a body that would not parse. The cluster's state is unknown, so
            // this must not read as "no chunks" -- see RetrievalResult.failed for what that cost.
            logger.warn("RAG retrieval failed for {}/{}, returning no chunks: {}", bucket, key, ex.getMessage());
            return RetrievalResult.unavailable();
        }
    }

    /**
     * What is known right now about whether this file version is indexed.
     *
     * Three states, not two, for the same reason {@link RetrievalResult#failed} exists: "the
     * cluster says there are no chunks" and "the cluster did not answer" lead to opposite
     * decisions, and collapsing them into a boolean is what let an outage read as a cold index.
     * {@link #isIndexed} keeps the boolean shape for callers that only need the yes, and it is
     * still the safe-by-default one -- anything other than a definite INDEXED reads as false
     * there, so the worst case remains a redundant re-index rather than retrieval from chunks
     * that are not there.
     */
    public enum IndexState {
        /** The cluster reported at least one chunk for this bucket/key/etag and embedding model. */
        INDEXED,
        /** The cluster answered, and it has nothing for this file version -- or has no index at all. */
        NOT_INDEXED,
        /** OpenSearch could not answer. Nothing was learned about this file either way. */
        UNKNOWN
    }

    /**
     * Whether this exact version of this file has already been chunked and embedded, by the model
     * this deployment is currently using, or {@link IndexState#UNKNOWN} when OpenSearch could not
     * say.
     *
     * The etag is most of the mechanism: a different upload of the same key is a different etag,
     * so this correctly says NOT_INDEXED for it and the caller re-indexes, while a repeat question
     * against an unchanged file gets INDEXED and skips straight to retrieval -- no re-extraction,
     * no re-chunking, no re-embedding. The embedding model name is the rest of it: chunks left
     * behind by a superseded model are not an index of this file as far as this deployment is
     * concerned, and saying "yes" for them means answering out of a vector space the question was
     * never embedded into.
     *
     * A store that is not configured at all answers NOT_INDEXED rather than UNKNOWN: there is no
     * index anywhere, which is a fact, not an outage.
     */
    public IndexState indexStateOf(String bucket, String key, String etag) {
        if (!this.isEnabled()) {
            return IndexState.NOT_INDEXED;
        }
        int count = this.countChunks(bucket, key, etag);
        if (count < 0) {
            return IndexState.UNKNOWN;
        }
        return count > 0 ? IndexState.INDEXED : IndexState.NOT_INDEXED;
    }

    /**
     * Every chunk of the file, in document order, with the vectors left where they are.
     *
     * Only reached when the file has no more chunks than the caller asked for, which is precisely
     * the case where a score cannot affect the answer.
     *
     * Completeness is decided from the chunk INDEXES, not from how many documents came back. The
     * previous version returned {@code complete = true} unconditionally here, reasoning that the
     * count which routed the call is the same filter the fetch uses, so nothing was left out.
     * That is true of what OpenSearch holds and false of what the file contains: a chunk rejected
     * at write time is missing from both the count and the fetch, and the two agree with each
     * other about a document that has a hole in the middle of it. Live proof at the time this was
     * written -- "I-94_I-95 Official Website - Get Most Recent Response.pdf" held chunkIndex 0 and
     * 2, count said 2, 2 <= topK, and roughly 850 characters from the middle of the notice were
     * handed to the model under the heading "FILE CONTENT" with no caveat attached.
     *
     * Chunks are written as a contiguous run from 0, so anything other than 0..n-1 is a loss.
     */
    private RetrievalResult fetchInDocumentOrder(String bucket, String key, String etag) throws Exception {
        List<Chunk> chunks = readChunks(this.runRetrievalQuery(bucket, key, etag, false));
        List<Chunk> ordered = chunks.stream()
            .sorted(Comparator.comparingInt((Chunk c) -> c.chunkIndex))
            .collect(Collectors.toList());

        boolean contiguous = true;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).chunkIndex != i) {
                contiguous = false;
                break;
            }
        }
        if (!contiguous) {
            logger.error("{}/{} is missing chunks: the index holds {} but their indexes are {}. "
                + "Answering from a file with a hole in it; re-index to repair.",
                bucket, key, ordered.size(),
                ordered.stream().map(c -> c.chunkIndex).collect(Collectors.toList()));
        }

        List<String> texts = ordered.stream().map(c -> c.text).collect(Collectors.toList());
        return new RetrievalResult(texts, contiguous);
    }

    /**
     * The best {@code ceiling} chunks for this question, from the vector index if the cluster will
     * do it and from the application-code cosine loop if it will not.
     *
     * The order matters and is the whole fix: the k-NN query is tried FIRST and its rejection is
     * what selects the old path, rather than the old path being the only path. What the old one
     * costs is not theoretical -- every matching chunk's full 768 floats crossed the wire on every
     * question, 229,285 bytes for a single question about a 20-chunk PDF, and every hit came back
     * carrying the identical constant {@code _score} because a {@code constant_score} filter is
     * all it ever asked for.
     *
     * {@code ceiling} of zero skips k-NN outright: {@code k} must be at least 1, and a query
     * asking for no neighbours is a 400 that would latch the fast path off over a caller's
     * degenerate argument rather than anything about the cluster.
     */
    private RetrievalResult rankByVector(String bucket, String key, String etag, float[] queryEmbedding,
        int ceiling, int chunkCount) throws Exception {
        if (ceiling > 0 && this.knnQuerySupported) {
            String rejection;
            try {
                JsonNode root = this.objectMapper.readTree(this.restTemplate.postForObject(
                    this.baseUrl + "/" + INDEX_NAME + "/_search",
                    this.jsonEntity(this.knnQuery(bucket, key, etag, queryEmbedding, ceiling)), String.class));
                rejection = knnRejection(root);
                if (rejection == null) {
                    return knnResult(root.path("hits").path("hits"), ceiling, chunkCount);
                }
            } catch (HttpClientErrorException.NotFound notFound) {
                // There is no index. That is the caller's case to handle (it clears the ensured
                // latch and reports "no chunks"), not a statement about k-NN support, and
                // swallowing it here would send the fallback query to the same missing index.
                throw notFound;
            } catch (HttpClientErrorException rejected) {
                rejection = "HTTP " + rejected.getStatusCode().value() + " -- "
                    + rejected.getResponseBodyAsString();
            }
            // Deliberately outside the catch: a 200 that reports every shard failed is the same
            // answer as a 400, and both mean this cluster will not serve the knn query.
            this.knnQuerySupported = false;
            logger.warn("OpenSearch refused the k-NN retrieval query against {} ({}). Falling back to fetching "
                + "every matching chunk's stored vector and ranking in application code, for the rest of this "
                + "process's life. That path answers correctly but pulls the whole file's vectors over HTTP on "
                + "every question; the causes worth checking are an OpenSearch older than 2.4, index.knn "
                + "disabled on the index, or an embedding field that is not a knn_vector because a _bulk "
                + "auto-created the index after a wipe.", INDEX_NAME, rejection);
        }
        return this.rankChunks(this.runRetrievalQuery(bucket, key, etag, true),
            queryEmbedding, ceiling, bucket, key);
    }

    /**
     * Why a {@code _search} response that arrived with a 200 is still not a k-NN answer, or null
     * when it is one.
     *
     * OpenSearch reports a shard-level failure inside the body, not as an HTTP status, so a
     * cluster that cannot execute the query on any shard can answer 200 with zero hits and a
     * populated {@code _shards.failures}. Read only as "no chunks", that is indistinguishable from
     * a file that genuinely has none -- which is the reading that sends FileChatServiceImpl off to
     * re-extract, re-chunk and re-embed the entire file, on every message, forever.
     *
     * Narrow on purpose: failures alongside actual hits are a partial result, not a refusal, and
     * must not latch the fast path off for what may be one sick shard.
     */
    static String knnRejection(JsonNode root) {
        JsonNode shards = root.path("_shards");
        if (shards.path("failed").asInt(0) <= 0 || root.path("hits").path("hits").size() > 0) {
            return null;
        }
        JsonNode firstFailure = shards.path("failures").path(0).path("reason");
        String type = firstFailure.path("type").asText("");
        String reason = firstFailure.path("reason").asText("");
        return "every shard failed -- " + (type.isEmpty() ? "no reason given" : type + ": " + reason);
    }

    /**
     * The hits of a k-NN search as a result, in document order.
     *
     * OpenSearch returns these best-match-first; they are re-sorted into the order they appear in
     * the source document for the same reason {@link #rankChunks} does it -- a "what does section
     * 3 say, and how does it relate to section 5" question reads far better that way -- and the
     * scores are then of no further use, which is why nothing here keeps them.
     *
     * Completeness cannot be read off the hit count alone here, because {@code k} caps it: a query
     * that returns exactly {@code ceiling} chunks looks the same whether the file had exactly that
     * many or a thousand. The count taken before the query is the answer when it is available, and
     * when it is not (the count failed, which is the only way to reach this with a negative one)
     * this errs toward "partial" -- telling the caller it has the whole file when it does not is
     * the direction that produces a confidently wrong answer.
     */
    private static RetrievalResult knnResult(JsonNode hits, int ceiling, int chunkCount) {
        List<String> texts = readChunks(hits).stream()
            .sorted(Comparator.comparingInt((Chunk c) -> c.chunkIndex))
            .map(c -> c.text)
            .collect(Collectors.toList());
        boolean complete = chunkCount >= 0 ? texts.size() >= chunkCount : texts.size() < ceiling;
        return new RetrievalResult(texts, complete);
    }

    /**
     * The k-NN retrieval body. Package-private for the same reason {@link #retrievalQuery} is:
     * what this class does and does not ask OpenSearch for is the whole of its behaviour, and it
     * is worth asserting on directly rather than inferring from a live cluster's answers.
     *
     * Three things in here are load-bearing:
     *
     * <ul>
     * <li>{@code filter} carries {@link #termsFilter} unchanged, so k-NN is scoped to exactly the
     *     same file version and vector space the term-only query was -- the filter is applied
     *     during the graph walk on 2.4+, not as a post-filter that could return k neighbours from
     *     other files and then discard them all.</li>
     * <li>{@code _source} lists {@code chunkIndex} and {@code chunkText} and NOT {@code embedding}.
     *     Leaving it out is the bandwidth fix; asking for it back would reproduce the payload this
     *     query exists to stop sending.</li>
     * <li>{@code size} equals {@code k}. The engine returns k neighbours per shard; a larger
     *     {@code size} would not produce more of them and a smaller one would silently truncate
     *     what the caller asked for.</li>
     * </ul>
     */
    Map<String, Object> knnQuery(String bucket, String key, String etag, float[] queryEmbedding, int k) {
        Map<String, Object> knn = new HashMap<>();
        knn.put("vector", toList(queryEmbedding));
        knn.put("k", k);
        knn.put("filter", this.termsFilter(bucket, key, etag));

        Map<String, Object> query = new HashMap<>();
        query.put("size", k);
        query.put("query", Collections.singletonMap("knn", Collections.singletonMap("embedding", knn)));
        query.put("_source", Arrays.asList("chunkIndex", "chunkText"));
        return query;
    }

    /**
     * Scores the fetched chunks against the question and picks the best {@code ceiling} of them.
     *
     * This is the FALLBACK path now -- see {@link #rankByVector} -- reached only when the cluster
     * refuses the k-NN query. It is kept, rather than deleted along with the query that used to
     * feed it, because the deployments it exists for are real: an OpenSearch too old for filtered
     * k-NN, or an index auto-created with a dynamic mapping after a wipe. Answering slowly beats
     * not answering.
     *
     * Package-private so the selection can be tested against a hand-written {@code _search} body,
     * without a cluster.
     *
     * The filter on {@link Double#NaN} is the fix for a quiet and nasty failure. {@code
     * cosineSimilarity} used to answer -1f both for "these two vectors point in opposite
     * directions" and for "these two vectors cannot be compared at all", and nothing downstream
     * looked at the score for any reason. So when an operator swapped {@code embedding.model} for
     * a model of a different dimension, every stored 768-float vector mismatched the new
     * question vector, every chunk scored exactly -1f, the descending sort became a total tie,
     * and the first eight chunks OpenSearch happened to return -- deterministically chunks 0
     * through 7, the opening ~8,000 characters of the document -- were handed to the model
     * labelled "RELEVANT EXCERPTS FROM THE FILE". The model then answered confidently out of the
     * top of a document that had never been consulted for relevance, and not one log line said
     * anything. Incomparable vectors are now dropped before the sort, so that case returns
     * nothing rather than something wrong, and an empty result is a state the caller already
     * handles correctly: it re-indexes the file, and if that fails too it falls back to sending
     * real file content.
     *
     * Note that dropped chunks also make the retrieval incomplete. Reporting {@code complete} as
     * true just because the surviving handful fit under the ceiling would tell the caller it had
     * the whole file when chunks had in fact been discarded.
     */
    RetrievalResult rankChunks(JsonNode hits, float[] queryEmbedding, int ceiling, String bucket, String key) {
        List<Chunk> chunks = readChunks(hits);
        List<Chunk> comparable = new ArrayList<>();
        int incomparable = 0;
        for (Chunk chunk : chunks) {
            chunk.score = cosineSimilarity(queryEmbedding, chunk.vector);
            if (Double.isNaN(chunk.score)) {
                incomparable++;
            } else {
                comparable.add(chunk);
            }
        }
        if (incomparable > 0 && comparable.isEmpty()) {
            logger.error("RAG retrieval for {}/{}: not one of the {} stored vectors could be compared with the "
                + "{}-dimension question vector. Every chunk was dropped, so this file now reads as un-indexed "
                + "and will be re-embedded. Check that embedding.model and embedding.dimensions match the "
                + "vectors already written to {}.", bucket, key, chunks.size(), queryEmbedding.length, INDEX_NAME);
        } else if (incomparable > 0) {
            logger.warn("RAG retrieval for {}/{}: dropped {} of {} chunks whose stored vector could not be "
                + "compared with the question vector.", bucket, key, incomparable, chunks.size());
        }
        // Best matches first to select the top K, then re-sorted into document order -- a
        // "what does section 3 say, and how does it relate to section 5" question reads far
        // better when its top matches come back in the order they appear in the source
        // document than sorted purely by relevance.
        List<String> top = comparable.stream()
            .sorted(Comparator.comparingDouble((Chunk c) -> c.score).reversed())
            .limit(ceiling)
            .sorted(Comparator.comparingInt(c -> c.chunkIndex))
            .map(c -> c.text)
            .collect(Collectors.toList());
        return new RetrievalResult(top, incomparable == 0 && comparable.size() <= ceiling);
    }

    private JsonNode runRetrievalQuery(String bucket, String key, String etag, boolean includeVectors)
        throws Exception {
        String response = this.restTemplate.postForObject(this.baseUrl + "/" + INDEX_NAME + "/_search",
            this.jsonEntity(this.retrievalQuery(bucket, key, etag, includeVectors)), String.class);
        return this.objectMapper.readTree(response).path("hits").path("hits");
    }

    /**
     * The retrieval body. Package-private because what it does and does not ask OpenSearch for is
     * the whole of this class's behaviour, and it is worth asserting on directly.
     *
     * {@code includeVectors} is the lever behind the small-file path: leaving {@code embedding}
     * out of {@code _source} is what stops a file's worth of 768-float arrays crossing the wire
     * for a ranking that cannot change anything. It used to always be included.
     *
     * Two callers remain, and neither is the common one any more. False is the document-order
     * fetch; true is the application-code ranking fallback in {@link #rankByVector}, which is the
     * only thing that still needs the stored vectors at all. Ordinary ranking is {@link #knnQuery}
     * now and never asks for them.
     */
    Map<String, Object> retrievalQuery(String bucket, String key, String etag, boolean includeVectors) {
        Map<String, Object> query = new HashMap<>();
        query.put("size", MAX_CHUNKS_PER_FILE);
        query.put("query", this.termsFilter(bucket, key, etag));
        query.put("_source", includeVectors
            ? Arrays.asList("chunkIndex", "chunkText", "embedding")
            : Arrays.asList("chunkIndex", "chunkText"));
        return query;
    }

    /** Called before indexing a new version of a file; see indexChunks. */
    private void deleteChunksForFile(String bucket, String key) {
        try {
            Map<String, Object> bool = new HashMap<>();
            bool.put("must", Arrays.asList(termQuery("bucket", bucket), termQuery("key", key)));
            bool.put("filter", Collections.singletonList(tenantClause()));
            Map<String, Object> body = new HashMap<>();
            body.put("query", Collections.singletonMap("bool", bool));
            this.restTemplate.postForObject(
                this.baseUrl + "/" + INDEX_NAME + "/_delete_by_query?conflicts=proceed",
                this.jsonEntity(body), String.class);
        } catch (HttpClientErrorException.NotFound notFound) {
            // Nothing to delete -- the index does not exist yet, or does not exist any more.
            this.noteIndexMissing("a delete-by-query");
        } catch (Exception ex) {
            logger.warn("Could not clear previous chunks for {}/{} before reindexing: {}", bucket, key, ex.getMessage());
        }
    }

    /**
     * The filter that scopes every read in this class: bucket + key + etag identify the version of
     * the file, and embeddingModel identifies the vector space its chunks live in.
     *
     * The model clause is new. The {@code embeddingModel} field has been written on every chunk
     * since it was added, and until now nothing read it back -- not the query, not even
     * {@code _source}. What that cost: swapping {@code embedding.model} between two models of the
     * same dimension left every stored vector structurally comparable with the new question
     * vector (same length, so {@code cosineSimilarity} returned a perfectly well-formed number
     * between -1 and 1) but semantically unrelated to it. The ranking became pure noise that no
     * log line, metric or return value could distinguish from a correct one, and on any file over
     * roughly 7,000 characters the top eight noise-ranked chunks went to the model labelled
     * "RELEVANT EXCERPTS FROM THE FILE". This is the strictly worse sibling of the dimension
     * mismatch, because it produces no sentinel at all for {@link #rankChunks} to catch.
     *
     * With the clause, a superseded model's chunks simply stop matching. The file reads as "not
     * indexed", FileChatServiceImpl's existing re-index path rewrites it with the current model,
     * and {@code deleteChunksForFile} -- which deliberately does not filter on the model -- clears
     * the stale ones. The swap becomes self-healing instead of silently wrong, and it covers the
     * dimension-mismatch case in the same clause, since a model of a different dimension is
     * necessarily a differently-named model.
     *
     * Chunks written before the field existed carry no value for it and so match no term clause.
     * That is the intended reading of them: unknown provenance, re-embed to be sure.
     *
     * Skipped entirely when the property resolves to blank, so a deployment that has somehow
     * cleared {@code embedding.model} keeps the old unfiltered behaviour rather than retrieving
     * nothing at all from a perfectly good index.
     */
    Map<String, Object> termsFilter(String bucket, String key, String etag) {
        List<Map<String, Object>> must = new ArrayList<>();
        must.add(termQuery("bucket", bucket));
        must.add(termQuery("key", key));
        must.add(termQuery("etag", etag));
        if (this.configuredEmbeddingModel != null && !this.configuredEmbeddingModel.trim().isEmpty()) {
            must.add(modelClause(this.configuredEmbeddingModel.trim()));
        }
        Map<String, Object> bool = new HashMap<>();
        bool.put("must", must);
        bool.put("filter", Collections.singletonList(tenantClause()));
        return Collections.singletonMap("bool", bool);
    }

    /**
     * The tenant predicate on every query this class sends to {@code file-rag-chunks} (MIG-10,
     * DEF-009): {@code tenantId} has been written on every chunk since the index existed, and until
     * this clause nothing read it back. Isolation lived entirely in
     * FileChatServiceImpl.validateBucketAccess plus the bucket term -- a check in another subsystem,
     * which leaves with the caller the moment bucket names stop being tenant-scoped or RAG is
     * extracted. That check and the bucket clause stay; this is defence in depth.
     *
     * The tenant is TenantContext's, never an argument's: a caller cannot ask for someone else's.
     * It sits in the bool's {@code filter}, which does not score, so a single tenant's relevance
     * ordering is exactly what it was. With no resolvable tenant the clause matches nothing -- the
     * read paths refuse before they get here, and this is what keeps a path that forgets to from
     * returning every tenant's chunks.
     */
    static Map<String, Object> tenantClause() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return Collections.singletonMap("bool",
                Collections.singletonMap("must_not", Collections.singletonList(
                    Collections.singletonMap("match_all", Collections.emptyMap()))));
        }
        return Collections.singletonMap("term", Collections.singletonMap("tenantId", tenantId));
    }

    private static Map<String, Object> termQuery(String field, String value) {
        return Collections.singletonMap("term", Collections.singletonMap(field, value));
    }

    /**
     * The embeddingModel clause, written to match the field whichever way it is mapped.
     *
     * A bare {@code term} on {@code embeddingModel} is right only for an index this class created,
     * where the field is a {@code keyword}. An index that predates that field in the mapping has it
     * dynamically mapped instead -- analyzed {@code text}, with the {@code .keyword} sub-field
     * OpenSearch adds by default -- and the standard analyzer has split the stored value into
     * [nomic, embed, text], so the exact-term clause matches nothing at all.
     *
     * That was not theoretical. On the live index the term clause matched 0 of 222 chunks while
     * the same clause against {@code embeddingModel.keyword} matched 219, so every question about
     * every file read as "not indexed yet", re-extracted, re-chunked and re-embedded the whole
     * file, wrote it back, searched again, still found nothing, and answered from the truncated
     * raw text -- on every message, forever. mappingComplaint diagnosed exactly this and the only
     * action taken on it was a log line.
     *
     * Matching either path fixes it without a reindex, and the reindex is the part worth avoiding:
     * the chunks and their vectors are perfectly good, it is only the query that could not reach
     * them. A term clause against a field the mapping does not define matches nothing rather than
     * erroring, so each side is inert on the index the other belongs to.
     */
    static Map<String, Object> modelClause(String model) {
        List<Map<String, Object>> either = new ArrayList<>();
        either.add(termQuery("embeddingModel", model));
        either.add(termQuery("embeddingModel.keyword", model));
        Map<String, Object> bool = new HashMap<>();
        bool.put("should", either);
        bool.put("minimum_should_match", 1);
        return Collections.singletonMap("bool", bool);
    }

    /**
     * Records that something just found no index where one is supposed to be, and un-latches
     * {@link #indexEnsured} so the next write rebuilds it properly.
     *
     * Cheap and idempotent on purpose -- it is called from catch blocks on the hot path. The
     * warning fires only on the transition, so a cluster that is simply down does not fill the log
     * with the same line once per message.
     */
    private void noteIndexMissing(String what) {
        if (this.indexEnsured) {
            logger.warn("The {} index was not found while running {}; clearing the created-it-already flag so "
                + "the next write re-creates it with its k-NN mapping instead of letting a _bulk auto-create a "
                + "dynamic one in its place.", INDEX_NAME, what);
        }
        this.indexEnsured = false;
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

    /**
     * Cosine similarity, or {@link Double#NaN} when the two vectors are not comparable at all.
     *
     * NaN rather than -1f is the whole point of this signature. -1f is a legitimate cosine score
     * -- two vectors pointing exactly opposite -- and using it to also mean "I could not compare
     * these" made the failure indistinguishable from a real, if unflattering, result. Every caller
     * that wanted to react to the failure had no way to detect it, so none of them did. NaN cannot
     * be produced by the arithmetic below, propagates through it, and is impossible to compare
     * into a ranking by accident: {@code Double.compare} sorts NaN above every real score, so an
     * unfiltered NaN would be promoted to the top of the results rather than quietly ignored.
     * {@link #rankChunks} filters on it before it ever reaches a comparator.
     *
     * Package-private so the sentinel itself can be asserted on directly.
     */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return Double.NaN;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            // A zero vector has no direction, so it has no angle to anything. Previously -1f,
            // which read downstream as "maximally irrelevant" rather than "unanswerable".
            return Double.NaN;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
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

    /**
     * The hits of a {@code _search} response as chunks. A hit with no text is skipped -- there is
     * nothing to send the model -- and a hit fetched without its vector simply carries an empty
     * one, which only the document-order path ever does and which that path never scores.
     */
    private static List<Chunk> readChunks(JsonNode hits) {
        List<Chunk> chunks = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            String text = source.path("chunkText").asText("");
            if (text.isEmpty()) {
                continue;
            }
            chunks.add(new Chunk(source.path("chunkIndex").asInt(0), text,
                toFloatArray(source.path("embedding"))));
        }
        return chunks;
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

    /**
     * Counts what the bulk response actually stored, and names why the rest did not land.
     *
     * The previous version logged one WARN reading "OpenSearch rejected some chunks" and named
     * neither how many nor which nor why -- so a file that lost a chunk mid-document looked, in
     * the log and to every caller, exactly like one that had indexed cleanly. It returned void,
     * which left no way for the caller to say so either. Both halves of that are why a Latin-1
     * encoding bug survived in this class long enough to mangle every file in the index.
     *
     * A bulk response is 200 even when individual items were rejected, so the body is the only
     * place the truth is written down.
     */
    private IndexOutcome reportBulkOutcome(String responseBody, String bucket, String key, int count) {
        if (responseBody == null) {
            logger.warn("OpenSearch returned no body indexing {} chunks for {}/{}", count, bucket, key);
            return new IndexOutcome(count, 0, "OpenSearch returned an empty bulk response.");
        }
        try {
            JsonNode root = this.objectMapper.readTree(responseBody);
            if (!root.path("errors").asBoolean(false)) {
                return new IndexOutcome(count, count, null);
            }

            int stored = 0;
            String firstReason = null;
            List<Integer> lostChunks = new ArrayList<>();
            JsonNode items = root.path("items");
            for (int i = 0; i < items.size(); i++) {
                JsonNode error = items.get(i).path("index").path("error");
                if (error.isMissingNode() || error.isNull()) {
                    stored++;
                    continue;
                }
                lostChunks.add(i);
                if (firstReason == null) {
                    firstReason = error.path("type").asText("unknown") + ": "
                        + error.path("reason").asText("no reason given");
                }
            }

            logger.error("OpenSearch stored {} of {} chunks for {}/{}. Lost chunk indexes {}. First reason -- {}",
                stored, count, bucket, key, lostChunks, firstReason);

            if (responseBody.contains("index_not_found_exception")) {
                this.noteIndexMissing("a bulk write");
            }
            return new IndexOutcome(count, stored,
                (count - stored) + " of " + count + " chunks were rejected (" + firstReason + ")");
        } catch (Exception ex) {
            // The bulk write succeeded at the HTTP level to reach here, but a body this class
            // cannot parse means it cannot claim the chunks landed either.
            logger.warn("Could not read the bulk response for {}/{}: {}", bucket, key, ex.getMessage());
            return new IndexOutcome(count, count, null);
        }
    }

    /**
     * One chunk as it came back from OpenSearch. {@code score} is filled in by {@link #rankChunks}
     * and is meaningless on the document-order path, which never sets it.
     */
    private static final class Chunk {
        private final int chunkIndex;
        private final String text;
        private final float[] vector;
        private double score;

        private Chunk(int chunkIndex, String text, float[] vector) {
            this.chunkIndex = chunkIndex;
            this.text = text;
            this.vector = vector;
        }
    }
}
