package process.pipeline.registry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.pipeline.StepTask;
import process.pipeline.StepTasks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Task Registry (MIG-231): every registered step task's entry, and whether a workspace may use it.
 *
 * <b>Code-registered, with per-workspace switches.</b> The entries are the {@link StepTask} beans' own {@link TaskSpec}s
 * -- a task's schema and code change in one commit, and no row can describe a task this build does not have. What a
 * workspace decides is only whether an overridable task is on: task_registry_override (V186), one row per task it has
 * switched against the default.
 *
 * <b>Enabled</b> = available here (the other service's side exists and is switched on in this Core's configuration)
 * and (the workspace's override, else the task's default). {@code legacy} is not overridable and never unavailable:
 * every existing pipeline stays runnable. A disabled task cannot be added to a pipeline (validate and save refuse it)
 * and a run whose pinned definition names one is declined, as a run naming a task that no longer exists is.
 *
 * <b>Blocked</b> (owner #16, V215): a platform administrator's block of a task for one workspace -- off whatever the
 * workspace's switch says, refused at design and save time and at run time with a message naming the task, and the
 * workspace's administrator cannot switch it on. The platform default stays each task's own (enabledByDefault).
 */
@Component
public class TaskRegistry {

    private final StepTasks tasks;
    private final TaskOverrideStore overrides;

    @Autowired
    public TaskRegistry(StepTasks tasks, TaskOverrideStore overrides) {
        this.tasks = tasks;
        this.overrides = overrides == null ? TaskOverrideStore.NONE : overrides;
    }

    /** Every task at its default: no workspace's switches. */
    public static TaskRegistry defaults(StepTasks tasks) {
        return new TaskRegistry(tasks, TaskOverrideStore.NONE);
    }

    public StepTasks tasks() {
        return this.tasks;
    }

    /** This workspace's switches; none for no workspace. */
    public Map<String, Boolean> overridesOf(Long tenantId) {
        return tenantId == null ? Collections.<String, Boolean>emptyMap() : this.overrides.overrides(tenantId);
    }

    /** This workspace's switches (who set each) and blocks; none for no workspace. */
    public TaskControls controlsOf(Long tenantId) {
        if (tenantId == null) {
            return TaskControls.NONE;
        }
        return new TaskControls(this.overrides.switchRows(tenantId), this.overrides.blocks(tenantId));
    }

    public TaskOverrideStore store() {
        return this.overrides;
    }

    public boolean enabled(StepTask task, Map<String, Boolean> switches) {
        return this.enabled(task, TaskControls.of(switches));
    }

    public boolean enabled(StepTask task, TaskControls controls) {
        if (task.unavailable().isPresent()) {
            return false;
        }
        TaskSpec spec = task.spec();
        if (!spec.overridable()) {
            return spec.enabledByDefault();
        }
        if (controls.blocked(task.code())) {
            return false;
        }
        Boolean own = controls.switched(task.code());
        return own == null ? spec.enabledByDefault() : own;
    }

    /** unavailable, blocked, on or off: what the registry and the console say a task is in this workspace. */
    public String effectiveState(StepTask task, TaskControls controls) {
        if (task.unavailable().isPresent()) {
            return "unavailable";
        }
        if (task.spec().overridable() && controls.blocked(task.code())) {
            return "blocked";
        }
        return this.enabled(task, controls) ? "on" : "off";
    }

    /**
     * Why this task cannot be in a pipeline for this caller: unavailable, disabled in the workspace, or needing a role
     * the caller does not have. {@code role} null skips the role (the engine, running a definition an admin saved).
     */
    public Optional<String> refusal(StepTask task, Map<String, Boolean> switches, String role) {
        return this.refusal(task, TaskControls.of(switches), role);
    }

    public Optional<String> refusal(StepTask task, TaskControls controls, String role) {
        Optional<String> unavailable = task.unavailable();
        if (unavailable.isPresent()) {
            return Optional.of(String.format("the task '%s' is not available: %s", task.code(), unavailable.get()));
        }
        TaskOverrideStore.Block block = task.spec().overridable() ? controls.blockOf(task.code()) : null;
        if (block != null) {
            return Optional.of(String.format("the task '%s' (%s) is blocked for this workspace by a platform administrator%s", task.code(),
                task.spec().name(), block.reason == null || block.reason.trim().isEmpty() ? "" : ": " + block.reason.trim()));
        }
        if (!this.enabled(task, controls)) {
            return Optional.of(String.format("the task '%s' is disabled in this workspace", task.code()));
        }
        if (role != null && rank(role) < rank(task.spec().requiredRole())) {
            return Optional.of(String.format("the task '%s' needs the %s role", task.code(),
                TaskSpec.ROLE_ADMIN.equals(task.spec().requiredRole()) ? "tenant administrator" : "tenant user"));
        }
        return Optional.empty();
    }

    /** The registry as this workspace sees it: each entry with its enabled, and why not when it is off. */
    public List<Map<String, Object>> entries(Long tenantId) {
        return this.entries(this.controlsOf(tenantId));
    }

    /**
     * The registry with these controls. Owner #16: each entry also says its effective state here (unavailable, blocked,
     * off, on), who set it (default, workspace, platform), the switch's and the block's who and when, and the block's
     * reason; the people's names are the caller's to add (ids here).
     */
    public List<Map<String, Object>> entries(TaskControls controls) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (StepTask task : this.tasks.all()) {
            Map<String, Object> entry = task.spec().toEntry();
            entry.put("runsInEngine", task.runsInEngine());
            boolean overridable = task.spec().overridable();
            TaskOverrideStore.Switch sw = overridable ? controls.switchOf(task.code()) : null;
            TaskOverrideStore.Block block = overridable ? controls.blockOf(task.code()) : null;
            boolean enabled = this.enabled(task, controls);
            entry.put("enabled", enabled);
            entry.put("overridden", sw != null);
            entry.put("available", !task.unavailable().isPresent());
            String reason = null;
            if (!enabled) {
                if (task.unavailable().isPresent()) {
                    reason = task.unavailable().get();
                } else if (block != null) {
                    reason = "Blocked for this workspace by a platform administrator" + (block.reason == null || block.reason.trim().isEmpty()
                        ? "." : ": " + block.reason.trim());
                } else if (sw != null) {
                    reason = "PLATFORM_ADMIN".equals(sw.updatedByRole) ? "Switched off for this workspace by a platform administrator."
                        : "Switched off in this workspace.";
                } else {
                    reason = "Off by default; a workspace admin can switch it on.";
                }
            }
            entry.put("disabledReason", reason);
            entry.put("effectiveState", this.effectiveState(task, controls));
            entry.put("setBy", block != null ? "platform" : sw == null ? "default" : "PLATFORM_ADMIN".equals(sw.updatedByRole) ? "platform" : "workspace");
            entry.put("switchedBy", sw == null ? null : sw.updatedBy);
            entry.put("switchedByRole", sw == null ? null : sw.updatedByRole);
            entry.put("switchedAt", sw == null || sw.at == null ? null : sw.at.toInstant().toString());
            entry.put("blocked", block != null);
            entry.put("blockedBy", block == null ? null : block.blockedBy);
            entry.put("blockedAt", block == null || block.at == null ? null : block.at.toInstant().toString());
            entry.put("blockReason", block == null ? null : block.reason);
            entries.add(entry);
        }
        return entries;
    }

    /** TENANT_USER 1, TENANT_ADMIN 2, PLATFORM_ADMIN 3; anything else 0. */
    static int rank(String role) {
        if (role == null) {
            return 0;
        }
        switch (role) {
            case "PLATFORM_ADMIN":
                return 3;
            case TaskSpec.ROLE_ADMIN:
                return 2;
            case TaskSpec.ROLE_USER:
                return 1;
            default:
                return 0;
        }
    }
}
