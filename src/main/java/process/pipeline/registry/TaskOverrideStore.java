package process.pipeline.registry;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A workspace's own controls on registered tasks: its switches (MIG-231, task_registry_override -- one row per task it
 * has turned on or off against its default), a platform administrator's blocks (owner #16, task_registry_block), and the
 * audit of both (task_registry_audit). Tenant rows under row-level security (V186, V215): every read and write is the
 * session's workspace's, or the named one's for a platform administrator.
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

    /** One workspace switch: on or off, and who set it (as which role) when. */
    final class Switch {
        public final boolean enabled;
        public final Long updatedBy;
        public final String updatedByRole;
        public final OffsetDateTime at;

        public Switch(boolean enabled, Long updatedBy, String updatedByRole, OffsetDateTime at) {
            this.enabled = enabled;
            this.updatedBy = updatedBy;
            this.updatedByRole = updatedByRole;
            this.at = at;
        }
    }

    /** Owner #16: a platform administrator's block of one task for one workspace. */
    final class Block {
        public final String reason;
        public final long blockedBy;
        public final OffsetDateTime at;

        public Block(String reason, long blockedBy, OffsetDateTime at) {
            this.reason = reason;
            this.blockedBy = blockedBy;
            this.at = at;
        }
    }

    /** Owner #16: one change of a task's control in a workspace. */
    final class Audit {
        public long id;
        public String taskCode;
        public String action;
        public String before;
        public String after;
        public String note;
        public Long actorUserId;
        public String actorRole;
        public OffsetDateTime at;
    }

    /** task code -> enabled, for the tasks this workspace has overridden. */
    Map<String, Boolean> overrides(long tenantId);

    void set(long tenantId, String taskCode, boolean enabled, Long userId);

    /** Back to the default; whether there was an override. */
    boolean clear(long tenantId, String taskCode);

    /** The switches with who set them; a store that keeps no more than the switch says nobody. */
    default Map<String, Switch> switchRows(long tenantId) {
        Map<String, Switch> rows = new LinkedHashMap<>();
        this.overrides(tenantId).forEach((code, enabled) -> rows.put(code, new Switch(enabled, null, null, null)));
        return rows;
    }

    /** A switch set by a person of this role (TENANT_ADMIN, or PLATFORM_ADMIN for the workspace). */
    default void set(long tenantId, String taskCode, boolean enabled, Long userId, String role) {
        this.set(tenantId, taskCode, enabled, userId);
    }

    /** task code -> block, for the tasks a platform administrator has blocked for this workspace. */
    default Map<String, Block> blocks(long tenantId) {
        return Collections.emptyMap();
    }

    default void block(long tenantId, String taskCode, String reason, long blockedBy) {
        throw new UnsupportedOperationException("No block store.");
    }

    /** Lifts a block; whether there was one. The switch stays as it was. */
    default boolean unblock(long tenantId, String taskCode) {
        return false;
    }

    /** Records one change (insert-only). A store without an audit records nothing. */
    default void audit(long tenantId, String taskCode, String action, String before, String after, String note, Long actorUserId,
        String actorRole) {
    }

    /** The changes in this workspace, newest first: one task's, or every task's (taskCode null). */
    default List<Audit> audits(long tenantId, String taskCode, int limit) {
        return Collections.emptyList();
    }
}
