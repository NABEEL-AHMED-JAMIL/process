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

    public boolean enabled(StepTask task, Map<String, Boolean> switches) {
        if (task.unavailable().isPresent()) {
            return false;
        }
        TaskSpec spec = task.spec();
        if (!spec.overridable()) {
            return spec.enabledByDefault();
        }
        Boolean own = switches.get(task.code());
        return own == null ? spec.enabledByDefault() : own;
    }

    /**
     * Why this task cannot be in a pipeline for this caller: unavailable, disabled in the workspace, or needing a role
     * the caller does not have. {@code role} null skips the role (the engine, running a definition an admin saved).
     */
    public Optional<String> refusal(StepTask task, Map<String, Boolean> switches, String role) {
        Optional<String> unavailable = task.unavailable();
        if (unavailable.isPresent()) {
            return Optional.of(String.format("the task '%s' is not available: %s", task.code(), unavailable.get()));
        }
        if (!this.enabled(task, switches)) {
            return Optional.of(String.format("the task '%s' is disabled in this workspace", task.code()));
        }
        if (role != null && rank(role) < rank(task.spec().requiredRole())) {
            return Optional.of(String.format("the task '%s' needs the %s role", task.code(), task.spec().requiredRole()));
        }
        return Optional.empty();
    }

    /** The registry as this workspace sees it: each entry with its enabled, and why not when it is off. */
    public List<Map<String, Object>> entries(Long tenantId) {
        Map<String, Boolean> switches = this.overridesOf(tenantId);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (StepTask task : this.tasks.all()) {
            Map<String, Object> entry = task.spec().toEntry();
            entry.put("runsInEngine", task.runsInEngine());
            boolean enabled = this.enabled(task, switches);
            entry.put("enabled", enabled);
            entry.put("overridden", switches.containsKey(task.code()) && task.spec().overridable());
            entry.put("available", !task.unavailable().isPresent());
            entry.put("disabledReason", enabled ? null : task.unavailable().orElse(switches.containsKey(task.code())
                ? "Switched off in this workspace." : "Off by default; a workspace admin can switch it on."));
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
