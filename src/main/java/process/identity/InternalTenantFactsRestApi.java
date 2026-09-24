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
import process.model.enums.Status;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.storage.remote.RemoteStorageDirectory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the platform administrator's workspace list shows that Core counts (MIG-107): per workspace, its
 * Kafka profiles, buckets, task types, tasks, pipelines and jobs -- the six counts TenantServiceImpl made
 * row by row while Identity lived here, now asked for a page of workspaces at once by identity-service.
 * POST /internal/core/tenantFacts {ids}, service token only. A count that cannot be made is left out
 * (identity-service shows it as unknown) rather than failing the whole list.
 */
@RestController
@RequestMapping("/internal/core")
public class InternalTenantFactsRestApi {

    static final int MAX_IDS = 500;

    private final Logger logger = LoggerFactory.getLogger(InternalTenantFactsRestApi.class);
    private final KafkaConnectionProfileRepository kafkaProfiles;
    private final RemoteStorageDirectory storage;
    private final SourceTaskTypeRepository taskTypes;
    private final SourceTaskRepository tasks;
    private final SourceJobRepository jobs;
    private final byte[] token;

    public InternalTenantFactsRestApi(KafkaConnectionProfileRepository kafkaProfiles, RemoteStorageDirectory storage,
        SourceTaskTypeRepository taskTypes, SourceTaskRepository tasks, SourceJobRepository jobs,
        @Value("${internal.service-token:}") String token) {
        this.kafkaProfiles = kafkaProfiles;
        this.storage = storage;
        this.taskTypes = taskTypes;
        this.tasks = tasks;
        this.jobs = jobs;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(value = "/tenantFacts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> tenantFacts(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody(required = false) Map<String, Object> body) {
        if (!this.admits(presented)) return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        Set<Long> ids = new TreeSet<>();
        Object asked = body == null ? null : body.get("ids");
        if (asked instanceof Collection) {
            for (Object id : (Collection<?>) asked) {
                if (id instanceof Number) ids.add(((Number) id).longValue());
            }
        }
        if (ids.size() > MAX_IDS) {
            return new ResponseEntity<>(Collections.singletonMap("message", "Ask for at most " + MAX_IDS + " at a time."),
                HttpStatus.BAD_REQUEST);
        }
        Map<String, Map<String, Object>> facts = new LinkedHashMap<>();
        for (Long tenantId : ids) {
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("kafkaProfileCount", this.kafkaProfiles.countByTenantIdAndStatusNot(tenantId, Status.Delete));
            try {
                counts.put("bucketCount", (long) this.storage.workspace(tenantId).size());
            } catch (RuntimeException unavailable) {
                this.logger.warn("Workspace {}'s buckets could not be counted: {}", tenantId, unavailable.getMessage());
            }
            counts.put("sourceTaskTypeCount", this.taskTypes.countByTenantIdAndStatusNot(tenantId, Status.Delete));
            counts.put("sourceTaskCount", this.tasks.countByTenantIdAndTaskStatusNot(tenantId, Status.Delete));
            counts.put("pipelineCount", this.tasks.countDistinctPipelinesByTenantId(tenantId, Status.Delete));
            counts.put("sourceJobCount", this.jobs.countByTenantIdAndJobStatusNot(tenantId, Status.Delete));
            facts.put(String.valueOf(tenantId), counts);
        }
        return ResponseEntity.ok(facts);
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            this.logger.warn("Refused a workspace-facts lookup without the internal token.");
        }
        return ok;
    }
}
