package process.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import process.model.dto.ResponseDto;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static process.util.ProcessUtil.ERROR;

/**
 * MediaPort as a call to media-service (MIG-48 part D): Media & Documents is its own service, and
 * this is the only way the rest of process reaches it.
 *
 * Every call carries two credentials. The internal token says this is process -- media-service
 * refuses /internal/media without it, and the gateway hides /internal from the outside. The signed-in
 * user's own bearer token says whose file it is: Media reads Storage as that user, as it does for the
 * console. Every caller of this port runs inside that user's request (FileChat, report export), so
 * the token is the request's own; a call outside a request is a bug and is refused before anything
 * is sent.
 *
 * The answers are turned back into what the callers already act on: text, or null for "no reader for
 * this type"; UnreadableFileException in the words the panel shows; any other failure as an exception
 * that says what failed.
 */
@Component
public class HttpMedia implements MediaPort {

    private static final Logger logger = LoggerFactory.getLogger(HttpMedia.class);
    private static final MediaType JSON = MediaType.get("application/json");
    private static final MediaType BYTES = MediaType.get("application/octet-stream");

    private final String base;
    private final String serviceToken;
    private final ObjectMapper json = new ObjectMapper();
    // Extraction can wait on a vision model page by page, or on a transcription that runs minutes.
    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build();

    @Autowired
    public HttpMedia(@Value("${media.url:http://media:9110}") String mediaUrl,
        @Value("${internal.service-token:}") String serviceToken) {
        this.base = mediaUrl.replaceAll("/+$", "") + "/api/v1/internal/media";
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    @Override
    public String extractText(String bucket, String key, String etag) throws Exception {
        return this.extract(this.target(bucket, key, etag));
    }

    @Override
    public String extractText(String bucket, String key, String etag, String visionModel, String visionInstructions)
        throws Exception {
        ObjectNode body = this.target(bucket, key, etag);
        body.put("visionModel", visionModel);
        body.put("visionInstructions", visionInstructions);
        return this.extract(body);
    }

    private String extract(ObjectNode body) throws Exception {
        try (Response response = this.send("/extractText", RequestBody.create(this.json.writeValueAsBytes(body), JSON))) {
            JsonNode answer = this.readJson(response);
            if (response.code() == 200) {
                JsonNode text = answer.path("data").path("text");
                return text.isMissingNode() || text.isNull() ? null : text.asText();
            }
            if (response.code() == 422) {
                throw new UnreadableFileException(this.messageOf(answer, response));
            }
            if (response.code() == 400) {
                throw new IllegalArgumentException(this.messageOf(answer, response));
            }
            throw new IllegalStateException(this.messageOf(answer, response));
        }
    }

    @Override
    public byte[] convertContent(byte[] content, String sourceExtension, String targetExtension) throws Exception {
        HttpUrl url = HttpUrl.get(this.base + "/convertContent").newBuilder()
            .addQueryParameter("source", sourceExtension).addQueryParameter("target", targetExtension).build();
        try (Response response = this.send(url, RequestBody.create(content, BYTES))) {
            if (response.code() == 200) {
                ResponseBody body = response.body();
                return body == null ? new byte[0] : body.bytes();
            }
            throw new IllegalStateException(this.messageOf(this.readJson(response), response));
        }
    }

    /**
     * Never fails its caller. It runs when a chat panel closes on a changed file, which has already
     * happened; an entry that could not be dropped expires on its TTL -- the same trade process made
     * when this was a local cache eviction.
     */
    @Override
    public void forgetExtraction(String bucket, String key, String etag) {
        try (Response response = this.send("/forgetExtraction",
                RequestBody.create(this.json.writeValueAsBytes(this.target(bucket, key, etag)), JSON))) {
            if (response.code() != 204 && response.code() != 200) {
                logger.warn("Media did not forget the extraction of {}/{}: answered {}", bucket, key, response.code());
            }
        } catch (Exception notForgotten) {
            logger.warn("Media did not forget the extraction of {}/{}: {}", bucket, key, notForgotten.getMessage());
        }
    }

    @Override
    public ResponseDto emailGeneratedFile(String recipientEmail, String itemName, String filename, String contentType,
        byte[] bytes, String message) throws Exception {
        MultipartBody.Builder form = new MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, RequestBody.create(bytes == null ? new byte[0] : bytes,
                MediaType.parse(contentType == null ? "application/octet-stream" : contentType)))
            .addFormDataPart("recipientEmail", recipientEmail == null ? "" : recipientEmail)
            .addFormDataPart("itemName", itemName == null ? "" : itemName);
        if (message != null) {
            form.addFormDataPart("message", message);
        }
        try (Response response = this.send("/emailGeneratedFile", form.build())) {
            JsonNode answer = this.readJson(response);
            if (answer != null && answer.hasNonNull("status")) {
                return new ResponseDto(answer.get("status").asText(), answer.path("message").asText(null));
            }
            return new ResponseDto(ERROR, "Could not send this email: Media answered " + response.code() + ".");
        }
    }

    private ObjectNode target(String bucket, String key, String etag) {
        ObjectNode body = this.json.createObjectNode();
        body.put("bucket", bucket);
        body.put("key", key);
        body.put("etag", etag);
        return body;
    }

    private Response send(String path, RequestBody body) throws IOException {
        return this.send(HttpUrl.get(this.base + path), body);
    }

    private Response send(HttpUrl url, RequestBody body) throws IOException {
        Request request = new Request.Builder().url(url)
            .header("Authorization", callerAuthorization())
            .header("X-Internal-Token", this.serviceToken)
            .post(body).build();
        try {
            return this.http.newCall(request).execute();
        } catch (IOException unreachable) {
            throw new IOException("Media could not be reached: " + unreachable.getMessage(), unreachable);
        }
    }

    /** The signed-in user's own token, from the request this call is part of. */
    private static String callerAuthorization() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        String authorization = attributes instanceof ServletRequestAttributes
            ? ((ServletRequestAttributes) attributes).getRequest().getHeader("Authorization") : null;
        if (authorization == null || authorization.trim().isEmpty()) {
            // Media reads Storage as the user; with no user there is nothing it may read as.
            throw new IllegalStateException("No signed-in caller to reach Media as.");
        }
        return authorization;
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

    private String messageOf(JsonNode answer, Response response) {
        return answer != null && answer.hasNonNull("message") ? answer.get("message").asText()
            : "Media answered " + response.code() + ".";
    }
}
