package process.media;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import process.model.dto.ResponseDto;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MediaPort over HTTP (MIG-48 part D): what FileChat and report export ask of Media now goes to
 * media-service. Every call carries two things -- process's internal token, and the signed-in
 * user's own bearer token, because Media reads Storage as that user -- and every answer comes back
 * as what the callers already act on: text or null, UnreadableFileException in the panel's words,
 * and a failure that says what failed.
 */
class HttpMediaTest {

    private static final String USER = "Bearer user-token";
    private static final String SERVICE_TOKEN = "process-to-media";

    private HttpServer media;
    private HttpMedia port;
    private final List<String> seenAuthorization = new CopyOnWriteArrayList<>();
    private final List<String> seenServiceToken = new CopyOnWriteArrayList<>();
    private final List<String> seenBodies = new CopyOnWriteArrayList<>();

    private void answer(String path, int status, String contentType, byte[] body) {
        this.media.createContext("/api/v1/internal/media/" + path, exchange -> {
            this.seenAuthorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            this.seenServiceToken.add(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            this.seenBodies.add(new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
    }

    private void answerJson(String path, int status, String json) {
        this.answer(path, status, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void start() throws Exception {
        this.media = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.media.start();
        this.port = new HttpMedia("http://127.0.0.1:" + this.media.getAddress().getPort(), SERVICE_TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", USER);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void stop() {
        RequestContextHolder.resetRequestAttributes();
        this.media.stop(0);
    }

    @Test
    void textIsAskedForAsTheUserWithProcesssToken() throws Exception {
        this.answerJson("extractText", 200, "{\"status\":\"SUCCESS\",\"message\":\"Text extracted.\",\"data\":{\"text\":\"the words\"}}");

        assertThat(this.port.extractText("b", "notes.pdf", "e1")).isEqualTo("the words");
        assertThat(this.seenAuthorization).containsExactly(USER);
        assertThat(this.seenServiceToken).containsExactly(SERVICE_TOKEN);
        assertThat(this.seenBodies.get(0)).contains("\"bucket\":\"b\"").contains("\"key\":\"notes.pdf\"").contains("\"etag\":\"e1\"")
            .doesNotContain("visionModel");
    }

    @Test
    void theVisionSettingsTravelOnlyOnTheAgentAwareCall() throws Exception {
        this.answerJson("extractText", 200, "{\"status\":\"SUCCESS\",\"data\":{\"text\":\"a table\"}}");

        assertThat(this.port.extractText("b", "scan.png", "e2", "llava:13b", "Read the table")).isEqualTo("a table");
        assertThat(this.seenBodies.get(0)).contains("\"visionModel\":\"llava:13b\"").contains("\"visionInstructions\":\"Read the table\"");
    }

    @Test
    void noReaderForTheTypeIsNull() throws Exception {
        this.answerJson("extractText", 200, "{\"status\":\"SUCCESS\",\"data\":{\"text\":null}}");

        assertThat(this.port.extractText("b", "x.bin", "e1")).isNull();
    }

    @Test
    void anUnreadableFileComesBackInThePanelsOwnWords() {
        this.answerJson("extractText", 422, "{\"status\":\"ERROR\",\"message\":\"notes.pdf is password-protected.\"}");

        assertThatThrownBy(() -> this.port.extractText("b", "notes.pdf", "e1"))
            .isInstanceOf(UnreadableFileException.class).hasMessage("notes.pdf is password-protected.");
    }

    @Test
    void anyOtherFailureSaysWhatFailed() {
        this.answerJson("extractText", 502, "{\"status\":\"ERROR\",\"message\":\"Storage could not deliver this file just now -- try again in a moment.\"}");

        assertThatThrownBy(() -> this.port.extractText("b", "notes.pdf", "e1"))
            .isNotInstanceOf(UnreadableFileException.class)
            .hasMessage("Storage could not deliver this file just now -- try again in a moment.");
    }

    @Test
    void mediaUnreachableIsAFailureNotANull() {
        HttpMedia nowhere = new HttpMedia("http://127.0.0.1:1", SERVICE_TOKEN);

        assertThatThrownBy(() -> nowhere.extractText("b", "notes.pdf", "e1")).hasMessageContaining("Media");
    }

    @Test
    void contentIsConvertedBytesInBytesOut() throws Exception {
        this.answer("convertContent", 200, "application/octet-stream", new byte[] {'P', 'K', 3, 4});

        assertThat(this.port.convertContent("a,b\n".getBytes(StandardCharsets.UTF_8), "csv", "xlsx")).containsExactly('P', 'K', 3, 4);
        assertThat(this.seenBodies.get(0)).isEqualTo("a,b\n");
        assertThat(this.seenAuthorization).containsExactly(USER);
    }

    @Test
    void aConversionThatFailedSaysWhy() {
        this.answerJson("convertContent", 500, "{\"status\":\"ERROR\",\"message\":\"LibreOffice is not running\"}");

        assertThatThrownBy(() -> this.port.convertContent(new byte[] {1}, "csv", "xlsx")).hasMessage("LibreOffice is not running");
    }

    /** Closing a chat panel has already happened; a cache that could not be dropped must not fail it. */
    @Test
    void forgettingAnExtractionNeverFailsTheCaller() {
        this.answer("forgetExtraction", 204, "application/json", new byte[0]);
        assertThatCode(() -> this.port.forgetExtraction("b", "notes.pdf", "e1")).doesNotThrowAnyException();
        assertThat(this.seenBodies.get(0)).contains("\"etag\":\"e1\"");

        HttpMedia nowhere = new HttpMedia("http://127.0.0.1:1", SERVICE_TOKEN);
        assertThatCode(() -> nowhere.forgetExtraction("b", "notes.pdf", "e1")).doesNotThrowAnyException();
    }

    @Test
    void aGeneratedFileIsEmailedAndMediasAnswerIsTheCallersAnswer() throws Exception {
        this.answerJson("emailGeneratedFile", 200, "{\"status\":\"SUCCESS\",\"message\":\"Email queued. You'll get a notice if it can't be delivered.\"}");

        ResponseDto answer = this.port.emailGeneratedFile("colleague@medaxis.example", "Q3 report", "q3.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {1, 2, 3}, "fyi");

        assertThat(answer.getStatus()).isEqualTo("SUCCESS");
        assertThat(answer.getMessage()).isEqualTo("Email queued. You'll get a notice if it can't be delivered.");
        assertThat(this.seenBodies.get(0)).contains("name=\"recipientEmail\"").contains("colleague@medaxis.example")
            .contains("filename=\"q3.xlsx\"");
    }

    @Test
    void outsideASignedInRequestNothingIsSent() {
        RequestContextHolder.resetRequestAttributes();

        assertThatThrownBy(() -> this.port.extractText("b", "notes.pdf", "e1")).isInstanceOf(IllegalStateException.class);
        assertThat(this.seenAuthorization).isEmpty();
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }
}
