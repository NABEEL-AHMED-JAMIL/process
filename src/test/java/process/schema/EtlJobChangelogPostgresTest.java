package process.schema;

import com.zaxxer.hikari.HikariDataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * etl_job built from nothing by its own changelog, as a new environment would build it.
 *
 * Opt-in, like NotificationStorePostgresTest: runs when NOTIFICATIONS_TEST_DB_URL and its user and
 * password point at a Postgres server; creates a throwaway database and drops it after.
 */
class EtlJobChangelogPostgresTest {

    @Test
    void aFreshEtlJobHasNoNotificationTable() throws Exception {
        String server = System.getenv("NOTIFICATIONS_TEST_DB_URL");
        assumeTrue(server != null, "NOTIFICATIONS_TEST_DB_URL is not set");
        String user = System.getenv("NOTIFICATIONS_TEST_DB_USER");
        String password = System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD");
        String scratch = "etl_job_fresh_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection admin = DriverManager.getConnection(server, user, password); Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + scratch);
        }
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(server.replaceAll("/[^/?]+(\\?.*)?$", "/" + scratch + "$1"));
        pool.setUsername(user);
        pool.setPassword(password);
        try {
            SpringLiquibase liquibase = new SpringLiquibase();
            liquibase.setDataSource(pool);
            liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
            liquibase.setContexts("init");
            liquibase.setResourceLoader(new DefaultResourceLoader(getClass().getClassLoader()));
            liquibase.afterPropertiesSet();

            JdbcTemplate sql = new JdbcTemplate(pool);
            // It lives in notifications_db now; an empty leftover here would invite someone to write to it.
            assertThat(sql.queryForObject("SELECT to_regclass('public.notification') IS NULL", Boolean.class)).isTrue();
            // And document_converter_task lives in media_db (MIG-41, V54).
            assertThat(sql.queryForObject("SELECT to_regclass('public.document_converter_task') IS NULL", Boolean.class)).isTrue();
            assertThat(sql.queryForObject("SELECT to_regclass('public.app_user') IS NOT NULL", Boolean.class)).isTrue();

            // MIG-53 (V55): an alias is unique within a workspace, and a platform name (no tenant)
            // is unique among platform rows -- NULLs must not be distinct here, or two platform rows
            // could claim one name, the hole bucket_credential has.
            // MIG-53 part b (V56): every analytics alias carries the connection's id beside it.
            for (String column : new String[] {"analytics_analysis.storage_connection_id", "analytics_benchmark_result.storage_connection_id",
                "analytics_dataset.storage_connection_id", "analytics_query.storage_connection_id", "analytics_query.second_storage_connection_id",
                "analytics_query_run.storage_connection_id", "analytics_query_run.second_storage_connection_id"}) {
                String[] parts = column.split("\\.");
                assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                    Integer.class, parts[0], parts[1])).as(column).isEqualTo(1);
            }
            // MIG-70 (V57): storage_connection is storage-service's now. The copy here is kept for its
            // retention period, read-only: every write is refused, and says where the table went.
            assertThat(sql.queryForObject("SELECT to_regclass('public.storage_connection') IS NOT NULL", Boolean.class)).isTrue();
            assertThatThrownBy(() -> sql.update("INSERT INTO storage_connection (storage_connection_id, tenant_id, alias, "
                + "connection_name, is_default, provider, status) VALUES (9001, NULL, 'x', 'c', false, 'MINIO', 'Active')"))
                .as("insert").hasMessageContaining("storage-service");
            assertThatThrownBy(() -> sql.update("UPDATE storage_connection SET alias = 'y'")).as("update").hasMessageContaining("storage-service");
            assertThatThrownBy(() -> sql.update("DELETE FROM storage_connection")).as("delete").hasMessageContaining("storage-service");
        } finally {
            pool.close();
            try (Connection admin = DriverManager.getConnection(server, user, password); Statement sql = admin.createStatement()) {
                sql.execute("DROP DATABASE IF EXISTS " + scratch);
            }
        }
    }
}
