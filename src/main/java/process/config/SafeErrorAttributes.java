package process.config;

import org.barco.platform.correlation.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import process.util.FailureReference;
import process.util.ProcessUtil;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * Boot's own error answer (/error), for what never reached GlobalExceptionHandler: an exception thrown in a filter, or
 * one the container caught. server.error.include-message=always keeps the sentences the code chose -- a filter's
 * sendError(401, "Unauthorized"), a ResponseStatusException's reason -- because the console and api-check read them
 * (MIG-204). But with an exception attached that message was the exception's own text, which can name internal hosts,
 * ports and temp paths (vulnerabilities review 2026-10-13, C8). Such a message is replaced by the fixed sentence and the
 * request's correlation id; the exception is logged with that id.
 */
@Component
public class SafeErrorAttributes extends DefaultErrorAttributes {

    private static final Logger logger = LoggerFactory.getLogger(SafeErrorAttributes.class);

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(request, options);
        Throwable error = this.getError(request);
        if (error != null && !(error instanceof ResponseStatusException) && attributes.containsKey("message")) {
            String reference = reference(request);
            logger.error("Request to {} failed outside the controllers (reference {})", attributes.get("path"), reference, error);
            attributes.put("message", FailureReference.sentence(ProcessUtil.INTERNAL_ERROR_500, reference));
        }
        return attributes;
    }

    /** The request's correlation id: still current, or as CorrelationIdFilter wrote it on the response. */
    static String reference(WebRequest request) {
        String current = CorrelationId.current();
        if (current != null) {
            return current;
        }
        if (request instanceof ServletWebRequest) {
            HttpServletResponse response = ((ServletWebRequest) request).getResponse();
            return response == null ? null : response.getHeader(CorrelationId.HEADER);
        }
        return null;
    }
}
