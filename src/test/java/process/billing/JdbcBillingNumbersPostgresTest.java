package process.billing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MIG-9, against a real PostgreSQL: billing numbers from a locked counter, not COUNT(*) plus probing.
 * Two "instances" here are two JdbcBillingNumbers over two separate data sources -- separate pools,
 * as two console replicas would have -- racing on one database. The counter table and the unique
 * index are created by V58's own SQL file, so what is tested is what Liquibase applies.
 *
 * Each run works in a throwaway schema it drops afterwards. Skipped (not failed) when no PostgreSQL
 * answers at BILLING_IT_POSTGRES_URL (default: the dev database, localhost:5433/etl_job).
 */
class JdbcBillingNumbersPostgresTest {

    private static final String URL = env("BILLING_IT_POSTGRES_URL", "jdbc:postgresql://localhost:5433/etl_job");
    private static final String USER = env("SPRING_DATASOURCE_USERNAME", "nabeel.amd93");
    private static final String PASSWORD = env("SPRING_DATASOURCE_PASSWORD", "admin");
    private static final String V58 = "/db/changelog/changelog-sets/V58.0-atomic-billing-numbers/V58__atomic_billing_numbers.sql";

    private String schema;
    private Instance first;
    private Instance second;

    /** One console replica: its own pool, its own transactions, its own counter client. */
    private static final class Instance {
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final JdbcBillingNumbers numbers;

        Instance(String schema) {
            DriverManagerDataSource source = new DriverManagerDataSource(URL + "?currentSchema=" + schema, USER, PASSWORD);
            this.jdbc = new JdbcTemplate(source);
            this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            this.numbers = new JdbcBillingNumbers(this.jdbc);
        }

        int next(BillingNumbers.Series series, String base) {
            return this.tx.execute(status -> this.numbers.next(series, base));
        }
    }

    @BeforeEach
    void schema() throws Exception {
        assumeTrue(reachable(), "no PostgreSQL at " + URL);
        this.schema = "mig9_it_" + System.nanoTime();
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + this.schema);
            s.execute("SET search_path TO " + this.schema);
            // Just the columns the counter seeds from, shaped as in etl_job.
            s.execute("CREATE TABLE invoice (invoice_id BIGSERIAL PRIMARY KEY, number VARCHAR(32) NOT NULL UNIQUE)");
            s.execute("CREATE TABLE payment (payment_id BIGSERIAL PRIMARY KEY, receipt_number VARCHAR(32))");
            s.execute("CREATE TABLE billing_document (billing_document_id BIGSERIAL PRIMARY KEY, kind VARCHAR(24) NOT NULL, number VARCHAR(64))");
            for (String statement : v58().split(";")) {
                if (!statement.replaceAll("(?m)^\\s*--.*$", "").trim().isEmpty()) {
                    s.execute(statement);
                }
            }
        }
        this.first = new Instance(this.schema);
        this.second = new Instance(this.schema);
    }

    @AfterEach
    void drop() throws Exception {
        if (this.schema != null) {
            try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA " + this.schema + " CASCADE");
            }
        }
    }

    @Test
    void concurrentIssuersOnTwoInstancesGetDistinctGaplessNumbers() throws Exception {
        int perThread = 10;
        int threadsPerInstance = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threadsPerInstance * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Integer>>> results = new ArrayList<>();
        for (Instance instance : new Instance[] {this.first, this.second}) {
            for (int t = 0; t < threadsPerInstance; t++) {
                Callable<List<Integer>> issuer = () -> {
                    start.await();
                    List<Integer> mine = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        mine.add(instance.next(BillingNumbers.Series.INVOICE, "INV-2026-09"));
                    }
                    return mine;
                };
                results.add(pool.submit(issuer));
            }
        }
        start.countDown();
        List<Integer> all = new ArrayList<>();
        for (Future<List<Integer>> result : results) {
            all.addAll(result.get());
        }
        pool.shutdown();

        int n = perThread * threadsPerInstance * 2;
        assertThat(all).hasSize(n).doesNotHaveDuplicates();
        assertThat(new TreeSet<>(all)).containsExactlyElementsOf(IntStream.rangeClosed(1, n).boxed().collect(Collectors.toList()));
    }

    /** Gapless within a period: a document that is never saved gives its number back. */
    @Test
    void aRolledBackNumberIsHandedOutAgain() {
        assertThat(this.first.next(BillingNumbers.Series.INVOICE, "INV-2026-09")).isEqualTo(1);
        Integer abandoned = this.first.tx.execute(status -> {
            int n = this.first.numbers.next(BillingNumbers.Series.INVOICE, "INV-2026-09");
            status.setRollbackOnly();
            return n;
        });
        assertThat(abandoned).isEqualTo(2);
        assertThat(this.second.next(BillingNumbers.Series.INVOICE, "INV-2026-09")).isEqualTo(2);
    }

    @Test
    void eachPeriodAndKindCountsOnItsOwn() {
        assertThat(this.first.next(BillingNumbers.Series.INVOICE, "INV-2026-09")).isEqualTo(1);
        assertThat(this.first.next(BillingNumbers.Series.INVOICE, "INV-2026-10")).isEqualTo(1);
        assertThat(this.first.next(BillingNumbers.Series.INVOICE, "CN-2026-09")).isEqualTo(1);
        assertThat(this.first.next(BillingNumbers.Series.INVOICE, "INV-2026-09")).isEqualTo(2);
    }

    /** The first number a counter hands out follows the highest one already issued, from each series' own table. */
    @Test
    void aNewCounterContinuesFromNumbersAlreadyIssued() {
        this.first.jdbc.update("INSERT INTO invoice (number) VALUES ('INV-2026-09-0007'), ('INV-2026-09-0003'), ('INV-2026-08-0012'), ('CN-2026-09-0002')");
        this.first.jdbc.update("INSERT INTO payment (receipt_number) VALUES ('RCP-2026-09-0004'), (NULL)");
        this.first.jdbc.update("INSERT INTO billing_document (kind, number) VALUES ('statement', 'STM-7-2026-01-01-2026-12-31'), "
            + "('statement', 'STM-7-2026-01-01-2026-12-31-3'), ('invoice', 'INV-2026-09-0007')");

        assertThat(this.first.next(BillingNumbers.Series.INVOICE, "INV-2026-09")).isEqualTo(8);
        assertThat(this.first.next(BillingNumbers.Series.INVOICE, "CN-2026-09")).isEqualTo(3);
        assertThat(this.first.next(BillingNumbers.Series.RECEIPT, "RCP-2026-09")).isEqualTo(5);
        assertThat(this.first.next(BillingNumbers.Series.STATEMENT, "STM-7-2026-01-01-2026-12-31")).isEqualTo(4);
        assertThat(this.first.next(BillingNumbers.Series.INVOICE, "INV-2026-09")).isEqualTo(9);
    }

    /** Outside a transaction the number would commit on its own and be lost if the document failed: a gap. */
    @Test
    void takingANumberOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> this.first.numbers.next(BillingNumbers.Series.INVOICE, "INV-2026-09"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
    }

    @Test
    void twoDocumentsCannotShareANumberAndTheCollisionIsRecognised() {
        this.first.jdbc.update("INSERT INTO billing_document (kind, number) VALUES ('invoice', 'INV-2026-09-0001')");
        this.first.jdbc.update("INSERT INTO billing_document (kind, number) VALUES ('payment_slip', NULL), ('payment_slip', NULL)");

        assertThatThrownBy(() -> this.first.jdbc.update("INSERT INTO billing_document (kind, number) VALUES ('invoice', 'INV-2026-09-0001')"))
            .isInstanceOf(DataIntegrityViolationException.class)
            .satisfies(ex -> assertThat(BillingNumbers.isNumberCollision(ex)).isTrue());
        assertThatThrownBy(() -> this.first.jdbc.update("INSERT INTO invoice (number) VALUES ('X'), ('X')"))
            .satisfies(ex -> assertThat(BillingNumbers.isNumberCollision(ex)).isTrue());
        assertThat(BillingNumbers.isNumberCollision(new IllegalStateException("no"))).isFalse();
    }

    // ---- plumbing ------------------------------------------------------------------------------

    private static String v58() throws IOException {
        try (InputStream in = JdbcBillingNumbersPostgresTest.class.getResourceAsStream(V58)) {
            assertThat(in).as(V58).isNotNull();
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
