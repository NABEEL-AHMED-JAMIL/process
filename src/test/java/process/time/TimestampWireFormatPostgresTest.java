package process.time;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import process.ScratchJpa;
import process.engine.BulkAction;
import process.model.dto.MessageQSearchDto;
import process.model.dto.PipelineRowDto;
import process.model.dto.ResponseDto;
import process.model.projection.JobAuditLogProjection;
import process.model.projection.PipelineRowProjection;
import process.model.projection.SourceJobProjection;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.LookupDataRepository;
import process.model.repository.PipelineRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.impl.DashboardServiceImpl;
import process.model.service.impl.MessageQServiceImpl;
import process.model.service.impl.QueryService;
import process.model.service.impl.SourceJobServiceImpl;
import process.model.service.impl.SourceTaskServiceImpl;
import process.schema.ScratchEtlJob;
import process.security.TenantContext;

import javax.persistence.EntityManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-28 / MIG-96 / MIG-163: what the two consoles are sent for a stored time, pinned before the columns
 * became timestamptz and the JVM stopped being told it lives in Chicago -- and held after.
 *
 * The rows are written as the database held them before V100 -- naive America/Chicago wall-clock strings -- into a
 * scratch etl_job built up to V100, which then converts them as it will convert the long-lived one.
 * Every expectation below was recorded from the code as it stood at e39381d, run the way production ran
 * it (a JVM defaulted to America/Chicago). The same expectations must hold on any JVM zone now: the suite
 * runs on UTC, which is what the containers are.
 *
 * Two shapes reach the wire and both are kept exactly. A java.sql.Timestamp field goes out as an instant
 * in UTC ("2026-01-16T05:30:00.000+00:00"): Jackson's own zone, never the JVM's. A LocalDateTime field, and
 * every time the SQL renders as text, goes out as Chicago wall-clock with no zone ("2026-01-15T23:30:00") --
 * what both consoles have always shown.
 *
 * The run at 23:30 on 15 January is the one that tells the zones apart: it is already the 16th in UTC, so
 * a day bucket, an hour bucket or a rendered string computed in the wrong zone lands on the wrong day.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchPostgres).
 */
class TimestampWireFormatPostgresTest {

    private static final long TENANT = 3101L;
    private static final long OWNER = 31901L;
    private static final long JOB = 31001L;
    private static final long TASK = 31201L;
    private static final long RUN = 310001L;

    private static ScratchEtlJob db;
    private static ScratchJpa jpa;
    private static ObjectMapper json;
    private static QueryService queries;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.buildUpTo("wire_format", TimestamptzMigrationPostgresTest.V100);
        AtomicReference<ObjectMapper> mapper = new AtomicReference<>();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withPropertyValues("spring.jackson.serialization.fail-on-empty-beans=false")
            .run(context -> mapper.set(context.getBean(ObjectMapper.class)));
        json = mapper.get();
        queries = new QueryService();

        JdbcTemplate sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', 'WFT', 'Wire Format')", TENANT);
        sql.update("INSERT INTO app_user (app_user_id, full_name, password, status, user_role, username, tenant_id, date_created) "
            + "VALUES (?, 'Wire Owner', 'x', 'Active', 'TENANT_ADMIN', 'owner@wire.test', ?, '2026-01-15 08:00:00')", OWNER, TENANT);
        sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, tenant_id) "
            + "VALUES (31101, 'worker', 'd', 'topic=wire-topic&partitions=[*]', ?)", TENANT);
        sql.update("INSERT INTO source_task (task_detail_id, task_name, task_status, source_task_type_id, tenant_id) "
            + "VALUES (?, 'wire task', 'Active', 31101, ?)", TASK, TENANT);
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id, task_detail_id, "
            + "assigned_user_id, last_job_run, job_running_status, complete_job, fail_job, skip_job) VALUES (?, '2026-01-15 08:00:00', 'Auto', "
            + "'wire job', 'Active', 1, ?, ?, ?, '2026-01-15 23:30:00', 'Completed', false, false, false)", JOB, TENANT, TASK, OWNER);
        sql.update("INSERT INTO scheduler (scheduler_id, job_id, start_date, start_time, frequency, interval_value, next_run_at, "
            + "date_created, expired) VALUES (31301, ?, '2026-01-15', '09:00:00', 'Daily', '1', '2026-01-16 09:00:00', "
            + "'2026-01-15 08:00:00', false)", JOB);
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, start_time, end_time, job_send) "
            + "VALUES (?, '2026-01-15 23:30:00', ?, 'Completed', 'Active', '2026-01-15 23:30:00', '2026-01-15 23:45:10.5', true)", RUN, JOB);
        sql.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status) "
            + "VALUES (31401, '2026-01-15 23:30:05.123', ?, 'started', 'Active')", RUN);
        sql.update("INSERT INTO pipeline (pipeline_key, pipeline_id, pipeline_name, tenant_id, status, date_created) "
            + "VALUES (31501, 'F-WIRE', 'wire pipeline', ?, 'Active', '2026-01-15 23:30:00')", TENANT);
        db.finish();
        jpa = new ScratchJpa(db.dataSource());
        EntityManager shared = jpa.sharedEntityManager();
        ReflectionTestUtils.setField(queries, "_em", shared);
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

    private static void asOwner() {
        TenantContext.set(TENANT, "TENANT_ADMIN", OWNER, "owner@wire.test");
    }

    private static String wire(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    /** The message-queue screen: SourceJobQueueDto, a Timestamp and three LocalDateTimes. */
    @Test
    void theQueueLogSendsTheRunAsItAlwaysHas() throws Exception {
        asOwner();
        MessageQServiceImpl messageQ = new MessageQServiceImpl(null, queries, null, null, null);
        MessageQSearchDto day = new MessageQSearchDto();
        day.setFromDate("2026-01-15");
        day.setToDate("2026-01-15");
        ResponseDto response = messageQ.fetchLogs(day);
        String sent = wire(response.getData());
        System.out.println("WIRE fetchLogs " + sent);
        assertThat(sent).isEqualTo("{\"jobStatusStatistic\":[{\"name\":\"COMPLETED\",\"value\":1}],\"sourceJobQueues\":[{\"jobQueueId\":310001,\"startTime\":\"2026-01-15T23:30:00\",\"endTime\":\"2026-01-15T23:45:10\",\"jobStatus\":\"Completed\",\"jobId\":31001,\"jobSend\":true,\"dateCreated\":\"2026-01-16T05:30:00.000+00:00\"}]}");
    }

    /** The Dashboard's per-day and per-hour tiles: the run belongs to 15 January, 23:00, in Chicago. */
    @Test
    void theDashboardBucketsTheRunIntoItsChicagoDayAndHour() throws Exception {
        asOwner();
        DashboardServiceImpl dashboard = new DashboardServiceImpl(queries, null, null, null, null, null);
        String days = wire(dashboard.weeklyRunningJobStatistics("2026-01-15", "2026-01-15").getData());
        String hours = wire(dashboard.weeklyHrsRunningJobStatistics("2026-01-15", "2026-01-15").getData());
        String dayAfter = wire(dashboard.weeklyRunningJobStatistics("2026-01-16", "2026-01-16").getData());
        System.out.println("WIRE weeklyRunning " + days);
        System.out.println("WIRE weeklyHrs " + hours);
        System.out.println("WIRE weeklyRunning 16th " + dayAfter);
        assertThat(days).isEqualTo("[{\"name\":\"Thu\",\"value\":1,\"tenantId\":3101,\"allWorkspaces\":false}]");
        assertThat(hours).isEqualTo("[{\"dayCode\":\"Thursday\",\"hr\":23,\"date\":\"2026-01-15\",\"count\":1,\"tenantId\":3101,\"allWorkspaces\":false}]");
        assertThat(dayAfter).isEqualTo("[]");
    }

    /** The Dashboard drill-down: the hour's runs, and the job and scheduler behind them. */
    @Test
    void theDashboardDrillDownSendsRunJobAndScheduler() throws Exception {
        asOwner();
        SourceJobRepository jobs = jpa.repository(SourceJobRepository.class);
        SchedulerRepository schedulers = jpa.repository(SchedulerRepository.class);
        DashboardServiceImpl dashboard = new DashboardServiceImpl(queries, jobs, jpa.repository(JobQueueRepository.class),
            schedulers, jpa.repository(LookupDataRepository.class), null);
        String sent = wire(jpa.transactions().execute(status -> {
            try {
                return dashboard.weeklyHrRunningStatisticsDimensionDetail("2026-01-15", 23L, "Completed", JOB).getData();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }));
        System.out.println("WIRE drillDown " + sent);
        assertThat(sent).contains("\"jobQueueId\":310001")
            .contains("\"dateCreated\":\"2026-01-16T05:30:00.000+00:00\"")
            .contains("\"startTime\":\"2026-01-15T23:30:00\"")
            .contains("\"endTime\":\"2026-01-15T23:45:10\"")
            // source_job: created 08:00 Chicago (an instant), last ran 23:30 Chicago (wall-clock)
            .contains("\"dateCreated\":\"2026-01-15T14:00:00.000+00:00\"")
            .contains("\"lastJobRun\":\"2026-01-15T23:30:00\"")
            // scheduler: next run 09:00 Chicago wall-clock
            .contains("\"nextRunAt\":\"2026-01-16T09:00:00\"");
    }

    /** A task's linked jobs: last run rendered by to_char, date created cast to text, both in SQL. */
    @Test
    void aTasksLinkedJobsSendTheSqlRenderedTimes() throws Exception {
        asOwner();
        SourceTaskServiceImpl tasks = new SourceTaskServiceImpl(null, queries, null, null, null, null, null, null, null, null);
        String sent = wire(tasks.fetchAllLinkJobsWithSourceTaskId(TASK, null, null, null, null, PageRequest.of(0, 10), null).getData());
        System.out.println("WIRE linkedJobs " + sent);
        assertThat(sent).isEqualTo("[{\"jobId\":31001,\"jobName\":\"wire job\",\"jobStatus\":\"Active\",\"jobRunningStatus\":\"Completed\",\"lastJobRun\":\"2026-01-15T23:30:00\",\"execution\":\"Auto\",\"priority\":1,\"dateCreated\":\"2026-01-15T14:00:00.000+00:00\",\"completeJob\":false,\"failJob\":false,\"skipJob\":false,\"tabActive\":false,\"stalled\":false}]");
    }

    /** The profile page's recent runs. */
    @Test
    void theProfileActivitySendsRunTimesAsWallClock() throws Exception {
        asOwner();
        SourceJobServiceImpl service = new SourceJobServiceImpl(jpa.repository(SourceJobRepository.class), null, null, null,
            jpa.repository(JobQueueRepository.class), null, null, null, null, null, null, null);
        String sent = wire(jpa.transactions().execute(status -> {
            try {
                return service.fetchMyActivity(5, 100000).getData();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }));
        System.out.println("WIRE activity " + sent);
        assertThat(sent).isEqualTo("{\"jobsAssigned\":1,\"activeJobs\":1,\"recentRuns\":1,\"recentFailures\":0,\"windowDays\":100000,\"runs\":[{\"jobQueueId\":310001,\"jobId\":31001,\"jobName\":\"wire job\",\"jobStatus\":\"Completed\",\"startTime\":\"2026-01-15T23:30:00\",\"endTime\":\"2026-01-15T23:45:10.5\"}],\"outcomes\":[{\"name\":\"Completed\",\"value\":1}]}");
    }

    /**
     * The live job event pushed over the socket: lastJobRun and nextRunAt as strings. Recorded at e39381d from the
     * projection, whose LocalDateTime lastJobRun BulkAction printed with toString; asserted now on the event itself.
     */
    @Test
    void theRunningJobEventCarriesWallClockStrings() throws Exception {
        List<SourceJobProjection> events = jpa.repository(SourceJobRepository.class).fetchRunningJobEvent(Collections.singletonList(JOB));
        assertThat(events).hasSize(1);
        String event = BulkAction.getSourceJobDetail(events.get(0));
        System.out.println("WIRE runningEvent " + event);
        assertThat(json.readTree(event).get("lastJobRun").asText()).isEqualTo("2026-01-15T23:30");
        assertThat(json.readTree(event).get("nextRunAt").asText()).isEqualTo("2026-01-16 09:00:00.0");
    }

    /** A run's audit trail, read back from job_audit_logs. */
    @Test
    void theAuditTrailSendsItsTimeAsAWallClockString() {
        List<JobAuditLogProjection> lines = jpa.repository(JobAuditLogRepository.class).findAllByJobQueueIdV1(RUN);
        assertThat(lines).hasSize(1);
        System.out.println("WIRE auditLog dateCreated=" + lines.get(0).getDateCreated());
        assertThat(lines.get(0).getDateCreated()).isEqualTo("2026-01-15 23:30:05.123");
    }

    /** The Pipeline Forms list. */
    @Test
    void thePipelineListSendsItsCreationTimeAsWallClock() throws Exception {
        List<PipelineRowProjection> rows = jpa.repository(PipelineRepository.class)
            .pageRows(false, TENANT, 0, false, "", 0, "", PageRequest.of(0, 10)).getContent();
        assertThat(rows).hasSize(1);
        String sent = wire(PipelineRowDto.from(rows.get(0)));
        System.out.println("WIRE pipelineRow " + sent);
        assertThat(sent).contains("\"dateCreated\":\"2026-01-15T23:30:00\"");
    }

    /** The run report (CSV, mail, submit): the day a run belongs to, rendered in SQL. */
    @Test
    void theRunReportDatesTheRunByItsChicagoDay() {
        asOwner();
        List<Object[]> rows = queries.executeQuery(queries.runReportRows("2026-01-15", "2026-01-15"));
        System.out.println("WIRE runReport rows=" + rows.size() + (rows.isEmpty() ? "" : " first=" + Arrays.toString(rows.get(0))));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[3]).isEqualTo("2026-01-15");
    }
}
