package process.api;

import org.barco.platform.api.IdempotencyKeys;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.customer.CustomerResponses;
import process.customer.CustomerReviews;
import process.customer.CustomerRunViews;
import process.customer.CustomerRuns;

import java.util.Map;

/**
 * The customer API's runs, steps, outputs and review (MIG-334, ADR-025), as the gateway sends /v1/runs here
 * (/api/v1/customer/runs). API clients only: JwtAuthenticationFilter admits nothing else under /customer, and each call
 * checks its scope again -- runs:read to read, reviews:write to decide. Answers are JSON or RFC 9457 problems.
 */
@RestController
@PreAuthorize("hasRole('API_CLIENT')")
@RequestMapping("/customer/runs")
public class CustomerRunsRestApi {

    private final CustomerRuns runs;
    private final CustomerReviews reviews;
    private final CustomerRunViews views;

    public CustomerRunsRestApi(CustomerRuns runs, CustomerReviews reviews, CustomerRunViews views) {
        this.runs = runs;
        this.reviews = reviews;
        this.views = views;
    }

    /** ?limit= &cursor= &pipelineId= &status= &createdAfter= &updatedAfter=: the workspace's runs, newest first. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "cursor", required = false) String cursor, @RequestParam(value = "pipelineId", required = false) String pipelineId,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "createdAfter", required = false) String createdAfter,
        @RequestParam(value = "updatedAfter", required = false) String updatedAfter) {
        return CustomerResponses.of(this.runs.list(limit, cursor, pipelineId, status, createdAfter, updatedAfter));
    }

    @GetMapping("/{runId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("runId") String runId) {
        return CustomerResponses.of(this.runs.get(runId));
    }

    @GetMapping("/{runId}/steps")
    public ResponseEntity<Map<String, Object>> steps(@PathVariable("runId") String runId) {
        return CustomerResponses.of(this.runs.steps(runId));
    }

    @GetMapping("/{runId}/outputs")
    public ResponseEntity<Map<String, Object>> outputs(@PathVariable("runId") String runId) {
        return CustomerResponses.of(this.runs.outputs(runId));
    }

    @GetMapping("/{runId}/review")
    public ResponseEntity<Map<String, Object>> review(@PathVariable("runId") String runId) {
        return CustomerResponses.of(this.reviews.get(runId));
    }

    /** Body {decision, reason, comment, rerun}; Idempotency-Key required. 200 with the Review. */
    @PostMapping("/{runId}/review")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable("runId") String runId,
        @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
        @RequestBody(required = false) byte[] body) {
        return CustomerResponses.of(this.reviews.decide(runId, body, idempotencyKey));
    }

    /**
     * MIG-335: body {expiresInSeconds?} (60..900, default 900); 201 {url, expiresAt}, a signed link to the run's read-only
     * page for a portal to show or frame. runs:read. Nothing is stored, so no Idempotency-Key: each call is a new link.
     */
    @PostMapping("/{runId}/view-links")
    public ResponseEntity<Map<String, Object>> viewLink(@PathVariable("runId") String runId, @RequestBody(required = false) byte[] body) {
        return CustomerResponses.of(this.views.create(runId, body));
    }
}
