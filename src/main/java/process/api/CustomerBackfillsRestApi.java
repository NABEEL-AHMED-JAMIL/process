package process.api;

import org.barco.platform.api.IdempotencyKeys;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import process.customer.CustomerBackfills;
import process.customer.CustomerResponses;

import java.util.Map;

/**
 * MIG-347: the customer API's backfills, as the gateway sends them here (/api/v1/customer/...): POST
 * /v1/pipelines/{pipelineId}/backfills, GET /v1/backfills/{backfillId}, POST /v1/backfills/{backfillId}/cancel. API clients
 * only; scopes checked again in CustomerBackfills (runs:write to start and cancel, runs:read to read).
 */
@RestController
@PreAuthorize("hasRole('API_CLIENT')")
public class CustomerBackfillsRestApi {

    private final CustomerBackfills backfills;

    public CustomerBackfillsRestApi(CustomerBackfills backfills) {
        this.backfills = backfills;
    }

    @PostMapping("/customer/pipelines/{pipelineId}/backfills")
    public ResponseEntity<Map<String, Object>> start(@PathVariable("pipelineId") String pipelineId,
        @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey, @RequestBody(required = false) byte[] body) {
        return CustomerResponses.of(this.backfills.start(pipelineId, body, idempotencyKey));
    }

    @GetMapping("/customer/backfills/{backfillId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("backfillId") String backfillId) {
        return CustomerResponses.of(this.backfills.get(backfillId));
    }

    @PostMapping("/customer/backfills/{backfillId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable("backfillId") String backfillId,
        @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        return CustomerResponses.of(this.backfills.cancel(backfillId, idempotencyKey));
    }
}
