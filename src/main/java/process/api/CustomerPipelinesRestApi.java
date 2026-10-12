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
import process.customer.CustomerPipelines;
import process.customer.CustomerResponses;

import java.util.Map;

/**
 * The customer API's pipelines and run starts (MIG-332, ADR-025), as the gateway sends /v1/pipelines here
 * (/api/v1/customer/pipelines). API clients only: JwtAuthenticationFilter admits nothing else under /customer, and each
 * call checks its scope again. Answers are JSON or RFC 9457 problems; never the console's envelope.
 */
@RestController
@PreAuthorize("hasRole('API_CLIENT')")
@RequestMapping("/customer/pipelines")
public class CustomerPipelinesRestApi {

    private final CustomerPipelines pipelines;

    public CustomerPipelinesRestApi(CustomerPipelines pipelines) {
        this.pipelines = pipelines;
    }

    /** ?limit= (50, at most 200) &cursor=: the workspace's pipelines, newest first. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "cursor", required = false) String cursor) {
        return CustomerResponses.of(this.pipelines.list(limit, cursor));
    }

    /** One pipeline, with its input contract. */
    @GetMapping("/{pipelineId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("pipelineId") String pipelineId) {
        return CustomerResponses.of(this.pipelines.get(pipelineId));
    }

    /** Body {record, files, reference}; Idempotency-Key required. 202 with the Run and Location: /v1/runs/{id}. */
    @PostMapping("/{pipelineId}/runs")
    public ResponseEntity<Map<String, Object>> startRun(@PathVariable("pipelineId") String pipelineId,
        @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
        @RequestBody(required = false) byte[] body) {
        return CustomerResponses.of(this.pipelines.startRun(pipelineId, body, idempotencyKey));
    }
}
