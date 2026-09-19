package process.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import process.model.dto.ResponseDto;
import process.util.ProcessUtil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** What a client is told when a request is malformed: what to fix, never the framework's own sentence. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void aWrongTypedParameterIsA400ThatNamesTheParameterOnly() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException("abc", Long.class, "invoiceId", null, new NumberFormatException("For input string: \"abc\""));
        ResponseEntity<ResponseDto> answer = this.handler.handleTypeMismatch(ex);
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(answer.getBody().getMessage()).isEqualTo("The value of 'invoiceId' is not valid.");
        assertThat(answer.getBody().getMessage()).doesNotContain("java.lang");
    }

    @Test
    void aServerSideFailureNeverRepeatsItsMessage() {
        ResponseEntity<Object> answer = this.handler.handleExceptionInternal(new IllegalStateException("org.hibernate: could not execute statement"),
            null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, mock(WebRequest.class));
        assertThat(((ResponseDto) answer.getBody()).getMessage()).isEqualTo(ProcessUtil.INTERNAL_ERROR_500);
        ResponseEntity<Object> client = this.handler.handleExceptionInternal(new HttpMediaTypeNotSupportedException("text/plain not supported"),
            null, new HttpHeaders(), HttpStatus.UNSUPPORTED_MEDIA_TYPE, mock(WebRequest.class));
        assertThat(((ResponseDto) client.getBody()).getMessage()).contains("not supported");
    }
}
