package process.pipeline.backing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import process.correlation.CorrelationInterceptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link ReportRenderer} as media-service's streamed render (ADR-027): POST /internal/media/render/table?tenantId=&usageKey=
 * with the internal token alone -- a step runs on the engine's thread, with no one signed in. The body is the report's
 * lines (ReportLines, application/x-ndjson) streamed from the step's scratch file; the answer is the PDF itself, streamed
 * into the run's file area, with its pages in X-Report-Pages. A refusal is media's usual JSON answer, whose message the
 * step reports.
 *
 * Until media-service has the endpoint, process.pipeline.media.internal-render is false and render_pdf is listed as
 * unavailable.
 */
@Component
public class HttpReportRenderer implements ReportRenderer {

    private static final MediaType NDJSON = MediaType.get("application/x-ndjson; charset=utf-8");

    private final String renderUrl;
    private final String serviceToken;
    private final boolean enabled;
    private final ObjectMapper json = new ObjectMapper();
    // A long report is minutes of layout at worst; the connection itself is local.
    private final OkHttpClient http = new OkHttpClient.Builder()
        .addInterceptor(new CorrelationInterceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build();

    public HttpReportRenderer(@Value("${media.url:http://media:9110}") String mediaUrl, @Value("${internal.service-token:}") String serviceToken,
        @Value("${process.pipeline.media.internal-render:false}") boolean enabled) {
        this.renderUrl = mediaUrl.replaceAll("/+$", "") + "/api/v1/internal/media/render/table";
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.enabled = enabled;
    }

    @Override
    public Optional<String> unavailable() {
        return this.enabled ? Optional.<String>empty()
            : Optional.of("media-service does not render reports for pipelines yet (process.pipeline.media.internal-render is off)");
    }

    @Override
    public Rendered render(long tenantId, String usageKey, ReportLines lines, OutputStream pdf) throws Exception {
        Optional<String> off = this.unavailable();
        if (off.isPresent()) {
            throw new IllegalStateException(off.get() + ".");
        }
        long length = lines.size();
        RequestBody body = new RequestBody() {
            @Override
            public MediaType contentType() {
                return NDJSON;
            }

            @Override
            public long contentLength() {
                return length;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                lines.writeTo(sink.outputStream());
            }
        };
        HttpUrl url = HttpUrl.get(this.renderUrl).newBuilder().addQueryParameter("tenantId", String.valueOf(tenantId))
            .addQueryParameter("usageKey", usageKey).build();
        Request call = new Request.Builder().url(url).header("X-Internal-Token", this.serviceToken).post(body).build();
        try (Response response = this.http.newCall(call).execute()) {
            if (response.code() != 200 || response.body() == null) {
                String text = response.body() == null ? "" : response.body().string();
                String message = "";
                try {
                    JsonNode answer = text.isEmpty() ? null : this.json.readTree(text);
                    message = answer == null ? "" : answer.path("message").asText("");
                } catch (IOException notJson) {
                    message = text.length() > 300 ? text.substring(0, 300) : text;
                }
                throw new IllegalStateException("media-service did not render the report (HTTP " + response.code() + ")"
                    + (message.isEmpty() ? "." : ": " + message));
            }
            try (InputStream in = response.body().byteStream()) {
                byte[] buffer = new byte[1 << 16];
                for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                    pdf.write(buffer, 0, n);
                }
            }
            return new Rendered(header(response, "X-Report-Pages"), header(response, "X-Report-Rows"));
        }
    }

    private static int header(Response response, String name) {
        try {
            return Integer.parseInt(response.header(name, "0").trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
