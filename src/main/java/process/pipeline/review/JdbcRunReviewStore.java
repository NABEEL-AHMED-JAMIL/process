package process.pipeline.review;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.model.enums.ReviewDecision;
import process.model.enums.ReviewParty;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link RunReviewStore} on process's own tables (V189), through the application's pool: row-level security applies. */
@Component
public class JdbcRunReviewStore implements RunReviewStore {

    private static final String DECISION_COLUMNS = "run_review_decision_id, job_queue_id, attempt, party, decision, comment, reason, "
        + "reviewer_user_id, reviewer_name, decided_at";
    private static final String STATUS_COLUMNS = "job_queue_id, required_parties, status, decided_at, rerun_job_queue_id";

    private final JdbcTemplate jdbc;

    public JdbcRunReviewStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Decision> decisionsOf(long jobQueueId) {
        return this.jdbc.query("SELECT " + DECISION_COLUMNS + " FROM run_review_decision WHERE job_queue_id = ? "
            + "ORDER BY decided_at, run_review_decision_id", JdbcRunReviewStore::decision, jobQueueId);
    }

    @Override
    public Optional<Status> statusOf(long jobQueueId) {
        List<Status> found = this.jdbc.query("SELECT " + STATUS_COLUMNS + " FROM run_review WHERE job_queue_id = ?",
            JdbcRunReviewStore::status, jobQueueId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public Status lockPending(long jobQueueId, Set<ReviewParty> required) {
        this.jdbc.update("INSERT INTO run_review (job_queue_id, required_parties, status) VALUES (?, ?, 'PENDING') "
            + "ON CONFLICT (job_queue_id) DO NOTHING", jobQueueId, partiesOf(required));
        return this.jdbc.queryForObject("SELECT " + STATUS_COLUMNS + " FROM run_review WHERE job_queue_id = ? FOR UPDATE",
            JdbcRunReviewStore::status, jobQueueId);
    }

    @Override
    public long record(Decision d) {
        return this.jdbc.queryForObject("INSERT INTO run_review_decision (job_queue_id, attempt, party, decision, comment, reason, "
            + "reviewer_user_id, reviewer_name, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING run_review_decision_id",
            Long.class, d.jobQueueId, d.attempt, d.party.name(), d.decision.name(), d.comment, d.reason, d.reviewerUserId,
            d.reviewerName, Timestamp.from(d.decidedAt));
    }

    @Override
    public void settle(long jobQueueId, RunReviewStatus status, Instant decidedAt) {
        this.jdbc.update("UPDATE run_review SET status = ?, decided_at = ?, date_updated = now() WHERE job_queue_id = ?",
            status.name(), decidedAt == null ? null : Timestamp.from(decidedAt), jobQueueId);
    }

    @Override
    public void linkRerun(long jobQueueId, long rerunJobQueueId) {
        this.jdbc.update("UPDATE run_review SET rerun_job_queue_id = ?, date_updated = now() WHERE job_queue_id = ?", rerunJobQueueId,
            jobQueueId);
    }

    @Override
    public int settleResults(long jobQueueId, RunReviewStatus status) {
        return this.jdbc.update("UPDATE result_record SET review_status = ?, date_updated = now() WHERE job_queue_id = ? "
            + "AND review_status = 'PENDING'", status.name(), jobQueueId);
    }

    /** As run_review.required_parties holds them: the party names, INTERNAL first, joined by commas; '' for none. */
    static String partiesOf(Set<ReviewParty> parties) {
        return parties.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }

    static Set<ReviewParty> partiesFrom(String stored) {
        Set<ReviewParty> parties = EnumSet.noneOf(ReviewParty.class);
        if (stored != null) {
            for (String name : stored.split(",")) {
                if (!name.trim().isEmpty()) {
                    parties.add(ReviewParty.valueOf(name.trim()));
                }
            }
        }
        return parties;
    }

    private static Decision decision(ResultSet rs, int n) throws SQLException {
        Decision d = new Decision();
        d.runReviewDecisionId = rs.getLong("run_review_decision_id");
        d.jobQueueId = rs.getLong("job_queue_id");
        d.attempt = rs.getInt("attempt");
        d.party = ReviewParty.valueOf(rs.getString("party"));
        d.decision = ReviewDecision.valueOf(rs.getString("decision"));
        d.comment = rs.getString("comment");
        d.reason = rs.getString("reason");
        d.reviewerUserId = (Long) rs.getObject("reviewer_user_id");
        d.reviewerName = rs.getString("reviewer_name");
        d.decidedAt = instant(rs, "decided_at");
        return d;
    }

    private static Status status(ResultSet rs, int n) throws SQLException {
        Status s = new Status();
        s.jobQueueId = rs.getLong("job_queue_id");
        s.required = partiesFrom(rs.getString("required_parties"));
        s.status = RunReviewStatus.valueOf(rs.getString("status"));
        s.decidedAt = instant(rs, "decided_at");
        s.rerunJobQueueId = (Long) rs.getObject("rerun_job_queue_id");
        return s;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }
}
