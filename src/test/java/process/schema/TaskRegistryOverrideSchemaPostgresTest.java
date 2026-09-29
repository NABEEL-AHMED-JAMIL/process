package process.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import process.pipeline.registry.JdbcTaskOverrideStore;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V186 (MIG-231) as the changelog builds it: task_registry_override -- one switch per task per workspace, never for
 * legacy, guarded as every tenant table (V181) -- read and written by JdbcTaskOverrideStore; V186 rolls back to exactly
 * what was there before and applies again.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchEtlJob).
 */
class TaskRegistryOverrideSchemaPostgresTest {

    static final String V186 = "186.0-task-registry-override";
    private static final long A = 8631L;
    private static final long B = 8632L;

    private static ScratchEtlJob db;
    private static JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("task_registry_v186");
        sql = db.sql();
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void oneSwitchPerTaskPerWorkspaceSavedAgainIsUpdatedAndClearedIsTheDefault() {
        JdbcTaskOverrideStore store = new JdbcTaskOverrideStore(sql);
        store.set(A, "write_database", true, 7L);
        store.set(A, "select", true, 7L);
        store.set(A, "select", false, 8L);
        store.set(B, "select", true, 9L);
        assertThat(store.overrides(A)).containsExactly(entry("select", false), entry("write_database", true));
        assertThat(sql.queryForObject("SELECT updated_by FROM task_registry_override WHERE tenant_id = ? AND task_code = 'select'",
            Long.class, A)).isEqualTo(8L);
        assertThat(store.clear(A, "select")).isTrue();
        assertThat(store.clear(A, "select")).isFalse();
        assertThat(store.overrides(A)).containsOnlyKeys("write_database");
        assertThat(store.overrides(B)).containsExactly(entry("select", true));
        assertThat(sql.queryForObject("SELECT min(task_registry_override_id) FROM task_registry_override", Long.class)).isGreaterThanOrEqualTo(1000L);
    }

    private static java.util.Map.Entry<String, Boolean> entry(String key, Boolean value) {
        return new java.util.AbstractMap.SimpleEntry<>(key, value);
    }

    @Test
    void legacyIsNeverSwitchedAndACodeIsACode() {
        assertThatThrownBy(() -> sql.update("INSERT INTO task_registry_override (tenant_id, task_code, enabled) VALUES (?, 'legacy', false)", A))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_task_registry_override_not_legacy");
        assertThatThrownBy(() -> sql.update("INSERT INTO task_registry_override (tenant_id, task_code, enabled) VALUES (?, 'Read API', true)", A))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_task_registry_override_code");
        assertThatThrownBy(() -> sql.update("INSERT INTO task_registry_override (tenant_id, task_code, enabled) VALUES (0, 'filter', true)"))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_task_registry_override_tenant");
    }

    @Test
    void theTableIsGuardedAndTheApplicationSeesAndWritesItsOwnWorkspaceOnly() throws Exception {
        assertThat(sql.queryForMap("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = 'public.task_registry_override'::regclass"))
            .containsEntry("relrowsecurity", true).containsEntry("relforcerowsecurity", true);
        assertThat(sql.queryForObject("SELECT qual FROM pg_policies WHERE tablename = 'task_registry_override' AND policyname = 'tenant_isolation'",
            String.class)).isEqualTo(sql.queryForObject("SELECT qual FROM pg_policies WHERE tablename = 'run_ai_step'", String.class));
        sql.update("INSERT INTO task_registry_override (tenant_id, task_code, enabled) VALUES (?, 'join', false) ON CONFLICT DO NOTHING", A);
        sql.update("INSERT INTO task_registry_override (tenant_id, task_code, enabled) VALUES (?, 'join', false) ON CONFLICT DO NOTHING", B);
        try (Connection connection = db.connect()) {
            connection.createStatement().execute("SET ROLE process_app");
            connection.createStatement().execute("SELECT set_config('app.tenant_id', '" + A + "', false)");
            ResultSet seen = connection.createStatement().executeQuery("SELECT DISTINCT tenant_id FROM task_registry_override");
            List<Long> tenants = new ArrayList<>();
            while (seen.next()) {
                tenants.add(seen.getLong(1));
            }
            assertThat(tenants).containsExactly(A);
            assertThatThrownBy(() -> connection.createStatement().execute("INSERT INTO task_registry_override (tenant_id, task_code, enabled) "
                + "VALUES (" + B + ", 'aggregate', false)")).hasMessageContaining("row-level security");
            assertThat(connection.createStatement().executeUpdate("DELETE FROM task_registry_override WHERE tenant_id = " + B))
                .as("B's switches are not A's to remove").isZero();
        }
    }

    @Test
    void theRollbackTakesItOffAndV186AppliesAgain() throws Exception {
        int after = sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE orderexecuted > "
            + "(SELECT orderexecuted FROM databasechangelog WHERE id = ?)", Integer.class, V186);
        if (after > 0) {
            db.rollback(after);
        }
        db.rollback(1);
        try {
            assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_name = 'task_registry_override'", Long.class)).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM pg_sequences WHERE schemaname = 'public' "
                + "AND sequencename = 'task_registry_override_seq'", Long.class)).isZero();
        } finally {
            db.finish();
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE id = ?", Long.class, V186)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM pg_policies WHERE tablename = 'task_registry_override'", Long.class)).isEqualTo(1);
    }
}
