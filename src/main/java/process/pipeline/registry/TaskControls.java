package process.pipeline.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One workspace's controls on the registry's tasks, read once for a check (owner #16): its switches (who set each) and a
 * platform administrator's blocks. A blocked task is off whatever the switch says.
 */
public final class TaskControls {

    public static final TaskControls NONE = new TaskControls(Collections.emptyMap(), Collections.emptyMap());

    private final Map<String, TaskOverrideStore.Switch> switches;
    private final Map<String, TaskOverrideStore.Block> blocks;

    public TaskControls(Map<String, TaskOverrideStore.Switch> switches, Map<String, TaskOverrideStore.Block> blocks) {
        this.switches = switches == null ? Collections.emptyMap() : switches;
        this.blocks = blocks == null ? Collections.emptyMap() : blocks;
    }

    /** Switches only, nobody named, nothing blocked: what a caller with a plain map of switches has. */
    public static TaskControls of(Map<String, Boolean> switches) {
        Map<String, TaskOverrideStore.Switch> rows = new LinkedHashMap<>();
        if (switches != null) {
            switches.forEach((code, on) -> rows.put(code, new TaskOverrideStore.Switch(Boolean.TRUE.equals(on), null, null, null)));
        }
        return new TaskControls(rows, Collections.emptyMap());
    }

    /** The workspace's own switch, or null for the task's default. */
    public Boolean switched(String code) {
        TaskOverrideStore.Switch row = this.switches.get(code);
        return row == null ? null : row.enabled;
    }

    public TaskOverrideStore.Switch switchOf(String code) {
        return this.switches.get(code);
    }

    public TaskOverrideStore.Block blockOf(String code) {
        return this.blocks.get(code);
    }

    public boolean blocked(String code) {
        return this.blocks.containsKey(code);
    }

    public Map<String, TaskOverrideStore.Switch> switches() {
        return Collections.unmodifiableMap(this.switches);
    }

    public Map<String, TaskOverrideStore.Block> blocks() {
        return Collections.unmodifiableMap(this.blocks);
    }
}
