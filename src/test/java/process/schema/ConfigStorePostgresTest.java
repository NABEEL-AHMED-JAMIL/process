package process.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import process.ScratchJpa;
import process.model.pojo.PipelineConfig;
import process.model.pojo.TaskReference;
import process.model.repository.PipelineConfigRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.TaskReferenceRepository;
import process.util.EncryptionUtil;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-167 on a built etl_job, through the application's own JPA with hibernate.hbm2ddl.auto=validate (what stage and
 * prod run): the new entities match V141-V143, the sequences hand out ids from 1000, a run's read of pipeline_config
 * is confined to one workspace, a home page's URL is only ever a HOME_PAGE's, and the "is it used" count of a
 * ${secret:KEY} is exact whatever underscores the key has.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class ConfigStorePostgresTest {

    private static ScratchEtlJob db;
    private static ScratchJpa jpa;
    private static PipelineConfigRepository entries;
    private static TaskReferenceRepository references;
    private static SourceTaskRepository tasks;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("config_store");
        jpa = new ScratchJpa(db.dataSource(), Collections.singletonMap("hibernate.hbm2ddl.auto", "validate"));
        entries = jpa.repository(PipelineConfigRepository.class);
        references = jpa.repository(TaskReferenceRepository.class);
        tasks = jpa.repository(SourceTaskRepository.class);
        JdbcTemplate sql = db.sql();
        LookupDecompositionPostgresTest.workspaces(sql);
        sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, tenant_id) "
            + "VALUES (7300, 'worker', 'd', 'topic=scrapping-topic&partitions=[*]', 2905)");
        String task = "INSERT INTO source_task (task_detail_id, task_name, task_status, source_task_type_id, tenant_id, task_payload) "
            + "VALUES (?, 't', ?, 7300, ?, ?)";
        sql.update(task, 7301L, "Active", 2905L, "<p><db_password>${secret:DB_PASSWORD}</db_password></p>");
        sql.update(task, 7302L, "Inactive", 2905L, "<p><x>${secret:DB_PASSWORD}</x><y>${config:DBXPASSWORD}</y></p>");
        sql.update(task, 7303L, "Delete", 2905L, "<p><db_password>${secret:DB_PASSWORD}</db_password></p>");
        sql.update(task, 7304L, "Active", 2901L, "<p><db_password>${secret:DB_PASSWORD}</db_password></p>");
    }

    @AfterAll
    static void drop() throws Exception {
        if (jpa != null) {
            jpa.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @Test
    void aRunReadsOnlyItsOwnWorkspacesEntries() {
        EncryptionUtil sealing = new EncryptionUtil();
        ReflectionTestUtils.setField(sealing, "currentKeyId", "p2026a");
        ReflectionTestUtils.setField(sealing, "currentKey", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        PipelineConfig mine = jpa.transactions().execute(status -> entries.save(secret(2905L, "DB_PASSWORD", sealing.encrypt("mine"))));
        jpa.transactions().execute(status -> entries.save(secret(2901L, "DB_PASSWORD", sealing.encrypt("theirs"))));
        jpa.transactions().execute(status -> entries.save(value(2905L, "INPUT_BUCKET", "etl-inputs")));

        assertThat(mine.getId()).isGreaterThanOrEqualTo(1000L);
        assertThat(mine.getValueSetAt()).isNotNull();
        List<PipelineConfig> read = entries.findForRun(2905L, Arrays.asList("DB_PASSWORD", "INPUT_BUCKET"));
        assertThat(read).extracting(PipelineConfig::getTenantId).containsOnly(2905L);
        assertThat(read.stream().filter(PipelineConfig::isSecret).map(e -> sealing.decrypt(e.getValueSealed())).collect(Collectors.toList()))
            .containsExactly("mine");
        assertThat(entries.findByTenantIdAndConfigKey(2901L, "INPUT_BUCKET")).isEmpty();
    }

    @Test
    void theDatabaseRefusesAPlaintextSecretFromTheApplicationToo() {
        assertThatThrownBy(() -> jpa.transactions().execute(status -> entries.save(secret(2905L, "API_TOKEN", "hunter2"))))
            .hasStackTraceContaining("ck_pipeline_config_one_value");
    }

    @Test
    void aReferenceIsCountedExactlyAndOnlyOnLiveTasksOfItsWorkspace() {
        assertThat(tasks.countLiveTasksWithPayloadContaining(2905L, "${secret:DB_PASSWORD}")).isEqualTo(2L);
        assertThat(tasks.countLiveTasksWithPayloadContaining(2905L, "${config:DB_PASSWORD}")).as("an underscore is not a wildcard").isZero();
        assertThat(tasks.countLiveTasksWithPayloadContaining(2901L, "${secret:DB_PASSWORD}")).isEqualTo(1L);
    }

    @Test
    void aHomePagesUrlIsOnlyEverAHomePages() {
        TaskReference home = jpa.transactions().execute(status -> references.save(reference(2905L, "HOME_PAGE", "Ops", "https://ops.demo")));
        TaskReference group = jpa.transactions().execute(status -> references.save(reference(2905L, "TASK_GROUP", "Nightly", "https://not-a-home.demo")));

        assertThat(home.getId()).isGreaterThanOrEqualTo(1000L);
        assertThat(references.findHomePageUrl(home.getId())).contains("https://ops.demo");
        assertThat(references.findHomePageUrl(group.getId())).isEmpty();
        assertThat(references.findByTenantIdAndKindOrderByNameAsc(2905L, "TASK_GROUP")).extracting(TaskReference::getName).containsExactly("Nightly");
        assertThat(references.findByTenantIdAndKindOrderByNameAsc(2901L, "TASK_GROUP")).isEmpty();
    }

    private static PipelineConfig secret(long tenant, String key, String sealed) {
        PipelineConfig entry = new PipelineConfig();
        entry.setTenantId(tenant);
        entry.setConfigKey(key);
        entry.setKind("SECRET");
        entry.setValueSealed(sealed);
        return entry;
    }

    private static PipelineConfig value(long tenant, String key, String value) {
        PipelineConfig entry = new PipelineConfig();
        entry.setTenantId(tenant);
        entry.setConfigKey(key);
        entry.setKind("VALUE");
        entry.setValue(value);
        return entry;
    }

    private static TaskReference reference(long tenant, String kind, String name, String value) {
        TaskReference row = new TaskReference();
        row.setTenantId(tenant);
        row.setKind(kind);
        row.setName(name);
        row.setValue(value);
        return row;
    }
}
