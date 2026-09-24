package process.tenancy;

import process.identity.TestIdentity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.engine.BulkAction;
import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskDto;
import process.model.dto.SourceTaskTypeDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobAuditLogs;
import process.model.pojo.JobQueue;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.pojo.Scheduler;
import process.model.pojo.SourceTaskPayload;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.PipelineRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantRepository;
import process.model.service.impl.SourceTaskServiceImpl;
import process.model.service.impl.TransactionServiceImpl;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.BusinessTime;
import process.util.OpenSearchAuditLogClient;
import process.util.TaskPayloadLocationUtil;

import javax.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-29 AC4: the application writes tenant_id itself when it inserts into each of the five tables V102 gave one --
 * through the paths that really write them, with the database's default-from-parent triggers switched OFF, so a
 * path that left it out fails on NOT NULL here instead of being quietly filled in.
 *
 * And AC3 / MIG-164: the new column is used, not merely present -- the standard tenant filter, on for tenant B,
 * returns none of tenant A's rows from any of the five entities, and on for A returns them.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchPostgres).
 */
class TenantIdWritersPostgresTest {

    private static final long A = 3501L;
    private static final long B = 3502L;
    private static final long TYPE = 35011L;
    private static final long TASK = 35012L;
    private static final long JOB = 35014L;

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private static TransactionServiceImpl transactions;
    private static OpenSearchAuditLogClient openSearch;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("tenant_id_writers");
        jpa = new ScratchJpa(db);
        JdbcTemplate sql = db.jdbc();
        for (String table : TenantIdOnChildTablesPostgresTest.DERIVED.keySet()) {
            sql.execute("ALTER TABLE " + table + " DISABLE TRIGGER " + table + "_tenant_id_from_parent");
        }
        for (long tenant : new long[] {A, B}) {
            sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", tenant, "W" + tenant, "W" + tenant);
        }
        sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, tenant_id, task_type_status) "
            + "VALUES (?, 'w', 'd', 'topic=writers&partitions=[*]', ?, 'Active')", TYPE, A);
        sql.update("INSERT INTO source_task (task_detail_id, task_name, task_status, source_task_type_id, tenant_id) VALUES (?, 'task', 'Active', ?, ?)",
            TASK, TYPE, A);
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id, task_detail_id, "
            + "complete_job, fail_job, skip_job) VALUES (?, now(), 'Auto', 'job', 'Active', 1, ?, ?, false, false, false)", JOB, A, TASK);
        openSearch = mock(OpenSearchAuditLogClient.class);
        when(openSearch.index(anyString(), anyLong(), anyString(), any(Timestamp.class))).thenReturn(false);
        when(openSearch.indexAllReturningFailures(anyList())).thenAnswer(call -> call.getArgument(0));
        transactions = new TransactionServiceImpl(jpa.repository(SourceJobRepository.class), jpa.repository(SchedulerRepository.class),
            jpa.repository(JobQueueRepository.class), null, jpa.repository(JobAuditLogRepository.class),
            jpa.repository(SourceTaskRepository.class), openSearch);
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

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static <T> T inTransaction(Supplier<T> work) {
        return jpa.transactions().execute(status -> work.get());
    }

    private static long tenantOf(String sql, Object... args) {
        return db.jdbc().queryForObject(sql, Long.class, args);
    }

    @Test
    void aRunTakesItsJobsTenant() {
        JobQueue run = inTransaction(() -> new BulkAction(transactions, null)
            .createJobQueue(JOB, BusinessTime.now(), JobStatus.Skip, "Job %s skipped.", true));
        assertThat(run.getTenantId()).isEqualTo(A);
        assertThat(tenantOf("SELECT tenant_id FROM job_queue WHERE job_queue_id = ?", run.getJobQueueId())).isEqualTo(A);
    }

    @Test
    void anAuditLineTakesItsRunsTenantOnEveryWayIn() {
        JobQueue run = inTransaction(() -> new BulkAction(transactions, null)
            .createJobQueue(JOB, BusinessTime.now(), JobStatus.Skip, "Job %s skipped.", true));
        // OpenSearch refuses, so each line falls back to the table: the single line, the batch, and the sync's native insert.
        inTransaction(() -> {
            transactions.saveJobAuditLogs(run.getJobQueueId(), "one line");
            transactions.saveJobAuditLogs(run.getJobQueueId(), Arrays.asList("two", "three"));
            return null;
        });
        int synced = inTransaction(() -> jpa.repository(JobAuditLogRepository.class)
            .upsertFromOpenSearch("writers-ext-1", run.getJobQueueId(), "from opensearch", Timestamp.from(Instant.now())));
        assertThat(synced).isEqualTo(1);
        List<Long> tenants = db.jdbc().queryForList("SELECT tenant_id FROM job_audit_logs WHERE job_queue_id = ?", Long.class, run.getJobQueueId());
        assertThat(tenants).hasSize(4).containsOnly(A);
    }

    @Test
    void aTasksTagsTakeItsTenantWhenTheTaskIsCreatedAndWhenItIsEdited() throws Exception {
        TenantContext.set(A, "TENANT_ADMIN", null, "admin@writers.test");
        SourceTaskServiceImpl tasks = new SourceTaskServiceImpl(null, null, null, jpa.repository(SourceTaskRepository.class),
            jpa.repository(SourceTaskTypeRepository.class), new TenantFilterHelper(), new TaskPayloadLocationUtil(),
            TestIdentity.over(null, jpa.repository(TenantRepository.class)), null, null);
        ReflectionTestUtils.setField(tasks, "entityManager", jpa.sharedEntityManager());
        SourceTaskDto created = new SourceTaskDto();
        created.setTaskName("tagged");
        created.setTaskPayload("<data><bucket>alpha</bucket><input_folder>in</input_folder></data>");
        SourceTaskTypeDto type = new SourceTaskTypeDto();
        type.setSourceTaskTypeId(TYPE);
        created.setSourceTaskType(type);
        ResponseDto response = inTransaction(() -> {
            try {
                return tasks.addSourceTask(created);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertThat(response.getStatus()).as(response.getMessage()).isEqualTo("SUCCESS");
        Long taskId = db.jdbc().queryForObject("SELECT task_detail_id FROM source_task WHERE task_name = 'tagged'", Long.class);
        assertThat(db.jdbc().queryForList("SELECT tenant_id FROM source_task_payload WHERE payload_id = ?", Long.class, taskId))
            .isNotEmpty().containsOnly(A);

        created.setTaskDetailId(taskId);
        created.setTaskPayload("<data><bucket>beta</bucket><output_folder>out</output_folder></data>");
        ResponseDto edited = inTransaction(() -> {
            try {
                return tasks.updateSourceTask(created);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertThat(edited.getStatus()).as(edited.getMessage()).isEqualTo("SUCCESS");
        assertThat(db.jdbc().queryForList("SELECT tag_value FROM source_task_payload WHERE payload_id = ?", String.class, taskId)).contains("beta");
        assertThat(db.jdbc().queryForList("SELECT tenant_id FROM source_task_payload WHERE payload_id = ?", Long.class, taskId))
            .isNotEmpty().containsOnly(A);
    }

    @Test
    void aPipelinesFieldsTakeItsTenant() {
        Pipeline pipeline = new Pipeline();
        pipeline.setPipelineId("F-WRITERS");
        pipeline.setPipelineName("writers");
        pipeline.setTenantId(A);
        for (String key : new String[] {"bucket", "prefix"}) {
            PipelineField field = new PipelineField();
            field.setTagKey(key);
            field.setLabel(key);
            field.setPipeline(pipeline);
            pipeline.getFields().add(field);
        }
        Pipeline saved = inTransaction(() -> jpa.repository(PipelineRepository.class).save(pipeline));
        assertThat(db.jdbc().queryForList("SELECT tenant_id FROM pipeline_field WHERE pipeline_key = ?", Long.class, saved.getPipelineKey()))
            .hasSize(2).containsOnly(A);
    }

    @Test
    void theTenantFilterScopesEveryOneOfTheFive() {
        JdbcTemplate sql = db.jdbc();
        // One of each, for tenant A, written directly (the writers above are what put the tenant there).
        sql.update("INSERT INTO scheduler (scheduler_id, job_id, start_date, start_time, frequency, interval_value, expired, tenant_id) "
            + "VALUES (35015, ?, '2026-01-01', '09:00', 'Daily', '1', false, ?) ON CONFLICT DO NOTHING", JOB, A);
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, job_send, tenant_id) "
            + "VALUES (35016, now(), ?, 'Completed', 'Active', true, ?)", JOB, A);
        sql.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status, tenant_id) "
            + "VALUES (35017, now(), 35016, 'x', 'Active', ?)", A);
        sql.update("INSERT INTO source_task_payload (task_payload_id, tag_key, tag_value, payload_id, tenant_id) VALUES (35013, 'k', 'v', ?, ?)", TASK, A);
        sql.update("INSERT INTO pipeline (pipeline_key, pipeline_id, pipeline_name, tenant_id, status) VALUES (35019, 'F-SCOPE', 'scope', ?, 'Active')", A);
        sql.update("INSERT INTO pipeline_field (pipeline_field_id, pipeline_key, tag_key, label, tenant_id) VALUES (35020, 35019, 'k', 'K', ?)", A);

        for (long tenant : new long[] {B, A}) {
            TenantContext.set(tenant, "TENANT_USER", null, "user@writers.test");
            List<Long> seen = inTransaction(() -> {
                EntityManager em = jpa.sharedEntityManager();
                new TenantFilterHelper().enableIfNeeded(em);
                return Arrays.asList(
                    (long) em.createQuery("select q from JobQueue q", JobQueue.class).getResultList().size(),
                    (long) em.createQuery("select s from Scheduler s", Scheduler.class).getResultList().size(),
                    (long) em.createQuery("select l from JobAuditLogs l", JobAuditLogs.class).getResultList().size(),
                    (long) em.createQuery("select p from SourceTaskPayload p", SourceTaskPayload.class).getResultList().size(),
                    (long) em.createQuery("select f from PipelineField f", PipelineField.class).getResultList().size());
            });
            if (tenant == B) {
                assertThat(seen).as("tenant B sees none of A's runs, schedules, audit lines, tags or fields").containsOnly(0L);
            } else {
                assertThat(seen).as("tenant A sees its own").allMatch(n -> n > 0);
            }
        }
        TenantContext.set(null, "PLATFORM_ADMIN", null, "root@writers.test");
        long everything = inTransaction(() -> {
            EntityManager em = jpa.sharedEntityManager();
            new TenantFilterHelper().enableIfNeeded(em);
            return (long) em.createQuery("select q from JobQueue q", JobQueue.class).getResultList().size();
        });
        assertThat(everything).isEqualTo(db.jdbc().queryForObject("SELECT count(*) FROM job_queue", Long.class));
    }
}
