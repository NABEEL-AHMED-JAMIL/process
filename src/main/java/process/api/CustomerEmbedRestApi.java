package process.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.customer.CustomerResponses;
import process.customer.CustomerRunViews;

import java.util.Map;

/**
 * The embeddable run view's reads (MIG-335, ADR-025 decision 10): GET /v1/embed/runs/{token} and its /frame, as the gateway
 * sends them here with no token and none of its own headers (the route table's "signed" lines). No caller: the view
 * link's signature is the permission, for that one run, read-only, until it expires (ViewLinks). SecurityConfig and
 * JwtAuthenticationFilter let these two paths past the API client check.
 *
 * Any origin may read them (the console's page does, from its own origin; a portal's server may too): the token in the path
 * is the only key, nothing rides on a cookie, and what is answered is what the page shows. Never cached.
 */
@RestController
@CrossOrigin(origins = "*", allowCredentials = "false")
@RequestMapping("/customer/embed/runs")
public class CustomerEmbedRestApi {

    /** The frame check's answer header: the CSP frame-ancestors source list the console's server sends with the page. */
    public static final String FRAME_ANCESTORS = "Embed-Frame-Ancestors";

    private final CustomerRunViews views;

    public CustomerEmbedRestApi(CustomerRunViews views) {
        this.views = views;
    }

    @GetMapping("/{token}")
    public ResponseEntity<Map<String, Object>> view(@PathVariable("token") String token) {
        ResponseEntity<Map<String, Object>> answer = CustomerResponses.of(this.views.view(token));
        return ResponseEntity.status(answer.getStatusCode()).headers(answer.getHeaders()).cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer").body(answer.getBody());
    }

    @GetMapping("/{token}/frame")
    public ResponseEntity<Map<String, Object>> frame(@PathVariable("token") String token) {
        CustomerRunViews.Frame frame = this.views.frame(token);
        ResponseEntity<Map<String, Object>> answer = CustomerResponses.of(frame.answer);
        return ResponseEntity.status(answer.getStatusCode()).headers(answer.getHeaders()).cacheControl(CacheControl.noStore())
            .header(FRAME_ANCESTORS, frame.ancestors).body(answer.getBody());
    }
}
