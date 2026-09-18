package process.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A real local HTTP server rather than a mock of OkHttp: what matters is the header and the
 * body that leave the process, and the usage that is read back off the answer.
 */
public class AiProviderGatewayTest {

    private HttpServer server;
    private final BlockingQueue<HttpExchange> received = new ArrayBlockingQueue<>(2);
    private final AiProviderGateway gateway = new AiProviderGateway();

    @AfterEach
    void stopServer() { if (this.server != null) this.server.stop(0); }

    private int startServer(String responseBody) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            this.received.offer(exchange);
        });
        this.server.start();
        return this.server.getAddress().getPort();
    }

    @Test
    void azureSendsTheApiKeyHeaderNotAuthorizationBearerAndReadsUsage() throws Exception {
        int port = this.startServer("{\"choices\":[{\"message\":{\"content\":\"the answer\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}");
        AiProviderGateway.ChatRequest req = new AiProviderGateway.ChatRequest();
        req.provider = "AzureOpenAI"; req.apiKey = "test-resource-key";
        req.apiEndpoint = "http://127.0.0.1:" + port + "/openai/deployments/gpt-4/chat/completions?api-version=2024-02-01";
        req.model = "gpt-4"; req.system = "You are a helpful assistant."; req.user = "What is two plus two?";

        AiProviderGateway.ChatAnswer answer = this.gateway.chat(req);

        HttpExchange exchange = this.received.poll(5, TimeUnit.SECONDS);
        assertThat(exchange).as("the request must actually have reached the server").isNotNull();
        // Azure authenticates a resource key through this exact header; Bearer is what it rejects.
        assertThat(exchange.getRequestHeaders().getFirst("api-key")).isEqualTo("test-resource-key");
        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
        assertThat(answer.text).isEqualTo("the answer");
        assertThat(answer.tokensIn).isEqualTo(12);
        assertThat(answer.tokensOut).isEqualTo(3);
    }

    @Test
    void azureStillRequiresAnApiEndpoint() {
        AiProviderGateway.ChatRequest req = new AiProviderGateway.ChatRequest();
        req.provider = "AzureOpenAI"; req.apiKey = "k"; req.model = "gpt-4"; req.user = "A question.";
        assertThatThrownBy(() -> this.gateway.chat(req)).isInstanceOf(IllegalStateException.class).hasMessageContaining("deployment URL");
    }

    @Test
    void ollamaListsItsModelsFromTags() throws Exception {
        int port = this.startServer("{\"models\":[{\"name\":\"llama3.1:8b\"},{\"name\":\"mistral:7b\"}]}");
        List<String> models = this.gateway.listModels("Ollama", null, "http://127.0.0.1:" + port);
        assertThat(models).containsExactly("llama3.1:8b", "mistral:7b");
    }

    @Test
    void aProviderRefusalCarriesItsStatus() throws Exception {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> { exchange.sendResponseHeaders(429, 0); exchange.close(); });
        this.server.start();
        AiProviderGateway.ChatRequest req = new AiProviderGateway.ChatRequest();
        req.provider = "Ollama"; req.apiEndpoint = "http://127.0.0.1:" + this.server.getAddress().getPort(); req.model = "m"; req.user = "u";
        assertThatThrownBy(() -> this.gateway.chat(req)).isInstanceOf(AiProviderGateway.ProviderException.class)
            .satisfies(ex -> assertThat(((AiProviderGateway.ProviderException) ex).status).isEqualTo(429));
    }
}
