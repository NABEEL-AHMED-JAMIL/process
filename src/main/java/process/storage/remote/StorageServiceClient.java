package process.storage.remote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import process.correlation.CorrelationInterceptor;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import process.model.dto.ObjectContentDto;
import process.storage.TrustedAccess;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * process's side of storage-service (MIG-68). Three kinds of call, three credentials:
 * - guarded (storage.json): as the signed-in user, with their own token, exactly as the console calls it;
 * - trusted (/internal/storage): the service token, a named caller, a reason and the row's workspace;
 * - directory (/internal/storage-connections): the service token, and for vending the user's token too.
 *
 * Answers keep the meaning callers already act on: a refusal is an IllegalArgumentException in
 * Storage's words, a missing object carries a FileNotFoundException (StorageNotFound.isNotFound),
 * anything else is an IllegalStateException.
 */
public class StorageServiceClient {

    private static final MediaType JSON = MediaType.get("application/json");

    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final OkHttpClient http = new OkHttpClient.Builder()
        .addInterceptor(new CorrelationInterceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build();
    private final String base;
    private final String serviceToken;

    public StorageServiceClient(String storageUrl, String serviceToken) {
        this.base = storageUrl.replaceAll("/+$", "") + "/api/v1";
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    ObjectMapper json() {
        return this.json;
    }

    // ---- guarded, as the signed-in user ---------------------------------------------------------

    public JsonNode guardedGet(String path, Map<String, String> query) {
        return this.data(this.call(new Request.Builder().url(this.url("/storage.json" + path, query))
            .header("Authorization", callerAuthorization()).header("Accept", "application/json").get().build()));
    }

    /** A streamed read: the stream is the response's and closes it. */
    public ObjectContentDto guardedStream(String path, Map<String, String> query, String range) {
        Request.Builder request = new Request.Builder().url(this.url("/storage.json" + path, query))
            .header("Authorization", callerAuthorization()).get();
        if (range != null) {
            request.header("Range", range);
        }
        Response response = this.execute(request.build());
        if (response.code() != 200 && response.code() != 206) {
            try {
                throw this.failure(response, this.readJson(response));
            } finally {
                response.close();
            }
        }
        return this.content(response);
    }

    public void guardedSend(String method, String path, Map<String, String> query, RequestBody body) {
        this.data(this.call(new Request.Builder().url(this.url("/storage.json" + path, query))
            .header("Authorization", callerAuthorization()).header("Accept", "application/json")
            .method(method, body).build()));
    }

    public void guardedUpload(String bucket, String prefix, String fileName, InputStream content, long size, String contentType) {
        RequestBody file = streamBody(content, size, contentType);
        MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("bucket", bucket).addFormDataPart("prefix", prefix == null ? "" : prefix)
            .addFormDataPart("file", fileName, file).build();
        this.data(this.call(new Request.Builder().url(this.url("/storage.json/uploadObject", null))
            .header("Authorization", callerAuthorization()).header("Accept", "application/json").post(body).build()));
    }

    RequestBody jsonBody(Object value) {
        try {
            return RequestBody.create(this.json.writeValueAsBytes(value), JSON);
        } catch (IOException unwritable) {
            throw new IllegalStateException(unwritable);
        }
    }

    // ---- trusted, as a named service caller -----------------------------------------------------

    public ObjectContentDto trustedRead(TrustedAccess access, String bucket, String key) {
        Response response = this.execute(new Request.Builder().url(this.trustedUrl(access, bucket, key))
            .header("X-Internal-Token", this.serviceToken).get().build());
        if (response.code() != 200) {
            try {
                throw this.failure(response, this.readJson(response));
            } finally {
                response.close();
            }
        }
        return this.content(response);
    }

    public void trustedUpload(TrustedAccess access, String bucket, String key, InputStream content, long size, String contentType) {
        this.call(new Request.Builder().url(this.trustedUrl(access, bucket, key)).header("X-Internal-Token", this.serviceToken)
            .put(streamBody(content, size, contentType == null ? "application/octet-stream" : contentType)).build());
    }

    private HttpUrl trustedUrl(TrustedAccess access, String bucket, String key) {
        HttpUrl.Builder url = HttpUrl.get(this.base + "/internal/storage/object").newBuilder()
            .addQueryParameter("caller", access.getCaller().name()).addQueryParameter("reason", access.getReason())
            .addQueryParameter("bucket", bucket).addQueryParameter("key", key);
        if (access.getTenantId() != null) {
            url.addQueryParameter("tenantId", String.valueOf(access.getTenantId()));
        }
        return url.build();
    }

    // ---- directory, as the service -----------------------------------------------------------

    /** The answer, or null for a 404. */
    public JsonNode directoryGet(String path, Map<String, String> query) {
        try (Response response = this.execute(new Request.Builder().url(this.url("/internal/storage-connections" + path, query))
            .header("X-Internal-Token", this.serviceToken).header("Accept", "application/json").get().build())) {
            JsonNode answer = this.readJson(response);
            if (response.code() == 404) {
                return null;
            }
            if (response.code() != 200) {
                throw this.failure(response, answer);
            }
            return answer;
        }
    }

    // ---- plumbing ------------------------------------------------------------------------------

    private HttpUrl url(String path, Map<String, String> query) {
        HttpUrl.Builder url = HttpUrl.get(this.base + path).newBuilder();
        if (query != null) {
            query.forEach((name, value) -> {
                if (value != null) {
                    url.addQueryParameter(name, value);
                }
            });
        }
        return url.build();
    }

    private Response execute(Request request) {
        try {
            return this.http.newCall(request).execute();
        } catch (IOException unreachable) {
            throw new IllegalStateException("Storage could not be reached: " + unreachable.getMessage(), unreachable);
        }
    }

    /** A JSON answer that must be a success; the parsed body. */
    private JsonNode call(Request request) {
        try (Response response = this.execute(request)) {
            JsonNode answer = this.readJson(response);
            if (response.code() != 200) {
                throw this.failure(response, answer);
            }
            return answer;
        }
    }

    /** The data of a ResponseDto envelope, or the envelope's own ERROR as a refusal. */
    private JsonNode data(JsonNode envelope) {
        if (envelope != null && "ERROR".equals(envelope.path("status").asText())) {
            throw new IllegalArgumentException(envelope.path("message").asText());
        }
        return envelope == null ? null : envelope.get("data");
    }

    private RuntimeException failure(Response response, JsonNode answer) {
        String message = answer != null && answer.hasNonNull("message") ? answer.get("message").asText()
            : "Storage answered " + response.code() + ".";
        if (response.code() == 404) {
            return new IllegalStateException(message, new FileNotFoundException(message));
        }
        if (response.code() == 400) {
            return new IllegalArgumentException(message);
        }
        return new IllegalStateException(message);
    }

    private ObjectContentDto content(Response response) {
        ResponseBody body = response.body();
        String contentType = response.header("Content-Type", "application/octet-stream");
        long size = body == null ? 0 : body.contentLength();
        long total = size;
        String range = response.header("Content-Range");
        if (range != null && range.contains("/")) {
            try {
                total = Long.parseLong(range.substring(range.lastIndexOf('/') + 1).trim());
            } catch (NumberFormatException unreadable) {
                total = size;
            }
        }
        String fileName = fileNameOf(response.header("Content-Disposition"));
        InputStream stream = body == null ? new ByteArrayInputStream(new byte[0]) : body.byteStream();
        return new ObjectContentDto(stream, contentType, size, total, fileName);
    }

    private static String fileNameOf(String disposition) {
        if (disposition == null) {
            return null;
        }
        int at = disposition.indexOf("filename*=UTF-8''");
        if (at < 0) {
            return null;
        }
        try {
            return URLDecoder.decode(disposition.substring(at + "filename*=UTF-8''".length()).replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private JsonNode readJson(Response response) {
        try {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            return text.isEmpty() ? null : this.json.readTree(text);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** A body read once from the caller's stream, never buffered whole. */
    private static RequestBody streamBody(InputStream content, long size, String contentType) {
        MediaType type = MediaType.parse(contentType == null ? "application/octet-stream" : contentType);
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return type;
            }

            @Override
            public long contentLength() {
                return size;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                try (Source source = Okio.source(content)) {
                    sink.writeAll(source);
                } catch (UncheckedIOException wrapped) {
                    throw wrapped.getCause();
                }
            }
        };
    }

    /** The signed-in user's own token, from the request this call is part of. */
    static String callerAuthorization() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        String authorization = attributes instanceof ServletRequestAttributes
            ? ((ServletRequestAttributes) attributes).getRequest().getHeader("Authorization") : null;
        if (authorization == null || authorization.trim().isEmpty()) {
            throw new IllegalStateException("No signed-in caller to reach Storage as.");
        }
        return authorization;
    }
}
