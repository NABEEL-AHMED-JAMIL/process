package process.forms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-279: Core reaches workflow-service where it serves (its /api/v1 context path), with the internal token, and reads
 * its answers -- found, not found (404), refused (its sentence) -- against a real HTTP server standing in for it.
 */
class HttpFormWorkflowsTest {

    private HttpServer server;
    private final List<String> calls = new ArrayList<>();
    private final List<JsonNode> bodies = new ArrayList<>();
    private int status = 200;
    private String answer = "{\"status\":\"SUCCESS\",\"data\":{\"key\":\"approve-visit\",\"name\":\"Approve a visit\",\"status\":\"Active\","
        + "\"currentVersion\":2,\"id\":3001,\"state\":\"Running\"}}";

    @BeforeEach
    void serve() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            this.calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " token="
                + exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            this.bodies.add(new ObjectMapper().readTree(exchange.getRequestBody()));
            byte[] out = this.answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(this.status, out.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(out);
            }
        });
        this.server.start();
    }

    @AfterEach
    void stop() {
        this.server.stop(0);
    }

    private HttpFormWorkflows client() {
        return new HttpFormWorkflows("http://127.0.0.1:" + this.server.getAddress().getPort() + "/", "secret-token");
    }

    @Test
    void findsAWorkflowAtItsInternalDoorWithTheToken() {
        FormWorkflows.Workflow found = this.client().find(2924L, "approve-visit").get();
        assertThat(found.name).isEqualTo("Approve a visit");
        assertThat(found.currentVersion).isEqualTo(2);
        assertThat(this.calls).containsExactly("POST /api/v1/internal/workflows/definition token=secret-token");
        assertThat(this.bodies.get(0).path("tenantId").asLong()).isEqualTo(2924L);
    }

    @Test
    void aMissingWorkflowIsEmptyAndARefusedStartSaysWhy() {
        this.status = 404;
        this.answer = "{\"status\":\"ERROR\",\"message\":\"No workflow \\\"nope\\\".\"}";
        assertThat(this.client().find(2924L, "nope")).isEmpty();
        assertThatThrownBy(() -> this.client().start(2924L, "nope", 41L, "Visit #41", Collections.emptyMap(), 4537L))
            .hasMessage("No workflow \"nope\".");
    }

    @Test
    void startsARequestForTheSubmissionOnceByItsEventId() {
        FormWorkflows.Started started = this.client().start(2924L, "approve-visit", 41L, "Visit #41",
            Collections.singletonMap("patient", "P-1"), 4537L);
        assertThat(started.instanceId).isEqualTo(3001L);
        assertThat(this.calls).containsExactly("POST /api/v1/internal/workflows/start token=secret-token");
        JsonNode body = this.bodies.get(0);
        assertThat(body.path("subjectId").asText()).isEqualTo("form-submission:41");
        assertThat(body.path("eventId").asText()).isEqualTo("form-submission-41");
        assertThat(body.path("requestedBy").asLong()).isEqualTo(4537L);
        assertThat(body.path("subject").path("patient").asText()).isEqualTo("P-1");
    }
}
