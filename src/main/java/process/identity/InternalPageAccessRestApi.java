package process.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.security.PageGate;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /internal/pageAccess/check: the page gate for the services that left process (MIG-192). The
 * gateway forwards the caller's own bearer token with the service token; process's JWT filter has
 * verified that token and set TenantContext before this runs, so the answer is about the real caller.
 * Answers {allowed, message} with the interceptor's own sentence, for the gateway to return as 403.
 */
@RestController
@RequestMapping("/internal/pageAccess")
public class InternalPageAccessRestApi {

    private final Logger logger = LoggerFactory.getLogger(InternalPageAccessRestApi.class);
    private final PageGate gate;
    private final byte[] token;

    public InternalPageAccessRestApi(PageGate gate, @Value("${internal.service-token:}") String token) {
        this.gate = gate;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** POST, as every /internal lookup is: SecurityConfig admits /internal/** for POST only. Body: {path}. */
    @PostMapping(value = "/check", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> check(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody Map<String, Object> body) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        Object path = body == null ? null : body.get("path");
        PageGate.Decision decision = this.gate.decideFresh(TenantContext.getUserRole(), TenantContext.getAppUserId(),
            path == null ? null : path.toString());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("allowed", decision.isAllowed());
        answer.put("message", decision.getMessage());
        return new ResponseEntity<>(answer, HttpStatus.OK);
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            this.logger.warn("Refused a page access check without the internal token.");
        }
        return ok;
    }
}
