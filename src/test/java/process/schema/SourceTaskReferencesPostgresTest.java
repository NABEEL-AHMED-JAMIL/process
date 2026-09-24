package process.schema;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.model.service.impl.QueryService;
import process.security.TenantContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-165 (P3, V70.3): source_task.home_page_id and group_id were varchars joined to lookup_data through
 * cast(lookup_id as varchar(10)) -- which defeats lookup_data's primary key and would truncate an id past
 * ten digits. They become bigint foreign keys, converted in place from what a long-lived database holds:
 * empty strings (the console saved '' for "none") become NULL, ids stay the same ids.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class SourceTaskReferencesPostgresTest {

    private static final String CHANGESET = "70.3-source-task-references-are-bigint-foreign-keys";

    @Test
    void aLongLivedDatabasesValuesConvertAndJoinThroughTheKey() throws Exception {
        try (ScratchEtlJob db = ScratchEtlJob.buildUpTo("st_refs", CHANGESET)) {
            JdbcTemplate sql = db.sql();
            this.rowsAsDevHasThem(sql);

            db.finish();

            assertThat(sql.queryForList("SELECT column_name || ':' || data_type FROM information_schema.columns "
                + "WHERE table_name = 'source_task' AND column_name IN ('home_page_id', 'group_id') ORDER BY 1", String.class))
                .containsExactly("group_id:bigint", "home_page_id:bigint");
            List<Map<String, Object>> tasks = sql.queryForList("SELECT task_detail_id, home_page_id, group_id FROM source_task ORDER BY 1");
            assertThat(tasks).extracting(row -> row.get("home_page_id")).containsExactly(1275L, null, null);
            assertThat(tasks).extracting(row -> row.get("group_id")).containsExactly(1280L, null, null);
            assertThat(sql.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = 'source_task'::regclass "
                + "AND confrelid = 'lookup_data'::regclass ORDER BY 1", String.class))
                .containsExactly("fk_source_task_group", "fk_source_task_home_page");
            // The foreign key holds: an id that is no lookup row is refused by the database itself.
            assertThatThrownBy(() -> sql.update("UPDATE source_task SET home_page_id = 99999 WHERE task_detail_id = 7301"))
                .hasMessageContaining("fk_source_task_home_page");

            // The task list's join, planned with scans ruled out: it can use lookup_data's key only now that
            // nothing is cast. Before, the best it could do was walk the whole index and filter.
            TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "admin@platform.local");
            try {
                sql.execute("SET enable_seqscan = off");
                String plan = String.join("\n", sql.queryForList("EXPLAIN " + new QueryService().listSourceTaskQuery(
                    false, null, null, null, null, null), String.class));
                assertThat(plan).contains("Index Cond: (lookup_id = st.home_page_id)").contains("Index Cond: (lookup_id = st.group_id)");
            } finally {
                sql.execute("RESET enable_seqscan");
                TenantContext.clear();
            }
        }
    }

    @Test
    void aValueThatIsNoLookupIdStopsTheMigrationWithTheQueryToFindIt() throws Exception {
        try (ScratchEtlJob db = ScratchEtlJob.buildUpTo("st_refs_bad", CHANGESET)) {
            JdbcTemplate sql = db.sql();
            this.rowsAsDevHasThem(sql);
            sql.update("UPDATE source_task SET group_id = 'Nightly' WHERE task_detail_id = 7302");

            assertThatThrownBy(db::finish).hasMessageContaining("home_page_id and group_id");
            assertThat(sql.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name = 'source_task' "
                + "AND column_name = 'group_id'", String.class)).isEqualTo("character varying");
        }
    }

    /** Dev on 2026-09-24: 385 tasks, one home page (1275) and one group (1280) set, the rest '' or NULL. */
    private void rowsAsDevHasThem(JdbcTemplate sql) {
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (2905, 'Active', 'MCN', 'MedAxis')");
        sql.update("INSERT INTO lookup_data (lookup_id, lookup_type, lookup_value, date_created) VALUES (1017, 'PIPELINE_HOME_PAGES', 'Home Pages', now())");
        sql.update("INSERT INTO lookup_data (lookup_id, lookup_type, lookup_value, date_created) VALUES (1033, 'TASK_GROUPS', 'TASK_GROUPS', now())");
        sql.update("INSERT INTO lookup_data (lookup_id, lookup_type, lookup_value, parent_lookup_id, tenant_id, date_created) "
            + "VALUES (1275, 'MedAxis Care Network Home', 'https://console.medaxiscare.demo/jobs/done', 1017, 2905, now())");
        sql.update("INSERT INTO lookup_data (lookup_id, lookup_type, lookup_value, parent_lookup_id, tenant_id, date_created) "
            + "VALUES (1280, 'e2e-configuration-group', 'Configuration e2e group', 1033, 2905, now())");
        sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, tenant_id) "
            + "VALUES (7300, 'worker', 'd', 'topic=scrapping-topic&partitions=[*]', 2905)");
        String task = "INSERT INTO source_task (task_detail_id, task_name, task_status, source_task_type_id, tenant_id, home_page_id, group_id) "
            + "VALUES (?, 't', 'Active', 7300, 2905, ?, ?)";
        sql.update(task, 7301L, "1275", "1280");
        sql.update(task, 7302L, "", "");
        sql.update(task, 7303L, null, null);
    }
}
