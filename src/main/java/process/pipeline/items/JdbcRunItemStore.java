package process.pipeline.items;

import java.sql.PreparedStatement;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@link RunItemStore} on run_item and run_item_file (V233), under the session's row security. */
@Repository
public class JdbcRunItemStore implements RunItemStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<ItemRow.StepEntry>> ENTRIES = new TypeReference<List<ItemRow.StepEntry>>() { };

    static final String COLUMNS = "run_item_id, job_queue_id, attempt, item_key, item_ref, parent_key, label, position, status, "
        + "failed_step_index, failed_step_key, reason, steps, child_count, child_failed, file_count, current, started_at, ended_at, duration_ms";

    private final JdbcTemplate jdbc;

    public JdbcRunItemStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(List<ItemRow> rows) {
        for (ItemRow row : rows) {
            Long id = this.jdbc.queryForObject("INSERT INTO run_item (job_queue_id, attempt, item_key, item_ref, parent_key, label, position, "
                + "status, failed_step_index, failed_step_key, reason, steps, child_count, child_failed, file_count, current, started_at, "
                + "ended_at, duration_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, true, ?, ?, ?) "
                + "ON CONFLICT (job_queue_id, item_key, attempt) DO UPDATE SET item_ref = EXCLUDED.item_ref, parent_key = EXCLUDED.parent_key, "
                + "label = EXCLUDED.label, position = EXCLUDED.position, status = EXCLUDED.status, failed_step_index = EXCLUDED.failed_step_index, "
                + "failed_step_key = EXCLUDED.failed_step_key, reason = EXCLUDED.reason, steps = EXCLUDED.steps, child_count = EXCLUDED.child_count, "
                + "child_failed = EXCLUDED.child_failed, file_count = EXCLUDED.file_count, current = true, started_at = EXCLUDED.started_at, "
                + "ended_at = EXCLUDED.ended_at, duration_ms = EXCLUDED.duration_ms, date_updated = now() RETURNING run_item_id", Long.class,
                row.jobQueueId, row.attempt, row.itemKey, row.itemRef, row.parentKey, ItemRow.cut(row.label, 512), row.position, row.status,
                row.failedStepIndex, row.failedStepKey, row.reason, json(row.steps), row.childCount, row.childFailed, row.fileCount,
                ts(row.startedAt), ts(row.endedAt), row.durationMs);
            row.runItemId = id;
            this.jdbc.update("UPDATE run_item SET current = false, date_updated = now() WHERE job_queue_id = ? AND item_key = ? AND attempt < ? "
                + "AND current", row.jobQueueId, row.itemKey, row.attempt);
        }
    }

    @Override
    public void retireBefore(long jobQueueId, int attempt) {
        this.jdbc.update("UPDATE run_item SET current = false, date_updated = now() WHERE job_queue_id = ? AND attempt < ? AND current",
            jobQueueId, attempt);
    }

    @Override
    public void addFiles(List<ItemRow.FileRow> files) {
        for (ItemRow.FileRow f : files) {
            this.jdbc.update("INSERT INTO run_item_file (job_queue_id, attempt, item_key, step_index, step_key, role, step_execution_id, "
                + "file_name, bucket_alias, object_key, byte_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", f.jobQueueId, f.attempt, f.itemKey,
                f.stepIndex, f.stepKey, f.role, f.stepExecutionId, ItemRow.cut(f.fileName, 512), f.bucketAlias, f.objectKey, f.byteCount);
        }
    }

    @Override
    public List<ItemRow> current(long jobQueueId) {
        return this.jdbc.query("SELECT " + COLUMNS + " FROM run_item WHERE job_queue_id = ? AND current ORDER BY parent_key NULLS FIRST, position",
            JdbcRunItemStore::row, jobQueueId);
    }

    @Override
    public List<ItemRow> ofAttempt(long jobQueueId, int attempt) {
        return this.jdbc.query("SELECT " + COLUMNS + " FROM run_item WHERE job_queue_id = ? AND attempt = ? ORDER BY parent_key NULLS FIRST, position",
            JdbcRunItemStore::row, jobQueueId, attempt);
    }

    @Override
    public List<ItemRow> versions(long jobQueueId, String itemKey) {
        return this.jdbc.query("SELECT " + COLUMNS + " FROM run_item WHERE job_queue_id = ? AND item_key = ? ORDER BY attempt DESC",
            JdbcRunItemStore::row, jobQueueId, itemKey);
    }

    @Override
    public Optional<ItemRow> byId(long runItemId) {
        List<ItemRow> found = this.jdbc.query("SELECT " + COLUMNS + " FROM run_item WHERE run_item_id = ?", JdbcRunItemStore::row, runItemId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private static final String FILE_COLUMNS = "f.run_item_file_id, f.job_queue_id, f.attempt, f.item_key, f.step_index, f.step_key, f.role, "
        + "f.step_execution_id, f.file_name, f.bucket_alias, f.object_key, f.byte_count, o.run_output_id, o.run_dataset_id, o.format, o.expires_at, "
        + "o.file_id, o.byte_count AS output_bytes, o.bucket_alias AS output_bucket, o.object_key AS output_key "
        + "FROM run_item_file f LEFT JOIN run_output o ON o.step_execution_id = f.step_execution_id AND o.name = f.file_name ";

    @Override
    public List<ItemRow.FileRow> files(long jobQueueId, String itemKey, int attempt, String stepKey, String search, int offset, int limit) {
        List<Object> args = new ArrayList<>(Arrays.asList(jobQueueId, itemKey, attempt));
        String where = filesWhere(stepKey, search, args);
        args.add(limit);
        args.add(offset);
        return this.jdbc.query("SELECT " + FILE_COLUMNS + where + " ORDER BY f.step_index, f.run_item_file_id LIMIT ? OFFSET ?",
            JdbcRunItemStore::file, args.toArray());
    }

    @Override
    public int countFiles(long jobQueueId, String itemKey, int attempt, String stepKey, String search) {
        List<Object> args = new ArrayList<>(Arrays.asList(jobQueueId, itemKey, attempt));
        String where = filesWhere(stepKey, search, args);
        Integer n = this.jdbc.queryForObject("SELECT count(*) FROM run_item_file f " + where, Integer.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** A document and its sub-items, each at its current version. */
    private static final String FAMILY = " JOIN run_item i ON i.job_queue_id = f.job_queue_id AND i.item_key = f.item_key AND i.attempt = f.attempt"
        + " AND i.current AND (i.item_key = ? OR i.parent_key = ?) ";

    @Override
    public List<ItemRow.FileRow> familyFiles(long jobQueueId, String parentKey, String stepKey, String search, int offset, int limit) {
        List<Object> args = new ArrayList<>(Arrays.asList(parentKey, parentKey, jobQueueId));
        String where = filter(new StringBuilder("WHERE f.job_queue_id = ?"), stepKey, search, args);
        args.add(limit);
        args.add(offset);
        return this.jdbc.query("SELECT " + FILE_COLUMNS + FAMILY + where
            + " ORDER BY f.step_index, i.parent_key NULLS FIRST, i.position, f.run_item_file_id LIMIT ? OFFSET ?", JdbcRunItemStore::file,
            args.toArray());
    }

    @Override
    public int countFamilyFiles(long jobQueueId, String parentKey, String stepKey, String search) {
        List<Object> args = new ArrayList<>(Arrays.asList(parentKey, parentKey, jobQueueId));
        String where = filter(new StringBuilder("WHERE f.job_queue_id = ?"), stepKey, search, args);
        Integer n = this.jdbc.queryForObject("SELECT count(*) FROM run_item_file f" + FAMILY + where, Integer.class, args.toArray());
        return n == null ? 0 : n;
    }

    private static String filesWhere(String stepKey, String search, List<Object> args) {
        return filter(new StringBuilder("WHERE f.job_queue_id = ? AND f.item_key = ? AND f.attempt = ?"), stepKey, search, args);
    }

    private static String filter(StringBuilder where, String stepKey, String search, List<Object> args) {
        if (stepKey != null && !stepKey.isEmpty()) {
            where.append(" AND f.step_key = ?");
            args.add(stepKey);
        }
        if (search != null && !search.trim().isEmpty()) {
            where.append(" AND f.file_name ILIKE ?");
            args.add("%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return where.toString();
    }

    @Override
    public Optional<ItemRow.FileRow> file(long runItemFileId) {
        List<ItemRow.FileRow> found = this.jdbc.query("SELECT " + FILE_COLUMNS + "WHERE f.run_item_file_id = ?", JdbcRunItemStore::file,
            runItemFileId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public Map<Long, int[]> counts(Collection<Long> jobQueueIds) {
        Map<Long, int[]> counts = new LinkedHashMap<>();
        if (jobQueueIds == null || jobQueueIds.isEmpty()) {
            return counts;
        }
        Long[] ids = jobQueueIds.toArray(new Long[0]);
        this.jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT job_queue_id, count(*), "
                + "count(*) FILTER (WHERE status = 'done' AND child_failed = 0), "
                + "count(*) FILTER (WHERE status = 'failed' OR child_failed > 0), count(*) FILTER (WHERE status = 'running') "
                + "FROM run_item WHERE job_queue_id = ANY (?) AND current AND parent_key IS NULL GROUP BY job_queue_id");
            Array array = con.createArrayOf("bigint", ids);
            ps.setArray(1, array);
            return ps;
        }, (ResultSet rs) -> {
            counts.put(rs.getLong(1), new int[] {rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)});
        });
        return counts;
    }

    @Override
    public List<Attention> attention(Instant since, int limit) {
        return this.jdbc.query("SELECT i.job_queue_id, q.job_id, count(*) FILTER (WHERE i.status = 'failed') AS failed, count(*) AS items, "
            + "max(q.end_time) AS ended, (array_agg(i.label ORDER BY i.position) FILTER (WHERE i.status = 'failed'))[1:5] AS labels, "
            + "array_agg(DISTINCT i.failed_step_key) FILTER (WHERE i.status = 'failed') AS steps "
            + "FROM run_item i JOIN job_queue q ON q.job_queue_id = i.job_queue_id "
            + "WHERE i.current AND q.end_time >= ? AND q.job_status IN ('Completed', 'Failed') "
            + "GROUP BY i.job_queue_id, q.job_id HAVING count(*) FILTER (WHERE i.status = 'failed') > 0 "
            + "ORDER BY max(q.end_time) DESC LIMIT ?", (rs, n) -> {
                Attention a = new Attention();
                a.jobQueueId = rs.getLong(1);
                a.jobId = rs.getLong(2);
                a.failed = rs.getInt(3);
                a.items = rs.getInt(4);
                Timestamp ended = rs.getTimestamp(5);
                a.endedAt = ended == null ? null : ended.toInstant();
                a.labels = strings(rs.getArray(6));
                a.stepKeys = strings(rs.getArray(7));
                return a;
            }, Timestamp.from(since), limit);
    }

    @Override
    public void journalCompleted(long runItemId) {
        this.jdbc.update("INSERT INTO api_event_out (tenant_id, kind, job_queue_id, run_item_id, attempt) SELECT tenant_id, 'item', job_queue_id, "
            + "run_item_id, attempt FROM run_item WHERE run_item_id = ?", runItemId);
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (Object o : (Object[]) array.getArray()) {
            if (o != null) {
                out.add(o.toString());
            }
        }
        return out;
    }

    static ItemRow row(ResultSet rs, int n) throws SQLException {
        ItemRow row = new ItemRow();
        row.runItemId = rs.getLong("run_item_id");
        row.jobQueueId = rs.getLong("job_queue_id");
        row.attempt = rs.getInt("attempt");
        row.itemKey = rs.getString("item_key");
        row.itemRef = rs.getString("item_ref");
        row.parentKey = rs.getString("parent_key");
        row.label = rs.getString("label");
        row.position = rs.getInt("position");
        row.status = rs.getString("status");
        int failedAt = rs.getInt("failed_step_index");
        row.failedStepIndex = rs.wasNull() ? null : failedAt;
        row.failedStepKey = rs.getString("failed_step_key");
        row.reason = rs.getString("reason");
        row.steps = entries(rs.getString("steps"));
        row.childCount = rs.getInt("child_count");
        row.childFailed = rs.getInt("child_failed");
        row.fileCount = rs.getInt("file_count");
        row.current = rs.getBoolean("current");
        row.startedAt = instant(rs.getTimestamp("started_at"));
        row.endedAt = instant(rs.getTimestamp("ended_at"));
        long ms = rs.getLong("duration_ms");
        row.durationMs = rs.wasNull() ? null : ms;
        return row;
    }

    static ItemRow.FileRow file(ResultSet rs, int n) throws SQLException {
        ItemRow.FileRow f = new ItemRow.FileRow();
        f.runItemFileId = rs.getLong("run_item_file_id");
        f.jobQueueId = rs.getLong("job_queue_id");
        f.attempt = rs.getInt("attempt");
        f.itemKey = rs.getString("item_key");
        f.stepIndex = rs.getInt("step_index");
        f.stepKey = rs.getString("step_key");
        f.role = rs.getString("role");
        long se = rs.getLong("step_execution_id");
        f.stepExecutionId = rs.wasNull() ? null : se;
        f.fileName = rs.getString("file_name");
        f.bucketAlias = rs.getString("bucket_alias");
        f.objectKey = rs.getString("object_key");
        long bytes = rs.getLong("byte_count");
        f.byteCount = rs.wasNull() ? null : bytes;
        long output = rs.getLong("run_output_id");
        f.runOutputId = rs.wasNull() ? null : output;
        long dataset = rs.getLong("run_dataset_id");
        f.runDatasetId = rs.wasNull() ? null : dataset;
        f.format = rs.getString("format");
        f.expiresAt = instant(rs.getTimestamp("expires_at"));
        f.fileId = rs.getString("file_id");
        long outputBytes = rs.getLong("output_bytes");
        if (!rs.wasNull() && f.byteCount == null) {
            f.byteCount = outputBytes;
        }
        // An upload the step recorded in the manifest: the object it wrote.
        if (f.bucketAlias == null && rs.getString("output_bucket") != null) {
            f.bucketAlias = rs.getString("output_bucket");
            f.objectKey = rs.getString("output_key");
        }
        return f;
    }

    static List<ItemRow.StepEntry> entries(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(JSON.readValue(json, ENTRIES));
        } catch (JsonProcessingException unreadable) {
            return new ArrayList<>();
        }
    }

    static String json(List<ItemRow.StepEntry> entries) {
        try {
            return JSON.writeValueAsString(entries == null ? Collections.emptyList() : entries);
        } catch (JsonProcessingException impossible) {
            return "[]";
        }
    }

    private static Timestamp ts(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    private static Instant instant(Timestamp at) {
        return at == null ? null : at.toInstant();
    }
}
