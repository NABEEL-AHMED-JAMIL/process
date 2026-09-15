package process.model.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What actually leaves this process, checked against a real local HTTP server rather than a mock
 * of OkHttp -- every defect this class exists for is about the shape and the COUNT of the requests
 * made, which a mocked client tells you about only indirectly:
 *
 * (1) {@code embedAll} batches, in bounded slices. One request per chunk was the original sin; one
 * request for ALL of them was the overcorrection, and it is worse, because a request carrying
 * hundreds of chunks cannot finish inside a read timeout sized for one -- so the 588-chunk file
 * this deployment holds could never be indexed at all and was re-extracted, re-chunked and
 * re-embedded from scratch on every single message, forever.
 *
 * (2) {@code isAvailable} is a cheap GET of {@code /api/tags}, not an inference. It used to call
 * {@code embed("ping")} -- loading the model if cold, computing a 768-float vector, throwing it
 * away -- and {@code FileChatServiceImpl} asks it three times per message, so two thirds of the
 * embedding round trips a normal message paid for existed only to answer a yes/no question.
 *
 * (3) It is still cached, and now asymmetrically: minutes for "up", seconds for "down".
 *
 * @author Nabeel Ahmed
 */
class EmbeddingServiceImplTest {

    private HttpServer server;
    private final AtomicInteger embedRequestCount = new AtomicInteger(0);
    private final AtomicInteger tagsRequestCount = new AtomicInteger(0);
    /** How many inputs each {@code /api/embed} request carried, in order. */
    private final List<Integer> embedInputCounts = Collections.synchronizedList(new ArrayList<>());
    /** Which {@code /api/embed} request to answer with a 500; 0 means none. */
    private volatile int failEmbedRequestNumber = 0;
    /** What {@code /api/tags} lists. Ollama reports a pulled model under its full tag. */
    private volatile String tagsBody =
        "{\"models\":[{\"name\":\"nomic-embed-text:latest\",\"model\":\"nomic-embed-text:latest\"}]}";

    private final Gson gson = new Gson();

    @AfterEach
    void stopServer() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    private EmbeddingServiceImpl startServerAndService() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/api/embed", this::handleEmbed);
        this.server.createContext("/api/tags", this::handleTags);
        this.server.start();
        int port = this.server.getAddress().getPort();

        EmbeddingServiceImpl service = new EmbeddingServiceImpl();
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + port);
        ReflectionTestUtils.setField(service, "model", "nomic-embed-text");
        ReflectionTestUtils.setField(service, "dimensions", 3);
        return service;
    }

    /**
     * Echoes back exactly as many 3-dim vectors as the request's own {@code input} carried -- real
     * Ollama behaviour, and the shape {@code embedAll}'s own size check requires. Each vector is
     * filled with the number at the end of its input string, so a caller can prove which chunk a
     * vector came back for; that is what makes an ordering mistake across batch boundaries visible
     * rather than merely plausible.
     */
    private void handleEmbed(HttpExchange exchange) throws IOException {
        int requestNumber = this.embedRequestCount.incrementAndGet();
        String requestBody;
        try (java.io.InputStream is = exchange.getRequestBody()) {
            requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonObject request = this.gson.fromJson(requestBody, JsonObject.class);
        JsonElement input = request.get("input");
        List<String> inputs = new ArrayList<>();
        if (input.isJsonArray()) {
            for (JsonElement element : input.getAsJsonArray()) {
                inputs.add(element.getAsString());
            }
        } else {
            inputs.add(input.getAsString());
        }
        this.embedInputCounts.add(inputs.size());

        if (requestNumber == this.failEmbedRequestNumber) {
            respond(exchange, 500, "{\"error\":\"llama runner process has terminated\"}");
            return;
        }

        StringBuilder responseBody = new StringBuilder("{\"embeddings\":[");
        for (int i = 0; i < inputs.size(); i++) {
            if (i > 0) {
                responseBody.append(",");
            }
            float seed = vectorSeedOf(inputs.get(i));
            responseBody.append("[").append(seed).append(",").append(seed).append(",").append(seed).append("]");
        }
        respond(exchange, 200, responseBody.append("]}").toString());
    }

    private void handleTags(HttpExchange exchange) throws IOException {
        this.tagsRequestCount.incrementAndGet();
        respond(exchange, 200, this.tagsBody);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** "chunk-137" answers 137; anything else answers 1. */
    private static float vectorSeedOf(String text) {
        int dash = text.lastIndexOf('-');
        if (dash >= 0) {
            try {
                return Float.parseFloat(text.substring(dash + 1));
            } catch (NumberFormatException notNumbered) {
                return 1f;
            }
        }
        return 1f;
    }

    private static List<String> numberedChunks(int count) {
        List<String> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            chunks.add("chunk-" + i);
        }
        return chunks;
    }

    // -------------------------------------------------------------------------------------------
    // Batching: bounded slices, not one request and not one per chunk.
    // -------------------------------------------------------------------------------------------

    @Test
    void embedAllSendsOneBatchedRequestNotOnePerChunk() throws Exception {
        EmbeddingServiceImpl service = this.startServerAndService();
        List<String> chunks = Arrays.asList("chunk one", "chunk two", "chunk three");

        List<float[]> vectors = service.embedAll(chunks);

        assertThat(vectors).hasSize(3);
        assertThat(this.embedRequestCount.get())
            .as("three chunks must reach the model in ONE request, not three")
            .isEqualTo(1);
    }

    /**
     * The defect this batching exists for. 588 chunks (the 95MB CSV, capped at 500,000 extracted
     * characters) went into a single /api/embed request against a fixed 30-second read timeout,
     * could not finish inside it, and took the whole extract-embed-index cycle down with it -- so
     * the file was never indexed, and the next message started the identical doomed cycle again.
     *
     * 150 chunks stands in for it here: what matters is that no single request carries more than
     * the batch size, and that the round trips stay countable.
     */
    @Test
    void embedAllSlicesALargeFileIntoBoundedBatches() throws Exception {
        EmbeddingServiceImpl service = this.startServerAndService();

        List<float[]> vectors = service.embedAll(numberedChunks(150));

        assertThat(vectors).hasSize(150);
        assertThat(this.embedRequestCount.get())
            .as("150 chunks in slices of 64 is three requests -- not one that cannot finish, and "
                + "not 150 sequential round trips")
            .isEqualTo(3);
        assertThat(this.embedInputCounts)
            .as("no single request may carry an unbounded number of chunks; that is the whole fix")
            .allMatch(count -> count <= 64)
            .containsExactly(64, 64, 22);
    }

    /**
     * Slicing must not reorder anything. OpenSearchRagClient.indexChunks writes chunkTexts.get(i)
     * with embeddings.get(i), so a vector that came back for chunk 70 landing at position 64 would
     * index one chunk's text against another's vector -- which retrieves as confident nonsense
     * rather than as an error, and nothing downstream could detect it.
     */
    @Test
    void batchingKeepsEveryVectorWithTheChunkItWasComputedFor() throws Exception {
        EmbeddingServiceImpl service = this.startServerAndService();

        List<float[]> vectors = service.embedAll(numberedChunks(150));

        for (int i = 0; i < 150; i++) {
            assertThat(vectors.get(i)[0])
                .as("the vector at position %d must be the one computed for chunk-%d", i, i)
                .isEqualTo((float) i);
        }
    }

    /**
     * A batch that fails takes the call down and SAYS SO, naming where it stopped. Returning the
     * vectors that did succeed would be worse than failing: indexChunks pairs the two lists by
     * position and requires them to be the same length, so a short list either throws there with a
     * message about lengths or -- if they happened to line up -- silently mis-pairs every chunk.
     */
    @Test
    void aFailedBatchIsReportedRatherThanSilentlyTruncatingTheFile() throws Exception {
        EmbeddingServiceImpl service = this.startServerAndService();
        this.failEmbedRequestNumber = 2;

        assertThatThrownBy(() -> service.embedAll(numberedChunks(150)))
            .as("a partial embed must not come back looking like a whole one")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("chunks 64-127 of 150")
            .hasMessageContaining("batch 2 of 3")
            .hasMessageContaining("64 chunks had already been embedded");
    }

    /**
     * The timeout that made the single giant request fatal rather than merely slow. It has to
     * describe the work being asked for: the one-string question embed keeps essentially the 30
     * seconds it always had, a full batch gets proportionately more, and the whole thing is capped
     * because past a few minutes a request is not slow, it is wedged.
     */
    @Test
    void theReadTimeoutIsProportionateToTheBatchRatherThanFixed() {
        assertThat(EmbeddingServiceImpl.readTimeoutSecondsFor(1))
            .as("one string -- the per-message question embed -- keeps roughly its old 30 seconds")
            .isEqualTo(32L);
        assertThat(EmbeddingServiceImpl.readTimeoutSecondsFor(64))
            .as("a full batch is 64 times the work and must not be held to a one-string timeout")
            .isEqualTo(158L);
        assertThat(EmbeddingServiceImpl.readTimeoutSecondsFor(588))
            .as("and the 588-chunk file that started all this cannot buy itself an unbounded wait")
            .isEqualTo(180L);
    }

    // -------------------------------------------------------------------------------------------
    // Availability: a cheap check, not an inference.
    // -------------------------------------------------------------------------------------------

    /**
     * The heart of it. isAvailable() called embed("ping") -- a real inference whose 768-float
     * result was discarded -- and FileChatServiceImpl consults it three times for one message
     * (twice from prepareContext, once from resolveContext immediately before embedding the
     * question). Three round trips to Ollama where one is needed.
     */
    @Test
    void isAvailableAsksWhichModelsExistAndNeverRunsAnInference() throws Exception {
        EmbeddingServiceImpl service = this.startServerAndService();

        assertThat(service.isAvailable()).isTrue();

        assertThat(this.embedRequestCount.get())
            .as("answering 'is the model up' by computing an embedding is the cost this removed")
            .isZero();
        assertThat(this.tagsRequestCount.get())
            .as("one cheap GET of /api/tags is the whole check")
            .isEqualTo(1);
    }

    /**
     * What the ping did buy, and what a bare reachability check would have thrown away: a model
     * being CONFIGURED is not the same as it being PULLED. A host answering HTTP with no
     * nomic-embed-text on it must not read as healthy, or every embed call 404s behind a green
     * readiness flag.
     */
    @Test
    void aHostThatIsUpWithoutTheConfiguredModelIsNotAvailable() throws Exception {
        EmbeddingServiceImpl service = this.startServerAndService();
        this.tagsBody = "{\"models\":[{\"name\":\"llama3:latest\",\"model\":\"llama3:latest\"}]}";

        assertThat(service.isAvailable())
            .as("the model is not pulled, so nothing can be embedded -- reporting 'available' here "
                + "is the failure mode the old inference-based ping did not have")
            .isFalse();
        assertThat(this.embedRequestCount.get()).isZero();
    }

    /**
     * And the reverse: a response this cannot read says nothing either way, so it defers to the
     * fact that the host answered. Not every deployment's ollama.base.url is literally Ollama --
     * a compatible proxy may serve /api/embed perfectly and answer something else on /api/tags --
     * and taking RAG away from a stack that embeds fine because a body would not parse is the
     * wrong trade.
     */
    @Test
    void anUnreadableTagsResponseDefersToTheHostHavingAnswered() throws Exception {
        EmbeddingServiceImpl service = this.startServerAndService();
        this.tagsBody = "{\"data\":[{\"id\":\"nomic-embed-text\"}]}";

        assertThat(service.isAvailable()).isTrue();
        assertThat(this.embedRequestCount.get()).isZero();
    }

    @Test
    void isAvailableDoesNotRepingWithinTheCacheWindow() throws Exception {
        EmbeddingServiceImpl service = this.startServerAndService();

        boolean first = service.isAvailable();
        boolean second = service.isAvailable();

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(this.tagsRequestCount.get())
            .as("a second call inside the cache window must reuse the first result, not check again")
            .isEqualTo(1);
    }

    /**
     * The two windows are deliberately not the same length, and the asymmetry is the point:
     * caching "up" for minutes costs at most one failed request when Ollama dies, while caching
     * "down" for minutes means every message in that window answers from raw truncated text long
     * after Ollama came back.
     *
     * Asserted by ageing the cache rather than by sleeping -- ten seconds is past the failure
     * window and nowhere near the success one.
     */
    @Test
    void aNegativeAnswerIsRetriedSoonWhileAPositiveOneIsTrustedForMinutes() throws Exception {
        EmbeddingServiceImpl service = this.startServerAndService();
        long tenSecondsAgo = System.currentTimeMillis() - 10_000L;

        ReflectionTestUtils.setField(service, "cachedAvailable", false);
        ReflectionTestUtils.setField(service, "cachedAvailableAtMillis", tenSecondsAgo);
        assertThat(service.isAvailable()).isTrue();
        assertThat(this.tagsRequestCount.get())
            .as("a 'down' answer has to be cheap to walk back, or recovery is invisible for minutes")
            .isEqualTo(1);

        ReflectionTestUtils.setField(service, "cachedAvailable", true);
        ReflectionTestUtils.setField(service, "cachedAvailableAtMillis", tenSecondsAgo);
        assertThat(service.isAvailable()).isTrue();
        assertThat(this.tagsRequestCount.get())
            .as("an 'up' answer is worth minutes now that confirming it is not an inference")
            .isEqualTo(1);
    }
}
