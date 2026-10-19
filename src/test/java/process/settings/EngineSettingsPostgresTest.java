package process.settings;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchPostgres;
import process.model.dto.ResponseDto;
import process.security.TenantContext;
import process.util.UserNameResolver;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Owner #24 on etl_job as the changelog builds it (V214), through the application's pool (process_app, row security): a
 * platform administrator's change of the platform's value and a workspace's own, both read by the engine (EngineDials)
 * right after the change, the audit of each, a reset back to the default, and the table's own guard on what a value is.
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL.
 */
class EngineSettingsPostgresTest {

    static final long A = 8971L;
    static final long B = 8972L;
    static ScratchPostgres db;
    static JdbcTemplate login;
    static JdbcTemplate app;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("engine_settings");
        login = db.jdbc();
        app = db.appJdbc();
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aChangeIsReadByTheEngineAtOnceAuditedAndResetToTheDefault() {
        UserNameResolver names = mock(UserNameResolver.class);
        when(names.namesFor(any())).thenReturn(Collections.singletonMap(9001L, "QA platform admin"));
        EngineDials dials = new EngineDials(app, null);
        EngineSettingsService service = new EngineSettingsService(new OrchestrationSettings(app), dials, new EngineSettingStore(app), names);
        TenantContext.set(null, "PLATFORM_ADMIN", 9001L, "qa-platform-admin@platform.local");

        assertThat(dials.forWorkspace(EngineDial.RUN_MEMORY_MB, A)).isEqualTo(128L);
        ResponseDto platform = service.update("RUN_MEMORY_MB", "256", null);
        assertThat(platform.getStatus()).as(platform.getMessage()).isEqualTo("SUCCESS");
        ResponseDto own = service.update("RUN_MEMORY_MB", "1024", A);
        assertThat(own.getStatus()).as(own.getMessage()).isEqualTo("SUCCESS");

        assertThat(dials.forWorkspace(EngineDial.RUN_MEMORY_MB, A)).as("A's own").isEqualTo(1024L);
        assertThat(dials.forWorkspace(EngineDial.RUN_MEMORY_MB, B)).as("the platform's").isEqualTo(256L);
        assertThat(login.queryForObject("SELECT tenant_id FROM engine_setting_override", Long.class)).isEqualTo(A);

        Map<String, Object> listed = (Map<String, Object>) service.list().getData();
        Map<String, Object> memory = ((List<Map<String, Object>>) listed.get("settings")).stream()
            .filter(r -> "RUN_MEMORY_MB".equals(r.get("key"))).findFirst().orElseThrow(IllegalStateException::new);
        assertThat(memory).containsEntry("value", 256L).containsEntry("atDefault", false).containsEntry("updatedByName", "QA platform admin");
        assertThat((List<Map<String, Object>>) memory.get("overrides")).singleElement()
            .satisfies(o -> assertThat(o).containsEntry("tenantId", A).containsEntry("value", 1024L));

        assertThat(service.reset("RUN_MEMORY_MB", A).getStatus()).isEqualTo("SUCCESS");
        assertThat(service.reset("RUN_MEMORY_MB", null).getStatus()).isEqualTo("SUCCESS");
        assertThat(dials.forWorkspace(EngineDial.RUN_MEMORY_MB, A)).isEqualTo(128L);
        assertThat(login.queryForObject("SELECT count(*) FROM orchestration_setting WHERE setting_key = 'RUN_MEMORY_MB'", Integer.class)).isZero();

        List<Map<String, Object>> history = (List<Map<String, Object>>) service.history("RUN_MEMORY_MB", 10).getData();
        assertThat(history).extracting(h -> h.get("action") + " " + h.get("tenantId") + " " + h.get("oldValue") + ">" + h.get("newValue"))
            .containsExactly("RESET null 256>128", "RESET " + A + " 1024>256", "SET " + A + " null>1024", "SET null null>256");
        assertThat(history).extracting(h -> h.get("changedByName")).containsOnly("QA platform admin");
    }

    @Test
    void aWorkspaceSeesNoOtherWorkspacesValuesAndTheTableRefusesAValueThatIsNotANumber() {
        login.update("INSERT INTO engine_setting_override (tenant_id, setting_key, setting_value) VALUES (?, 'BACKFILL_MAX_OPEN', '7') "
            + "ON CONFLICT DO NOTHING", B);
        TenantContext.set(A, "TENANT_ADMIN", 9011L, "admin@a");
        assertThat(app.queryForObject("SELECT count(*) FROM engine_setting_override", Integer.class)).isZero();
        assertThat(app.queryForObject("SELECT count(*) FROM engine_setting_audit", Integer.class)).isZero();
        TenantContext.clear();
        assertThatThrownBy(() -> login.update("INSERT INTO engine_setting_override (tenant_id, setting_key, setting_value) "
            + "VALUES (?, 'RUN_MEMORY_MB', '12 MB')", A)).hasMessageContaining("ck_engine_setting_override_value");
        login.update("DELETE FROM engine_setting_override WHERE tenant_id = ?", B);
    }
}
