package process.pipeline.backing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-255 / ADR-027: the render_pdf step's call to media-service -- the service token alone, the run's workspace and usage
 * key on the query, the report as lines (its settings first, then each table and its rows), and the PDF streamed back
 * with its pages; a refusal carries media's own words. The lines are written to a scratch file and removed with it.
 */
class HttpReportRendererTest {

    private HttpServer server;
    private final BlockingQueue<String[]> seen = new ArrayBlockingQueue<>(2);
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path scratch;

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    private int serve(int status, String contentType, byte[] answer, String pages) throws Exception {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            this.seen.offer(new String[] {exchange.getRequestURI().toString(), exchange.getRequestHeaders().getFirst("X-Internal-Token"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                exchange.getRequestHeaders().getFirst("Content-Type")});
            exchange.getResponseHeaders().add("Content-Type", contentType);
            if (pages != null) {
                exchange.getResponseHeaders().add("X-Report-Pages", pages);
                exchange.getResponseHeaders().add("X-Report-Rows", "2");
            }
            exchange.sendResponseHeaders(status, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        this.server.start();
        return this.server.getAddress().getPort();
    }

    private ReportLines lines(Long templateId) throws Exception {
        ReportLines lines = new ReportLines(this.scratch);
        lines.section(null, null, Arrays.asList("case_id", "trend"), 2, "2 rows.");
        lines.row(Arrays.<Object>asList("C1", "improving"));
        lines.row(Arrays.<Object>asList("C2", 12.5));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("Status", "DRAFT for clinician review");
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("title", "Wound assessment");
        head.put("fields", fields);
        head.put("orientation", "landscape");
        head.put("watermark", "DRAFT");
        head.put("templateId", templateId);
        lines.head(head);
        return lines;
    }

    @Test
    void theReportGoesAsLinesWithTheServiceTokenAndThePdfComesBackAsAStream() throws Exception {
        byte[] pdf = "%PDF-1.7 two pages".getBytes(StandardCharsets.US_ASCII);
        int port = this.serve(200, "application/pdf", pdf, "2");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ReportRenderer.Rendered rendered;
        try (ReportLines lines = this.lines(null)) {
            rendered = new HttpReportRenderer("http://127.0.0.1:" + port, "core-to-media", true)
                .render(2901L, "pipeline#7430#report#a1", lines, out);
        }

        assertThat(out.toByteArray()).isEqualTo(pdf);
        assertThat(rendered.pages).isEqualTo(2);
        assertThat(rendered.rows).isEqualTo(2);
        String[] call = this.seen.poll(5, TimeUnit.SECONDS);
        assertThat(call[0]).isEqualTo("/api/v1/internal/media/render/table?tenantId=2901&usageKey=pipeline%237430%23report%23a1");
        assertThat(call[1]).isEqualTo("core-to-media");
        assertThat(call[3]).startsWith("application/x-ndjson");
        String[] body = call[2].split("\n");
        assertThat(body).hasSize(4);
        JsonNode head = this.json.readTree(body[0]);
        assertThat(head.path("title").asText()).isEqualTo("Wound assessment");
        assertThat(head.path("fields").path("Status").asText()).isEqualTo("DRAFT for clinician review");
        assertThat(head.path("watermark").asText()).isEqualTo("DRAFT");
        assertThat(head.has("templateId")).as("no template: media's own layout").isFalse();
        JsonNode section = this.json.readTree(body[1]).path("section");
        assertThat(section.path("rows").asInt()).isEqualTo(2);
        assertThat(section.path("trailer").asText()).isEqualTo("2 rows.");
        assertThat(section.has("title")).isFalse();
        assertThat(this.json.readTree(body[3]).get(1).decimalValue()).isEqualByComparingTo("12.5");
        try (Stream<Path> left = Files.list(this.scratch)) {
            assertThat(left).as("the lines' file goes with the report").isEmpty();
        }
    }

    @Test
    void aNamedTemplateIsSentByItsId() throws Exception {
        int port = this.serve(200, "application/pdf", new byte[] {'%'}, "1");
        try (ReportLines lines = this.lines(12L)) {
            new HttpReportRenderer("http://127.0.0.1:" + port, "t", true).render(1L, "k", lines, new ByteArrayOutputStream());
        }
        JsonNode head = this.json.readTree(this.seen.poll(5, TimeUnit.SECONDS)[2].split("\n")[0]);
        assertThat(head.path("templateId").asLong()).isEqualTo(12L);
    }

    @Test
    void aRefusalCarriesMediasWords() throws Exception {
        int port = this.serve(413, "application/json",
            "{\"status\":\"ERROR\",\"message\":\"Table 1 sends 60,000 rows; a PDF prints at most 50,000 a table.\"}".getBytes(StandardCharsets.UTF_8), null);
        try (ReportLines lines = this.lines(null)) {
            assertThatThrownBy(() -> new HttpReportRenderer("http://127.0.0.1:" + port, "t", true).render(1L, "k", lines,
                new ByteArrayOutputStream()))
                .hasMessage("media-service did not render the report (HTTP 413): Table 1 sends 60,000 rows; a PDF prints at most 50,000 a table.");
        }
        assertThat(new HttpReportRenderer("http://x", "t", false).unavailable()).isPresent();
    }

    @Test
    void linesThatDoNotMatchTheirTableAreRefusedBeforeAnythingIsSent() throws Exception {
        try (ReportLines lines = new ReportLines(this.scratch)) {
            lines.section("A", null, Arrays.asList("x"), 2, null);
            lines.row(Arrays.<Object>asList(1));
            assertThatThrownBy(() -> lines.section("B", null, Arrays.asList("x"), 0, null)).hasMessage("A table declared 2 rows and has 1.");
            lines.row(Arrays.<Object>asList(2));
            assertThatThrownBy(() -> lines.row(Arrays.<Object>asList(3))).hasMessage("The table already has the rows it declared.");
            assertThatThrownBy(lines::size).hasMessage("The report has no settings line.");
        }
    }
}
