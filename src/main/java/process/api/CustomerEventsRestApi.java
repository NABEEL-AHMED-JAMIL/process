package process.api;

import org.barco.platform.api.IdempotencyKeys;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.customer.CustomerEvents;
import process.customer.CustomerResponses;

import java.util.Map;

/**
 * POST /v1/events (MIG-332, ADR-025), as the gateway sends it (/api/v1/customer/events): an event of the organisation's
 * own type, started through the workspace's event routes. API clients only; scope events:write.
 */
@RestController
@PreAuthorize("hasRole('API_CLIENT')")
@RequestMapping("/customer/events")
public class CustomerEventsRestApi {

    private final CustomerEvents events;

    public CustomerEventsRestApi(CustomerEvents events) {
        this.events = events;
    }

    /** Body {type, data}; Idempotency-Key required. 202 {eventId, started, workflows, notStarted}. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> send(@RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
        @RequestBody(required = false) byte[] body) {
        return CustomerResponses.of(this.events.send(body, idempotencyKey));
    }
}
