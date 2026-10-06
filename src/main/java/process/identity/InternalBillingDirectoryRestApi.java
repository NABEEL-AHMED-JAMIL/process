package process.identity;

import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.model.repository.SourceTaskTypeRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What billing-service asks Core (MIG-88/89): the facts the nightly measurement counts for one workspace --
 * seats that are not deleted and Kafka topics in use. The workspaces it bills and who a user id is come
 * from identity-service (/internal/identity/liveWorkspaces, /people) since MIG-92, so the /tenants and
 * /userNames lookups that used to sit here are gone (MIG-329 read-through: nothing called them).
 *
 * Internal-token only: the answer is not scoped to a signed-in caller.
 */
@RestController
@RequestMapping("/internal/billingDirectory")
public class InternalBillingDirectoryRestApi {

    private final Logger logger = LoggerFactory.getLogger(InternalBillingDirectoryRestApi.class);
    private final IdentityPort identity;
    private final SourceTaskTypeRepository taskTypes;
    private final byte[] token;

    public InternalBillingDirectoryRestApi(IdentityPort identity, SourceTaskTypeRepository taskTypes,
        @Value("${internal.service-token:}") String token) {
        this.identity = identity;
        this.taskTypes = taskTypes;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** One workspace's facts for the nightly measurement: {tenantId, seats, topicsInUse}. */
    @PostMapping(value = "/usageFacts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> usageFacts(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestParam Long tenantId) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        // One named workspace (service token): the session works for it alone (MIG-258).
        Map<String, Object> facts = new LinkedHashMap<>();
        RowSecurity.forTenant(tenantId == null ? 0L : tenantId, () -> {
            facts.put("tenantId", tenantId);
            facts.put("seats", this.identity.seats(tenantId));
            facts.put("topicsInUse", this.taskTypes.countTopicsInUse(tenantId));
        });
        return new ResponseEntity<>(facts, HttpStatus.OK);
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            this.logger.warn("Refused a billing directory lookup without the internal token.");
        }
        return ok;
    }
}
