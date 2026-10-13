package process.pipeline.review;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;

/** {@link ReviewPolicyStore} on review_policy (V209), through the application's pool: row-level security applies. */
@Component
public class JdbcReviewPolicyStore implements ReviewPolicyStore {

    private final JdbcTemplate jdbc;

    public JdbcReviewPolicyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Saved> find(long tenantId) {
        return this.jdbc.query("SELECT tenant_id, bulk_approve_runs, updated_by, date_updated FROM review_policy WHERE tenant_id = ?",
            (rs, n) -> {
                Saved saved = new Saved();
                saved.tenantId = rs.getLong("tenant_id");
                saved.bulkApproveRuns = rs.getBoolean("bulk_approve_runs");
                saved.updatedBy = (Long) rs.getObject("updated_by");
                OffsetDateTime at = rs.getObject("date_updated", OffsetDateTime.class);
                saved.updatedAt = at == null ? null : at.toInstant();
                return saved;
            }, tenantId).stream().findFirst();
    }

    @Override
    public void save(long tenantId, boolean bulkApproveRuns, Long by) {
        this.jdbc.update("INSERT INTO review_policy (tenant_id, bulk_approve_runs, updated_by, date_updated) VALUES (?, ?, ?, now()) "
            + "ON CONFLICT (tenant_id) DO UPDATE SET bulk_approve_runs = EXCLUDED.bulk_approve_runs, updated_by = EXCLUDED.updated_by, "
            + "date_updated = now()", tenantId, bulkApproveRuns, by);
    }
}
