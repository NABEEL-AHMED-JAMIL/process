package process.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A schema of its own in the dev PostgreSQL for one test, dropped afterwards: where the billing
 * changesets' own SQL files run against real tables. Skips the test (never fails it) when no
 * PostgreSQL answers at BILLING_IT_POSTGRES_URL (default localhost:5433/etl_job).
 */
final class ThrowawaySchema implements AutoCloseable {

    static final String URL = env("BILLING_IT_POSTGRES_URL", "jdbc:postgresql://localhost:5433/etl_job");
    static final String USER = env("SPRING_DATASOURCE_USERNAME", "nabeel.amd93");
    static final String PASSWORD = env("SPRING_DATASOURCE_PASSWORD", "admin");

    final String name;

    private ThrowawaySchema(String name) {
        this.name = name;
    }

    static ThrowawaySchema create(String prefix) {
        assumeTrue(reachable(), "no PostgreSQL at " + URL);
        ThrowawaySchema schema = new ThrowawaySchema(prefix + "_" + System.nanoTime());
        schema.admin("CREATE SCHEMA " + schema.name);
        return schema;
    }

    /** A data source of its own on this schema: one per "instance" where a test needs two. */
    DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(URL + "?currentSchema=" + this.name, USER, PASSWORD);
    }

    JdbcTemplate jdbc() {
        return new JdbcTemplate(this.dataSource());
    }

    /** Runs a changeset's SQL file from the classpath, split on semicolons as Liquibase splits it. */
    void run(String resource, JdbcTemplate jdbc) {
        for (String statement : read(resource).split(";")) {
            if (!statement.replaceAll("(?m)^\\s*--.*$", "").trim().isEmpty()) {
                jdbc.execute(statement);
            }
        }
    }

    @Override
    public void close() {
        this.admin("DROP SCHEMA " + this.name + " CASCADE");
    }

    private void admin(String sql) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException ex) {
            throw new IllegalStateException(sql, ex);
        }
    }

    private static String read(String resource) {
        try (InputStream in = ThrowawaySchema.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("not on the classpath: " + resource);
            }
            byte[] bytes = new byte[in.available()];
            int read = 0;
            while (read < bytes.length) {
                read += in.read(bytes, read, bytes.length - read);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static boolean reachable() {
        try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
            return true;
        } catch (SQLException unreachable) {
            return false;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
