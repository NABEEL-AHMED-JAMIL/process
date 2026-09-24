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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.model.enums.Status;
import process.model.enums.TenantStatus;
import process.model.pojo.Tenant;
import process.model.repository.AppUserRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantRepository;
import process.util.UserNameResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What billing-service asks Core (MIG-88/89): the workspaces it bills and their names, the facts the
 * nightly measurement counts -- seats that are not deleted, Kafka topics in use -- and who a user id is.
 * Identity stays in process until it is extracted; Billing reads it here, with the shared service token.
 *
 * Internal-token only: none of these answers is scoped to a signed-in caller.
 */
@RestController
@RequestMapping("/internal/billingDirectory")
public class InternalBillingDirectoryRestApi {

    private final Logger logger = LoggerFactory.getLogger(InternalBillingDirectoryRestApi.class);
    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final SourceTaskTypeRepository taskTypes;
    private final UserNameResolver names;
    private final byte[] token;

    public InternalBillingDirectoryRestApi(TenantRepository tenants, AppUserRepository users, SourceTaskTypeRepository taskTypes,
        UserNameResolver names, @Value("${internal.service-token:}") String token) {
        this.tenants = tenants;
        this.users = users;
        this.taskTypes = taskTypes;
        this.names = names;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Every workspace that is not deleted: [{tenantId, tenantName, status}], newest first. POST, as every
     * /internal lookup is: SecurityConfig admits /internal/** for POST only, and the token is checked here.
     */
    @PostMapping(value = "/tenants", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> tenants(@RequestHeader(value = "X-Internal-Token", required = false) String presented) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Tenant tenant : this.tenants.findByStatusNotOrderByTenantIdDesc(TenantStatus.Delete)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tenantId", tenant.getTenantId());
            row.put("tenantName", tenant.getTenantName());
            row.put("status", tenant.getStatus() == null ? null : tenant.getStatus().name());
            rows.add(row);
        }
        return new ResponseEntity<>(rows, HttpStatus.OK);
    }

    /** One workspace's facts for the nightly measurement: {tenantId, seats, topicsInUse}. */
    @PostMapping(value = "/usageFacts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> usageFacts(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestParam Long tenantId) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("tenantId", tenantId);
        facts.put("seats", this.users.countByTenantIdAndStatusNot(tenantId, Status.Delete));
        facts.put("topicsInUse", this.taskTypes.countTopicsInUse(tenantId));
        return new ResponseEntity<>(facts, HttpStatus.OK);
    }

    /** Body {"ids": [..]}; answers {"<id>": "<display name>"} for the ids that are still people. */
    @PostMapping(value = "/userNames", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> userNames(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody Map<String, Object> body) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        Set<Long> ids = new HashSet<>();
        Object raw = body == null ? null : body.get("ids");
        if (raw instanceof Collection) {
            for (Object id : (Collection<?>) raw) {
                if (id instanceof Number) {
                    ids.add(((Number) id).longValue());
                }
            }
        }
        Map<String, String> answer = new LinkedHashMap<>();
        for (Map.Entry<Long, String> name : this.names.namesFor(ids).entrySet()) {
            answer.put(String.valueOf(name.getKey()), name.getValue());
        }
        return new ResponseEntity<>(answer, HttpStatus.OK);
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
