package process.pipeline.registry;

import java.util.Collections;
import java.util.Map;

/**
 * A workspace's own switches on registered tasks (MIG-231): task_registry_override, one row per task a workspace has
 * turned on or off against its default. Tenant rows under row-level security (V186): every read and write is the
 * session's workspace's.
 */
public interface TaskOverrideStore {

    /** No overrides anywhere: every task at its default. The registry of a test, or of a validator built bare. */
    TaskOverrideStore NONE = new TaskOverrideStore() {
        @Override
        public Map<String, Boolean> overrides(long tenantId) {
            return Collections.emptyMap();
        }

        @Override
        public void set(long tenantId, String taskCode, boolean enabled, Long userId) {
            throw new UnsupportedOperationException("No override store.");
        }

        @Override
        public boolean clear(long tenantId, String taskCode) {
            return false;
        }
    };

    /** task code -> enabled, for the tasks this workspace has overridden. */
    Map<String, Boolean> overrides(long tenantId);

    void set(long tenantId, String taskCode, boolean enabled, Long userId);

    /** Back to the default; whether there was an override. */
    boolean clear(long tenantId, String taskCode);
}
