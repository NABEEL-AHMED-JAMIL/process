package process.schema;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-167 (V140-V144): lookup_data is decomposed by kind of row and retired.
 *
 * The rows dev held on 2026-09-24, at their dev ids, go through the changesets: the home pages and groups become
 * task_reference rows with the same ids and source_task's keys follow them, the two watermarks join
 * QUEUE_FETCH_LIMIT in orchestration_setting, the owner's three test-residue rows are deleted (D2), pipeline_config
 * is created with constraints that keep a secret out of the readable column, and lookup_data refuses every write.
 * A row that fits no destination -- including an ENCRYPTED row of a kind that moves, since neither destination
 * stores ciphertext -- halts the deploy with nothing changed.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class LookupDecompositionPostgresTest {

    static final String FIRST = "140.0-lookup-data-test-residue";

    @Test
    void devsRowsMoveWithTheirIdsAndTheTableIsRetired() throws Exception {
        try (ScratchEtlJob db = ScratchEtlJob.buildUpTo("lookup_split", FIRST)) {
            JdbcTemplate sql = db.sql();
            rowsAsDevHasThem(sql);

            db.finish();

            List<Map<String, Object>> moved = sql.queryForList(
                "SELECT id, tenant_id, kind, name, value FROM task_reference ORDER BY id");
            assertThat(moved).extracting(row -> row.get("id"))
                .containsExactly(1274L, 1275L, 1276L, 1277L, 1278L, 1280L, 1282L);
            assertThat(moved).extracting(row -> row.get("kind"))
                .containsExactly("HOME_PAGE", "HOME_PAGE", "HOME_PAGE", "HOME_PAGE", "HOME_PAGE", "TASK_GROUP", "TASK_GROUP");
            assertThat(moved).extracting(row -> row.get("tenant_id"))
                .containsExactly(2901L, 2905L, 2902L, 2903L, 2904L, 2905L, 2901L);
            assertThat(moved.get(1)).containsEntry("name", "MedAxis Care Network Home")
                .containsEntry("value", "https://console.medaxiscare.demo/jobs/done");

            // D2: exactly the three residue rows are gone, and nothing else.
            assertThat(sql.queryForList("SELECT lookup_id FROM lookup_data WHERE lookup_id IN (1279, 1283, 1284)", Long.class)).isEmpty();
            assertThat(sql.queryForObject("SELECT count(*) FROM lookup_data WHERE lookup_id BETWEEN 1001 AND 1282", Long.class)).isEqualTo(9L);

            assertThat(sql.queryForObject("SELECT setting_value FROM orchestration_setting WHERE setting_key = 'SCHEDULER_LAST_RUN_TIME'",
                String.class)).isEqualTo("2026-08-17T23:52:47.604023180");
            assertThat(sql.queryForObject("SELECT setting_value FROM orchestration_setting WHERE setting_key = 'AUDIT_LOG_SYNC_LAST_RUN_TIME'",
                String.class)).isEqualTo("2026-09-20T23:18:17.307Z");
            assertThat(sql.queryForObject("SELECT setting_value FROM orchestration_setting WHERE setting_key = 'QUEUE_FETCH_LIMIT'",
                String.class)).isEqualTo("1000");
            assertThat(sql.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name IN "
                + "('orchestration_setting', 'task_reference', 'pipeline_config') AND column_name LIKE '%encrypt%'", String.class))
                .as("no destination can hold a flagged-encrypted value").isEmpty();

            // source_task's keys follow the rows: same ids, now task_reference's.
            assertThat(sql.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = 'source_task'::regclass "
                + "AND confrelid = 'task_reference'::regclass ORDER BY 1", String.class))
                .containsExactly("fk_source_task_group", "fk_source_task_home_page");
            assertThat(sql.queryForMap("SELECT home_page_id, group_id FROM source_task WHERE task_detail_id = 7301"))
                .containsEntry("home_page_id", 1275L).containsEntry("group_id", 1280L);
            assertThatThrownBy(() -> sql.update("UPDATE source_task SET home_page_id = 1283 WHERE task_detail_id = 7301"))
                .hasMessageContaining("fk_source_task_home_page");

            // D3: a name is unique per workspace and kind, not across the platform.
            sql.update("INSERT INTO task_reference (tenant_id, kind, name, value) VALUES (2901, 'HOME_PAGE', 'MedAxis Care Network Home', 'https://a.demo')");
            sql.update("INSERT INTO task_reference (tenant_id, kind, name) VALUES (2905, 'TASK_GROUP', 'MedAxis Care Network Home')");
            assertThatThrownBy(() -> sql.update("INSERT INTO task_reference (tenant_id, kind, name) VALUES (2905, 'HOME_PAGE', 'MedAxis Care Network Home')"))
                .hasMessageContaining("uq_task_reference_tenant_kind_name");
            assertThat(sql.queryForObject("SELECT min(id) FROM task_reference WHERE id > 1282", Long.class)).isEqualTo(1283L);
            assertThatThrownBy(() -> sql.update("INSERT INTO task_reference (tenant_id, kind, name) VALUES (2905, 'BUCKET', 'x')"))
                .hasMessageContaining("ck_task_reference_kind");

            // lookup_data is retired: every write is refused.
            assertThatThrownBy(() -> sql.update("INSERT INTO lookup_data (lookup_id, lookup_type, date_created) VALUES (5000, 'NEW', now())"))
                .hasMessageContaining("lookup_data is read-only");
            assertThatThrownBy(() -> sql.update("UPDATE lookup_data SET lookup_value = 'x' WHERE lookup_id = 1001"))
                .hasMessageContaining("lookup_data is read-only");
            assertThatThrownBy(() -> sql.update("DELETE FROM lookup_data WHERE lookup_id = 1274"))
                .hasMessageContaining("lookup_data is read-only");
        }
    }

    @Test
    void pipelineConfigKeepsASecretOutOfTheReadableColumn() throws Exception {
        try (ScratchEtlJob db = ScratchEtlJob.build("pipeline_config")) {
            JdbcTemplate sql = db.sql();
            String insert = "INSERT INTO pipeline_config (tenant_id, config_key, kind, value, value_sealed) VALUES (?, ?, ?, ?, ?)";
            sql.update(insert, 2905L, "INPUT_BUCKET", "VALUE", "etl-inputs", null);
            sql.update(insert, 2905L, "DB_PASSWORD", "SECRET", null, "kp2026a:AAECAwQFBgcICQ");
            sql.update(insert, 2901L, "DB_PASSWORD", "SECRET", null, "kp2026a:CQgHBgUEAwIBAA");
            assertThat(sql.queryForObject("SELECT min(id) FROM pipeline_config", Long.class)).isGreaterThanOrEqualTo(1000L);

            assertThatThrownBy(() -> sql.update(insert, 2905L, "API_TOKEN", "SECRET", null, "hunter2"))
                .as("plaintext in the sealed column").hasMessageContaining("ck_pipeline_config_one_value");
            assertThatThrownBy(() -> sql.update(insert, 2905L, "API_TOKEN", "SECRET", "hunter2", "kp2026a:AAEC"))
                .as("a secret with a readable copy").hasMessageContaining("ck_pipeline_config_one_value");
            assertThatThrownBy(() -> sql.update(insert, 2905L, "REGION", "VALUE", "us", "kp2026a:AAEC"))
                .hasMessageContaining("ck_pipeline_config_one_value");
            assertThatThrownBy(() -> sql.update(insert, 2905L, "db_password", "VALUE", "x", null))
                .hasMessageContaining("ck_pipeline_config_key");
            assertThatThrownBy(() -> sql.update(insert, 2905L, "INPUT_BUCKET", "VALUE", "other", null))
                .hasMessageContaining("uq_pipeline_config_tenant_key");
            assertThatThrownBy(() -> sql.update(insert, null, "REGION", "VALUE", "us", null))
                .hasMessageContaining("tenant_id");
        }
    }

    @Test
    void aRowThatFitsNowhereHaltsTheDeployAndDeletesNothing() throws Exception {
        try (ScratchEtlJob db = ScratchEtlJob.buildUpTo("lookup_split_unmapped", FIRST)) {
            JdbcTemplate sql = db.sql();
            rowsAsDevHasThem(sql);
            sql.update("INSERT INTO lookup_data (lookup_id, lookup_type, lookup_value, date_created) VALUES (1290, 'SOME_OTHER_FAMILY', 'x', now())");

            assertThatThrownBy(db::finish).hasMessageContaining("no destination takes");
            assertThat(sql.queryForObject("SELECT count(*) FROM lookup_data WHERE lookup_id IN (1279, 1283, 1284)", Long.class)).isEqualTo(3L);
            assertThat(sql.queryForObject("SELECT to_regclass('task_reference') IS NULL", Boolean.class)).isTrue();
        }
    }

    /** is_encrypted travels: an encrypted row of a kind that moves halts, rather than arriving as unreadable ciphertext. */
    @Test
    void anEncryptedRowOfAMovingKindHaltsTheDeploy() throws Exception {
        try (ScratchEtlJob db = ScratchEtlJob.buildUpTo("lookup_split_encrypted", FIRST)) {
            JdbcTemplate sql = db.sql();
            rowsAsDevHasThem(sql);
            sql.update("UPDATE lookup_data SET is_encrypted = true, lookup_value = 'kp2026a:AAEC' WHERE lookup_id = 1276");

            assertThatThrownBy(db::finish).hasMessageContaining("no destination takes");
            assertThat(sql.queryForObject("SELECT to_regclass('task_reference') IS NULL", Boolean.class)).isTrue();
        }
        try (ScratchEtlJob db = ScratchEtlJob.buildUpTo("lookup_split_encrypted_mark", FIRST)) {
            JdbcTemplate sql = db.sql();
            rowsAsDevHasThem(sql);
            sql.update("UPDATE lookup_data SET is_encrypted = true WHERE lookup_id = 1001");

            assertThatThrownBy(db::finish).hasMessageContaining("no destination takes");
        }
    }

    /** D2 deletes the residue by id only while each id is still the row it was. */
    @Test
    void anIdOfTheResidueHoldingSomethingElseIsNotDeleted() throws Exception {
        try (ScratchEtlJob db = ScratchEtlJob.buildUpTo("lookup_split_ids", FIRST)) {
            JdbcTemplate sql = db.sql();
            workspaces(sql);
            sql.update("INSERT INTO lookup_data (lookup_id, lookup_type, lookup_value, parent_lookup_id, tenant_id, date_created) "
                + "VALUES (1279, 'Nightly group', 'Nightly', ?, 2905, now())", family(sql, "TASK_GROUPS"));

            db.finish();

            assertThat(sql.queryForMap("SELECT kind, name, tenant_id FROM task_reference WHERE id = 1279"))
                .containsEntry("kind", "TASK_GROUP").containsEntry("name", "Nightly group").containsEntry("tenant_id", 2905L);
        }
    }

    @Test
    void aTaskPointingAtAnotherWorkspacesHomePageHaltsTheDeploy() throws Exception {
        try (ScratchEtlJob db = ScratchEtlJob.buildUpTo("lookup_split_cross", FIRST)) {
            JdbcTemplate sql = db.sql();
            rowsAsDevHasThem(sql);
            sql.update("UPDATE source_task SET tenant_id = 2901 WHERE task_detail_id = 7301");

            assertThatThrownBy(db::finish).hasMessageContaining("home page that is not a PIPELINE_HOME_PAGES row of the task's own workspace");
            assertThat(sql.queryForObject("SELECT to_regclass('task_reference') IS NULL", Boolean.class)).isTrue();
        }
    }

    @Test
    void theRetirementRollsBackToLookupData() throws Exception {
        try (ScratchEtlJob db = ScratchEtlJob.buildUpTo("lookup_split_rollback", FIRST)) {
            JdbcTemplate sql = db.sql();
            rowsAsDevHasThem(sql);
            db.finish();

            db.rollback(4);

            assertThat(sql.queryForObject("SELECT to_regclass('task_reference') IS NULL AND to_regclass('pipeline_config') IS NULL",
                Boolean.class)).isTrue();
            assertThat(sql.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = 'source_task'::regclass "
                + "AND confrelid = 'lookup_data'::regclass ORDER BY 1", String.class))
                .containsExactly("fk_source_task_group", "fk_source_task_home_page");
            assertThat(sql.queryForList("SELECT setting_key FROM orchestration_setting", String.class)).containsExactly("QUEUE_FETCH_LIMIT");
            sql.update("UPDATE lookup_data SET lookup_value = lookup_value WHERE lookup_id = 1001");

            db.finish();
            assertThat(sql.queryForObject("SELECT count(*) FROM task_reference", Long.class)).isEqualTo(7L);
        }
    }

    /** The five demo workspaces (a built database's lookup_data still has its tenant foreign key). */
    static void workspaces(JdbcTemplate sql) {
        for (long tenant = 2901; tenant <= 2905; tenant++) {
            sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)",
                tenant, "T" + tenant, "Workspace " + tenant);
        }
    }

    private static long family(JdbcTemplate sql, String type) {
        return sql.queryForObject("SELECT lookup_id FROM lookup_data WHERE lookup_type = ? AND parent_lookup_id IS NULL", Long.class, type);
    }

    /**
     * dev's lookup_data on 2026-09-24, at dev's ids, under the families a built database seeds (V70.5): the two
     * watermarks, the five home pages, the two groups, and the owner's three rows of test residue.
     */
    static void rowsAsDevHasThem(JdbcTemplate sql) {
        workspaces(sql);
        long homePages = family(sql, "PIPELINE_HOME_PAGES");
        long groups = family(sql, "TASK_GROUPS");
        String top = "INSERT INTO lookup_data (lookup_id, lookup_type, lookup_value, description, is_encrypted, date_created) VALUES (?, ?, ?, ?, false, now())";
        sql.update(top, 1001L, "SCHEDULER_LAST_RUN_TIME", "2026-08-17T23:52:47.604023180", "This Scheduler use for send the job into queue");
        sql.update(top, 1077L, "AUDIT_LOG_SYNC_LAST_RUN_TIME", "2026-09-20T23:18:17.307Z", "Watermark for the OpenSearch sync");
        String child = "INSERT INTO lookup_data (lookup_id, lookup_type, lookup_value, parent_lookup_id, tenant_id, is_encrypted, date_created) "
            + "VALUES (?, ?, ?, ?, ?, ?, now())";
        sql.update(child, 1274L, "CareBridge Health Services Home", "https://portal.carebridgehealth.demo/pipelines", homePages, 2901L, false);
        sql.update(child, 1275L, "MedAxis Care Network Home", "https://console.medaxiscare.demo/jobs/done", homePages, 2905L, false);
        sql.update(child, 1276L, "NorthStar Health Network Home", "https://ops.northstarhealth.demo/etl/summary", homePages, 2902L, false);
        sql.update(child, 1277L, "HealthCore Community Services Home", "https://data.healthcorecommunity.demo/runs", homePages, 2903L, false);
        sql.update(child, 1278L, "EverWell Medical Group Home", "https://hub.everwellmedical.demo/reports", homePages, 2904L, false);
        sql.update(child, 1279L, "Encrypt probe", "kp2026a:pfKfFC4aqeN38hrLyvbX", groups, null, true);
        sql.update(child, 1280L, "e2e-configuration-group-825949", "Configuration e2e group", groups, 2905L, false);
        sql.update(child, 1282L, "e2e-configuration-chs-group-825949", "CareBridge e2e group", groups, 2901L, false);
        sql.update(top, 1283L, "E2E_CONFIGURATION_FAMILY_825949", "e2e family", "edited by the platform admin");
        sql.update(child, 1284L, "E2E_CONFIGURATION_FAMILY_825949_child", "child value", 1283L, null, false);
        sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, tenant_id) "
            + "VALUES (7300, 'worker', 'd', 'topic=scrapping-topic&partitions=[*]', 2905)");
        sql.update("INSERT INTO source_task (task_detail_id, task_name, task_status, source_task_type_id, tenant_id, home_page_id, group_id) "
            + "VALUES (7301, 't', 'Active', 7300, 2905, 1275, 1280)");
        sql.update("INSERT INTO source_task (task_detail_id, task_name, task_status, source_task_type_id, tenant_id) "
            + "VALUES (7302, 'u', 'Active', 7300, 2905)");
    }
}
