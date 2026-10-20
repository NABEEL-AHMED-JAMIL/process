package process.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import process.util.FailureReference;
import process.util.ProcessUtil;

import jakarta.servlet.RequestDispatcher;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Vulnerabilities review 2026-10-13, C8: Boot's error answer never carries an exception's own text. */
class SafeErrorAttributesTest {

    private static final ErrorAttributeOptions WITH_MESSAGE = ErrorAttributeOptions.of(ErrorAttributeOptions.Include.MESSAGE);

    private static Map<String, Object> answer(Throwable error, int status, String sentMessage) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/anything");
        if (error != null) {
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, error);
        }
        if (sentMessage != null) {
            request.setAttribute(RequestDispatcher.ERROR_MESSAGE, sentMessage);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("X-Correlation-Id", "01JTESTREFERENCE0000000000");
        return new SafeErrorAttributes().getErrorAttributes(new ServletWebRequest(request, response), WITH_MESSAGE);
    }

    @Test
    void anExceptionsOwnTextIsReplacedByTheFixedSentenceAndTheReference() {
        Map<String, Object> answer = answer(new IllegalStateException("Connection refused: minio:9000 (/tmp/upload-81723.part)"), 500, null);

        assertThat(answer.get("message")).isEqualTo(ProcessUtil.INTERNAL_ERROR_500 + " Reference: 01JTESTREFERENCE0000000000.");
        assertThat(answer.toString()).doesNotContain("minio").doesNotContain("/tmp/");
    }

    @Test
    void aSentenceTheCodeChoseIsKept() {
        assertThat(answer(null, 401, "Unauthorized").get("message")).isEqualTo("Unauthorized");
        assertThat((String) answer(new ResponseStatusException(HttpStatus.NOT_FOUND, "Pipeline not found."), 404, null).get("message"))
            .as("Boot's own wording of a reason the code gave").contains("Pipeline not found.");
    }

    @Test
    void theSentenceWithoutAReferenceIsJustTheSentence() {
        assertThat(FailureReference.sentence("The export failed.", null)).isEqualTo("The export failed.");
        assertThat(FailureReference.sentence("The export failed.", "01JABC12345")).isEqualTo("The export failed. Reference: 01JABC12345.");
    }

    /** MIG-389: a path nothing serves (Spring 6.1+ raises it) is Boot 2.7's 404 answer, not an internal error. */
    @Test
    void aPathNothingServesIsBoot27sNotFound() {
        Map<String, Object> answer = answer(new NoResourceFoundException(HttpMethod.GET, "/api/v1/nope", "nope"), 404, null);

        assertThat(answer.get("message")).isEqualTo("No message available");
    }
}
