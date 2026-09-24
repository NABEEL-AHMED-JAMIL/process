package process.schema;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.time.TimestamptzRehearsal;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MIG-167: V140-V144 on a full copy of a long-lived etl_job -- row by row, what happened to each lookup_data row.
 *
 * Like TimestamptzRehearsal, not part of `mvn test`: scripts/rehearse-lookup-decomposition.sh makes the copy, runs
 * this, and drops it. Every lookup_data row is accounted for: a home page or group is in task_reference with the same
 * id, workspace, name and value; a watermark is in orchestration_setting with the same value; the owner's three
 * residue rows are gone; nothing else changed. Tasks point at the same ids. Then the rollback, and the keys back.
 */
class LookupDecompositionRehearsal {

    @Test
    void aFullCopyIsDecomposedRowByRow() throws Exception {
        String server = System.getenv("NOTIFICATIONS_TEST_DB_URL");
        String copy = System.getenv("LOOKUP_REHEARSAL_DB");
        assumeTrue(server != null && copy != null, "NOTIFICATIONS_TEST_DB_URL and LOOKUP_REHEARSAL_DB are not set");
        assertThat(copy).as("a copy, never the live database").isNotEqualTo("etl_job").matches("[a-z0-9_]+");
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setJdbcUrl(server.replaceAll("/[^/?]+(\\?.*)?$", "/" + copy + "$1"));
            pool.setUsername(System.getenv("NOTIFICATIONS_TEST_DB_USER"));
            pool.setPassword(System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
            pool.setMaximumPoolSize(2);
            JdbcTemplate sql = new JdbcTemplate(pool);

            List<Map<String, Object>> before = sql.queryForList("SELECT l.lookup_id, l.lookup_type, l.lookup_value, l.tenant_id, "
                + "l.is_encrypted, l.description, p.lookup_type AS family FROM lookup_data l "
                + "LEFT JOIN lookup_data p ON p.lookup_id = l.parent_lookup_id ORDER BY l.lookup_id");
            List<Map<String, Object>> tasksBefore = sql.queryForList(
                "SELECT task_detail_id, home_page_id, group_id FROM source_task ORDER BY task_detail_id");
            String fetchLimitBefore = sql.queryForObject(
                "SELECT setting_value FROM orchestration_setting WHERE setting_key = 'QUEUE_FETCH_LIMIT'", String.class);
            List<String> had = sql.queryForList("SELECT id FROM databasechangelog", String.class);

            TimestamptzRehearsal.liquibase(pool, null);

            List<String> ran = sql.queryForList("SELECT id FROM databasechangelog ORDER BY orderexecuted", String.class);
            ran.removeAll(had);
            assertThat(ran).containsExactly("140.0-lookup-data-test-residue", "141.0-task-reference",
                "142.0-orchestration-setting-watermarks", "143.0-pipeline-config", "144.0-lookup-data-read-only");

            for (Map<String, Object> row : before) {
                long id = ((Number) row.get("lookup_id")).longValue();
                String type = (String) row.get("lookup_type");
                String family = (String) row.get("family");
                String destination;
                if (id == 1279L || id == 1283L || id == 1284L) {
                    assertThat(sql.queryForObject("SELECT count(*) FROM lookup_data WHERE lookup_id = ?", Long.class, id)).isZero();
                    destination = "deleted (owner's D2: test residue)";
                } else if ("PIPELINE_HOME_PAGES".equals(family) || "TASK_GROUPS".equals(family)) {
                    Map<String, Object> moved = sql.queryForMap("SELECT kind, tenant_id, name, value, description FROM task_reference WHERE id = ?", id);
                    assertThat(moved.get("kind")).isEqualTo("PIPELINE_HOME_PAGES".equals(family) ? "HOME_PAGE" : "TASK_GROUP");
                    assertThat(moved.get("tenant_id")).isEqualTo(row.get("tenant_id"));
                    assertThat(moved.get("name")).isEqualTo(type.trim());
                    assertThat(moved.get("value")).isEqualTo(row.get("lookup_value"));
                    assertThat(moved.get("description")).isEqualTo(row.get("description"));
                    assertThat(row.get("is_encrypted")).isEqualTo(false);
                    destination = "task_reference " + moved.get("kind") + " of workspace " + moved.get("tenant_id") + ", same id";
                } else if ("SCHEDULER_LAST_RUN_TIME".equals(type) || "AUDIT_LOG_SYNC_LAST_RUN_TIME".equals(type)) {
                    assertThat(sql.queryForObject("SELECT setting_value FROM orchestration_setting WHERE setting_key = ?", String.class, type))
                        .isEqualTo(row.get("lookup_value"));
                    destination = "orchestration_setting " + type + ", value unchanged";
                } else {
                    assertThat(family).as("row %d", id).isNull();
                    assertThat(type).isIn("PIPELINE_HOME_PAGES", "TASK_GROUPS", "BUCKET_LIST", "QUEUE_FETCH_LIMIT");
                    destination = "a family row: nothing to move (its children moved)";
                }
                System.out.println("REHEARSAL V140-V144 lookup " + id + " " + type + (family == null ? "" : " (in " + family + ")")
                    + " -> " + destination);
            }
            assertThat(sql.queryForObject("SELECT setting_value FROM orchestration_setting WHERE setting_key = 'QUEUE_FETCH_LIMIT'",
                String.class)).isEqualTo(fetchLimitBefore);
            assertThat(sql.queryForList("SELECT task_detail_id, home_page_id, group_id FROM source_task ORDER BY task_detail_id"))
                .isEqualTo(tasksBefore);
            assertThat(sql.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = 'source_task'::regclass "
                + "AND confrelid = 'task_reference'::regclass ORDER BY 1", String.class))
                .containsExactly("fk_source_task_group", "fk_source_task_home_page");
            assertThat(sql.queryForObject("SELECT count(*) FROM pipeline_config", Long.class)).isZero();
            assertThatThrownBy(() -> sql.update("UPDATE lookup_data SET description = description")).hasMessageContaining("read-only");
            System.out.println("REHEARSAL V140-V144 task_reference " + sql.queryForObject("SELECT count(*) FROM task_reference", Long.class)
                + " rows, orchestration_setting " + sql.queryForList("SELECT setting_key FROM orchestration_setting ORDER BY 1", String.class)
                + ", lookup_data " + sql.queryForObject("SELECT count(*) FROM lookup_data", Long.class) + " rows kept read-only");

            TimestamptzRehearsal.liquibase(pool, 4);
            assertThat(sql.queryForObject("SELECT to_regclass('task_reference') IS NULL AND to_regclass('pipeline_config') IS NULL",
                Boolean.class)).isTrue();
            assertThat(sql.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = 'source_task'::regclass "
                + "AND confrelid = 'lookup_data'::regclass ORDER BY 1", String.class))
                .containsExactly("fk_source_task_group", "fk_source_task_home_page");
            System.out.println("REHEARSAL V140-V144 rolled back V141-V144; V140's deletions stay deleted");
        }
    }
}
