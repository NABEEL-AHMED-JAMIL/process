package process.billing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MIG-47 / MIG-86, against a real PostgreSQL: V59 gives invoice_line its invoice's tenant_id and
 * holds it there with a composite foreign key, so a line cannot claim a tenant its invoice does not
 * have. The tables are shaped as V50's baseline creates them, lines are written the way they were
 * before V59, and then V59's own SQL runs over them.
 *
 * Works in a throwaway schema it drops afterwards; skipped when no PostgreSQL answers.
 */
class InvoiceLineTenantPostgresTest {

    private static final String URL = env("BILLING_IT_POSTGRES_URL", "jdbc:postgresql://localhost:5433/etl_job");
    private static final String USER = env("SPRING_DATASOURCE_USERNAME", "nabeel.amd93");
    private static final String PASSWORD = env("SPRING_DATASOURCE_PASSWORD", "admin");
    private static final String V59 = "/db/changelog/changelog-sets/V59.0-invoice-line-tenant/V59__invoice_line_tenant.sql";

    private String schema;
    private JdbcTemplate jdbc;

    @BeforeEach
    void before() throws Exception {
        assumeTrue(reachable(), "no PostgreSQL at " + URL);
        this.schema = "mig47_it_" + System.nanoTime();
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + this.schema);
        }
        this.jdbc = new JdbcTemplate(new DriverManagerDataSource(URL + "?currentSchema=" + this.schema, USER, PASSWORD));
        // V50's shape, the parts V59 touches.
        this.jdbc.execute("CREATE TABLE invoice (invoice_id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL, number VARCHAR(32) NOT NULL UNIQUE)");
        this.jdbc.execute("CREATE TABLE invoice_line (invoice_line_id BIGSERIAL PRIMARY KEY, invoice_id BIGINT NOT NULL "
            + "REFERENCES invoice (invoice_id) ON DELETE CASCADE, sort INTEGER NOT NULL DEFAULT 0, description VARCHAR(300) NOT NULL)");
        // Two workspaces' bills, written before V59: lines with no tenant of their own.
        this.jdbc.update("INSERT INTO invoice (invoice_id, tenant_id, number) VALUES (1, 2905, 'INV-2026-09-0001'), (2, 2901, 'INV-2026-09-0002')");
        this.jdbc.update("INSERT INTO invoice_line (invoice_id, sort, description) VALUES "
            + "(1, 0, 'Seats'), (1, 1, 'Storage kept'), (2, 0, 'Model tokens in')");
        this.migrate();
    }

    @AfterEach
    void after() throws Exception {
        if (this.schema != null) {
            try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA " + this.schema + " CASCADE");
            }
        }
    }

    private void migrate() throws IOException {
        for (String statement : v59().split(";")) {
            if (!statement.replaceAll("(?m)^\\s*--.*$", "").trim().isEmpty()) {
                this.jdbc.execute(statement);
            }
        }
    }

    @Test
    void everyExistingLineTakesItsInvoicesTenant() {
        List<Map<String, Object>> rows = this.jdbc.queryForList("SELECT invoice_id, tenant_id FROM invoice_line ORDER BY invoice_line_id");
        assertThat(rows).extracting(r -> ((Number) r.get("tenant_id")).longValue()).containsExactly(2905L, 2905L, 2901L);
    }

    /** The reconciliation the task asks for: no line's tenant differs from its parent invoice's. */
    @Test
    void noLinesTenantDiffersFromItsInvoices() {
        Integer mismatched = this.jdbc.queryForObject("SELECT count(*) FROM invoice_line l JOIN invoice i ON i.invoice_id = l.invoice_id "
            + "WHERE l.tenant_id <> i.tenant_id", Integer.class);
        assertThat(mismatched).isZero();
    }

    /** The attack: a line claiming another workspace's tenant for this invoice -- refused by the database itself. */
    @Test
    void aLineCannotClaimATenantItsInvoiceDoesNotHave() {
        assertThatThrownBy(() -> this.jdbc.update("INSERT INTO invoice_line (invoice_id, tenant_id, sort, description) VALUES (1, 2901, 2, 'x')"))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_invoice_line_invoice_tenant");
        assertThatThrownBy(() -> this.jdbc.update("UPDATE invoice_line SET tenant_id = 2901 WHERE invoice_id = 1"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aLineWithoutATenantIsRefused() {
        assertThatThrownBy(() -> this.jdbc.update("INSERT INTO invoice_line (invoice_id, sort, description) VALUES (1, 2, 'x')"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Read by invoice id AND tenant: another workspace's invoice id reaches nothing. */
    @Test
    void readingByAnotherWorkspacesInvoiceIdReturnsNoLines() {
        List<Map<String, Object>> leaked = this.jdbc.queryForList(
            "SELECT * FROM invoice_line WHERE invoice_id = ? AND tenant_id = ? ORDER BY sort", 1L, 2901L);
        assertThat(leaked).isEmpty();
        assertThat(this.jdbc.queryForList("SELECT * FROM invoice_line WHERE invoice_id = ? AND tenant_id = ? ORDER BY sort", 1L, 2905L))
            .extracting(r -> r.get("description")).containsExactly("Seats", "Storage kept");
    }

    @Test
    void deletingAnInvoiceStillTakesItsLines() {
        this.jdbc.update("DELETE FROM invoice WHERE invoice_id = 1");
        assertThat(this.jdbc.queryForObject("SELECT count(*) FROM invoice_line WHERE invoice_id = 1", Integer.class)).isZero();
    }

    // ---- plumbing ------------------------------------------------------------------------------

    private static String v59() throws IOException {
        try (InputStream in = InvoiceLineTenantPostgresTest.class.getResourceAsStream(V59)) {
            assertThat(in).as(V59).isNotNull();
            byte[] bytes = new byte[in.available()];
            int read = 0;
            while (read < bytes.length) {
                read += in.read(bytes, read, bytes.length - read);
            }
            return new String(bytes, StandardCharsets.UTF_8);
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
