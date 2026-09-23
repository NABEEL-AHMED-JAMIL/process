package process.media.converter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import process.model.enums.Status;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * media_db.document_converter_task, and every way it is read or written (MIG-41).
 *
 * Reads are scoped as the Hibernate tenant filter scoped them: a tenant sees its own rows, and a
 * platform admin -- visibleTenant null -- sees every tenant's. A platform admin's own conversion
 * has no tenant; it is written as PLATFORM_SCOPE (tenant_id is NOT NULL here) and read back as
 * null, so the service above sees exactly what it always saw -- including the meter, which bills
 * only a real tenant.
 *
 * @author Nabeel Ahmed
 */
public class ConverterTaskStore {

    public static final long PLATFORM_SCOPE = 0L;

    private static final String COLUMNS = "document_converter_task_id, tenant_id, task_name, input_file_name, input_format, "
        + "input_content_type, input_file_size, output_format, output_file_name, output_content_type, output_file_size, "
        + "bucket_name, target_folder, input_storage_key, output_storage_key, status, date_created";

    private static final RowMapper<DocumentConverterTask> ROW = (rs, i) -> {
        DocumentConverterTask task = new DocumentConverterTask();
        task.setDocumentConverterTaskId(rs.getLong("document_converter_task_id"));
        long tenant = rs.getLong("tenant_id");
        task.setTenantId(tenant == PLATFORM_SCOPE ? null : tenant);
        task.setTaskName(rs.getString("task_name"));
        task.setInputFileName(rs.getString("input_file_name"));
        task.setInputFormat(rs.getString("input_format"));
        task.setInputContentType(rs.getString("input_content_type"));
        task.setInputFileSize((Long) rs.getObject("input_file_size"));
        task.setOutputFormat(rs.getString("output_format"));
        task.setOutputFileName(rs.getString("output_file_name"));
        task.setOutputContentType(rs.getString("output_content_type"));
        task.setOutputFileSize((Long) rs.getObject("output_file_size"));
        task.setBucketName(rs.getString("bucket_name"));
        task.setTargetFolder(rs.getString("target_folder"));
        task.setInputStorageKey(rs.getString("input_storage_key"));
        task.setOutputStorageKey(rs.getString("output_storage_key"));
        task.setStatus(Status.valueOf(rs.getString("status")));
        task.setDateCreated(rs.getTimestamp("date_created"));
        return task;
    };

    private final JdbcTemplate jdbc;

    public ConverterTaskStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Writes a new task and gives it its id; stamps date_created and defaults status, as @PrePersist did. */
    public DocumentConverterTask insert(DocumentConverterTask task) {
        Long id = this.jdbc.queryForObject("SELECT nextval('document_converter_task_id_seq')", Long.class);
        task.setDocumentConverterTaskId(id);
        task.setDateCreated(new Timestamp(System.currentTimeMillis()));
        if (task.getStatus() == null) {
            task.setStatus(Status.Active);
        }
        this.jdbc.update("INSERT INTO document_converter_task (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, scopeOf(task.getTenantId()), task.getTaskName(), task.getInputFileName(), task.getInputFormat(),
            task.getInputContentType(), task.getInputFileSize(), task.getOutputFormat(), task.getOutputFileName(),
            task.getOutputContentType(), task.getOutputFileSize(), task.getBucketName(), task.getTargetFolder(),
            task.getInputStorageKey(), task.getOutputStorageKey(), task.getStatus().name(), task.getDateCreated());
        return task;
    }

    /** What a task may change after it is written: its storage keys and its status. */
    public void update(DocumentConverterTask task) {
        this.jdbc.update("UPDATE document_converter_task SET input_storage_key = ?, output_storage_key = ?, status = ? "
            + "WHERE document_converter_task_id = ?", task.getInputStorageKey(), task.getOutputStorageKey(),
            task.getStatus().name(), task.getDocumentConverterTaskId());
    }

    /** Removes a placeholder whose uploads failed -- convert()'s compensating delete. */
    public void delete(long documentConverterTaskId) {
        this.jdbc.update("DELETE FROM document_converter_task WHERE document_converter_task_id = ?", documentConverterTaskId);
    }

    /** @param visibleTenant the caller's tenant, or null for a platform admin, who sees every tenant's. */
    public Optional<DocumentConverterTask> find(Long visibleTenant, long documentConverterTaskId) {
        List<DocumentConverterTask> rows = visibleTenant == null
            ? this.jdbc.query("SELECT " + COLUMNS + " FROM document_converter_task WHERE document_converter_task_id = ?",
                ROW, documentConverterTaskId)
            : this.jdbc.query("SELECT " + COLUMNS + " FROM document_converter_task WHERE document_converter_task_id = ? "
                + "AND tenant_id = ?", ROW, documentConverterTaskId, visibleTenant);
        return rows.stream().findFirst();
    }

    /** Every task not deleted, newest first. @param visibleTenant as find. */
    public List<DocumentConverterTask> listLive(Long visibleTenant) {
        return visibleTenant == null
            ? this.jdbc.query("SELECT " + COLUMNS + " FROM document_converter_task WHERE status <> ? "
                + "ORDER BY document_converter_task_id DESC", ROW, Status.Delete.name())
            : this.jdbc.query("SELECT " + COLUMNS + " FROM document_converter_task WHERE status <> ? AND tenant_id = ? "
                + "ORDER BY document_converter_task_id DESC", ROW, Status.Delete.name(), visibleTenant);
    }

    private static long scopeOf(Long tenantId) {
        return tenantId == null ? PLATFORM_SCOPE : tenantId;
    }
}
