package process.media.converter;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.model.enums.Status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * media_db.document_converter_task as MIG-41 defines it, against a real Postgres: the schema its
 * own changelog builds, the sequence that starts at 1000, and a store whose reads are scoped to a
 * tenant except for a platform admin, as the Hibernate tenant filter scoped them.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD, as the other Postgres tests.
 */
class ConverterTaskStorePostgresTest {

    private static final long TENANT_A = 2901L;
    private static final long TENANT_B = 2905L;

    private static String server;
    private static String scratch;
    private static HikariDataSource dataSource;
    private static ConverterTaskStore store;

    @BeforeAll
    static void createDatabase() throws Exception {
        server = System.getenv("NOTIFICATIONS_TEST_DB_URL");
        assumeTrue(server != null, "NOTIFICATIONS_TEST_DB_URL is not set");
        scratch = "media_store_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection admin = admin(); Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + scratch);
        }
        dataSource = MediaDatabase.pool(server.replaceAll("/[^/?]+(\\?.*)?$", "/" + scratch + "$1"),
            System.getenv("NOTIFICATIONS_TEST_DB_USER"), System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
        MediaDatabase.migrate(dataSource);
        store = new ConverterTaskStore(dataSource);
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        if (dataSource == null) return;
        dataSource.close();
        try (Connection admin = admin(); Statement sql = admin.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS " + scratch);
        }
    }

    private static Connection admin() throws Exception {
        return DriverManager.getConnection(server, System.getenv("NOTIFICATIONS_TEST_DB_USER"), System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
    }

    @BeforeEach
    void empty() {
        new JdbcTemplate(dataSource).execute("DELETE FROM document_converter_task");
    }

    private static DocumentConverterTask task(Long tenantId, String name) {
        DocumentConverterTask task = new DocumentConverterTask();
        task.setTenantId(tenantId);
        task.setTaskName(name);
        task.setInputFileName("report.docx");
        task.setInputFormat("docx");
        task.setOutputFormat("pdf");
        task.setOutputFileName("report.pdf");
        task.setBucketName("medaxis-docs");
        task.setTargetFolder("document-converter");
        task.setInputStorageKey("pending");
        task.setOutputStorageKey("pending");
        return task;
    }

    @Test
    void theSequenceStartsAtAThousandAndInsertStampsWhatPrePersistDid() {
        DocumentConverterTask saved = store.insert(task(TENANT_A, "first"));

        assertThat(saved.getDocumentConverterTaskId()).isGreaterThanOrEqualTo(1000L);
        assertThat(saved.getStatus()).isEqualTo(Status.Active);
        assertThat(saved.getDateCreated()).isNotNull();
    }

    @Test
    void theNotNullColumnsAndTheUniquePrimaryKeyAreDeclared() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        List<String> notNull = sql.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = "
            + "'document_converter_task' AND is_nullable = 'NO'", String.class);
        assertThat(notNull).contains("document_converter_task_id", "tenant_id", "bucket_name", "input_storage_key",
            "output_storage_key", "input_file_name", "input_format", "output_format", "status", "task_name", "date_created");
        assertThat(sql.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename = 'document_converter_task' "
            + "AND indexname = 'idx_document_converter_task_tenant_id'", Integer.class)).isEqualTo(1);
        // The PK is also declared UNIQUE, as the migration rules ask -- in the DDL. Postgres folds a
        // unique constraint identical to the primary key (etl_job's table showed only the pkey too),
        // so the declaration is checked where it lives, and the key where Postgres keeps it.
        assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.table_constraints WHERE table_name = "
            + "'document_converter_task' AND constraint_type = 'PRIMARY KEY'", Integer.class)).isEqualTo(1);
        try {
            assertThat(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/resources/db/media/sql/V1.0-document_converter_task.sql")), "UTF-8"))
                .contains("UNIQUE (document_converter_task_id)");
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
        assertThatThrownBy(() -> sql.update("INSERT INTO document_converter_task (document_converter_task_id, tenant_id, task_name, "
            + "input_file_name, input_format, output_format, bucket_name, input_storage_key, output_storage_key, status, date_created) "
            + "VALUES (1, NULL, 'x', 'x', 'x', 'x', 'x', 'x', 'x', 'Active', now())")).hasMessageContaining("tenant_id");
    }

    /** A platform admin has no tenant: stored as the platform scope, read back as no tenant. */
    @Test
    void aPlatformAdminsTaskHasNoTenantEitherSideOfTheStore() {
        DocumentConverterTask saved = store.insert(task(null, "admin's"));

        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT tenant_id FROM document_converter_task "
            + "WHERE document_converter_task_id = ?", Long.class, saved.getDocumentConverterTaskId())).isEqualTo(ConverterTaskStore.PLATFORM_SCOPE);
        assertThat(store.find(null, saved.getDocumentConverterTaskId()).get().getTenantId()).isNull();
    }

    /** As the Hibernate tenant filter: a tenant sees its own rows; a platform admin (null) sees all. */
    @Test
    void readsAreScopedToTheTenantExceptForAPlatformAdmin() {
        DocumentConverterTask mine = store.insert(task(TENANT_A, "mine"));
        DocumentConverterTask theirs = store.insert(task(TENANT_B, "theirs"));

        assertThat(store.listLive(TENANT_A)).extracting(DocumentConverterTask::getTaskName).containsExactly("mine");
        assertThat(store.find(TENANT_A, theirs.getDocumentConverterTaskId())).isEmpty();
        assertThat(store.listLive(null)).extracting(DocumentConverterTask::getTaskName).containsExactly("theirs", "mine");
        assertThat(store.find(null, mine.getDocumentConverterTaskId())).isPresent();
    }

    @Test
    void aDeletedTaskIsNoLongerListedAndAPlaceholderCanBeRemoved() {
        DocumentConverterTask kept = store.insert(task(TENANT_A, "kept"));
        DocumentConverterTask gone = store.insert(task(TENANT_A, "gone"));
        DocumentConverterTask placeholder = store.insert(task(TENANT_A, "placeholder"));

        gone.setStatus(Status.Delete);
        store.update(gone);
        kept.setInputStorageKey("document-converter/" + kept.getDocumentConverterTaskId() + "/input/report.docx");
        store.update(kept);
        store.delete(placeholder.getDocumentConverterTaskId());

        assertThat(store.listLive(TENANT_A)).extracting(DocumentConverterTask::getTaskName).containsExactly("kept");
        assertThat(store.find(TENANT_A, kept.getDocumentConverterTaskId()).get().getInputStorageKey()).endsWith("/input/report.docx");
        assertThat(store.find(TENANT_A, placeholder.getDocumentConverterTaskId())).isEmpty();
    }
}
