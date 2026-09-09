package process.model.service.impl;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.AdHocPromptRequestDto;
import process.model.dto.ResponseDto;
import process.model.repository.AiAgentRepository;
import process.model.repository.TenantRepository;
import process.security.TenantFilterHelper;
import process.util.EncryptionUtil;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Azure OpenAI is not the generic OpenAI-compatible path with a different hostname -- it
 * genuinely rejects the request the generic path would send. Azure's Chat Completions API
 * authenticates a plain resource key through an `api-key` header; `Authorization: Bearer` is
 * only accepted there for Azure AD OAuth tokens. Sent through the generic path, an Azure OpenAI
 * agent configured the ordinary way (a resource key) failed authentication on every request,
 * silently -- the generic path's only validation is that an endpoint was supplied at all.
 *
 * A real local HTTP server, not a mock of OkHttp, so this asserts the header that actually
 * leaves the process rather than an internal call that happened to be made -- the distinction
 * that matters is what Azure receives on the wire.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class AzureOpenAiAuthHeaderTest {

    @Mock private AiAgentRepository aiAgentRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private EncryptionUtil encryptionUtil;
    @Mock private TenantFilterHelper tenantFilterHelper;
    @Mock private UserNameResolver userNameResolver;

    private HttpServer server;
    private final BlockingQueue<HttpExchange> received = new ArrayBlockingQueue<>(1);

    @AfterEach
    void stopServer() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    private AiAgentServiceImpl serviceAllowingLocalhost() {
        AiAgentServiceImpl service = new AiAgentServiceImpl(this.aiAgentRepository, this.tenantRepository,
            this.encryptionUtil, this.tenantFilterHelper, this.userNameResolver);
        // The endpoint allowlist refuses a private address by default -- see
        // AiAgentServiceImpl.validateEndpoint. A test server on loopback needs the same explicit
        // opt-in an operator would configure for any other private host.
        ReflectionTestUtils.setField(service, "allowedEndpointHosts", "127.0.0.1");
        return service;
    }

    /** The request's headers are parsed before the handler runs and do not change afterwards,
     *  so handing the exchange itself to the test thread (once a response has been sent) is
     *  safe -- nothing here reads the request body or writes the response a second time. */
    private int startServer(String responseBody) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            this.received.offer(exchange);
        });
        this.server.start();
        return this.server.getAddress().getPort();
    }

    @Test
    void sendsApiKeyHeaderNotAuthorizationBearer() throws Exception {
        int port = this.startServer(
            "{\"choices\":[{\"message\":{\"content\":\"the answer\"}}]}");
        AiAgentServiceImpl service = this.serviceAllowingLocalhost();

        AdHocPromptRequestDto dto = new AdHocPromptRequestDto();
        dto.setProvider("AzureOpenAI");
        dto.setApiKey("test-resource-key");
        dto.setApiEndpoint("http://127.0.0.1:" + port + "/openai/deployments/gpt-4/chat/completions?api-version=2024-02-01");
        dto.setModel("gpt-4");
        dto.setInstructions("You are a helpful assistant.");
        dto.setText("What is two plus two?");

        ResponseDto response = service.processAdHoc(dto);

        HttpExchange exchange = this.received.poll(5, TimeUnit.SECONDS);
        assertThat(exchange).as("the request must actually have reached the server").isNotNull();

        assertThat(exchange.getRequestHeaders().getFirst("api-key"))
            .as("Azure OpenAI authenticates a resource key through this exact header")
            .isEqualTo("test-resource-key");
        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
            .as("Authorization: Bearer is what Azure rejects for a plain resource key -- "
                + "this is the header the generic OpenAI-compatible path sent instead, and "
                + "why every AzureOpenAI agent configured with a resource key failed silently")
            .isNull();

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(response.getData()).isEqualTo("the answer");
    }

    @Test
    void stillRequiresAnApiEndpoint() throws Exception {
        AiAgentServiceImpl service = this.serviceAllowingLocalhost();
        AdHocPromptRequestDto dto = new AdHocPromptRequestDto();
        dto.setProvider("AzureOpenAI");
        dto.setApiKey("test-resource-key");
        dto.setModel("gpt-4");
        dto.setInstructions("You are a helpful assistant.");
        dto.setText("A question.");
        // apiEndpoint deliberately left unset -- there is no sensible default the way
        // api.openai.com is for plain OpenAI, since a deployment URL is tenant-specific.

        ResponseDto response = service.processAdHoc(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
    }
}
