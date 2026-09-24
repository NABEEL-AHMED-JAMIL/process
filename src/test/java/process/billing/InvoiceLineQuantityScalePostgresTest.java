package process.billing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-197, against a real PostgreSQL: after V60 an invoice line holds a quantity exactly as the meter
 * does, numeric(24,6). The table starts as V50's baseline left it -- quantity numeric(18,6),
 * included_quantity and billable_quantity numeric(20,5) -- with a row already in it.
 */
class InvoiceLineQuantityScalePostgresTest {

    private static final String V60 = "/db/changelog/changelog-sets/V60.0-one-quantity-scale/V60__one_quantity_scale.sql";

    private ThrowawaySchema schema;
    private JdbcTemplate jdbc;

    @BeforeEach
    void before() {
        this.schema = ThrowawaySchema.create("mig197_it");
        this.jdbc = this.schema.jdbc();
        this.jdbc.execute("CREATE TABLE invoice_line (invoice_line_id BIGSERIAL PRIMARY KEY, quantity NUMERIC(18,6) NOT NULL DEFAULT 0, "
            + "included_quantity NUMERIC(20,5), billable_quantity NUMERIC(20,5))");
        this.jdbc.update("INSERT INTO invoice_line (quantity, included_quantity, billable_quantity) VALUES (140.5, 10.25, 130.25)");
        this.schema.run(V60, this.jdbc);
    }

    @AfterEach
    void after() {
        if (this.schema != null) {
            this.schema.close();
        }
    }

    private Map<String, Object> write(String quantity, String included, String billable) {
        Long id = this.jdbc.queryForObject("INSERT INTO invoice_line (quantity, included_quantity, billable_quantity) VALUES (?, ?, ?) RETURNING invoice_line_id",
            Long.class, new BigDecimal(quantity), new BigDecimal(included), new BigDecimal(billable));
        return this.jdbc.queryForMap("SELECT quantity, included_quantity, billable_quantity FROM invoice_line WHERE invoice_line_id = ?", id);
    }

    @Test
    void aFullScaleQuantitySurvivesInEveryColumn() {
        Map<String, Object> row = this.write("123456789012.123456", "123456789012.123456", "0.000001");

        assertThat(row.get("quantity")).isEqualTo(new BigDecimal("123456789012.123456"));
        assertThat(row.get("included_quantity")).isEqualTo(new BigDecimal("123456789012.123456"));
        assertThat(row.get("billable_quantity")).isEqualTo(new BigDecimal("0.000001"));
    }

    /**
     * At an allowance boundary the sixth place is the difference between billed and not: a month of
     * 1000.000001 against an allowance of 1000 bills 0.000001. At five places it read as 0.00000.
     */
    @Test
    void theSixthPlaceSurvivesAtAnAllowanceBoundary() {
        Map<String, Object> row = this.write("1000.000001", "1000", "0.000001");

        assertThat(((BigDecimal) row.get("billable_quantity")).signum()).isPositive();
        assertThat(((BigDecimal) row.get("quantity")).subtract((BigDecimal) row.get("included_quantity")))
            .isEqualByComparingTo((BigDecimal) row.get("billable_quantity"));
    }

    /** Past the old 18-digit type's ceiling: two terabytes of reads in a month. */
    @Test
    void aByteMeterPastATerabyteFits() {
        Map<String, Object> row = this.write("2199023255552", "0", "2199023255552");
        assertThat(row.get("quantity")).isEqualTo(new BigDecimal("2199023255552.000000"));
    }

    @Test
    void rowsWrittenBeforeKeepTheirValues() {
        Map<String, Object> old = this.jdbc.queryForMap("SELECT quantity, included_quantity, billable_quantity FROM invoice_line ORDER BY invoice_line_id LIMIT 1");
        assertThat((BigDecimal) old.get("quantity")).isEqualByComparingTo("140.5");
        assertThat((BigDecimal) old.get("included_quantity")).isEqualByComparingTo("10.25");
        assertThat((BigDecimal) old.get("billable_quantity")).isEqualByComparingTo("130.25");
        Integer scale = this.jdbc.queryForObject("SELECT numeric_scale FROM information_schema.columns WHERE table_schema = ? "
            + "AND table_name = 'invoice_line' AND column_name = 'included_quantity'", Integer.class, this.schema.name);
        assertThat(scale).isEqualTo(6);
    }
}
