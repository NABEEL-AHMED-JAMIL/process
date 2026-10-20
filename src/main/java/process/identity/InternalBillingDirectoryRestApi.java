package process.identity;

import org.barco.platform.tenancy.RowSecurity;
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
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.pipeline.PipelineDefinition;
import process.pipeline.PipelineDefinitionStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What billing-service asks Core (MIG-88/89): the facts the nightly measurement counts for one workspace --
 * seats that are not deleted and Kafka topics in use. The workspaces it bills and who a user id is come
 * from identity-service (/internal/identity/liveWorkspaces, /people) since MIG-92, so the /tenants and
 * /userNames lookups that used to sit here are gone (MIG-329 read-through: nothing called them).
 *
 * Since the UX review of 2026-10-12 (BL1) it also names runs: Billing keeps only a run's id with its usage, and asks here,
 * once per Cost &amp; usage answer, for each run's schedule and pipeline.
 *
 * Internal-token only: the answer is not scoped to a signed-in caller -- the workspace Billing names is, under row-level
 * security, the only one each lookup sees.
 */
@RestController
@RequestMapping("/internal/billingDirectory")
public class InternalBillingDirectoryRestApi {

    private final Logger logger = LoggerFactory.getLogger(InternalBillingDirectoryRestApi.class);
    private final IdentityPort identity;
    private final SourceTaskTypeRepository taskTypes;
    private final JobQueueRepository runs;
    /** Owner #23: the runs' pipelines' latest definitions, for the facts a rate card's pricing rule matches on. */
    private final PipelineDefinitionStore definitions;
    private final byte[] token;

    /** The most run ids one question may name: Billing asks for a month of a workspace's runs at a time. */
    static final int MAX_RUN_IDS = 10000;

    public InternalBillingDirectoryRestApi(IdentityPort identity, SourceTaskTypeRepository taskTypes, JobQueueRepository runs,
        PipelineDefinitionStore definitions, @Value("${internal.service-token:}") String token) {
        this.identity = identity;
        this.taskTypes = taskTypes;
        this.runs = runs;
        this.definitions = definitions;
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

    /**
     * Body {tenantId, jobQueueIds: [..]}: the workspace's runs among these ids, each {jobQueueId, jobId, schedule,
     * taskDetailId, pipeline, startedAt (ISO-8601, UTC), status, sensitivity, tags}, by id. A run of another workspace, or one
     * that does not exist, is simply absent. Runs as that workspace (row-level security) and filters by it too.
     *
     * <p>Owner #23: sensitivity and tags are what a rate card's pricing rule matches a run on (CLASS, TAG) -- from its
     * pipeline's latest definition: settings.sensitivity (internal when not said) and settings.tags; null and none for a
     * legacy pipeline, which has no settings. Billing keeps the answer once per run.
     */
    @PostMapping(value = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> runs(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody(required = false) Map<String, Object> body) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        Object tenant = body == null ? null : body.get("tenantId");
        Object requested = body == null ? null : body.get("jobQueueIds");
        if (!(tenant instanceof Number) || ((Number) tenant).longValue() <= 0 || !(requested instanceof List)) {
            return new ResponseEntity<>(Collections.singletonMap("message", "tenantId and jobQueueIds are required."), HttpStatus.BAD_REQUEST);
        }
        long tenantId = ((Number) tenant).longValue();
        Set<Long> ids = new LinkedHashSet<>();
        for (Object id : (List<?>) requested) {
            if (id instanceof Number && ((Number) id).longValue() > 0) {
                ids.add(((Number) id).longValue());
            }
        }
        if (ids.size() > MAX_RUN_IDS) {
            return new ResponseEntity<>(Collections.singletonMap("message", "At most " + MAX_RUN_IDS + " runs a call."), HttpStatus.BAD_REQUEST);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!ids.isEmpty()) {
            List<Object[]> found = RowSecurity.forTenant(tenantId, () -> this.runs.findRunNames(tenantId, JobQueueRepository.idArray(ids)));
            Map<Long, PipelineDefinition.Settings> settings = this.settingsOf(tenantId, found);
            for (Object[] run : found) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("jobQueueId", number(run[0]));
                row.put("jobId", number(run[1]));
                row.put("schedule", run[2] == null ? null : run[2].toString());
                row.put("taskDetailId", number(run[3]));
                row.put("pipeline", run[4] == null ? null : run[4].toString());
                Instant started = instant(run[5]);
                row.put("startedAt", started == null ? null : started.toString());
                row.put("status", run[6] == null ? null : run[6].toString());
                Long task = number(run[3]);
                boolean engine = task != null && settings.containsKey(task);
                PipelineDefinition.Settings s = engine ? settings.get(task) : null;
                row.put("sensitivity", !engine ? null : s == null ? PipelineDefinition.Settings.INTERNAL : s.effectiveSensitivity());
                row.put("tags", s == null || s.getTags() == null ? new ArrayList<String>() : new ArrayList<>(s.getTags()));
                rows.add(row);
            }
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("tenantId", tenantId);
        answer.put("runs", rows);
        return new ResponseEntity<>(answer, HttpStatus.OK);
    }

    /**
     * Owner #23: the settings of the runs' pipelines' latest definitions, by pipeline (task_detail_id) -- a pipeline with a
     * definition maps to its settings (null when it says none); a legacy pipeline is absent. One query for the page.
     */
    private Map<Long, PipelineDefinition.Settings> settingsOf(long tenantId, List<Object[]> found) {
        Set<Long> tasks = new LinkedHashSet<>();
        for (Object[] run : found) {
            Long task = number(run[3]);
            if (task != null) {
                tasks.add(task);
            }
        }
        Map<Long, PipelineDefinition.Settings> out = new LinkedHashMap<>();
        if (tasks.isEmpty()) {
            return out;
        }
        for (PipelineDefinitionStore.TaskDefinition task : RowSecurity.forTenant(tenantId, () -> this.definitions.latestForTasks(tenantId, tasks))) {
            try {
                PipelineDefinition definition = task.stored.definition();
                if (!definition.isLegacy()) {
                    out.put(task.taskDetailId, definition.getSettings());
                }
            } catch (RuntimeException unreadable) {
                this.logger.warn("Pipeline {}'s definition is not read for billing: {}", task.taskDetailId, unreadable.getMessage());
            }
        }
        return out;
    }

    private static Long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    /** A timestamptz as the driver hands it back: an instant either way. */
    private static Instant instant(Object value) {
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toInstant();
        }
        if (value instanceof OffsetDateTime) {
            return ((OffsetDateTime) value).toInstant();
        }
        return value instanceof Instant ? (Instant) value : null;
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
