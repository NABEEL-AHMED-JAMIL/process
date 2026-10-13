package process.monitoring;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.model.dto.ResponseDto;
import process.security.TenantContext;
import process.util.ProcessUtil;

/**
 * MIG-355: alert rules and alerts, under the prefix the gateway already sends to Core (/reliability.json). A workspace
 * administrator works on their workspace's; a platform administrator on the platform's (services, memory, Kafka, disk,
 * billing). Nobody else: a reviewer or viewer hears of alerts in the bell when a rule names their role or group, and is
 * refused here (403).
 *
 * <ul>
 *   <li>GET reliability.json/alerts/rules -- the caller's rules, and the kinds they may make (each with its runbook line);</li>
 *   <li>POST reliability.json/alerts/rules {rule} -- makes one (no id) or changes one (its id); 400 with a sentence when it
 *       is not valid, 404 when the id is not the caller's;</li>
 *   <li>POST reliability.json/alerts/rules/delete {id} -- removes a rule and its alerts;</li>
 *   <li>GET reliability.json/alerts/incidents?state=open|resolved|all&amp;limit= -- the caller's alerts, open first;</li>
 *   <li>POST reliability.json/alerts/incidents/resolve {id} -- a person resolves an open alert (MANUAL); its rule's
 *       recipients are told, and alert.resolved goes to the webhooks when the rule asks for them.</li>
 * </ul>
 * Every refusal is a 4xx with its sentence in the envelope.
 */
@RestController
@RequestMapping("/reliability.json/alerts")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class AlertRestApi {

    private static final Logger logger = LoggerFactory.getLogger(AlertRestApi.class);

    private final AlertRules rules;
    private final AlertIncidents incidents;
    private final AlertEngine engine;

    public AlertRestApi(AlertRules rules, AlertIncidents incidents, AlertEngine engine) {
        this.rules = rules;
        this.incidents = incidents;
        this.engine = engine;
    }

    /** The scope a caller works on: their workspace, or null (the platform's) for a platform administrator. */
    static Long scope() {
        if (TenantContext.isPlatformAdmin()) {
            return null;
        }
        Long tenant = TenantContext.getTenantId();
        if (tenant == null || tenant <= 0) {
            throw new AlertRules.Refused(HttpStatus.BAD_REQUEST, "Alert rules belong to a workspace; this account is not attached to one.");
        }
        return tenant;
    }

    @GetMapping("/rules")
    public ResponseEntity<ResponseDto> list() {
        return this.answer(() -> {
            Long scope = scope();
            List<Map<String, Object>> out = new ArrayList<>();
            this.rules.list(scope).forEach(rule -> out.add(rule.toMap()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("scope", scope == null ? "platform" : "workspace");
            data.put("rules", out);
            data.put("kinds", kinds(scope == null));
            data.put("maxRules", AlertRules.MAX_RULES);
            return new ResponseDto(ProcessUtil.SUCCESS, out.size() + " alert rule(s).", data);
        });
    }

    @PostMapping("/rules")
    public ResponseEntity<ResponseDto> save(@RequestBody(required = false) Map<String, Object> body) {
        return this.answer(() -> {
            Long scope = scope();
            AlertRule saved = this.rules.save(AlertRules.fromBody(body), scope, TenantContext.getAppUserId());
            logger.info("Alert rule {} ({}) saved by user {} for {}.", saved.id, saved.kind, TenantContext.getAppUserId(),
                scope == null ? "the platform" : "workspace " + scope);
            return new ResponseDto(ProcessUtil.SUCCESS, "Alert rule saved.", saved.toMap());
        });
    }

    @PostMapping("/rules/delete")
    public ResponseEntity<ResponseDto> delete(@RequestBody(required = false) Map<String, Object> body) {
        return this.answer(() -> {
            Long scope = scope();
            Long id = id(body);
            if (!this.rules.delete(id, scope)) {
                throw new AlertRules.Refused(HttpStatus.NOT_FOUND, "No such alert rule.");
            }
            logger.info("Alert rule {} removed by user {}.", id, TenantContext.getAppUserId());
            return new ResponseDto(ProcessUtil.SUCCESS, "Alert rule removed, with its alerts.");
        });
    }

    @GetMapping("/incidents")
    public ResponseEntity<ResponseDto> incidents(@RequestParam(value = "state", required = false) String state,
        @RequestParam(value = "limit", required = false) Integer limit) {
        return this.answer(() -> {
            Long scope = scope();
            String wanted = state == null || state.trim().isEmpty() || "all".equalsIgnoreCase(state.trim()) ? null
                : state.trim().toUpperCase(Locale.ROOT);
            if (wanted != null && !"OPEN".equals(wanted) && !"RESOLVED".equals(wanted)) {
                throw new AlertRules.Refused(HttpStatus.BAD_REQUEST, "state is open, resolved or all.");
            }
            List<Map<String, Object>> out = new ArrayList<>();
            this.incidents.list(scope, wanted, limit == null ? 100 : limit).forEach(i -> out.add(i.toMap()));
            long open = out.stream().filter(i -> "OPEN".equals(i.get("state"))).count();
            return new ResponseDto(ProcessUtil.SUCCESS, open + " open alert(s).", out);
        });
    }

    @PostMapping("/incidents/resolve")
    public ResponseEntity<ResponseDto> resolve(@RequestBody(required = false) Map<String, Object> body) {
        return this.answer(() -> {
            Long scope = scope();
            Long id = id(body);
            if (!this.engine.resolveByHand(id, scope, TenantContext.getAppUserId(), Instant.now())) {
                throw new AlertRules.Refused(HttpStatus.NOT_FOUND, "No such open alert.");
            }
            return new ResponseDto(ProcessUtil.SUCCESS, "Alert resolved.");
        });
    }

    /** The kinds this scope may make, with their defaults and runbook lines, for the console's editor. */
    static List<Map<String, Object>> kinds(boolean platform) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AlertKind kind : AlertKind.values()) {
            if (kind.isPlatform() != platform) {
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("kind", kind.name());
            one.put("label", kind.label());
            one.put("threshold", AlertRules.defaultThreshold(kind));
            one.put("windowMinutes", AlertRules.defaultWindow(kind));
            one.put("whatToDo", kind.whatToDo());
            one.put("runbook", kind.runbook());
            out.add(one);
        }
        return out;
    }

    private static Long id(Map<String, Object> body) {
        Object id = body == null ? null : body.get("id");
        if (id instanceof Number) {
            return ((Number) id).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(id).trim());
        } catch (NumberFormatException unreadable) {
            throw new AlertRules.Refused(HttpStatus.BAD_REQUEST, "id is required.");
        }
    }

    private ResponseEntity<ResponseDto> answer(Supplier<ResponseDto> work) {
        try {
            return new ResponseEntity<>(work.get(), HttpStatus.OK);
        } catch (AlertRules.Refused refused) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, refused.getMessage()), refused.getStatus());
        } catch (RuntimeException failed) {
            logger.error("An alert request failed.", failed);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
