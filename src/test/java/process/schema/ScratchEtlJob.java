package process.schema;

import com.zaxxer.hikari.HikariDataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A throwaway etl_job built by its own changelog, for the opt-in Postgres tests.
 *
 * Opt-in like EtlJobChangelogPostgresTest: a test using this is skipped unless NOTIFICATIONS_TEST_DB_URL and
 * its user and password point at a Postgres server. The database is created under a random name, built from
 * db.changelog-master.yaml exactly as a new environment would build it, and dropped by close().
 */
public final class ScratchEtlJob implements AutoCloseable {

    private final String server;
    private final String name;
    private final HikariDataSource pool;

    private ScratchEtlJob(String server, String name, HikariDataSource pool) {
        this.server = server;
        this.name = name;
        this.pool = pool;
    }

    /** Skips the calling test when no server is configured. One connection, so session settings stick. */
    public static ScratchEtlJob build(String prefix) throws Exception {
        return build(prefix, new ArrayList<>());
    }

    /** As build(prefix), with Liquibase changelog parameters as name=value pairs. */
    public static ScratchEtlJob build(String prefix, List<String> parameters) throws Exception {
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
        pool.setMaximumPoolSize(1);
        ScratchEtlJob scratch = new ScratchEtlJob(server, name, pool);
        try {
            SpringLiquibase liquibase = new SpringLiquibase();
            liquibase.setDataSource(pool);
            liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
            liquibase.setContexts("init");
            liquibase.setResourceLoader(new DefaultResourceLoader(ScratchEtlJob.class.getClassLoader()));
            Map<String, String> values = new HashMap<>();
            for (String parameter : parameters) {
                String[] pair = parameter.split("=", 2);
                values.put(pair[0], pair[1]);
            }
            liquibase.setChangeLogParameters(values);
            liquibase.afterPropertiesSet();
        } catch (Exception | Error failed) {
            scratch.close();
            throw failed;
        }
        return scratch;
    }

    /**
     * Stops the build before the named changeset, so a test can put a long-lived database's rows in place
     * and then watch that changeset convert them. finish() applies the rest.
     */
    public static ScratchEtlJob buildUpTo(String prefix, String changeSetId) throws Exception {
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
        pool.setMaximumPoolSize(1);
        ScratchEtlJob scratch = new ScratchEtlJob(server, name, pool);
        try {
            scratch.migrate(changeSetId);
        } catch (Exception | Error failed) {
            scratch.close();
            throw failed;
        }
        return scratch;
    }

    /** The rest of the changelog, after buildUpTo. */
    public void finish() throws Exception {
        this.migrate(null);
    }

    /** Applies the changelog up to, not including, stopBefore -- or all of it when that is null. */
    private void migrate(String stopBefore) throws Exception {
        try (Connection connection = this.connect()) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml",
                new ClassLoaderResourceAccessor(ScratchEtlJob.class.getClassLoader()), database);
            if (stopBefore == null) {
                liquibase.update(new Contexts("init"), new LabelExpression());
                return;
            }
            List<ChangeSet> unrun = liquibase.listUnrunChangeSets(new Contexts("init"), new LabelExpression());
            for (int i = 0; i < unrun.size(); i++) {
                if (unrun.get(i).getId().equals(stopBefore)) {
                    liquibase.update(i, new Contexts("init"), new LabelExpression());
                    return;
                }
            }
            throw new IllegalArgumentException("No changeset " + stopBefore + " in the changelog");
        }
    }

    public JdbcTemplate sql() {
        return new JdbcTemplate(this.pool);
    }

    public HikariDataSource dataSource() {
        return this.pool;
    }

    /** A connection of its own, outside the pool, for a test that needs a second session. */
    public Connection connect() throws Exception {
        return DriverManager.getConnection(this.pool.getJdbcUrl(), System.getenv("NOTIFICATIONS_TEST_DB_USER"),
            System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
    }

    @Override
    public void close() throws Exception {
        this.pool.close();
        try (Connection admin = admin(this.server); Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + this.name);
        }
    }

    private static Connection admin(String server) throws Exception {
        return DriverManager.getConnection(server, System.getenv("NOTIFICATIONS_TEST_DB_USER"),
            System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
    }
}
