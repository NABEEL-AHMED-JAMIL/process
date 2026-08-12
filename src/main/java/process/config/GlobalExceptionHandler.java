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
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import process.model.dto.ResponseDto;
import process.util.ProcessUtil;

/**
 * Safety net for whatever isn't already handled by a controller's own try/catch -- every one of
 * the ~18 REST controllers hand-rolls a generic "catch (Exception ex) { log; return
 * ResponseDto(ERROR_MESSAGE, INTERNAL_ERROR_500) }" block already, so this deliberately does NOT
 * replace those (same response body shape, no behavior change for anything already caught) --
 * it only catches an exception that manages to escape a controller method entirely.
 *
 * Extends ResponseEntityExceptionHandler (not just @ExceptionHandler(Exception.class) on a plain
 * class) specifically to avoid a regression an earlier version of this class had: Spring MVC's
 * own well-known exceptions -- wrong HTTP method (HttpRequestMethodNotSupportedException, should
 * be 405), a missing/misnamed required @RequestParam (MissingServletRequestParameterException,
 * should be 400), unsupported content type, unreadable/malformed JSON body, etc. -- are normally
 * resolved to their correct status by Spring's own DefaultHandlerExceptionResolver *only when
 * nothing else claims them first*. A bare "@ExceptionHandler(Exception.class)" on any
 * @ControllerAdvice bean DOES claim them first (Exception is a valid, if weak, supertype match),
 * which silently downgraded every one of those into a generic 500 the moment this class existed
 * -- confirmed live via an end-to-end API scan (GET on a POST-only endpoint returned 500 instead
 * of 405; a request missing a required param returned 500 instead of 400). Extending
 * ResponseEntityExceptionHandler instead means Spring resolves those ~15 known exception types
 * through its own inherited, exact-type-matched handler (which always wins priority over this
 * class's own generic Exception.class handler below, by Spring's most-specific-match rule) --
 * this class only overrides how their response BODY is shaped, not their status code, and
 * handleUncaught below is reached only for genuinely unexpected exceptions.
 *
 * Also explicitly handles AccessDeniedException (thrown by @PreAuthorize method-security checks,
 * e.g. a TENANT_USER hitting a TENANT_ADMIN-only controller like SettingRestApi) -- this is a
 * Spring SECURITY exception, a completely different hierarchy from the Spring MVC ones
 * ResponseEntityExceptionHandler covers above, so extending that class did NOT fix this one.
 * Found live: a TENANT_USER calling /setting.json/appSetting or /setting.json/updateLookupData
 * got back a generic 500 ("Some internal error occurred") instead of a clear 403 -- indistinguishable
 * from a real server bug, which is exactly what led to this being reported as a tenant-specific
 * data bug ("only default tenant users can update") when it was actually a role check whose
 * failure this class was mis-presenting as a crash, for ANY tenant's non-admin user.
 * @author Nabeel Ahmed
 */
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseDto> handleUncaught(Exception ex) {
        logger.error("Unhandled exception reached GlobalExceptionHandler", ex);
        return new ResponseEntity<>(
            new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500),
            HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /** Shapes the body for every exception ResponseEntityExceptionHandler already knows how to
     * map to a specific status -- status itself is untouched (still whatever Spring computed:
     * 405/400/415/404/etc.), only the JSON shape changes to match this app's ResponseDto
     * convention instead of Spring Boot's default error body. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
        HttpHeaders headers, HttpStatus status, WebRequest request) {
        logger.warn("Request handling exception resolved to {}: {}", status, ex.getMessage());
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), headers, status);
    }

}
