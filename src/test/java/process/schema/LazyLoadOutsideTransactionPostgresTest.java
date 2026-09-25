package process.schema;

import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import process.model.pojo.AppUser;
import process.model.pojo.JobAuditLogs;
import process.model.pojo.JobQueue;
import process.model.pojo.KafkaConnectionProfile;
import process.model.pojo.Scheduler;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskPayload;
import process.model.pojo.SourceTaskType;
import process.model.pojo.Tenant;
import process.model.pojo.TenantTaskTypeKafkaRoute;
import process.model.repository.SourceTaskRepository;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-67 (DEF-064): with hibernate.enable_lazy_load_no_trans off, as every profile now runs.
 *
 * The flag let a lazy association load after its session had closed, so a read that happened outside any
 * transaction silently worked -- and each of those reads is a future remote call once the entity is
 * another service's. With it off they fail, so each is replaced by a read that fetches what it uses.
 * Repositories here are built as the application builds them outside a transaction: a shared entity
 * manager that closes after each query, so what comes back is detached -- the exact condition of a
 * @PostConstruct, a scheduler thread, or a web request once open-in-view is off.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class LazyLoadOutsideTransactionPostgresTest {

    private static ScratchEtlJob db;
    private static LocalContainerEntityManagerFactoryBean factoryBean;
    private static SourceTaskRepository tasks;
    private static EntityManager detached;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("lazy_load");
        JdbcTemplate sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (2905, 'Active', 'MCN', 'MedAxis')");
        sql.update("INSERT INTO kafka_connection_profile (kafka_connection_profile_id, tenant_id, profile_name, bootstrap_servers, "
            + "security_protocol, is_default, status, date_created) VALUES (7290, 2905, 'p', 'k:9092', 'PLAINTEXT', false, 'Active', now())");
        sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, tenant_id, "
            + "kafka_connection_profile_id) VALUES (7300, 'worker', 'd', 'topic=scrapping-topic&partitions=[*]', 2905, 7290)");
        sql.update("INSERT INTO source_task (task_detail_id, task_name, task_status, source_task_type_id, tenant_id) "
            + "VALUES (7301, 't', 'Active', 7300, 2905)");
        sql.update("INSERT INTO source_task_payload (task_payload_id, tag_key, tag_value, payload_id) VALUES (1, 'bucket', 'b', 7301)");
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id, task_detail_id, "
            + "complete_job, fail_job, skip_job) VALUES (7304, now(), 'Auto', 'job', 'Active', 1, 2905, 7301, false, false, false)");
        sql.update("INSERT INTO scheduler (scheduler_id, job_id, start_date, start_time, frequency, interval_value, expired) "
            + "VALUES (7305, 7304, '2026-01-01', '09:00', 'Daily', '1', false)");
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, job_send) "
            + "VALUES (7306, now(), 7304, 'Completed', 'Active', true)");
        sql.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status) "
            + "VALUES (7307, now(), 7306, 'line', 'Active')");
        sql.update("INSERT INTO app_user (app_user_id, tenant_id, full_name, password, status, user_role, username) "
            + "VALUES (7308, 2905, 'A', 'x', 'Active', 'TENANT_ADMIN', 'a')");
        sql.update("INSERT INTO tenant_task_type_kafka_route (tenant_task_type_kafka_route_id, tenant_id, source_task_type_id, "
            + "kafka_connection_profile_id, date_created) VALUES (7309, 2905, 7300, 7290, now())");

        factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setDataSource(db.dataSource());
        factoryBean.setPackagesToScan("process.model.pojo");
        factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.enable_lazy_load_no_trans", "false");
        properties.put("hibernate.hbm2ddl.auto", "none");
        properties.put("hibernate.physical_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
        properties.put("hibernate.implicit_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy");
        factoryBean.setJpaPropertyMap(properties);
        factoryBean.afterPropertiesSet();
        EntityManagerFactory factory = factoryBean.getObject();
        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(factory);
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(shared);
        tasks = repositories.getRepository(SourceTaskRepository.class);
        detached = shared;
    }

    @AfterAll
    static void drop() throws Exception {
        if (factoryBean != null) {
            factoryBean.destroy();
        }
        if (db != null) {
            db.close();
        }
    }

    /**
     * The condition itself: the old read, detached, fails the moment it touches a lazy collection. (This was shown on
     * lookup_data's children until MIG-167 retired the table; a task's tag rows are the same kind of collection.)
     */
    @Test
    void aDetachedLazyCollectionNoLongerLoads() {
        SourceTask task = tasks.findById(7301L).get();

        assertThatThrownBy(() -> task.getSourceTaskPayload().size()).isInstanceOf(LazyInitializationException.class);
    }

    /** SourceTaskServiceImpl.fetchSourceTaskWithSourceTaskId: the task and its tag rows in one read. */
    @Test
    void aTaskIsReadWithItsPayloadRows() {
        Optional<SourceTask> task = tasks.findWithPayloadByTaskDetailId(7301L);

        assertThat(task).isPresent();
        assertThat(task.get().getSourceTaskPayload()).extracting(row -> row.getTagKey()).containsExactly("bucket");
    }

    /**
     * MIG-261: the Gson toString() on the eleven entities that have one, on what a read outside a transaction hands
     * back. It walked every field, associations included: an uninitialised proxy (Gson reached its Class and threw),
     * a detached lazy collection (a LazyInitializationException), and on Java 17 any java.time field (the module
     * system refuses the reflection). Eight of the eleven threw. A log line or an exception message that named the
     * entity would have failed with them. They now write the entity's own columns and nothing it points to.
     */
    @Test
    void everyEntitysToStringWritesItsOwnColumnsWhenDetached() {
        Object[][] rows = {{AppUser.class, 7308L, "appUserId", "tenant"}, {KafkaConnectionProfile.class, 7290L, "kafkaConnectionProfileId", null},
            {JobAuditLogs.class, 7307L, "jobAuditLogId", "jobQueue"}, {JobQueue.class, 7306L, "jobQueueId", "sourceJob"},
            {Scheduler.class, 7305L, "schedulerId", "sourceJob"}, {SourceTask.class, 7301L, "taskDetailId", "sourceTaskPayload"},
            {SourceJob.class, 7304L, "jobId", "sourceTask"}, {SourceTaskPayload.class, 1L, "taskPayloadId", null},
            {SourceTaskType.class, 7300L, "sourceTaskTypeId", "kafkaConnectionProfile"}, {Tenant.class, 2905L, "tenantId", null},
            {TenantTaskTypeKafkaRoute.class, 7309L, "tenantTaskTypeKafkaRouteId", "sourceTaskType"}};
        for (Object[] row : rows) {
            Object entity = detached.find((Class<?>) row[0], row[1]);
            String name = ((Class<?>) row[0]).getSimpleName();
            JsonObject written = JsonParser.parseString(entity.toString()).getAsJsonObject();
            assertThat(written.get((String) row[2]).getAsLong()).as(name).isEqualTo((Long) row[1]);
            if (row[3] != null) {
                assertThat(written.has((String) row[3])).as(name + " leaves out " + row[3]).isFalse();
            }
        }
        SourceJob job = detached.find(SourceJob.class, 7304L);
        assertThat(Arrays.asList("sourceTask", "sourceTaskType", "sourceTaskPayload"))
            .noneMatch(key -> JsonParser.parseString(job.toString()).getAsJsonObject().has(key));
        assertThat(JsonParser.parseString(detached.find(Scheduler.class, 7305L).toString()).getAsJsonObject()
            .get("startDate").getAsString()).isEqualTo("2026-01-01");
    }
}
