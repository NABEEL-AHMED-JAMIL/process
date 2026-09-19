package process.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import process.analytics.AnalyticsException;
import process.model.dto.ResponseDto;
import process.util.ProcessUtil;

/**
 * @author Nabeel Ahmed
 * */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ResponseDto> handleAccessDenied(AccessDeniedException ex) {
        logger.warn("Access denied: {}", ex.getMessage());
        return new ResponseEntity<>(
            new ResponseDto(ProcessUtil.ERROR_MESSAGE, "You don't have permission to perform this action."),
            HttpStatus.FORBIDDEN);
    }

    /**
     * A business refusal from the analytics module, wherever one escapes a controller.
     *
     * The module's contract is that a refusal a person can act on -- the feature is switched off,
     * the dataset is not yours, that statement will not be admitted -- is an HTTP 200 carrying
     * status ERROR and the sentence whoever threw it wrote. Every analytics endpoint catches
     * AnalyticsException itself and returns exactly that; 24 of the 32 did not, and because the
     * exception is CHECKED and extends Exception it fell into their generic catch and became a
     * 500 with INTERNAL_ERROR_500. Switching analytics off therefore looked to a user like a
     * crash and to the operator like a stack trace at ERROR for a state they had just chosen.
     *
     * Those 24 now catch it locally, which is where the sentence belongs. This exists so the
     * twenty-fifth endpoint -- the one written next year by somebody who copies a method and
     * forgets -- degrades to the right answer instead of the wrong one. It logs at WARN and not
     * ERROR because reaching here is a gap in a controller, not a failure of the request.
     */
    @ExceptionHandler(AnalyticsException.class)
    public ResponseEntity<ResponseDto> handleAnalyticsRefusal(AnalyticsException ex) {
        logger.warn("An analytics refusal reached GlobalExceptionHandler, so some endpoint is "
            + "missing its own catch: {}", ex.getMessage());
        return new ResponseEntity<>(
            new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.OK);
    }

    /** ?invoiceId=abc for a Long: the client's mistake, said plainly, without the converter's own sentence. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ResponseDto> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        logger.warn("Request value '{}' has the wrong type for {}", ex.getName(), ex.getRequiredType() == null ? "?" : ex.getRequiredType().getSimpleName());
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, "The value of '" + ex.getName() + "' is not valid."), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseDto> handleUncaught(Exception ex) {
        logger.error("Unhandled exception reached GlobalExceptionHandler", ex);
        return new ResponseEntity<>(
            new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500),
            HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
        HttpHeaders headers, HttpStatus status, WebRequest request) {
        logger.warn("Request handling exception resolved to {}: {}", status, ex.getMessage());
        // A 4xx sentence names what the client got wrong (a missing parameter, an unreadable
        // body); a 5xx sentence is the framework's own and stays in the log.
        String message = status.is5xxServerError() ? ProcessUtil.INTERNAL_ERROR_500 : ex.getMessage();
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, message), headers, status);
    }

}
