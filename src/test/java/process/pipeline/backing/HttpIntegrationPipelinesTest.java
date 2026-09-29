package process.pipeline.backing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.row;

/**
 * MIG-231: Core's side of the pipeline calls integration-service needs -- the service token, the run's workspace in the
 * body, integration-service's envelope read back, its refusals in its words -- and off until integration-service has them.
 */
class HttpIntegrationPipelinesTest {

    private static final String TOKEN = "core-to-integration";

    private HttpServer server;
    private final List<String> seen = new ArrayList<>();
    private int status = 200;
    private String reply = "{}";

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    private String start() throws Exception {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/api/v1/internal/pipelines", exchange -> {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = exchange.getRequestBody()) {
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    body.write(chunk, 0, n);
                }
            }
            this.seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " token=" + exchange.getRequestHeaders()
                .getFirst("X-Internal-Token") + " " + body.toString("UTF-8"));
            byte[] answer = this.reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(this.status, answer.length == 0 ? -1 : answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        this.server.start();
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    @Test
    void offUntilIntegrationServiceHasItsEndpointsAndWriteHasItsOwnSwitch() {
        HttpIntegrationPipelines off = new HttpIntegrationPipelines("http://nowhere", TOKEN, false, true);
        assertThat(off.unavailable()).hasValueSatisfying(reason -> assertThat(reason).contains("internal-endpoints is off"));
        HttpIntegrationPipelines on = new HttpIntegrationPipelines("http://nowhere", TOKEN, true, false);
        assertThat(on.unavailable()).isEmpty();
        assertThat(new IntegrationDatabaseWriter(on).unavailable()).hasValueSatisfying(reason -> assertThat(reason).contains("read-only"));
        assertThat(new HttpIntegrationPipelines("http://nowhere", "", true, true).unavailable())
            .hasValueSatisfying(reason -> assertThat(reason).contains("no service token"));
        ApiRunner.ApiCall call = new ApiRunner.ApiCall();
        assertThatThrownBy(() -> off.run(call)).hasMessageContaining("not deployed yet");
    }

    @Test
    void anApiRunNamesTheRunsWorkspaceAndReadsTheRunResult() throws Exception {
        this.reply = "{\"status\":\"SUCCESS\",\"data\":{\"outcome\":\"OK\",\"statusCode\":200,\"body\":{\"a\":1},\"items\":[{\"id\":1}],"
            + "\"truncated\":true}}";
        HttpIntegrationPipelines integration = new HttpIntegrationPipelines(this.start() + "/", TOKEN, true, false);
        ApiRunner.ApiCall call = new ApiRunner.ApiCall();
        call.tenantId = 41L;
        call.jobQueueId = 7401L;
        call.stepKey = "fetch";
        call.requestId = 12L;
        call.variables = Collections.singletonMap("since", "2026-09-28");

        ApiRunner.ApiRunResult result = integration.run(call);

        assertThat(result.ok()).isTrue();
        assertThat(result.statusCode).isEqualTo(200);
        assertThat(result.items.get(0).get("id").asInt()).isEqualTo(1);
        assertThat(result.truncated).isTrue();
        assertThat(this.seen).containsExactly("POST /api/v1/internal/pipelines/api/run token=" + TOKEN + " {\"tenantId\":41,\"jobQueueId\":7401,"
            + "\"stepKey\":\"fetch\",\"requestId\":12,\"environmentId\":null,\"version\":null,\"variables\":{\"since\":\"2026-09-28\"}}");
    }

    @Test
    void contractVerdictsQueryRowsAndWrites() throws Exception {
        String base = this.start();
        HttpIntegrationPipelines integration = new HttpIntegrationPipelines(base, TOKEN, true, true);
        this.reply = "{\"status\":\"SUCCESS\",\"data\":{\"contractId\":4,\"name\":\"claims\",\"version\":3,\"results\":[{\"index\":0,"
            + "\"valid\":true,\"errors\":[]},{\"index\":1,\"valid\":false,\"errorCount\":1,\"errors\":[{\"path\":\"/amount\",\"keyword\":\"type\","
            + "\"message\":\"must be a number\"}]}]}}";
        ContractChecker.ContractCall check = new ContractChecker.ContractCall();
        check.tenantId = 41L;
        check.contractId = 4L;
        check.rows = Arrays.asList(row("amount", 1), row("amount", "x"));
        ContractChecker.ContractVerdicts verdicts = integration.validate(check);
        assertThat(verdicts.name).isEqualTo("claims");
        assertThat(verdicts.rows).extracting(verdict -> verdict.valid).containsExactly(true, false);
        assertThat(verdicts.rows.get(1).errors).containsExactly("/amount: must be a number");

        this.reply = "{\"status\":\"SUCCESS\",\"data\":{\"columns\":[\"id\",\"name\"],\"rows\":[{\"id\":1,\"name\":\"Acme\"}],\"truncated\":false}}";
        DatabaseReader.QueryCall query = new DatabaseReader.QueryCall();
        query.tenantId = 41L;
        query.connectionId = 5L;
        query.query = "select id, name from customers";
        query.maxRows = 10;
        DatabaseReader.QueryResult rows = integration.query(query);
        assertThat(rows.columns).containsExactly("id", "name");
        assertThat(rows.rows).containsExactly(row("id", 1L, "name", "Acme"));

        this.reply = "{\"status\":\"SUCCESS\",\"data\":{\"written\":2}}";
        DatabaseWriter.WriteCall write = new DatabaseWriter.WriteCall();
        write.tenantId = 41L;
        write.connectionId = 5L;
        write.table = "claims";
        write.mode = "insert";
        write.rows = Arrays.asList(row("id", 1), row("id", 2));
        assertThat(new IntegrationDatabaseWriter(integration).write(write)).isEqualTo(2L);
        assertThat(this.seen).extracting(line -> line.substring(0, line.indexOf(" token="))).containsExactly(
            "POST /api/v1/internal/pipelines/contract/validateRows", "POST /api/v1/internal/pipelines/database/query",
            "POST /api/v1/internal/pipelines/database/write");
    }

    @Test
    void refusalsAreIntegrationServicesWords() throws Exception {
        HttpIntegrationPipelines integration = new HttpIntegrationPipelines(this.start(), TOKEN, true, false);
        DatabaseReader.QueryCall query = new DatabaseReader.QueryCall();
        query.query = "select 1";
        this.status = 400;
        this.reply = "{\"status\":\"ERROR\",\"message\":\"Connection 5 is not this workspace's.\"}";
        assertThatThrownBy(() -> integration.query(query)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Connection 5 is not this workspace's.");
        this.status = 401;
        this.reply = "";
        assertThatThrownBy(() -> integration.query(query)).hasMessage("integration-service refused Core's service token.");
        this.status = 404;
        assertThatThrownBy(() -> integration.query(query))
            .hasMessage("integration-service has no /api/v1/internal/pipelines/database/query endpoint.");
    }
}
