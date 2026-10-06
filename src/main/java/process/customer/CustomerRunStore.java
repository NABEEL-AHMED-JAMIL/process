package process.customer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The runs the customer API reads (MIG-334, ADR-025 decision 3), in plain SQL as the caller (row security): a run of the
 * workspace's -- however it started -- that is not deleted, of a pipeline (job) that is not deleted. A run of another
 * workspace, a deleted one or none at all is simply not there.
 */
@Component
public class CustomerRunStore {

    /** One run, with what the API shows of it. Times are instants. */
    public static final class Row {
        public long runId;
        public long jobId;
        public long tenantId;
        public String jobStatus;
        public String message;
        public Instant createdAt;
        public Instant startedAt;
        public Instant endedAt;
        public int attempt;
        public String reference;
    }

    /** A run started through the API: the intake it was given (api_intake, V201). */
    public static final class Intake {
        public long runId;
        public String clientId;
        public String reference;
        public Long eventId;
        public String inputBucket;
        public String inputKey;
        /** The file ids it named, in order; empty for none. */
        public List<String> fileIds = new ArrayList<>();
    }

    private static final String COLUMNS = "SELECT q.job_queue_id, q.job_id, q.tenant_id, q.job_status, q.job_status_message, q.date_created, "
        + "q.start_time, COALESCE(q.end_time, q.skip_time) AS ended, q.attempt, i.reference "
        + "FROM job_queue q JOIN source_job j ON j.job_id = q.job_id AND j.tenant_id = q.tenant_id "
        + "LEFT JOIN api_intake i ON i.job_queue_id = q.job_queue_id "
        + "WHERE q.tenant_id = ? AND q.status <> 'Delete' AND j.job_status <> 'Delete' ";

    private static final RowMapper<Row> ROW = (rs, n) -> {
        Row row = new Row();
        row.runId = rs.getLong("job_queue_id");
        row.jobId = rs.getLong("job_id");
        row.tenantId = rs.getLong("tenant_id");
        row.jobStatus = rs.getString("job_status");
        row.message = rs.getString("job_status_message");
        row.createdAt = instant(rs.getObject("date_created", OffsetDateTime.class));
        row.startedAt = instant(rs.getObject("start_time", OffsetDateTime.class));
        row.endedAt = instant(rs.getObject("ended", OffsetDateTime.class));
        row.attempt = Math.max(1, rs.getInt("attempt"));
        row.reference = rs.getString("reference");
        return row;
    };

    private final JdbcTemplate sql;

    public CustomerRunStore(JdbcTemplate sql) {
        this.sql = sql;
    }

    /** One run of the workspace by its id. */
    public Optional<Row> find(long tenantId, long runId) {
        return this.sql.query(COLUMNS + "AND q.job_queue_id = ?", ROW, tenantId, runId).stream().findFirst();
    }

    /**
     * The workspace's runs, newest first, before {@code beforeRunId} (keyset), at most {@code limit}; each filter applies
     * when given. updatedAfter compares the run's latest time: its end, else its start, else its creation.
     */
    public List<Row> page(long tenantId, Long beforeRunId, int limit, Long jobId, Collection<String> jobStatuses, Instant createdAfter,
        Instant updatedAfter) {
        StringBuilder where = new StringBuilder(COLUMNS).append("AND q.job_queue_id < ? ");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(beforeRunId == null ? Long.MAX_VALUE : beforeRunId);
        if (jobId != null) {
            where.append("AND q.job_id = ? ");
            args.add(jobId);
        }
        if (jobStatuses != null && !jobStatuses.isEmpty()) {
            where.append("AND q.job_status IN (");
            int i = 0;
            for (String status : jobStatuses) {
                where.append(i++ == 0 ? "?" : ", ?");
                args.add(status);
            }
            where.append(") ");
        }
        if (createdAfter != null) {
            where.append("AND q.date_created > ? ");
            args.add(createdAfter.atOffset(ZoneOffset.UTC));
        }
        if (updatedAfter != null) {
            where.append("AND COALESCE(q.end_time, q.skip_time, q.start_time, q.date_created) > ? ");
            args.add(updatedAfter.atOffset(ZoneOffset.UTC));
        }
        where.append("ORDER BY q.job_queue_id DESC LIMIT ?");
        args.add(limit);
        return this.sql.query(where.toString(), ROW, args.toArray());
    }

    /** The intake of a run started through the API; empty for a run started any other way. */
    public Optional<Intake> intakeOf(long tenantId, long runId) {
        return this.sql.query("SELECT job_queue_id, client_id, reference, event_id, input_bucket, input_key, file_ids FROM api_intake "
            + "WHERE tenant_id = ? AND job_queue_id = ?", (rs, n) -> {
                Intake intake = new Intake();
                intake.runId = rs.getLong("job_queue_id");
                intake.clientId = rs.getString("client_id");
                intake.reference = rs.getString("reference");
                intake.eventId = (Long) rs.getObject("event_id");
                intake.inputBucket = rs.getString("input_bucket");
                intake.inputKey = rs.getString("input_key");
                String ids = rs.getString("file_ids");
                if (ids != null) {
                    for (String id : ids.split(",")) {
                        if (!id.trim().isEmpty()) {
                            intake.fileIds.add(id.trim());
                        }
                    }
                }
                return intake;
            }, tenantId, runId).stream().findFirst();
    }

    private static Instant instant(OffsetDateTime at) {
        return at == null ? null : at.toInstant();
    }
}
