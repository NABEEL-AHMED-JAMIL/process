package process.security.signing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * identity_signing_key through plain JDBC: no entity, so neither the tenant filter nor a stale persistence
 * context can come between Identity and the key it signs with.
 *
 * @author Nabeel Ahmed
 */
@Component
public class JdbcSigningKeyStore implements SigningKeyStore {

    private static final RowMapper<StoredSigningKey> ROW = (rs, n) -> new StoredSigningKey(rs.getString("kid"),
        rs.getString("public_key"), rs.getString("private_key_sealed"), rs.getString("status"),
        rs.getTimestamp("created_at"), rs.getTimestamp("retired_at"));

    private final JdbcTemplate sql;
    private final long publishRetiredForDays;

    public JdbcSigningKeyStore(JdbcTemplate sql, @Value("${jwt.refresh-token.expiry-days:7}") long refreshDays) {
        this.sql = sql;
        // A retired key's refresh tokens live out their seven days; a day more covers clock skew.
        this.publishRetiredForDays = refreshDays + 1;
    }

    @Override
    public Optional<StoredSigningKey> active() {
        List<StoredSigningKey> rows = this.sql.query(
            "SELECT * FROM identity_signing_key WHERE status = 'active'", ROW);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<StoredSigningKey> published() {
        return this.sql.query("SELECT * FROM identity_signing_key WHERE status = 'active' "
            + "OR (status = 'retired' AND retired_at > now() - (? * interval '1 day')) ORDER BY created_at DESC",
            ROW, this.publishRetiredForDays);
    }

    @Override
    public boolean insertActiveIfNone(StoredSigningKey key) {
        return this.sql.update("INSERT INTO identity_signing_key (kid, algorithm, public_key, private_key_sealed, status, "
            + "created_at) VALUES (?, 'RS256', ?, ?, 'active', ?) ON CONFLICT DO NOTHING",
            key.getKid(), key.getPublicKey(), key.getSealedPrivateKey(), new Timestamp(System.currentTimeMillis())) == 1;
    }

    @Override
    public void retire(String kid) {
        this.sql.update("UPDATE identity_signing_key SET status = 'retired', retired_at = now() WHERE kid = ? AND status = 'active'", kid);
    }
}
