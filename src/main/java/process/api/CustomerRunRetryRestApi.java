package process.api;

import org.barco.platform.api.IdempotencyKeys;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.customer.CustomerResponses;
import process.customer.CustomerRetries;

import java.util.Map;

/**
 * MIG-345: POST /v1/runs/{runId}/retry, as the gateway sends it here (/api/v1/customer/runs/{runId}/retry). API clients
 * only, scope runs:write (checked again in CustomerRetries); Idempotency-Key required. 202 with the Run and Location, 409
 * problem+json with the reason when the run is not retryable.
 */
@RestController
@PreAuthorize("hasRole('API_CLIENT')")
@RequestMapping("/customer/runs")
public class CustomerRunRetryRestApi {

    private final CustomerRetries retries;

    public CustomerRunRetryRestApi(CustomerRetries retries) {
        this.retries = retries;
    }

    /** Body optional: {"from": "failed_step" | "start"}; failed_step when none. */
    @PostMapping("/{runId}/retry")
    public ResponseEntity<Map<String, Object>> retry(@PathVariable("runId") String runId,
        @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
        @RequestBody(required = false) byte[] body) {
        return CustomerResponses.of(this.retries.retry(runId, body, idempotencyKey));
    }
}
