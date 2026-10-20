package process.pipeline.backing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import process.correlation.CorrelationInterceptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * media-service's Report Studio for pipelines (/api/v1/internal/media/reportStudio), with the service token alone, as
 * HttpReportRenderer calls the render: generate (the rows as NDJSON), job, file, templates. On whenever media renders for
 * pipelines at all (process.pipeline.media.internal-render). A refusal names media's code and message.
 */
@Component
public class HttpReportStudio implements ReportStudio {

    private static final MediaType NDJSON = MediaType.get("application/x-ndjson; charset=utf-8");

    private final String base;
    private final String serviceToken;
    private final boolean enabled;
    private final ObjectMapper json = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
        .addInterceptor(new CorrelationInterceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build();

    public HttpReportStudio(@Value("${media.url:http://media:9110}") String mediaUrl, @Value("${internal.service-token:}") String serviceToken,
        @Value("${process.pipeline.media.internal-render:false}") boolean enabled) {
        this.base = mediaUrl.replaceAll("/+$", "") + "/api/v1/internal/media/reportStudio";
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.enabled = enabled;
    }

    @Override
    public Optional<String> unavailable() {
        return this.enabled ? Optional.<String>empty()
            : Optional.of("media-service does not make reports for pipelines yet (process.pipeline.media.internal-render is off)");
    }

    @Override
    public JsonNode generate(long tenantId, Long userId, String userName, String idempotencyKey, Path lines) throws IOException {
        HttpUrl.Builder url = HttpUrl.get(this.base + "/generate").newBuilder().addQueryParameter("tenantId", String.valueOf(tenantId));
        if (userId != null) {
            url.addQueryParameter("userId", String.valueOf(userId));
        }
        if (userName != null) {
            url.addQueryParameter("userName", userName);
        }
        Request call = new Request.Builder().url(url.build()).header("X-Internal-Token", this.serviceToken).header("Idempotency-Key", idempotencyKey)
            .post(RequestBody.create(lines.toFile(), NDJSON)).build();
        return this.data(call, "queue the report");
    }

    @Override
    public JsonNode job(long tenantId, String jobId) throws IOException {
        HttpUrl url = HttpUrl.get(this.base + "/job").newBuilder().addQueryParameter("tenantId", String.valueOf(tenantId))
            .addQueryParameter("jobId", jobId).build();
        return this.data(new Request.Builder().url(url).header("X-Internal-Token", this.serviceToken).get().build(), "follow the report's job");
    }

    @Override
    public void file(long tenantId, String reportId, int version, String format, OutputStream out) throws IOException {
        HttpUrl url = HttpUrl.get(this.base + "/file").newBuilder().addQueryParameter("tenantId", String.valueOf(tenantId))
            .addQueryParameter("reportId", reportId).addQueryParameter("version", String.valueOf(version)).addQueryParameter("format", format).build();
        Request call = new Request.Builder().url(url).header("X-Internal-Token", this.serviceToken).get().build();
        try (Response response = this.http.newCall(call).execute()) {
            if (response.code() != 200 || response.body() == null) {
                throw new IOException(this.refusal(response, "read the report's file"));
            }
            try (InputStream in = response.body().byteStream()) {
                in.transferTo(out);
            }
        }
    }

    @Override
    public JsonNode check(long tenantId, Collection<String> ids, String version, Collection<String> columns) throws IOException {
        HttpUrl.Builder url = HttpUrl.get(this.base + "/templates").newBuilder().addQueryParameter("tenantId", String.valueOf(tenantId))
            .addQueryParameter("ids", String.join(",", ids));
        if (version != null) {
            url.addQueryParameter("version", version);
        }
        if (columns != null) {
            url.addQueryParameter("columns", String.join(",", columns));
        }
        OkHttpClient quick = this.http.newBuilder().readTimeout(10, TimeUnit.SECONDS).build();
        Request call = new Request.Builder().url(url.build()).header("X-Internal-Token", this.serviceToken).get().build();
        try (Response response = quick.newCall(call).execute()) {
            if (response.code() != 200 || response.body() == null) {
                throw new IOException(this.refusal(response, "check the report design"));
            }
            return this.json.readTree(response.body().string()).path("data");
        }
    }

    private JsonNode data(Request call, String what) throws IOException {
        try (Response response = this.http.newCall(call).execute()) {
            if (response.code() != 200 || response.body() == null) {
                throw new IOException(this.refusal(response, what));
            }
            return this.json.readTree(response.body().string()).path("data");
        }
    }

    /** "media-service could not <what> (HTTP n, CODE): message" -- media's own words, never a body of data. */
    private String refusal(Response response, String what) {
        String code = "";
        String message = "";
        try {
            String text = response.body() == null ? "" : response.body().string();
            JsonNode answer = text.isEmpty() ? null : this.json.readTree(text);
            if (answer != null) {
                code = answer.path("code").asText("");
                message = answer.path("message").asText("");
            }
        } catch (IOException notJson) {
            message = "";
        }
        return "media-service could not " + what + " (HTTP " + response.code() + (code.isEmpty() ? "" : ", " + code) + ")"
            + (message.isEmpty() ? "." : ": " + message);
    }
}
