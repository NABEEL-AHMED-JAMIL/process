package process.model.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import process.model.service.EmbeddingService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Ollama-backed embeddings. Local and free, which is the right default for a RAG pipeline whose
 * whole point is avoiding a paid API call on every chunk of every file a tenant has ever opened
 * -- generating a question's own embedding is one more call per question, and re-embedding a
 * file that changed is one call per chunk, both of which would carry a real per-token cost
 * against a hosted embedding API.
 *
 * @author Nabeel Ahmed
 * */
@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingServiceImpl.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${ollama.base.url:http://host.docker.internal:11434}")
    private String baseUrl;

    /**
     * nomic-embed-text produces 768-dimension vectors. Configurable because swapping the model
     * later (a larger embedding model, or a provider-hosted one) changes this, and the OpenSearch
     * index mapping has to be built with whatever dimension is actually in use -- a mismatch
     * there is a silent shape error on every index write, not a clear one.
     */
    @Value("${embedding.model:nomic-embed-text}")
    private String model;

    @Value("${embedding.dimensions:768}")
    private int dimensions;

    private final Gson gson = new Gson();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(30, TimeUnit.SECONDS)
        .build();

    /**
     * How long a successful or failed availability check is trusted before {@link #isAvailable()}
     * pings the model again. File chat calls this once per message on top of the real embed
     * calls a message already makes (the question, and a whole file's worth of chunks the first
     * time it's indexed) -- without a cache, a short back-and-forth conversation re-pays a live
     * network round trip for a fact ("is Ollama up") that essentially never changes between one
     * message and the next few seconds later.
     */
    private static final long AVAILABILITY_CACHE_MILLIS = 15_000;

    private volatile boolean cachedAvailable = false;
    // 0, not Long.MIN_VALUE -- "now - Long.MIN_VALUE" overflows a long (wraps negative, since
    // now is a modest positive epoch-millis value and MIN_VALUE is a huge negative one), which
    // made the very first call ever made read as "inside the cache window" and return the
    // default `false` without ever actually pinging the model. A real epoch-millis `now` is
    // always far more than AVAILABILITY_CACHE_MILLIS past 0, so this never wrongly hits the
    // cache on the first call the way MIN_VALUE did.
    private volatile long cachedAvailableAtMillis = 0L;

    @Override
    public int dimensions() {
        return this.dimensions;
    }

    @Override
    public String model() {
        return this.model;
    }

    /**
     * A real call rather than a config check -- the model being *configured* is not the same as
     * it being *pulled*. Cached for {@link #AVAILABILITY_CACHE_MILLIS}: still a real check, just
     * not re-run more often than the state it reports could plausibly have changed. A tolerable,
     * unsynchronized race exists at the cache boundary (two calls arriving right as it expires
     * can both ping) -- harmless, and not worth a lock for.
     */
    @Override
    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (now - this.cachedAvailableAtMillis < AVAILABILITY_CACHE_MILLIS) {
            return this.cachedAvailable;
        }
        boolean available;
        try {
            this.embed("ping");
            available = true;
        } catch (Exception ex) {
            logger.warn("Embedding model '{}' is not reachable at {}: {}", this.model, this.baseUrl, ex.getMessage());
            available = false;
        }
        this.cachedAvailable = available;
        this.cachedAvailableAtMillis = now;
        return available;
    }

    @Override
    public float[] embed(String text) throws Exception {
        return this.embedAll(java.util.Collections.singletonList(text)).get(0);
    }

    /**
     * One batched request for every chunk rather than one request per chunk -- Ollama's
     * {@code /api/embed} accepts {@code input} as either a single string or an array of them,
     * returning a matching array of vectors either way. A file that chunks into 50-200 pieces
     * used to pay 50-200 sequential HTTP round trips to index once; this is one.
     *
     * A batch failing loses every chunk in it rather than just one -- but the per-chunk loop this
     * replaced had the same failure shape one level up: the first {@code embedOne} exception
     * aborted the loop and propagated to {@code FileChatServiceImpl.resolveContext}'s catch block
     * regardless of how many chunks had already succeeded, since nothing there kept partial
     * results either. Batching removes 50-200 round trips of latency for the same all-or-nothing
     * failure behavior the caller already had, not a new risk.
     */
    @Override
    public List<float[]> embedAll(List<String> texts) throws Exception {
        if (texts.isEmpty()) {
            return new ArrayList<>();
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", this.model);
        JsonArray input = new JsonArray();
        for (String text : texts) {
            input.add(text);
        }
        body.add("input", input);
        Request request = new Request.Builder()
            .url(this.baseUrl + "/api/embed")
            .post(RequestBody.create(this.gson.toJson(body), JSON))
            .build();
        try (Response response = this.httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IllegalStateException(String.format(
                    "Embedding model '%s' returned HTTP %d: %s", this.model, response.code(), responseBody));
            }
            JsonObject json = this.gson.fromJson(responseBody, JsonObject.class);
            if (json == null || !json.has("embeddings")) {
                throw new IllegalStateException("Embedding response carried no 'embeddings' field.");
            }
            JsonArray outer = json.getAsJsonArray("embeddings");
            if (outer.size() != texts.size()) {
                throw new IllegalStateException(String.format(
                    "Embedding response carried %d vectors for %d inputs.", outer.size(), texts.size()));
            }
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (JsonElement outerElement : outer) {
                JsonArray inner = outerElement.getAsJsonArray();
                float[] vector = new float[inner.size()];
                int i = 0;
                for (JsonElement e : inner) {
                    vector[i++] = e.getAsFloat();
                }
                if (vector.length != this.dimensions) {
                    // Not fatal here -- OpenSearchRagClient is where a dimension mismatch actually
                    // matters, and it will refuse the write with a clearer error naming the index.
                    // Logged at warn because a silently-changed model (embedding.model overridden
                    // without updating embedding.dimensions to match) is exactly the kind of drift
                    // that is invisible until a k-NN write fails downstream.
                    logger.warn("Embedding model '{}' returned {} dimensions; configured embedding.dimensions is {}.",
                        this.model, vector.length, this.dimensions);
                }
                vectors.add(vector);
            }
            return vectors;
        }
    }
}
