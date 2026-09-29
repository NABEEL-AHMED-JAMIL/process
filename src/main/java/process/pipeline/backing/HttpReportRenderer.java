package process.pipeline.backing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import process.correlation.CorrelationInterceptor;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link ReportRenderer} as media-service's service render (MIG-255): POST /internal/media/render?tenantId=&usageKey= with
 * the internal token alone -- a step runs on the engine's thread, with no one signed in. The rows go as a dataset
 * ({title, fields, columns, rows}); the PDF comes back base64 in the answer. Nothing is read from or saved into storage.
 *
 * media-service must have the endpoint first; until it does, process.pipeline.media.internal-render is false and the
 * render_pdf task is listed as unavailable.
 */
@Component
public class HttpReportRenderer implements ReportRenderer {

    private static final MediaType JSON = MediaType.get("application/json");

    private final String renderUrl;
    private final String serviceToken;
    private final boolean enabled;
    private final ObjectMapper json = new ObjectMapper();
    // A long report is minutes of layout at worst; the connection itself is local.
    private final OkHttpClient http = new OkHttpClient.Builder()
        .addInterceptor(new CorrelationInterceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .build();

    public HttpReportRenderer(@Value("${media.url:http://media:9110}") String mediaUrl, @Value("${internal.service-token:}") String serviceToken,
        @Value("${process.pipeline.media.internal-render:false}") boolean enabled) {
        this.renderUrl = mediaUrl.replaceAll("/+$", "") + "/api/v1/internal/media/render";
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.enabled = enabled;
    }

    @Override
    public Optional<String> unavailable() {
        return this.enabled ? Optional.<String>empty()
            : Optional.of("media-service does not render reports for pipelines yet (process.pipeline.media.internal-render is off)");
    }

    /**
     * Core's own layout when no template is named: the title, every header field on its own line -- so a label such as
     * DRAFT is always printed, never left to a template remembering it -- the row count, the table, the page turned as
     * asked and the watermark. Labels are plain text (RenderPdfStepTask checks them); they are escaped all the same.
     */
    private ObjectNode ownTemplate(ReportRenderer.Report request) {
        StringBuilder html = new StringBuilder("<h1>{{title}}</h1>");
        for (String label : request.fields.keySet()) {
            html.append("<p class=\"report-meta\"><strong>").append(escape(label)).append("</strong>: {{field:").append(label).append("}}</p>");
        }
        html.append("<p class=\"report-meta\">{{rowCount}} rows | generated {{generatedAt}}</p>{{table}}");
        ObjectNode template = this.json.createObjectNode();
        template.put("name", "Pipeline report");
        template.put("bodyHtml", html.toString());
        template.put("pageSize", "A4");
        template.put("orientation", request.orientation == null ? "portrait" : request.orientation);
        if (request.watermark != null && !request.watermark.trim().isEmpty()) {
            template.put("watermarkText", request.watermark.trim());
        }
        return template;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Override
    public byte[] renderPdf(ReportRenderer.Report request) throws Exception {
        Optional<String> off = this.unavailable();
        if (off.isPresent()) {
            throw new IllegalStateException(off.get() + ".");
        }
        ObjectNode body = this.json.createObjectNode();
        body.put("outputFormat", "pdf");
        body.put("fileName", request.fileName);
        body.put("save", false);
        if (request.templateId != null) {
            body.put("templateId", request.templateId);
        } else {
            body.set("template", this.ownTemplate(request));
        }
        ObjectNode dataset = body.putObject("dataset");
        dataset.put("title", request.title);
        ObjectNode fields = dataset.putObject("fields");
        for (Map.Entry<String, String> field : request.fields.entrySet()) {
            fields.put(field.getKey(), field.getValue());
        }
        ArrayNode columns = dataset.putArray("columns");
        request.columns.forEach(columns::add);
        ArrayNode rows = dataset.putArray("rows");
        for (List<Object> row : request.rows) {
            rows.add(this.json.valueToTree(row));
        }
        HttpUrl url = HttpUrl.get(this.renderUrl).newBuilder().addQueryParameter("tenantId", String.valueOf(request.tenantId))
            .addQueryParameter("usageKey", request.usageKey).build();
        Request call = new Request.Builder().url(url).header("X-Internal-Token", this.serviceToken)
            .post(RequestBody.create(this.json.writeValueAsBytes(body), JSON)).build();
        try (Response response = this.http.newCall(call).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            JsonNode answer = text.isEmpty() ? null : this.json.readTree(text);
            if (response.code() != 200 || answer == null || !"SUCCESS".equals(answer.path("status").asText())) {
                String message = answer == null ? "" : answer.path("message").asText("");
                throw new IllegalStateException("media-service did not render the report (HTTP " + response.code() + ")"
                    + (message.isEmpty() ? "." : ": " + message));
            }
            return Base64.getDecoder().decode(answer.path("data").path("outputBase64").asText(""));
        }
    }
}
