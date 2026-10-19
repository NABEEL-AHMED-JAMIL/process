package process.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.identity.IdentityPort;
import process.model.dto.ResponseDto;
import process.pipeline.registry.TaskOverrideStore;
import process.pipeline.registry.TaskRegistry;
import process.pipeline.registry.TaskSpec;
import process.security.TenantContext;
import process.util.UserNameResolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Owner #16 (2026-10-19): a platform administrator's control over each Task Registry task per workspace -- the tools' model
 * (ai-service's tools/platformState, owner #15) for pipeline step tasks.
 *
 * <ul>
 *   <li><b>Default</b>: no switch, no block -- the task's own default (its TaskSpec's enabledByDefault, the platform default).</li>
 *   <li><b>Enabled / Disabled</b>: the workspace's switch, set by the platform (recorded as PLATFORM_ADMIN's); the
 *       workspace's administrator may still change it. Either lifts a block.</li>
 *   <li><b>Blocked</b>: the platform's alone -- off whatever the switch says; the workspace's administrator cannot switch it
 *       on; a pipeline cannot add it (validate and save refuse it) and a run whose definition names it is declined, both
 *       naming the task (TaskRegistry.refusal).</li>
 * </ul>
 *
 * Every change -- the platform's here, a workspace administrator's switch (PipelineDefinitionService.switchTask) -- is
 * written to task_registry_audit; {@link #history} reads it back, newest first. legacy is never switched or blocked:
 * every existing pipeline stays runnable.
 */
@Service
public class TaskControlService {

    static final int HISTORY_LIMIT = 100;

    private final Logger logger = LoggerFactory.getLogger(TaskControlService.class);

    private final TaskRegistry registry;
    private final IdentityPort identity;
    private final UserNameResolver names;

    public TaskControlService(TaskRegistry registry, IdentityPort identity, UserNameResolver names) {
        this.registry = registry;
        this.identity = identity;
        this.names = names;
    }

    /** A platform administrator's control over one task in one workspace. */
    public static class PlatformControl {
        private String code;
        /** enabled, disabled, blocked, or default (no block, no switch). */
        private String state;
        private String reason;

        public String getCode() { return code; }

        public void setCode(String code) { this.code = code; }

        public String getState() { return state; }

        public void setState(String state) { this.state = state; }

        public String getReason() { return reason; }

        public void setReason(String reason) { this.reason = reason; }
    }

    public ResponseDto platformState(PlatformControl control, Long tenantId) {
        if (!TenantContext.isPlatformAdmin()) {
            return new ResponseDto(ERROR, "Only a platform administrator controls a task for a workspace.");
        }
        if (tenantId == null || tenantId < 1) {
            return new ResponseDto(ERROR, "Pick the workspace (tenantId).");
        }
        if (this.identity != null && !IdentityPort.live(this.identity, tenantId).isPresent()) {
            return new ResponseDto(ERROR, String.format("Workspace %d is not a workspace.", tenantId));
        }
        if (control == null || control.getCode() == null || control.getCode().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Which task (code) is required.");
        }
        Optional<StepTask> task = this.registry.tasks().find(control.getCode());
        if (!task.isPresent()) {
            return new ResponseDto(ERROR, String.format("No task '%s' is registered.", control.getCode().trim()));
        }
        TaskSpec spec = task.get().spec();
        if (!spec.overridable()) {
            return new ResponseDto(ERROR, String.format("'%s' cannot be switched or blocked: existing pipelines always stay runnable.", spec.code()));
        }
        String reason = control.getReason() == null || control.getReason().trim().isEmpty() ? null : control.getReason().trim();
        if (reason != null && reason.length() > 500) {
            return new ResponseDto(ERROR, "A reason is at most 500 characters.");
        }
        String state = control.getState() == null ? "" : control.getState().trim().toLowerCase(Locale.ROOT);
        TaskOverrideStore store = this.registry.store();
        long me = TenantContext.getAppUserId() == null ? 0L : TenantContext.getAppUserId();
        String before = store.blocks(tenantId).containsKey(spec.code()) ? "blocked" : switchWord(store.switchRows(tenantId).get(spec.code()));
        String message;
        switch (state) {
            case "blocked":
                store.block(tenantId, spec.code(), reason, me);
                store.audit(tenantId, spec.code(), "block", before, "blocked", reason, me, TenantContext.getUserRole());
                message = String.format("%s is blocked for this workspace: its pipelines cannot use it and its administrator cannot switch it on.",
                    spec.name());
                break;
            case "enabled":
            case "disabled":
                if ("enabled".equals(state) && task.get().unavailable().isPresent()) {
                    return new ResponseDto(ERROR, String.format("'%s' is not available here: %s.", spec.code(), task.get().unavailable().get()));
                }
                store.unblock(tenantId, spec.code());
                store.set(tenantId, spec.code(), "enabled".equals(state), me, "PLATFORM_ADMIN");
                store.audit(tenantId, spec.code(), "enabled".equals(state) ? "enable" : "disable", before, "enabled".equals(state) ? "on" : "off",
                    reason, me, TenantContext.getUserRole());
                message = String.format("%s is %s for this workspace; its administrator may change that.", spec.name(),
                    "enabled".equals(state) ? "on" : "off");
                break;
            case "default":
                store.unblock(tenantId, spec.code());
                store.clear(tenantId, spec.code());
                store.audit(tenantId, spec.code(), "default", before, "default", reason, me, TenantContext.getUserRole());
                message = String.format("%s is back to its default for this workspace: %s, not blocked.", spec.name(),
                    spec.enabledByDefault() ? "on" : "off");
                break;
            default:
                return new ResponseDto(ERROR, "state is enabled, disabled, blocked or default.");
        }
        this.logger.info("Task {} set to {} for workspace {} by platform administrator {}.", spec.code(), state, tenantId, me);
        List<Map<String, Object>> entries = this.registry.entries(tenantId);
        attachNames(entries, this.names);
        Map<String, Object> entry = entries.stream().filter(one -> spec.code().equals(one.get("code"))).findFirst().orElse(null);
        return new ResponseDto(SUCCESS, message, entry);
    }

    /** A task's (or every task's) switch and block changes in the workspace, newest first. */
    public ResponseDto history(String code, Long requestedTenantId) {
        Long tenantId = TenantContext.isPlatformAdmin() ? requestedTenantId : TenantContext.getTenantId();
        if (tenantId == null || tenantId < 1) {
            return new ResponseDto(ERROR, TenantContext.isPlatformAdmin()
                ? "Say which workspace (tenantId): a platform administrator reads one workspace's changes at a time."
                : "Tasks are switched in a workspace; this caller has none.");
        }
        String task = code == null || code.trim().isEmpty() ? null : code.trim();
        List<TaskOverrideStore.Audit> rows = this.registry.store().audits(tenantId, task, HISTORY_LIMIT);
        Set<Long> people = new HashSet<>();
        rows.forEach(a -> { if (a.actorUserId != null) people.add(a.actorUserId); });
        Map<Long, String> named = safeNames(this.names, people);
        List<Map<String, Object>> out = new ArrayList<>();
        for (TaskOverrideStore.Audit a : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", a.taskCode);
            row.put("action", a.action);
            row.put("from", a.before);
            row.put("to", a.after);
            row.put("reason", a.note);
            row.put("by", a.actorUserId);
            row.put("byName", a.actorUserId == null ? null : named.get(a.actorUserId));
            row.put("byRole", a.actorRole);
            row.put("at", a.at == null ? null : a.at.toInstant().toString());
            out.add(row);
        }
        return new ResponseDto(SUCCESS, String.format("%d change(s).", out.size()), out);
    }

    /** default, on or off: a switch as the audit writes it. */
    static String switchWord(TaskOverrideStore.Switch sw) {
        return sw == null ? "default" : sw.enabled ? "on" : "off";
    }

    /** switchedByName and blockedByName on each entry, from the ids the registry put there. */
    static void attachNames(List<Map<String, Object>> entries, UserNameResolver names) {
        Set<Long> people = new HashSet<>();
        for (Map<String, Object> entry : entries) {
            for (String key : new String[] { "switchedBy", "blockedBy" }) {
                Object id = entry.get(key);
                if (id instanceof Number) {
                    people.add(((Number) id).longValue());
                }
            }
        }
        Map<Long, String> named = safeNames(names, people);
        for (Map<String, Object> entry : entries) {
            if (entry.containsKey("switchedBy")) {
                entry.put("switchedByName", entry.get("switchedBy") instanceof Number ? named.get(((Number) entry.get("switchedBy")).longValue()) : null);
            }
            if (entry.containsKey("blockedBy")) {
                entry.put("blockedByName", entry.get("blockedBy") instanceof Number ? named.get(((Number) entry.get("blockedBy")).longValue()) : null);
            }
        }
    }

    private static Map<Long, String> safeNames(UserNameResolver names, Collection<Long> ids) {
        if (names == null || ids.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<Long, String> got = names.namesFor(ids);
            return got == null ? new LinkedHashMap<>() : got;
        } catch (RuntimeException unknown) {
            return new LinkedHashMap<>();
        }
    }
}
