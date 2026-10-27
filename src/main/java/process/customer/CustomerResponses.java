package process.customer;

import org.barco.platform.api.IdempotencyKeys;
import org.barco.platform.api.Problem;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A customer API answer as HTTP (MIG-332): JSON or application/problem+json, Location, Idempotent-Replayed. MIG-426: or a
 * run's result as a document -- its own type, an attachment by name, never cached, never sniffed, an HTML one in a sandbox
 * of its own -- with the headers that say what it is made from.
 */
public final class CustomerResponses {

    /** An HTML result is a document to keep, not a page of the API's: nothing in it runs or loads from anywhere. */
    static final String HTML_POLICY = "default-src 'none'; img-src data:; font-src data:; style-src 'unsafe-inline'; sandbox";

    private CustomerResponses() {
    }

    public static ResponseEntity<Map<String, Object>> of(CustomerAnswer answer) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.valueOf(answer.status))
            .contentType(answer.isProblem() ? MediaType.parseMediaType(Problem.MEDIA_TYPE) : MediaType.APPLICATION_JSON);
        if (answer.location != null) {
            response.header(HttpHeaders.LOCATION, answer.location);
        }
        if (answer.replayed) {
            response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
        }
        return response.body(answer.body);
    }

    /** MIG-426: an answer that may be a document (the run's result as pdf, html or md) or JSON as {@link #of}. */
    public static ResponseEntity<?> ofResult(CustomerAnswer answer) {
        if (answer.document == null) {
            return of(answer);
        }
        ResultDocument d = answer.document;
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.OK).contentType(MediaType.parseMediaType(d.contentType))
            .contentLength(d.bytes.length)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName, StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Content-Type-Options", "nosniff");
        if (d.contentType.startsWith("text/html")) {
            response.header("Content-Security-Policy", HTML_POLICY);
        }
        d.headers.forEach(response::header);
        if (answer.location != null) {
            response.header(HttpHeaders.LOCATION, answer.location);
        }
        if (answer.replayed) {
            response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
        }
        return response.body(d.bytes);
    }
}
