package process;

import com.zaxxer.hikari.HikariDataSource;
import org.barco.platform.tenancy.RowSecurityDataSource;
import process.config.RowSecurityConfig;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A throwaway Postgres database built by the real changelog, for the opt-in Postgres tests.
 *
 * The same recipe StalledRunSweepPostgresTest and OutboxPostgresTest spell out inline: runs when
 * NOTIFICATIONS_TEST_DB_URL and its user and password point at a Postgres server, creates a database
 * with a random name, applies db.changelog-master.yaml to it, and drops it on close. Nothing is written
 * to any database that existed before.
 *
 * @author Nabeel Ahmed
 */
public final class ScratchPostgres implements AutoCloseable {

    private final String server;
    private final String name;
    private final HikariDataSource pool;
    private HikariDataSource appPool;
    private RowSecurityDataSource asApplication;

    private ScratchPostgres(String server, String name, HikariDataSource pool) {
        this.server = server;
        this.name = name;
        this.pool = pool;
    }

    /** Skips the calling test class when no server is configured. */
    public static ScratchPostgres create(String prefix) throws Exception {
        String server = System.getenv("NOTIFICATIONS_TEST_DB_URL");
        assumeTrue(server != null, "NOTIFICATIONS_TEST_DB_URL is not set");
        String name = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection admin = admin(server); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(server.replaceAll("/[^/?]+(\\?.*)?$", "/" + name + "$1"));
        pool.setUsername(System.getenv("NOTIFICATIONS_TEST_DB_USER"));
        pool.setPassword(System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
        pool.setMaximumPoolSize(8);
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(pool);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.setContexts("init");
        liquibase.setResourceLoader(new DefaultResourceLoader(ScratchPostgres.class.getClassLoader()));
        liquibase.afterPropertiesSet();
        return new ScratchPostgres(server, name, pool);
    }

    private static Connection admin(String server) throws Exception {
        return DriverManager.getConnection(server, System.getenv("NOTIFICATIONS_TEST_DB_USER"),
            System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
    }

    public HikariDataSource pool() {
        return this.pool;
    }

    /**
     * The database as the application reaches it since V181 (MIG-258): every connection SET ROLE process_app, so row
     * security and process_app's grants apply, and the caller -- Core's TenantContext, or a RowSecurity grant -- written
     * on each connection by platform-commons' RowSecurityDataSource, as RowSecurityConfig wires it. Seed through
     * {@link #jdbc()} (the login, which row security never applies to); run the code under test on this.
     */
    public synchronized DataSource appPool() {
        if (this.asApplication == null) {
            this.appPool = new HikariDataSource();
            this.appPool.setJdbcUrl(this.pool.getJdbcUrl());
            this.appPool.setUsername(this.pool.getUsername());
            this.appPool.setPassword(this.pool.getPassword());
            this.appPool.setMaximumPoolSize(8);
            this.appPool.setConnectionInitSql("SET ROLE process_app");
            this.asApplication = new RowSecurityDataSource(this.appPool, () -> RowSecurityConfig.CORE_CALLER);
        }
        return this.asApplication;
    }

    /** A JdbcTemplate on {@link #appPool()}. */
    public JdbcTemplate appJdbc() {
        return new JdbcTemplate(this.appPool());
    }

    public JdbcTemplate jdbc() {
        return new JdbcTemplate(this.pool);
    }

    /** A transaction per call, on the same pool the JdbcTemplates use, as the application's are. */
    public TransactionTemplate transactions() {
        return new TransactionTemplate(new DataSourceTransactionManager(this.pool));
    }

    @Override
    public void close() throws Exception {
        if (this.appPool != null) {
            this.appPool.close();
        }
        this.pool.close();
        try (Connection admin = admin(this.server); Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + this.name);
        }
    }
}
