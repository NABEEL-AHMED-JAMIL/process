package process.retention;

import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * MIG-354: what the console's Retention section asks of Core (RetentionRestApi) -- the policy, the preview, the holds,
 * a confirmation, "Run now", the history and the audit -- with the checks a sentence explains when they refuse.
 *
 * <ul>
 *   <li>Days are 1 to 36,500 or empty (forever). Audit records are never kept for less than the platform's floor (6 years
 *   unless a platform administrator changes it): a shorter number is refused, not quietly raised.</li>
 *   <li>Turning retention on removes nothing by itself: the next pass does, after 02:00 Chicago time, or "Run now". The
 *   first time it is turned on, the workspace gets its RETENTION alert rule (administrators, bell and mail).</li>
 *   <li>A hold names the workspace, one of its pipelines, or one of its runs, with a reason; releasing one needs a reason
 *   too. Both are in the retention audit with who did it.</li>
 *   <li>A confirmation is for the class as it is now: the administrator sees how many would go and confirms about that
 *   many; a pass may then remove up to 10% (at least 10) more, and the confirmation is spent when the class is done.</li>
 * </ul>
 */
@Service
public class RetentionService implements DisposableBean {

    static final int MAX_DAYS = 36_500;
    static final int MAX_REASON = 500;

    private static final Logger logger = LoggerFactory.getLogger(RetentionService.class);

    private final RetentionStore store;
    private final RetentionPass pass;
    /** "Run now": one pass at a time on this replica, a few waiting; more is a 429. */
    private final ThreadPoolExecutor manual = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4), work -> {
        Thread thread = new Thread(work, "retention-manual");
        thread.setDaemon(true);
        return thread;
    });

    public RetentionService(RetentionStore store, RetentionPass pass) {
        this.store = store;
        this.pass = pass;
    }

    /** A refusal with its status and the sentence the console shows. */
    public static final class Refused extends RuntimeException {
        private final HttpStatus status;

        public Refused(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus getStatus() {
            return this.status;
        }
    }

    // -------------------------------------------------------------------------------------------------------------- read

    public Map<String, Object> view(long tenantId) {
        RetentionStore.Policy policy = this.store.policy(tenantId);
        RetentionStore.Platform platform = this.store.platform();
        Map<DataClass, RetentionStore.ClassResult> last = this.store.latestByClass(tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId);
        out.put("enabled", policy.enabled);
        out.put("updatedAt", policy.updatedAt == null ? null : policy.updatedAt.toString());
        out.put("updatedBy", policy.updatedBy);
        out.put("platform", platformMap(platform));
        List<Map<String, Object>> classes = new ArrayList<>();
        for (DataClass dataClass : DataClass.values()) {
            RetentionStore.Rule rule = policy.rules.get(dataClass);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("dataClass", dataClass.name());
            one.put("label", dataClass.label());
            one.put("covers", dataClass.covers());
            one.put("owner", dataClass.owner().name().toLowerCase(Locale.ROOT));
            one.put("configurable", dataClass.configurable());
            one.put("holds", dataClass.knowsRuns() ? "workspace, pipeline, run" : "workspace");
            one.put("keepDays", rule == null ? null : rule.keepDays);
            one.put("minDays", dataClass == DataClass.AUDIT ? platform.auditFloorDays : null);
            one.put("confirmedCount", rule == null ? null : rule.confirmedCount);
            one.put("confirmedAt", rule == null || rule.confirmedAt == null ? null : rule.confirmedAt.toString());
            RetentionStore.ClassResult result = last.get(dataClass);
            one.put("last", result == null ? null : classMap(result));
            classes.add(one);
        }
        out.put("classes", classes);
        List<Map<String, Object>> holds = new ArrayList<>();
        this.store.holds(tenantId, 50).forEach(h -> holds.add(holdMap(h)));
        out.put("holds", holds);
        Optional<RetentionStore.Pass> latest = this.store.latestPass(tenantId);
        out.put("lastPass", latest.map(RetentionService::passMap).orElse(null));
        out.put("running", latest.isPresent() && "RUNNING".equals(latest.get().state));
        return out;
    }

    public List<Map<String, Object>> passes(long tenantId, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RetentionStore.Pass p : this.store.passes(tenantId, limit)) {
            Map<String, Object> one = passMap(p);
            List<Map<String, Object>> classes = new ArrayList<>();
            p.classes.forEach(c -> classes.add(classMap(c)));
            one.put("classes", classes);
            out.add(one);
        }
        return out;
    }

    public List<Map<String, Object>> audit(long tenantId, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RetentionStore.AuditLine line : this.store.auditLines(tenantId, limit)) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", line.id);
            one.put("at", line.at == null ? null : line.at.toString());
            one.put("actorUserId", line.actor);
            one.put("action", line.action);
            one.put("dataClass", line.dataClass);
            one.put("passId", line.passId);
            one.put("detail", line.detail);
            out.add(one);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------- write

    /** Saves the switch and the days the body names (classes it leaves out keep theirs). */
    @Transactional
    public Map<String, Object> save(long tenantId, Map<String, Object> body, Long by) {
        if (body == null) {
            throw new Refused(HttpStatus.BAD_REQUEST, "Send the policy: enabled, and the days for each class (empty for forever).");
        }
        RetentionStore.Platform platform = this.store.platform();
        RetentionStore.Policy before = this.store.policy(tenantId);
        Map<DataClass, Integer> days = this.days(body.get("classes"), platform, true);
        Boolean enabled = body.containsKey("enabled") ? bool(body.get("enabled")) : null;
        List<String> changes = new ArrayList<>();
        for (Map.Entry<DataClass, Integer> entry : days.entrySet()) {
            RetentionStore.Rule rule = before.rules.get(entry.getKey());
            Integer was = rule == null ? null : rule.keepDays;
            if (java.util.Objects.equals(was, entry.getValue())) {
                continue;
            }
            this.store.saveDays(tenantId, entry.getKey(), entry.getValue(), by);
            String line = entry.getKey().label() + ": " + daysText(was) + " -> " + daysText(entry.getValue()) + ".";
            changes.add(line);
            this.store.audit(tenantId, by, "DAYS_CHANGED", entry.getKey(), null, line);
        }
        if (enabled != null && enabled != before.enabled) {
            this.store.saveSwitch(tenantId, enabled, by);
            this.store.audit(tenantId, by, enabled ? "POLICY_ON" : "POLICY_OFF", null, null, enabled
                ? "Retention turned on: the next pass removes what each class's days say (after 02:00, or Run now)."
                : "Retention turned off: no pass removes anything until it is turned on again.");
            changes.add(enabled ? "Retention turned on." : "Retention turned off.");
            if (enabled && this.store.ensureAlertRule(tenantId, by)) {
                changes.add("Alert rule \"Retention needs attention\" added for the workspace's administrators.");
            }
        }
        logger.info("Retention policy of workspace {} saved by user {}: {}", tenantId, by, changes.isEmpty() ? "no change" : String.join(" ", changes));
        Map<String, Object> out = this.view(tenantId);
        out.put("changes", changes);
        return out;
    }

    /** What a pass would do now, per class: with the days the body names, or the saved ones. Writes nothing. */
    public Map<String, Object> preview(long tenantId, Map<String, Object> body) {
        RetentionStore.Platform platform = this.store.platform();
        RetentionStore.Policy policy = this.store.policy(tenantId);
        Map<DataClass, Integer> days = new EnumMap<>(DataClass.class);
        policy.rules.forEach((c, r) -> days.put(c, r.keepDays));
        if (body != null && body.get("classes") != null) {
            days.putAll(this.days(body.get("classes"), platform, false));
        }
        Holds holds = this.store.activeHolds(tenantId);
        Map<DataClass, RetentionStore.ClassResult> results = this.pass.preview(tenantId, days, policy.rules, platform, holds);
        List<Map<String, Object>> classes = new ArrayList<>();
        long items = 0;
        long bytes = 0;
        for (RetentionStore.ClassResult r : results.values()) {
            Map<String, Object> one = classMap(r);
            one.put("label", r.dataClass.label());
            one.put("bytes", r.bytes < 0 ? null : r.bytes);
            classes.add(one);
            items += r.eligible;
            bytes += Math.max(0, r.bytes);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId);
        out.put("enabled", policy.enabled);
        out.put("at", Instant.now().toString());
        out.put("classes", classes);
        out.put("items", items);
        out.put("bytes", bytes);
        out.put("workspaceHold", holds.workspace);
        out.put("pipelineHolds", holds.jobIds.size());
        out.put("runHolds", holds.runIds.size());
        return out;
    }

    /** An administrator confirms a class's pass over the platform's share, for about as many items as are eligible now. */
    public Map<String, Object> confirm(long tenantId, Map<String, Object> body, Long by) {
        DataClass dataClass = dataClass(body == null ? null : body.get("dataClass"));
        RetentionStore.Policy policy = this.store.policy(tenantId);
        RetentionStore.Rule rule = policy.rules.get(dataClass);
        if (rule == null || rule.keepDays == null) {
            throw new Refused(HttpStatus.BAD_REQUEST, dataClass.label() + " is kept forever; there is nothing to confirm.");
        }
        RetentionStore.Platform platform = this.store.platform();
        Map<DataClass, Integer> days = new EnumMap<>(DataClass.class);
        days.put(dataClass, rule.keepDays);
        RetentionStore.ClassResult result = this.pass.preview(tenantId, days, new EnumMap<>(DataClass.class), platform,
            this.store.activeHolds(tenantId)).get(dataClass);
        if (result == null || "FAILED".equals(result.state) || "UNAVAILABLE".equals(result.state)) {
            throw new Refused(HttpStatus.BAD_GATEWAY, dataClass.label() + " could not be counted just now"
                + (result != null && result.note != null ? " (" + result.note + ")" : "") + "; try again.");
        }
        if (!RetentionPass.overShare(result.eligible, result.total, platform.maxSharePct)) {
            throw new Refused(HttpStatus.BAD_REQUEST, dataClass.label() + ": " + result.eligible + " of " + result.total + " item(s) would go, "
                + "within the " + platform.maxSharePct + "% a pass removes without asking; there is nothing to confirm.");
        }
        Long expected = body == null ? null : asLong(body.get("expectedCount"));
        if (expected != null && result.eligible > Math.max((long) Math.ceil(expected * RetentionPass.CONFIRM_SLACK),
            expected + RetentionPass.CONFIRM_SLACK_MIN)) {
            throw new Refused(HttpStatus.CONFLICT, dataClass.label() + ": " + result.eligible + " item(s) would go now, more than the " + expected
                + " you saw. Preview again, then confirm.");
        }
        this.store.confirm(tenantId, dataClass, result.eligible, by);
        String line = dataClass.label() + ": an administrator confirmed removing " + result.eligible + " of " + result.total
            + " item(s) older than " + rule.keepDays + " day(s).";
        this.store.audit(tenantId, by, "CLASS_CONFIRMED", dataClass, null, line);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataClass", dataClass.name());
        out.put("confirmedCount", result.eligible);
        out.put("total", result.total);
        out.put("message", line);
        return out;
    }

    /** "Run now": starts a pass of the workspace in the background; its id. */
    public Map<String, Object> runNow(long tenantId, Long by) {
        RetentionStore.Policy policy = this.store.policy(tenantId);
        if (!policy.enabled) {
            throw new Refused(HttpStatus.BAD_REQUEST, "Retention is off in this workspace; turn it on first.");
        }
        Optional<Long> started = this.store.startPass(tenantId, "MANUAL", by);
        if (!started.isPresent()) {
            throw new Refused(HttpStatus.CONFLICT, "A retention pass is already running in this workspace.");
        }
        long passId = started.get();
        try {
            this.manual.execute(() -> {
                try {
                    RowSecurity.forTenant(tenantId, () -> this.pass.execute(tenantId, passId, "MANUAL", by));
                } catch (RuntimeException failed) {
                    logger.warn("Retention pass {} of workspace {} (Run now) failed: {}", passId, tenantId, failed.getMessage());
                }
            });
        } catch (RejectedExecutionException busy) {
            this.store.finishPass(passId, "FAILED", 0, 0, "Not started: too many passes waiting on this replica.");
            throw new Refused(HttpStatus.TOO_MANY_REQUESTS, "Too many retention passes are waiting; try again in a few minutes.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passId", passId);
        out.put("state", "RUNNING");
        return out;
    }

    public Map<String, Object> placeHold(long tenantId, Map<String, Object> body, Long by) {
        if (body == null) {
            throw new Refused(HttpStatus.BAD_REQUEST, "Say what to hold (scope: workspace, pipeline or run) and why.");
        }
        String scope = body.get("scope") == null ? "" : String.valueOf(body.get("scope")).trim().toUpperCase(Locale.ROOT);
        String reason = reason(body.get("reason"), "A legal hold needs its reason (who asked, and what for).");
        Long jobId = null;
        Long runId = null;
        String what;
        switch (scope) {
            case "WORKSPACE":
                what = "the whole workspace";
                break;
            case "PIPELINE":
                jobId = asLong(body.get("jobId"));
                if (jobId == null || !this.store.pipelineExists(tenantId, jobId)) {
                    throw new Refused(HttpStatus.BAD_REQUEST, "Name one of this workspace's pipelines (jobId).");
                }
                what = "pipeline " + jobId;
                break;
            case "RUN":
                runId = asLong(body.get("runId"));
                if (runId == null || !this.store.runExists(tenantId, runId)) {
                    throw new Refused(HttpStatus.BAD_REQUEST, "Name one of this workspace's runs (runId).");
                }
                what = "run " + runId;
                break;
            default:
                throw new Refused(HttpStatus.BAD_REQUEST, "scope is workspace, pipeline or run.");
        }
        long id = this.store.placeHold(tenantId, scope, jobId, runId, reason, by);
        this.store.audit(tenantId, by, "HOLD_PLACED", null, null, "Legal hold " + id + " placed on " + what + ": " + reason);
        return holdMap(this.store.hold(tenantId, id).orElseThrow(() -> new Refused(HttpStatus.NOT_FOUND, "No such hold.")));
    }

    public Map<String, Object> releaseHold(long tenantId, Map<String, Object> body, Long by) {
        Long id = body == null ? null : asLong(body.get("id"));
        if (id == null) {
            throw new Refused(HttpStatus.BAD_REQUEST, "Say which hold to release (id).");
        }
        String reason = reason(body.get("reason"), "Releasing a legal hold needs its reason.");
        Optional<RetentionStore.Hold> hold = this.store.hold(tenantId, id);
        if (!hold.isPresent()) {
            throw new Refused(HttpStatus.NOT_FOUND, "No such hold.");
        }
        if (!this.store.releaseHold(tenantId, id, reason, by)) {
            throw new Refused(HttpStatus.CONFLICT, "Hold " + id + " was already released.");
        }
        this.store.audit(tenantId, by, "HOLD_RELEASED", null, null, "Legal hold " + id + " (" + hold.get().scope.toLowerCase(Locale.ROOT)
            + ") released: " + reason);
        return holdMap(this.store.hold(tenantId, id).orElseThrow(() -> new Refused(HttpStatus.NOT_FOUND, "No such hold.")));
    }

    // ---------------------------------------------------------------------------------------------------------- platform

    public Map<String, Object> platform() {
        return platformMap(this.store.platform());
    }

    public Map<String, Object> savePlatform(Map<String, Object> body, Long by) {
        RetentionStore.Platform now = this.store.platform();
        Long floor = body == null ? null : asLong(body.get("auditFloorDays"));
        Long share = body == null ? null : asLong(body.get("maxSharePct"));
        int newFloor = floor == null ? now.auditFloorDays : floor.intValue();
        int newShare = share == null ? now.maxSharePct : share.intValue();
        if (newFloor < 365 || newFloor > MAX_DAYS) {
            throw new Refused(HttpStatus.BAD_REQUEST, "The audit records' minimum is 365 to " + MAX_DAYS + " days.");
        }
        if (newShare < 1 || newShare > 100) {
            throw new Refused(HttpStatus.BAD_REQUEST, "The share a pass removes without asking is 1 to 100 percent.");
        }
        this.store.savePlatform(newFloor, newShare, by);
        logger.info("Retention platform settings changed by user {}: audit floor {} -> {} days, share {}% -> {}%.", by, now.auditFloorDays,
            newFloor, now.maxSharePct, newShare);
        return this.platform();
    }

    // ----------------------------------------------------------------------------------------------------------- helpers

    /** The days per class a body names: {"RUN_OUTPUTS": 90, "AUDIT": null, ...}; checked against the rules above. */
    Map<DataClass, Integer> days(Object classes, RetentionStore.Platform platform, boolean strictAudit) {
        Map<DataClass, Integer> out = new EnumMap<>(DataClass.class);
        if (classes == null) {
            return out;
        }
        if (!(classes instanceof Map)) {
            throw new Refused(HttpStatus.BAD_REQUEST, "classes is an object of class: days (empty for forever).");
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) classes).entrySet()) {
            DataClass dataClass = dataClass(entry.getKey());
            if (!dataClass.configurable()) {
                throw new Refused(HttpStatus.BAD_REQUEST, dataClass.label() + " is bounded by the platform and has no days to set.");
            }
            Object value = entry.getValue();
            Integer days;
            if (value == null || String.valueOf(value).trim().isEmpty() || "forever".equalsIgnoreCase(String.valueOf(value).trim())) {
                days = null;
            } else {
                Long n = asLong(value);
                if (n == null || n < 1 || n > MAX_DAYS) {
                    throw new Refused(HttpStatus.BAD_REQUEST, dataClass.label() + ": days are 1 to " + MAX_DAYS + ", or empty to keep forever.");
                }
                days = n.intValue();
            }
            if (dataClass == DataClass.AUDIT && days != null && days < platform.auditFloorDays && strictAudit) {
                throw new Refused(HttpStatus.BAD_REQUEST, "Audit records are kept at least " + platform.auditFloorDays + " days (the platform's "
                    + "minimum); choose " + platform.auditFloorDays + " or more, or keep them forever.");
            }
            out.put(dataClass, days);
        }
        return out;
    }

    static DataClass dataClass(Object value) {
        return DataClass.of(value == null ? null : String.valueOf(value))
            .orElseThrow(() -> new Refused(HttpStatus.BAD_REQUEST, "dataClass is one of " + java.util.Arrays.toString(DataClass.values()) + "."));
    }

    static String reason(Object value, String missing) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isEmpty()) {
            throw new Refused(HttpStatus.BAD_REQUEST, missing);
        }
        if (text.length() > MAX_REASON) {
            throw new Refused(HttpStatus.BAD_REQUEST, "The reason is at most " + MAX_REASON + " characters.");
        }
        return text;
    }

    static Boolean bool(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String text = value == null ? "" : String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new Refused(HttpStatus.BAD_REQUEST, "enabled is true or false.");
    }

    static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }

    static String daysText(Integer days) {
        return days == null ? "forever" : days + " day(s)";
    }

    static Map<String, Object> platformMap(RetentionStore.Platform platform) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("auditFloorDays", platform.auditFloorDays);
        out.put("maxSharePct", platform.maxSharePct);
        out.put("updatedAt", platform.updatedAt == null ? null : platform.updatedAt.toString());
        return out;
    }

    static Map<String, Object> classMap(RetentionStore.ClassResult c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataClass", c.dataClass == null ? null : c.dataClass.name());
        out.put("state", c.state);
        out.put("keepDays", c.keepDays);
        out.put("cutoff", c.cutoff == null ? null : c.cutoff.toString());
        out.put("eligible", c.eligible);
        out.put("total", c.total);
        out.put("deleted", c.deleted);
        out.put("bytes", c.bytes);
        out.put("oldest", c.oldest == null ? null : c.oldest.toString());
        out.put("note", c.note);
        out.put("finishedAt", c.finishedAt == null ? null : c.finishedAt.toString());
        return out;
    }

    static Map<String, Object> passMap(RetentionStore.Pass p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passId", p.id);
        out.put("startedHow", p.startedHow);
        out.put("state", p.state);
        out.put("startedBy", p.startedBy);
        out.put("startedAt", p.startedAt == null ? null : p.startedAt.toString());
        out.put("finishedAt", p.finishedAt == null ? null : p.finishedAt.toString());
        out.put("deleted", p.deleted);
        out.put("bytes", p.bytes);
        out.put("note", p.note);
        return out;
    }

    static Map<String, Object> holdMap(RetentionStore.Hold h) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", h.id);
        out.put("scope", h.scope);
        out.put("jobId", h.jobId);
        out.put("runId", h.runId);
        out.put("reason", h.reason);
        out.put("placedBy", h.placedBy);
        out.put("placedAt", h.placedAt == null ? null : h.placedAt.toString());
        out.put("active", h.releasedAt == null);
        out.put("releasedBy", h.releasedBy);
        out.put("releasedAt", h.releasedAt == null ? null : h.releasedAt.toString());
        out.put("releaseReason", h.releaseReason);
        return out;
    }

    @Override
    public void destroy() {
        this.manual.shutdownNow();
    }
}
