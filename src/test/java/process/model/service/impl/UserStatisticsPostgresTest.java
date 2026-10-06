package process.model.service.impl;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.schema.ScratchEtlJob;
import process.security.TenantContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-107: the people's work, counted by Core alone for the ids Identity listed -- executed against the
 * real schema. Opt-in, like every ScratchEtlJob test.
 */
class UserStatisticsPostgresTest {

    private static ScratchEtlJob db;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("user_stats");
        JdbcTemplate sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (2901, 'Active', 'CHS', 'CareBridge')");
        // Before the cutover (V69.1) source_job still keys its assignee to app_user.
        sql.update("INSERT INTO app_user (app_user_id, tenant_id, full_name, password, status, user_role, username) VALUES "
            + "(7, 2901, 'Olivia', 'x', 'Active', 'TENANT_USER', 'olivia@x.example'), (8, 2901, 'Omar', 'x', 'Active', 'TENANT_USER', 'omar@x.example')");
        // Olivia (7): two live jobs, one Inactive, runs Completed, Failed and one outside the range. Omar (8): a deleted job only.
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id, assigned_user_id) "
            + "VALUES (1, '2026-09-01', 'Auto', 'a', 'Active', 1, 2901, 7), (2, '2026-09-01', 'Auto', 'b', 'Inactive', 1, 2901, 7), "
            + "(3, '2026-09-01', 'Auto', 'c', 'Delete', 1, 2901, 8)");
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status) VALUES "
            + "(10, '2026-09-21 10:00', 1, 'Completed', 'Active'), (11, '2026-09-21 11:00', 1, 'Failed', 'Active'), "
            + "(12, '2026-08-01 11:00', 2, 'Completed', 'Active'), (13, '2026-09-21 11:00', 3, 'Completed', 'Active')");
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
    void eachListedPersonsWorkIsCountedAndNobodyElses() {
        TenantContext.set(2901L, "TENANT_ADMIN", 42L, "ops@carebridge.test");

        List<Map<String, Object>> rows = db.sql().queryForList(
            new QueryService().userStatistics("2026-09-01", "2026-09-30", Arrays.asList(7L, 8L)));

        assertThat(rows).hasSize(1);
        Map<String, Object> olivia = rows.get(0);
        assertThat(((Number) olivia.get("assigned_user_id")).longValue()).isEqualTo(7L);
        assertThat(((Number) olivia.get("job_count")).intValue()).isEqualTo(2);
        assertThat(((Number) olivia.get("active_jobs")).intValue()).isEqualTo(1);
        assertThat(((Number) olivia.get("run_count")).intValue()).as("the August run is outside the range").isEqualTo(2);
        assertThat(((Number) olivia.get("completed_count")).intValue()).isEqualTo(1);
        assertThat(((Number) olivia.get("failed_count")).intValue()).isEqualTo(1);
    }

    @Test
    void anotherWorkspacesCallerCountsNothingHere() {
        TenantContext.set(2999L, "TENANT_ADMIN", 42L, "ops@elsewhere.test");

        assertThat(db.sql().queryForList(new QueryService().userStatistics(null, null, Arrays.asList(7L)))).isEmpty();
    }

    @Test
    void nobodyListedCountsNothingEvenInTheirOwnWorkspace() {
        TenantContext.set(2901L, "TENANT_ADMIN", 42L, "ops@carebridge.test");

        assertThat(db.sql().queryForList(new QueryService().userStatistics(null, null, Collections.emptyList()))).isEmpty();
    }
}
