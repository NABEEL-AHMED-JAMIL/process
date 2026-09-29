package process.pipeline.registry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** task_registry_override in memory, for unit tests. */
public class InMemoryTaskOverrideStore implements TaskOverrideStore {

    public final Map<Long, Map<String, Boolean>> rows = new ConcurrentHashMap<>();

    @Override
    public Map<String, Boolean> overrides(long tenantId) {
        return new LinkedHashMap<>(this.rows.getOrDefault(tenantId, new LinkedHashMap<>()));
    }

    @Override
    public void set(long tenantId, String taskCode, boolean enabled, Long userId) {
        this.rows.computeIfAbsent(tenantId, absent -> new LinkedHashMap<>()).put(taskCode, enabled);
    }

    @Override
    public boolean clear(long tenantId, String taskCode) {
        Map<String, Boolean> own = this.rows.get(tenantId);
        return own != null && own.remove(taskCode) != null;
    }
}
