package process.pipeline.backing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-255: the render_pdf step's call to media-service -- the service token alone, the run's workspace and usage key on
 * the query, the rows as a dataset, and (with no template named) a template of Core's own that prints every header field
 * -- the DRAFT label must never depend on a template remembering it -- turns the page for a wide table, and prints the
 * watermark. A label is plain text (RenderPdfStepTask checks it when the definition is saved).
 */
class HttpReportRendererTest {

    private HttpServer server;
    private final BlockingQueue<String[]> seen = new ArrayBlockingQueue<>(2);
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    private int serve(int status, String answer) throws Exception {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            this.seen.offer(new String[] {exchange.getRequestURI().toString(), exchange.getRequestHeaders().getFirst("X-Internal-Token"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)});
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        this.server.start();
        return this.server.getAddress().getPort();
    }

    private static ReportRenderer.Report report(Long templateId) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Status", "DRAFT for clinician review");
        fields.put("Measurement (sizes)", "Sizes are estimates");
        return new ReportRenderer.Report(2901L, "pipeline#7430#report#a1", "wound.pdf", "Wound assessment", fields,
            Arrays.asList("case_id", "trend"), Collections.singletonList(Arrays.<Object>asList("C1", "improving")), templateId, "landscape", "DRAFT");
    }

    @Test
    void theReportGoesWithTheServiceTokenAndCoresOwnTemplate() throws Exception {
        int port = this.serve(200, "{\"status\":\"SUCCESS\",\"data\":{\"outputBase64\":\"JVBERi0xLjc=\"}}");
        byte[] pdf = new HttpReportRenderer("http://127.0.0.1:" + port, "core-to-media", true).renderPdf(report(null));

        assertThat(new String(pdf, StandardCharsets.US_ASCII)).isEqualTo("%PDF-1.7");
        String[] call = this.seen.poll(5, TimeUnit.SECONDS);
        assertThat(call[0]).isEqualTo("/api/v1/internal/media/render?tenantId=2901&usageKey=pipeline%237430%23report%23a1");
        assertThat(call[1]).isEqualTo("core-to-media");
        JsonNode body = this.json.readTree(call[2]);
        assertThat(body.path("save").asBoolean()).isFalse();
        assertThat(body.path("dataset").path("fields").path("Status").asText()).isEqualTo("DRAFT for clinician review");
        assertThat(body.path("dataset").path("rows").get(0).get(1).asText()).isEqualTo("improving");
        JsonNode template = body.path("template");
        assertThat(template.path("orientation").asText()).isEqualTo("landscape");
        assertThat(template.path("watermarkText").asText()).isEqualTo("DRAFT");
        assertThat(template.path("bodyHtml").asText()).contains("{{title}}").contains("{{table}}")
            .contains("<strong>Status</strong>: {{field:Status}}")
            .contains("<strong>Measurement (sizes)</strong>: {{field:Measurement (sizes)}}");
    }

    @Test
    void aNamedTemplateIsUsedAsItIs() throws Exception {
        int port = this.serve(200, "{\"status\":\"SUCCESS\",\"data\":{\"outputBase64\":\"JVBERi0=\"}}");
        new HttpReportRenderer("http://127.0.0.1:" + port, "t", true).renderPdf(report(12L));
        JsonNode body = this.json.readTree(this.seen.poll(5, TimeUnit.SECONDS)[2]);
        assertThat(body.path("templateId").asLong()).isEqualTo(12L);
        assertThat(body.has("template")).isFalse();
    }

    @Test
    void aRefusalCarriesMediasWords() throws Exception {
        int port = this.serve(413, "{\"status\":\"ERROR\",\"message\":\"The rendered file is too large.\"}");
        assertThatThrownBy(() -> new HttpReportRenderer("http://127.0.0.1:" + port, "t", true).renderPdf(report(null)))
            .hasMessage("media-service did not render the report (HTTP 413): The rendered file is too large.");
        assertThat(new HttpReportRenderer("http://x", "t", false).unavailable()).isPresent();
    }
}
