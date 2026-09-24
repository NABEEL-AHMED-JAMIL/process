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
 * /internal/users/resolve (MIG-192): who a user id is, for the services that stamp created_by and
 * updated_by but do not own the people. A batch read, and five columns only -- app_user_id,
 * tenant_id, username, full_name, status -- so no service learns more about a person than a name.
 */
@RestController
@RequestMapping("/internal/users")
public class InternalUserDirectoryRestApi {

    private final Logger logger = LoggerFactory.getLogger(InternalUserDirectoryRestApi.class);
    private final IdentityPort users;
    private final byte[] token;

    public InternalUserDirectoryRestApi(IdentityPort users, @Value("${internal.service-token:}") String token) {
        this.users = users;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** POST, as every /internal lookup is. Body {ids: [...]}; anything that is not a number is ignored. */
    @PostMapping(value = "/resolve", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> resolve(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody Map<String, Object> body) {
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
        List<Map<String, Object>> rows = new ArrayList<>();
        // A directory for other services, answering for every tenant (MIG-13), through the port (MIG-93).
        for (IdentityPort.Person user : this.users.people(ids).values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("appUserId", user.getAppUserId());
            row.put("tenantId", user.getTenantId());
            row.put("username", user.getUsername());
            row.put("fullName", user.getFullName());
            row.put("status", user.getStatus());
            rows.add(row);
        }
        return new ResponseEntity<>(rows, HttpStatus.OK);
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            this.logger.warn("Refused a user directory lookup without the internal token.");
        }
        return ok;
    }
}
