package process.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.EnumMap;
import java.util.Map;

/**
 * {@link BillingNumbers} on PostgreSQL: billing_number_counter (V58), one row per series. The
 * INSERT ... ON CONFLICT DO UPDATE takes the row's lock, so concurrent issuers -- on one instance or
 * several -- queue on it and each gets the next value; the first issuer of a new series seeds the row
 * from the numbers that series already has, so a counter added to a live database continues it.
 */
@Component
public class JdbcBillingNumbers implements BillingNumbers {

    /** Where each series' numbers already are. Fixed strings, never input. */
    private static final Map<Series, String[]> ISSUED = new EnumMap<>(Series.class);

    static {
        ISSUED.put(Series.INVOICE, new String[] {"invoice", "number"});
        ISSUED.put(Series.RECEIPT, new String[] {"payment", "receipt_number"});
        ISSUED.put(Series.STATEMENT, new String[] {"billing_document", "number"});
    }

    private final JdbcTemplate jdbc;

    public JdbcBillingNumbers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int next(Series series, String base) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // On its own the counter would commit at once, and a document that then failed would leave a gap.
            throw new IllegalStateException("A billing number is taken inside the transaction that saves its document.");
        }
        String[] issued = ISSUED.get(series);
        String table = issued[0];
        String column = issued[1];
        // The running number is what follows "<base>-"; anything else sharing the prefix is not ours.
        String sql = "INSERT INTO billing_number_counter (base, last_value) VALUES (?, 1 + COALESCE("
            + "(SELECT MAX(CAST(substring(" + column + " FROM ?) AS INTEGER)) FROM " + table
            + " WHERE " + column + " LIKE ? AND substring(" + column + " FROM ?) ~ '^[0-9]{1,9}$'), 0)) "
            + "ON CONFLICT (base) DO UPDATE SET last_value = billing_number_counter.last_value + 1, updated_at = now() "
            + "RETURNING last_value";
        int from = base.length() + 2;
        String like = base.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "-%";
        Integer value = this.jdbc.queryForObject(sql, Integer.class, base, from, like, from);
        if (value == null) {
            throw new IllegalStateException("billing_number_counter answered no number for " + base);
        }
        return value;
    }
}
