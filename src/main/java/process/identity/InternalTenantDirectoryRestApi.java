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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * /internal/tenants/resolve (MIG-192): what a workspace id is called, for the services that show a
 * workspace they do not own -- the tenants counterpart of /internal/users/resolve. A batch read of
 * three columns, tenantId, tenantName and status. A deleted workspace still answers, with its status,
 * because saved work and history keep pointing at it. POST rather than the GET the contract first
 * named, as every /internal lookup is: SecurityConfig admits /internal/** for POST only.
 */
@RestController
@RequestMapping("/internal/tenants")
public class InternalTenantDirectoryRestApi {

    /** A batch, not an export: enough for any page of work, and a bound on one request's query. */
    static final int MAX_IDS = 500;

    private final Logger logger = LoggerFactory.getLogger(InternalTenantDirectoryRestApi.class);
    private final IdentityPort tenants;
    private final byte[] token;

    public InternalTenantDirectoryRestApi(IdentityPort tenants, @Value("${internal.service-token:}") String token) {
        this.tenants = tenants;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Body {ids: [...]}; anything that is not a number is ignored, and duplicates count once. */
    @PostMapping(value = "/resolve", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> resolve(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody(required = false) Map<String, Object> body) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        Set<Long> ids = new TreeSet<>();
        Object asked = body == null ? null : body.get("ids");
        if (asked instanceof Collection) {
            for (Object id : (Collection<?>) asked) {
                if (id instanceof Number) ids.add(((Number) id).longValue());
            }
        }
        if (ids.isEmpty()) {
            return new ResponseEntity<>(Collections.emptyList(), HttpStatus.OK);
        }
        if (ids.size() > MAX_IDS) {
            return new ResponseEntity<>(Collections.singletonMap("message",
                "Ask for at most " + MAX_IDS + " workspaces at a time."), HttpStatus.BAD_REQUEST);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (IdentityPort.Workspace tenant : this.tenants.workspaces(ids)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tenantId", tenant.getTenantId());
            row.put("tenantName", tenant.getName());
            row.put("status", tenant.getStatus());
            rows.add(row);
        }
        return new ResponseEntity<>(rows, HttpStatus.OK);
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            this.logger.warn("Refused a workspace directory lookup without the internal token.");
        }
        return ok;
    }
}
