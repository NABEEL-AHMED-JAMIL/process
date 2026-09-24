package process.config;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import process.model.dto.ResponseDto;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import process.util.ProcessUtil;
import process.util.RequestRefused;

/**
 * @author Nabeel Ahmed
 * */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** A refusal the person can act on: 200 + ERROR + its own sentence, never a 500 (ADR-023, MIG-103). */
    @ExceptionHandler(RequestRefused.class)
    public ResponseEntity<ResponseDto> handleRefusal(RequestRefused refused) {
        logger.info("Request refused: {}", refused.getMessage());
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, refused.getMessage()), HttpStatus.OK);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ResponseDto> handleAccessDenied(AccessDeniedException ex) {
        logger.warn("Access denied: {}", ex.getMessage());
        return new ResponseEntity<>(
            new ResponseDto(ProcessUtil.ERROR_MESSAGE, "You don't have permission to perform this action."),
            HttpStatus.FORBIDDEN);
    }

    /** ?invoiceId=abc for a Long: the client's mistake, said plainly, without the converter's own sentence. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ResponseDto> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        logger.warn("Request value '{}' has the wrong type for {}", ex.getName(), ex.getRequiredType() == null ? "?" : ex.getRequiredType().getSimpleName());
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, "The value of '" + ex.getName() + "' is not valid."), HttpStatus.BAD_REQUEST);
    }

    /** A write that lost a race to one of Core's unique rules is refused in words (MIG-71, RaceRefusals). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ResponseDto> handleIntegrityViolation(DataIntegrityViolationException ex) {
        Optional<ResponseDto> refused = RaceRefusals.refusalFor(ex);
        if (refused.isPresent()) {
            logger.warn("A concurrent write lost and was refused: {}", refused.get().getMessage());
            return new ResponseEntity<>(refused.get(), HttpStatus.CONFLICT);
        }
        return this.handleUncaught(ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseDto> handleUncaught(Exception ex) {
        logger.error("Unhandled exception reached GlobalExceptionHandler", ex);
        return new ResponseEntity<>(
            new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500),
            HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * A body that does not parse: name what was wrong in plain words -- "COUNT" is not one of the
     * aggregations -- never the parser's class names and stream offsets.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
        HttpHeaders headers, HttpStatus status, WebRequest request) {
        String message = "The request could not be read.";
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof InvalidFormatException) {
            InvalidFormatException bad = (InvalidFormatException) cause;
            String field = bad.getPath().isEmpty() ? "a value" : bad.getPath().get(bad.getPath().size() - 1).getFieldName();
            String allowed = bad.getTargetType() != null && bad.getTargetType().isEnum()
                ? " Allowed: " + Arrays.stream(bad.getTargetType().getEnumConstants()).map(String::valueOf).collect(Collectors.joining(", ")) + "."
                : "";
            message = "'" + bad.getValue() + "' is not valid for " + field + "." + allowed;
        }
        logger.warn("Unreadable request body: {}", cause == null ? ex.getMessage() : cause.getMessage());
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, message), headers, status);
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
