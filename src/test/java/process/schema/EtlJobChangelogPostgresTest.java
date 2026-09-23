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
            sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (1, 'Active', 't1', 'One'), (2, 'Active', 't2', 'Two')");
            String insert = "INSERT INTO storage_connection (storage_connection_id, tenant_id, alias, connection_name, "
                + "is_default, provider, status) VALUES (?, ?, ?, 'c', false, 'MINIO', 'Active')";
            sql.update(insert, 9001L, 1L, "exports");
            sql.update(insert, 9002L, 2L, "exports");
            assertThatThrownBy(() -> sql.update(insert, 9003L, 1L, "exports"))
                .as("one workspace, the same name twice").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            sql.update(insert, 9004L, null, "etl-shared");
            assertThatThrownBy(() -> sql.update(insert, 9005L, null, "etl-shared"))
                .as("two platform rows, one name").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            pool.close();
            try (Connection admin = DriverManager.getConnection(server, user, password); Statement sql = admin.createStatement()) {
                sql.execute("DROP DATABASE IF EXISTS " + scratch);
            }
        }
    }
}
