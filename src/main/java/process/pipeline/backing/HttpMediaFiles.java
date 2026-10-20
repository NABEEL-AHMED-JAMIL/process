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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * media-service's pipeline files (/api/v1/internal/media/pipelineFiles) with the service token alone, as HttpReportStudio
 * calls Report Studio: transcribe (the recording as the body, the transcript back as JSON) and convert (the file as the
 * body, the converted file back as the body, with X-Output-Name and X-Pages). On whenever media takes pipeline files
 * (process.pipeline.media.internal-files, by default what internal-render says). A refusal names media's message.
 */
@Component
public class HttpMediaFiles implements MediaFiles {

    private static final MediaType OCTETS = MediaType.get("application/octet-stream");
    /** media waits up to 30 minutes on the transcriber; this side waits a little longer. */
    static final long TRANSCRIBE_TIMEOUT_MINUTES = 35;

    private final String base;
    private final String serviceToken;
    private final boolean enabled;
    private final ObjectMapper json = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
        .addInterceptor(new CorrelationInterceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(TRANSCRIBE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build();

    public HttpMediaFiles(@Value("${media.url:http://media:9110}") String mediaUrl, @Value("${internal.service-token:}") String serviceToken,
        @Value("${process.pipeline.media.internal-files:${process.pipeline.media.internal-render:false}}") boolean enabled) {
        this.base = mediaUrl.replaceAll("/+$", "") + "/api/v1/internal/media/pipelineFiles";
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.enabled = enabled;
    }

    @Override
    public Optional<String> unavailable() {
        return this.enabled ? Optional.<String>empty()
            : Optional.of("media-service takes no pipeline files yet (process.pipeline.media.internal-files is off)");
    }

    private HttpUrl.Builder url(String path, long tenantId, String usageKey, String fileName) {
        return HttpUrl.get(this.base + path).newBuilder().addQueryParameter("tenantId", String.valueOf(tenantId))
            .addQueryParameter("usageKey", usageKey).addQueryParameter("fileName", fileName);
    }

    @Override
    public Transcript transcribe(long tenantId, String usageKey, Path file, String fileName, boolean timestamps) throws IOException {
        HttpUrl url = this.url("/transcribe", tenantId, usageKey, fileName).addQueryParameter("timestamps", String.valueOf(timestamps)).build();
        Request call = new Request.Builder().url(url).header("X-Internal-Token", this.serviceToken)
            .post(RequestBody.create(file.toFile(), OCTETS)).build();
        try (Response response = this.http.newCall(call).execute()) {
            if (response.code() != 200 || response.body() == null) {
                throw this.refusal(response, "transcribe the recording");
            }
            JsonNode data = this.json.readTree(response.body().string()).path("data");
            Transcript out = new Transcript();
            out.text = data.path("transcript").asText("");
            out.durationSeconds = data.path("durationSeconds").isNumber() ? data.path("durationSeconds").asDouble() : null;
            out.language = data.path("language").isTextual() ? data.path("language").asText() : null;
            out.model = data.path("model").isTextual() ? data.path("model").asText() : null;
            if (data.path("segments").isArray()) {
                out.segments = new ArrayList<>();
                for (JsonNode s : data.path("segments")) {
                    Map<String, Object> segment = new LinkedHashMap<>();
                    segment.put("startMs", s.path("startMs").asLong());
                    segment.put("endMs", s.path("endMs").asLong());
                    segment.put("text", s.path("text").asText(""));
                    out.segments.add(segment);
                }
            }
            return out;
        }
    }

    @Override
    public Converted convert(long tenantId, String usageKey, Path file, String fileName, String outputFormat, Path out) throws IOException {
        HttpUrl url = this.url("/convert", tenantId, usageKey, fileName).addQueryParameter("outputFormat", outputFormat).build();
        Request call = new Request.Builder().url(url).header("X-Internal-Token", this.serviceToken)
            .post(RequestBody.create(file.toFile(), OCTETS)).build();
        OkHttpClient shorter = this.http.newBuilder().readTimeout(10, TimeUnit.MINUTES).build();
        try (Response response = shorter.newCall(call).execute()) {
            if (response.code() != 200 || response.body() == null) {
                throw this.refusal(response, "convert the file");
            }
            try (InputStream in = response.body().byteStream()) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
            String pages = response.header("X-Pages");
            String name = response.header("X-Output-Name");
            return new Converted(name == null || name.isBlank() ? null : name, pages == null ? null : Integer.valueOf(pages.trim()), Files.size(out));
        }
    }

    /** media's own sentence and status; never a body of data. */
    private Refused refusal(Response response, String what) {
        String message = "";
        try {
            String text = response.body() == null ? "" : response.body().string();
            JsonNode answer = text.isEmpty() ? null : this.json.readTree(text);
            if (answer != null) {
                message = answer.path("message").asText("");
            }
        } catch (IOException notJson) {
            message = "";
        }
        int retryAfter = 30;
        try {
            String header = response.header("Retry-After");
            if (header != null) {
                retryAfter = Math.max(1, Math.min(600, Integer.parseInt(header.trim())));
            }
        } catch (NumberFormatException notSeconds) {
            retryAfter = 30;
        }
        return new Refused(response.code(), message.isEmpty() ? "media-service could not " + what + " (HTTP " + response.code() + ")." : message,
            retryAfter);
    }
}
