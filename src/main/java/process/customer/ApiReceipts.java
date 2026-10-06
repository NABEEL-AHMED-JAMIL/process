package process.customer;

import org.barco.platform.api.IdempotencyKeys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * The customer API's Idempotency-Key receipts (MIG-332, ADR-025 decision 6; V201 api_idempotency_receipt), per workspace,
 * client and key, under row security as the caller. The worker callbacks' receipts (CallbackReceipts, V80) are the
 * pattern: a claim is one INSERT ... ON CONFLICT DO NOTHING, and a request that did not insert reads what the first one
 * left -- its fingerprint, and once answered its answer, replayed for {@link IdempotencyKeys#REPLAY_WINDOW}.
 */
@Repository
public class ApiReceipts {

    /** One receipt: the request's fingerprint and, once answered, the answer. */
    public static final class Receipt {
        public final String requestHash;
        public final Integer status;
        public final String body;
        public final String location;

        Receipt(String requestHash, Integer status, String body, String location) {
            this.requestHash = requestHash;
            this.status = status;
            this.body = body;
            this.location = location;
        }

        public boolean answered() {
            return this.status != null;
        }
    }

    private static final String WINDOW = "interval '" + IdempotencyKeys.REPLAY_WINDOW.toHours() + " hours'";

    private final JdbcTemplate sql;

    public ApiReceipts(JdbcTemplate sql) {
        this.sql = sql;
    }

    /** The receipt still inside the replay window. */
    public Optional<Receipt> find(long tenantId, String clientId, String key) {
        return this.sql.query("SELECT request_hash, response_status, response_body, location FROM api_idempotency_receipt "
            + "WHERE tenant_id = ? AND client_id = ? AND idempotency_key = ? AND created_at >= now() - " + WINDOW,
            (rs, n) -> new Receipt(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3), rs.getString(4)), tenantId, clientId, key)
            .stream().findFirst();
    }

    /** True when this request now holds the key. The client's keys past the window are purged first. */
    public boolean claim(long tenantId, String clientId, String key, String requestHash) {
        this.sql.update("DELETE FROM api_idempotency_receipt WHERE tenant_id = ? AND client_id = ? AND created_at < now() - " + WINDOW,
            tenantId, clientId);
        return this.sql.update("INSERT INTO api_idempotency_receipt (tenant_id, client_id, idempotency_key, request_hash) VALUES "
            + "(?, ?, ?, ?) ON CONFLICT (tenant_id, client_id, idempotency_key) DO NOTHING", tenantId, clientId, key, requestHash) == 1;
    }

    public void answer(long tenantId, String clientId, String key, int status, String body, String location) {
        this.sql.update("UPDATE api_idempotency_receipt SET response_status = ?, response_body = ?, location = ?, answered_at = now() "
            + "WHERE tenant_id = ? AND client_id = ? AND idempotency_key = ?", status, body, location, tenantId, clientId, key);
    }

    /** The request failed for a reason of ours: the key is free again for the client's retry. */
    public void release(long tenantId, String clientId, String key) {
        this.sql.update("DELETE FROM api_idempotency_receipt WHERE tenant_id = ? AND client_id = ? AND idempotency_key = ? "
            + "AND response_status IS NULL", tenantId, clientId, key);
    }
}
