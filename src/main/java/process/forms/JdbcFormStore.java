package process.forms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link FormStore} through plain JDBC, as the inbox and step stores write their tables. Every statement names the
 * workspace out loud; row security (V192) says the same thing again. form_submission's tenant_id is V192's trigger's: its
 * form's.
 */
@Component
public class JdbcFormStore implements FormStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<FormField>> FIELDS = new TypeReference<List<FormField>>() { };
    private static final TypeReference<LinkedHashMap<String, Object>> ANSWERS = new TypeReference<LinkedHashMap<String, Object>>() { };

    private static final String FORM_COLUMNS = "f.form_id, f.tenant_id, f.name, f.description, f.status, f.fields::text AS fields, "
        + "f.job_id, f.version, f.updated_by, f.date_created, f.date_updated";
    private static final String SUBMISSION_COLUMNS = "submission_id, form_id, tenant_id, form_version, answers::text AS answers, "
        + "submitted_by, submitted_by_name, submitted_at, status, job_id, job_queue_id, reason, bucket, storage_key";

    private final JdbcTemplate jdbc;

    public JdbcFormStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Form> list(long tenantId, boolean withArchived, boolean activeOnly) {
        String which = activeOnly ? " AND f.status = 'Active'" : withArchived ? "" : " AND f.status <> 'Archived'";
        return this.jdbc.query("SELECT " + FORM_COLUMNS + ", (SELECT count(*) FROM form_submission s WHERE s.form_id = f.form_id "
            + "AND s.tenant_id = f.tenant_id) AS submissions FROM form_definition f WHERE f.tenant_id = ?" + which
            + " ORDER BY lower(f.name), f.form_id", JdbcFormStore::form, tenantId);
    }

    @Override
    public Optional<Form> find(long tenantId, long formId) {
        return this.jdbc.query("SELECT " + FORM_COLUMNS + ", (SELECT count(*) FROM form_submission s WHERE s.form_id = f.form_id "
            + "AND s.tenant_id = f.tenant_id) AS submissions FROM form_definition f WHERE f.tenant_id = ? AND f.form_id = ?",
            JdbcFormStore::form, tenantId, formId).stream().findFirst();
    }

    @Override
    public long create(long tenantId, String name, String description, String status, List<FormField> fields, Long jobId, Long actor) {
        Long formId = this.jdbc.queryForObject("INSERT INTO form_definition (tenant_id, name, description, status, fields, job_id, "
            + "created_by, updated_by) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?) RETURNING form_id", Long.class, tenantId, name, description,
            status, json(fields), jobId, actor, actor);
        if (formId == null) {
            throw new IllegalStateException("The new form has no id.");
        }
        this.keepVersion(tenantId, formId, actor);
        return formId;
    }

    @Override
    public boolean update(long tenantId, long formId, String name, String description, String status, List<FormField> fields, Long jobId,
        Long actor) {
        boolean updated = this.jdbc.update("UPDATE form_definition SET name = ?, description = ?, status = ?, fields = ?::jsonb, job_id = ?, "
            + "version = version + 1, updated_by = ?, date_updated = now() WHERE tenant_id = ? AND form_id = ?", name, description, status,
            json(fields), jobId, actor, tenantId, formId) > 0;
        if (updated) {
            this.keepVersion(tenantId, formId, actor);
        }
        return updated;
    }

    /** The form's fields at its current version, as form_version keeps them (in the saving transaction). */
    private void keepVersion(long tenantId, long formId, Long actor) {
        this.jdbc.update("INSERT INTO form_version (form_id, tenant_id, version, name, fields, saved_by) SELECT form_id, tenant_id, version, "
            + "name, fields, ? FROM form_definition WHERE tenant_id = ? AND form_id = ? ON CONFLICT (form_id, version) DO NOTHING", actor,
            tenantId, formId);
    }

    @Override
    public Optional<List<FormField>> fieldsAt(long tenantId, long formId, int version) {
        List<String> found = this.jdbc.queryForList("SELECT fields::text FROM form_version WHERE tenant_id = ? AND form_id = ? AND version = ?",
            String.class, tenantId, formId, version);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(read(found.get(0), FIELDS));
        } catch (SQLException unreadable) {
            throw new IllegalStateException(unreadable.getMessage(), unreadable);
        }
    }

    @Override
    public List<String> answerValues(long tenantId, long formId, String fieldKey, int limit) {
        return this.jdbc.queryForList("SELECT value FROM (SELECT answers ->> ? AS value, submitted_at FROM form_submission "
            + "WHERE tenant_id = ? AND form_id = ? AND jsonb_typeof(answers -> ?) IN ('string', 'number')) a "
            + "WHERE btrim(value) <> '' GROUP BY value ORDER BY max(submitted_at) DESC LIMIT ?", String.class, fieldKey, tenantId, formId,
            fieldKey, limit);
    }

    @Override
    public long createUpload(long tenantId, long formId, String fieldKey, Long uploadedBy, String fileName, String contentType, long size,
        String bucket, String storageKey) {
        Long uploadId = this.jdbc.queryForObject("INSERT INTO form_upload (form_id, tenant_id, field_key, uploaded_by, file_name, content_type, "
            + "size_bytes, bucket, storage_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING upload_id", Long.class, formId, tenantId, fieldKey,
            uploadedBy, fileName, contentType, size, bucket, storageKey);
        if (uploadId == null) {
            throw new IllegalStateException("The new upload has no id.");
        }
        return uploadId;
    }

    @Override
    public Optional<FormFields.Upload> openUpload(long tenantId, long formId, String fieldKey, Long uploadedBy, long uploadId) {
        return this.jdbc.query("SELECT upload_id, file_name, content_type, size_bytes, bucket, storage_key FROM form_upload WHERE tenant_id = ? "
            + "AND form_id = ? AND field_key = ? AND uploaded_by IS NOT DISTINCT FROM ? AND upload_id = ? AND submission_id IS NULL",
            (rs, i) -> new FormFields.Upload(rs.getLong("upload_id"), rs.getString("file_name"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("bucket"), rs.getString("storage_key")),
            tenantId, formId, fieldKey, uploadedBy, uploadId).stream().findFirst();
    }

    @Override
    public void claimUploads(long tenantId, long submissionId, Collection<Long> uploadIds) {
        for (Long uploadId : uploadIds) {
            int claimed = this.jdbc.update("UPDATE form_upload SET submission_id = ? WHERE tenant_id = ? AND upload_id = ? AND submission_id IS NULL",
                submissionId, tenantId, uploadId);
            if (claimed == 0) {
                throw new IllegalStateException("Upload " + uploadId + " was sent with another submission.");
            }
        }
    }

    @Override
    public boolean setStatus(long tenantId, long formId, String status, Long actor) {
        return this.jdbc.update("UPDATE form_definition SET status = ?, updated_by = ?, date_updated = now() WHERE tenant_id = ? "
            + "AND form_id = ?", status, actor, tenantId, formId) > 0;
    }

    @Override
    public Submission receive(long tenantId, long formId, int formVersion, Map<String, Object> answers, Long jobId, Long submittedBy,
        String submittedByName) {
        return this.jdbc.queryForObject("INSERT INTO form_submission (form_id, tenant_id, form_version, answers, job_id, submitted_by, "
            + "submitted_by_name, status) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, 'Received') RETURNING " + SUBMISSION_COLUMNS,
            JdbcFormStore::submission, formId, tenantId, formVersion, json(answers), jobId, submittedBy, submittedByName);
    }

    @Override
    public void outcome(long tenantId, long submissionId, String status, Long jobQueueId, String reason, String bucket, String storageKey) {
        this.jdbc.update("UPDATE form_submission SET status = ?, job_queue_id = ?, reason = ?, bucket = ?, storage_key = ?, "
            + "date_updated = now() WHERE tenant_id = ? AND submission_id = ?", status, jobQueueId, reason, bucket, storageKey, tenantId,
            submissionId);
    }

    @Override
    public List<Submission> submissions(long tenantId, long formId, int limit) {
        return this.jdbc.query("SELECT " + SUBMISSION_COLUMNS + " FROM form_submission WHERE tenant_id = ? AND form_id = ? "
            + "ORDER BY submitted_at DESC, submission_id DESC LIMIT ?", JdbcFormStore::submission, tenantId, formId, limit);
    }

    @Override
    public Optional<Submission> submission(long tenantId, long submissionId) {
        return this.jdbc.query("SELECT " + SUBMISSION_COLUMNS + " FROM form_submission WHERE tenant_id = ? AND submission_id = ?",
            JdbcFormStore::submission, tenantId, submissionId).stream().findFirst();
    }

    @Override
    public List<Map<String, Object>> linkableJobs(long tenantId, int limit) {
        return this.jdbc.query("SELECT job_id, job_name, job_status FROM source_job WHERE tenant_id = ? AND job_status <> 'Delete' "
            + "ORDER BY lower(job_name), job_id LIMIT ?", (rs, i) -> {
                Map<String, Object> job = new LinkedHashMap<>();
                job.put("jobId", rs.getLong("job_id"));
                job.put("jobName", rs.getString("job_name"));
                job.put("jobStatus", rs.getString("job_status"));
                return job;
            }, tenantId, limit);
    }

    private static Form form(ResultSet rs, int row) throws SQLException {
        return new Form(rs.getLong("form_id"), rs.getLong("tenant_id"), rs.getString("name"), rs.getString("description"),
            rs.getString("status"), read(rs.getString("fields"), FIELDS), rs.getObject("job_id", Long.class), rs.getInt("version"),
            rs.getObject("updated_by", Long.class), instant(rs.getTimestamp("date_created")), instant(rs.getTimestamp("date_updated")),
            rs.getLong("submissions"));
    }

    private static Submission submission(ResultSet rs, int row) throws SQLException {
        return new Submission(rs.getLong("submission_id"), rs.getLong("form_id"), rs.getLong("tenant_id"), rs.getInt("form_version"),
            read(rs.getString("answers"), ANSWERS), rs.getObject("submitted_by", Long.class), rs.getString("submitted_by_name"),
            instant(rs.getTimestamp("submitted_at")), rs.getString("status"), rs.getObject("job_id", Long.class),
            rs.getObject("job_queue_id", Long.class), rs.getString("reason"), rs.getString("bucket"), rs.getString("storage_key"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("A form could not be written as JSON.", unwritable);
        }
    }

    private static <T> T read(String text, TypeReference<T> type) throws SQLException {
        try {
            return JSON.readValue(text, type);
        } catch (IOException unreadable) {
            throw new SQLException("A form's JSON could not be read.", unreadable);
        }
    }
}
