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
        } finally {
            pool.close();
            try (Connection admin = DriverManager.getConnection(server, user, password); Statement sql = admin.createStatement()) {
                sql.execute("DROP DATABASE IF EXISTS " + scratch);
            }
        }
    }
}
