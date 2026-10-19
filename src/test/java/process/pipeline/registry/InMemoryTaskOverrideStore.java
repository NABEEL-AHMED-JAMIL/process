package process.pipeline.registry;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** task_registry_override, task_registry_block and task_registry_audit in memory, for unit tests. */
public class InMemoryTaskOverrideStore implements TaskOverrideStore {

    public final Map<Long, Map<String, Boolean>> rows = new ConcurrentHashMap<>();
    public final Map<Long, Map<String, Switch>> switchRows = new ConcurrentHashMap<>();
    public final Map<Long, Map<String, Block>> blockRows = new ConcurrentHashMap<>();
    public final List<Object[]> audit = new ArrayList<>();
    private long nextAudit = 1000;

    @Override
    public Map<String, Boolean> overrides(long tenantId) {
        return new LinkedHashMap<>(this.rows.getOrDefault(tenantId, new LinkedHashMap<>()));
    }

    @Override
    public Map<String, Switch> switchRows(long tenantId) {
        Map<String, Switch> out = new LinkedHashMap<>();
        Map<String, Switch> who = this.switchRows.getOrDefault(tenantId, new LinkedHashMap<>());
        this.overrides(tenantId).forEach((code, on) -> out.put(code, who.containsKey(code) ? who.get(code) : new Switch(on, null, null, null)));
        return out;
    }

    @Override
    public void set(long tenantId, String taskCode, boolean enabled, Long userId) {
        this.set(tenantId, taskCode, enabled, userId, null);
    }

    @Override
    public void set(long tenantId, String taskCode, boolean enabled, Long userId, String role) {
        this.rows.computeIfAbsent(tenantId, absent -> new LinkedHashMap<>()).put(taskCode, enabled);
        this.switchRows.computeIfAbsent(tenantId, absent -> new LinkedHashMap<>()).put(taskCode,
            new Switch(enabled, userId, role, OffsetDateTime.now()));
    }

    @Override
    public boolean clear(long tenantId, String taskCode) {
        Map<String, Boolean> own = this.rows.get(tenantId);
        Map<String, Switch> who = this.switchRows.get(tenantId);
        if (who != null) {
            who.remove(taskCode);
        }
        return own != null && own.remove(taskCode) != null;
    }

    @Override
    public Map<String, Block> blocks(long tenantId) {
        return new LinkedHashMap<>(this.blockRows.getOrDefault(tenantId, new LinkedHashMap<>()));
    }

    @Override
    public void block(long tenantId, String taskCode, String reason, long blockedBy) {
        this.blockRows.computeIfAbsent(tenantId, absent -> new LinkedHashMap<>()).put(taskCode, new Block(reason, blockedBy, OffsetDateTime.now()));
    }

    @Override
    public boolean unblock(long tenantId, String taskCode) {
        Map<String, Block> own = this.blockRows.get(tenantId);
        return own != null && own.remove(taskCode) != null;
    }

    @Override
    public synchronized void audit(long tenantId, String taskCode, String action, String before, String after, String note, Long actorUserId,
        String actorRole) {
        this.audit.add(new Object[] { this.nextAudit++, tenantId, taskCode, action, before, after, note, actorUserId, actorRole });
    }

    @Override
    public synchronized List<Audit> audits(long tenantId, String taskCode, int limit) {
        List<Audit> out = new ArrayList<>();
        for (int i = this.audit.size() - 1; i >= 0 && out.size() < limit; i--) {
            Object[] row = this.audit.get(i);
            if ((long) row[1] != tenantId || (taskCode != null && !taskCode.equals(row[2]))) {
                continue;
            }
            Audit a = new Audit();
            a.id = (long) row[0];
            a.taskCode = (String) row[2];
            a.action = (String) row[3];
            a.before = (String) row[4];
            a.after = (String) row[5];
            a.note = (String) row[6];
            a.actorUserId = (Long) row[7];
            a.actorRole = (String) row[8];
            a.at = OffsetDateTime.now();
            out.add(a);
        }
        return out;
    }
}
