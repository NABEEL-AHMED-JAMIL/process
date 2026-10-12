package process.customer;

import org.barco.platform.api.IdempotencyKeys;
import org.barco.platform.api.Problem;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** A customer API answer as HTTP (MIG-332): JSON or application/problem+json, Location, Idempotent-Replayed. */
public final class CustomerResponses {

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
}
