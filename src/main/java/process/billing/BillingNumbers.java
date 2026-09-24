package process.billing;

import java.sql.SQLException;

/**
 * Where billing's running numbers come from (MIG-9): one counter per series -- INV-2026-09,
 * RCP-2026-09, STM-2905-2026-01-01-2026-12-31 -- taken and advanced in the caller's transaction.
 *
 * It replaces COUNT(*) plus one and a probe, which handed two concurrent issuers the same number and
 * left the unique index to answer with a stack trace. Numbers stay gapless within a period: a
 * transaction that rolls back returns its number, because the counter row rolls back with it.
 */
public interface BillingNumbers {

    /**
     * Which table a series' numbers already live in, so a new counter starts after the highest of them:
     * invoices and credit notes (invoice.number, INV-/CN-), receipts, statements. The base keeps
     * INV-2026-09 and CN-2026-09 apart; the series only says where to look.
     */
    enum Series { INVOICE, RECEIPT, STATEMENT }

    /** What a caller is told when a number was taken under it anyway; trying again takes the next. */
    String COLLISION = "Another billing document took that number at the same moment. Nothing was saved; try again.";

    /** The next running number of this series, in the caller's transaction. Refused outside one. */
    int next(Series series, String base);

    /**
     * True when a failure is two documents meeting on one number (a unique violation on a number
     * column): a retryable refusal to answer in {@link #COLLISION}'s words, not an internal error.
     */
    static boolean isNumberCollision(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException && "23505".equals(((SQLException) cause).getSQLState())) {
                String message = String.valueOf(cause.getMessage());
                return message.contains("number");
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
