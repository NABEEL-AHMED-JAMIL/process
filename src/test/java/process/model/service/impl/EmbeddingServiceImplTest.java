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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two things checked against a real local HTTP server, not a mock of OkHttp -- both are about
 * how many requests actually leave the process, which a mocked HTTP client would only tell you
 * about indirectly:
 *
 * (1) {@code embedAll} sends every chunk as ONE batched request to {@code /api/embed} (Ollama
 * accepts {@code input} as an array), not one request per chunk -- a file that chunks into
 * dozens of pieces used to pay that many sequential round trips to index once.
 *
 * (2) {@code isAvailable} is cached for a short window rather than pinging the model on every
 * call -- {@code FileChatServiceImpl} calls it once per chat message on top of the embed calls
 * a message already makes, so an uncached ping doubled the live network calls on every message.
 *
 * @author Nabeel Ahmed
 */
class EmbeddingServiceImplTest {

    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final Gson gson = new Gson();

    @AfterEach
    void stopServer() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    /** Echoes back exactly as many 3-dim vectors as the request's own {@code input} carried --
     *  real Ollama behaviour, and the shape {@code embedAll}'s own size check requires; a fixed
     *  vector count regardless of input size would fail that check for anything but one input. */
    private EmbeddingServiceImpl startServerAndService() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/api/embed", this::handle);
        this.server.start();
        int port = this.server.getAddress().getPort();

        EmbeddingServiceImpl service = new EmbeddingServiceImpl();
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + port);
        ReflectionTestUtils.setField(service, "model", "nomic-embed-text");
        ReflectionTestUtils.setField(service, "dimensions", 3);
        return service;
    }

    private void handle(HttpExchange exchange) throws IOException {
        this.requestCount.incrementAndGet();
        String requestBody;
        try (java.io.InputStream is = exchange.getRequestBody()) {
            requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonObject request = this.gson.fromJson(requestBody, JsonObject.class);
        JsonElement input = request.get("input");
        int inputCount = input.isJsonArray() ? input.getAsJsonArray().size() : 1;

        StringBuilder responseBody = new StringBuilder("{\"embeddings\":[");
        for (int i = 0; i < inputCount; i++) {
            if (i > 0) {
                responseBody.append(",");
            }
            responseBody.append("[1.0,2.0,3.0]");
        }
        responseBody.append("]}");

        byte[] bytes = responseBody.toString().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void embedAllSendsOneBatchedRequestNotOnePerChunk() throws Exception {
        EmbeddingServiceImpl service = this.startServerAndService();
        List<String> chunks = Arrays.asList("chunk one", "chunk two", "chunk three");

        List<float[]> vectors = service.embedAll(chunks);

        assertThat(vectors).hasSize(3);
        assertThat(this.requestCount.get())
            .as("three chunks must reach the model in ONE request, not three")
            .isEqualTo(1);
    }

    @Test
    void isAvailableDoesNotRepingWithinTheCacheWindow() throws Exception {
        EmbeddingServiceImpl service = this.startServerAndService();

        boolean first = service.isAvailable();
        boolean second = service.isAvailable();

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(this.requestCount.get())
            .as("a second call inside the cache window must reuse the first result, not ping again")
            .isEqualTo(1);
    }
}
