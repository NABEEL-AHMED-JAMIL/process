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
import java.util.Collections;

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

    /**
     * How long a POSITIVE availability answer is trusted before {@link #isAvailable()} asks again.
     *
     * Minutes rather than the seconds this used to be, and it is the check itself changing that
     * bought them. While "is the model available" was answered by running a real inference
     * ({@code embed("ping")}), a long cache window was the only thing standing between file chat
     * and a third inference per message -- so the window had to be short enough to notice Ollama
     * dying and long enough to be worth having, and 15 seconds was that unhappy compromise.
     * A GET of {@code /api/tags} costs the host essentially nothing, so the trade is gone: an
     * Ollama that was up a few minutes ago is overwhelmingly likely to still be up, and the cost
     * of being wrong is one wasted embed attempt whose failure the caller already degrades past.
     */
    private static final long AVAILABLE_CACHE_MILLIS = 300_000;

    /**
     * And how long a NEGATIVE one is, which is deliberately a fraction of the above.
     *
     * The two are not symmetrical. Caching "up" for five minutes costs at most one failed request
     * when it goes down; caching "down" for five minutes means file chat keeps refusing to use RAG
     * for five minutes after Ollama has come back, and every message in that window answers from
     * raw truncated text instead. Recovery has to be cheap to notice.
     */
    private static final long UNAVAILABLE_CACHE_MILLIS = 5_000;

    /** Seconds. Short on purpose -- see {@link #availabilityClient}. */
    private static final int AVAILABILITY_TIMEOUT_SECONDS = 2;

    /**
     * How many chunks go into one {@code /api/embed} request.
     *
     * Not "all of them", which is what this used to do and what made a large file unindexable
     * rather than slow. The 95MB CSV this deployment actually holds extracts to the 500,000-char
     * cap and chunks into 588 pieces; all 588 went into a single request against a 30-second read
     * timeout, could not possibly finish inside it, and the timeout aborted the whole thing. The
     * caller degrades past that and answers from raw text -- and because nothing was indexed, the
     * NEXT message re-extracted, re-chunked and re-embedded the same 588 chunks and timed out
     * again. Forever, at full cost, on every message.
     *
     * 64 is chosen to be small enough that one batch comfortably finishes inside its own timeout
     * on a CPU-only Ollama and large enough that the round-trip count stays trivial: 588 chunks
     * become 10 requests rather than 588 (which is what the per-chunk loop before the batching
     * cost) or 1 (which is what cannot finish).
     */
    private static final int EMBED_BATCH_SIZE = 64;

    /**
     * The read timeout for a batch, in seconds: {@code BASE + PER_CHUNK * batchSize}, capped.
     *
     * A fixed 30 seconds was wrong in both directions at once -- generous for the single-string
     * question embed that happens once per message, and impossible for a request carrying hundreds
     * of chunks. The base keeps the single embed at essentially the 30 seconds it has always had
     * (32, and that floor is mostly there so a cold Ollama has room to load the model before it
     * answers anything at all), while the per-chunk term is what makes the timeout describe the
     * work actually being asked for -- a full batch gets 158. The cap is there because past a few
     * minutes a batch is not slow, it is wedged, and the retry is worth more than the wait.
     */
    private static final int EMBED_READ_TIMEOUT_BASE_SECONDS = 30;
    private static final int EMBED_READ_TIMEOUT_PER_CHUNK_SECONDS = 2;
    private static final int EMBED_READ_TIMEOUT_MAX_SECONDS = 180;

    /**
     * The embedding client, carrying the base timeout. Every actual request is issued through a
     * {@link #clientForBatchOf} derivative of it rather than through it directly, so that the read
     * timeout can describe the size of the batch being sent; the derivative shares this client's
     * connection pool and dispatcher, which is what makes building one per batch free.
     */
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(EMBED_READ_TIMEOUT_BASE_SECONDS, TimeUnit.SECONDS)
        .build();

    /**
     * The availability probe's own client: the same connection pool, far shorter timeouts.
     *
     * {@link #isAvailable()} sits on the path of a user's chat message, deciding whether to even
     * attempt RAG, so an honest answer to "is the model host up" that arrives slowly is worse than
     * useless -- a host that is down usually fails by not answering at all, and the ten-second
     * connect timeout the embedding client carries would spend ten seconds of a user's message
     * establishing that.
     */
    private final OkHttpClient availabilityClient = this.httpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(AVAILABILITY_TIMEOUT_SECONDS))
        .readTimeout(AVAILABILITY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build();

    private volatile boolean cachedAvailable = false;
    // 0, not Long.MIN_VALUE -- "now - Long.MIN_VALUE" overflows a long (wraps negative, since
    // now is a modest positive epoch-millis value and MIN_VALUE is a huge negative one), which
    // made the very first call ever made read as "inside the cache window" and return the
    // default `false` without ever actually checking. A real epoch-millis `now` is always far
    // more than either cache window past 0, so this never wrongly hits the cache on the first
    // call the way MIN_VALUE did.
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
     * Whether the embedding model is reachable AND pulled -- answered by listing the host's models,
     * not by running an inference.
     *
     * This used to call {@code embed("ping")}. That is a real inference: the model is loaded if it
     * is cold, a 768-float vector is computed and returned, and the result is thrown away. It was
     * the most expensive way available to ask a yes/no question, and file chat asks it three times
     * for a single message -- {@code prepareContext} consults {@code ragAvailable()} twice, and
     * {@code resolveContext} consults it again immediately before embedding the question. A normal
     * message therefore cost three round trips to Ollama where one is genuinely needed, and the two
     * wasted ones were the two that computed a vector nobody wanted.
     *
     * What is NOT given up in the swap is the reason the ping existed: the model being *configured*
     * is not the same as it being *pulled*, and a check that only proves the host answers HTTP
     * would report a deployment with no {@code nomic-embed-text} on it as healthy right up until
     * every embed call 404s. {@code /api/tags} lists what the host actually has, so the configured
     * model can be looked for by name -- same guarantee, no inference. See {@link #modelIsListed}
     * for the one case where that list cannot be read and this falls back to "the host answered".
     *
     * A tolerable, unsynchronized race exists at the cache boundary (two calls arriving right as it
     * expires can both probe) -- harmless, and not worth a lock for.
     */
    @Override
    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        // Asymmetric on purpose: a "yes" is trusted for minutes, a "no" for seconds. See the
        // two constants for why treating them the same is wrong in whichever direction it is set.
        long window = this.cachedAvailable ? AVAILABLE_CACHE_MILLIS : UNAVAILABLE_CACHE_MILLIS;
        if (now - this.cachedAvailableAtMillis < window) {
            return this.cachedAvailable;
        }
        boolean available = this.probeModelHost();
        this.cachedAvailable = available;
        this.cachedAvailableAtMillis = now;
        return available;
    }

    /** One short GET of {@code /api/tags}; never an inference. See {@link #isAvailable()}. */
    private boolean probeModelHost() {
        Request request = new Request.Builder().url(this.baseUrl + "/api/tags").get().build();
        try (Response response = this.availabilityClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                logger.warn("Embedding host {} answered HTTP {} for /api/tags; treating embeddings as "
                    + "unavailable.", this.baseUrl, response.code());
                return false;
            }
            Boolean listed = this.modelIsListed(responseBody);
            if (Boolean.FALSE.equals(listed)) {
                logger.warn("Embedding host {} is up but does not list model '{}'. It has almost certainly not "
                    + "been pulled -- `ollama pull {}` on that host. Until it is, file chat will answer from raw "
                    + "truncated file text rather than retrieved excerpts.", this.baseUrl, this.model, this.model);
                return false;
            }
            return true;
        } catch (Exception ex) {
            logger.warn("Embedding host is not reachable at {}: {}", this.baseUrl, ex.getMessage());
            return false;
        }
    }

    /**
     * TRUE when the configured model appears in an {@code /api/tags} body, FALSE when the body
     * lists models and this one is not among them, and null when the body is not a model list at
     * all.
     *
     * The third answer is the point of the boxed return. Not every deployment's
     * {@code ollama.base.url} is literally Ollama -- a compatible proxy or a gateway may implement
     * {@code /api/embed} and answer something else entirely on {@code /api/tags} -- and reporting
     * "unavailable" because a response could not be parsed would take RAG away from a stack that
     * embeds perfectly well. Unreadable means unknown, and unknown defers to the fact that the host
     * answered at all. Only a list that genuinely does not contain the model is a no.
     *
     * Ollama lists a pulled model under its full tag, so {@code nomic-embed-text} appears as
     * {@code nomic-embed-text:latest} -- a configured name carrying no tag has to match that or
     * the check would report every default deployment as missing its own model.
     */
    private Boolean modelIsListed(String responseBody) {
        try {
            JsonObject json = this.gson.fromJson(responseBody, JsonObject.class);
            if (json == null || !json.has("models") || !json.get("models").isJsonArray()) {
                return null;
            }
            for (JsonElement element : json.getAsJsonArray("models")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                if (this.namesTheConfiguredModel(entry.get("name"))
                    || this.namesTheConfiguredModel(entry.get("model"))) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        } catch (Exception ex) {
            // A body this cannot read says nothing either way -- see the javadoc above.
            return null;
        }
    }

    private boolean namesTheConfiguredModel(JsonElement listed) {
        if (listed == null || !listed.isJsonPrimitive() || this.model == null) {
            return false;
        }
        String name = listed.getAsString();
        return name.equals(this.model)
            || (!this.model.contains(":") && name.equals(this.model + ":latest"));
    }

    @Override
    public float[] embed(String text) throws Exception {
        return this.embedAll(Collections.singletonList(text)).get(0);
    }

    /**
     * Every chunk embedded, in slices of {@link #EMBED_BATCH_SIZE} rather than all at once.
     *
     * Ollama's {@code /api/embed} accepts {@code input} as either a single string or an array of
     * them, returning a matching array of vectors either way, and this used to exploit that by
     * putting EVERY chunk of a file in one request. That was the right instinct against the
     * per-chunk loop it replaced (50-200 sequential round trips to index one file) and the wrong
     * conclusion: one request has one read timeout, and a request carrying hundreds of chunks
     * cannot finish inside a timeout sized for one. See {@link #EMBED_BATCH_SIZE} for the 588-chunk
     * file this deployment holds, which could never be indexed and so was re-extracted, re-chunked
     * and re-embedded from scratch on every single message, forever.
     *
     * Batching restores a bounded amount of work per request while keeping the round-trip count
     * trivial. A batch still fails as a unit and still takes the whole call down with it, which is
     * deliberate: {@code FileChatServiceImpl} hands what comes back to
     * {@code OpenSearchRagClient.indexChunks} alongside the chunk texts, and that call requires the
     * two lists to be the same length. Returning the vectors that did succeed would either throw
     * there or, if the lengths happened to line up, index chunk N's text against chunk M's vector
     * -- so the partial result is REPORTED, naming where it stopped and how much had already been
     * done, and not returned.
     */
    @Override
    public List<float[]> embedAll(List<String> texts) throws Exception {
        if (texts.isEmpty()) {
            return new ArrayList<>();
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += EMBED_BATCH_SIZE) {
            int end = Math.min(start + EMBED_BATCH_SIZE, texts.size());
            try {
                vectors.addAll(this.embedBatch(texts.subList(start, end)));
            } catch (Exception ex) {
                // Named precisely because the alternative -- "embedding failed" -- is what made the
                // 588-chunk file's permanent re-index loop invisible in the log. Where it stopped
                // and how far it had got are what distinguishes a batch that is too big from a
                // model that is down, and the two have completely different fixes.
                String summary = String.format(
                    "Embedding failed on chunks %d-%d of %d (batch %d of %d); %d chunks had already been "
                        + "embedded and are discarded with them. Cause: %s",
                    start, end - 1, texts.size(), (start / EMBED_BATCH_SIZE) + 1,
                    batchCount(texts.size()), vectors.size(), ex.getMessage());
                // Logged here as well as thrown. The caller degrades past an embedding failure by
                // design -- FileChatServiceImpl answers from raw file text rather than showing the
                // user an error -- so a partial embed that is only ever described inside an
                // exception message is one refactor away from being silent again, and silent is
                // exactly how this file went un-indexed for as long as it did.
                logger.error(summary, ex);
                throw new IllegalStateException(summary, ex);
            }
        }
        this.warnOnceIfDimensionsDisagree(vectors);
        return vectors;
    }

    /**
     * How many requests {@code texts} will take, for the failure message -- "batch 7 of 10" says
     * something "batch 7" does not.
     */
    private static int batchCount(int total) {
        return (total + EMBED_BATCH_SIZE - 1) / EMBED_BATCH_SIZE;
    }

    /**
     * The read timeout for a request carrying {@code batchSize} inputs, in seconds.
     *
     * Package-private, and a pure function of the batch size, so the thing that actually broke
     * indexing -- a timeout that did not scale with the work being asked for -- can be asserted on
     * without waiting for one.
     */
    static long readTimeoutSecondsFor(int batchSize) {
        return Math.min(EMBED_READ_TIMEOUT_MAX_SECONDS,
            EMBED_READ_TIMEOUT_BASE_SECONDS + (long) EMBED_READ_TIMEOUT_PER_CHUNK_SECONDS * batchSize);
    }

    /**
     * A client whose read timeout matches the batch about to be sent. Derived from
     * {@link #httpClient}, so it shares its connection pool and dispatcher -- this allocates a
     * small wrapper, not a new HTTP stack, which is what makes one per batch reasonable.
     */
    private OkHttpClient clientForBatchOf(int batchSize) {
        return this.httpClient.newBuilder()
            .readTimeout(readTimeoutSecondsFor(batchSize), TimeUnit.SECONDS)
            .build();
    }

    /**
     * One {@code /api/embed} request for one slice of chunks.
     *
     * The size check below is not paranoia: Ollama answers {@code embeddings} positionally, so a
     * response carrying a different number of vectors than the request carried inputs means the
     * mapping from chunk to vector is unknowable -- and indexing under that mapping writes chunk
     * N's text with some other chunk's vector, which retrieves as confident nonsense rather than
     * as an error.
     */
    private List<float[]> embedBatch(List<String> texts) throws Exception {
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
        try (Response response = this.clientForBatchOf(texts.size()).newCall(request).execute()) {
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
                vectors.add(vector);
            }
            return vectors;
        }
    }

    /**
     * One line per call when the model's vectors are not the configured width, rather than one
     * line per vector.
     *
     * Not fatal here -- OpenSearchRagClient is where a dimension mismatch actually matters, and it
     * refuses the write with a clearer error naming the index. It is still worth saying, because a
     * silently-changed model ({@code embedding.model} overridden without updating
     * {@code embedding.dimensions} to match) is invisible until a k-NN write fails downstream.
     *
     * The move out of the per-vector parse loop is what batching made necessary: this fired once
     * per chunk, so the 588-chunk file that motivated the batching would have printed 588 copies
     * of the same sentence -- a volume that hides the line instead of surfacing it.
     */
    private void warnOnceIfDimensionsDisagree(List<float[]> vectors) {
        int mismatched = 0;
        int firstWidth = -1;
        for (float[] vector : vectors) {
            if (vector.length != this.dimensions) {
                mismatched++;
                if (firstWidth < 0) {
                    firstWidth = vector.length;
                }
            }
        }
        if (mismatched > 0) {
            logger.warn("Embedding model '{}' returned {} dimensions for {} of {} inputs; configured "
                + "embedding.dimensions is {}.", this.model, firstWidth, mismatched, vectors.size(),
                this.dimensions);
        }
    }
}
