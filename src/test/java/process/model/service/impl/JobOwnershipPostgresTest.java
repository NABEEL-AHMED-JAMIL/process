package process.model.service.impl;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.engine.BulkAction;
import process.engine.ProducerBulkEngine;
import process.identity.IdentityPort;
import process.model.dto.JobAssistantRequestDto;
import process.model.dto.JobStatusStatisticDto;
import process.model.dto.MessageQSearchDto;
import process.model.dto.QueueMessageStatusDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobDto;
import process.model.dto.SourceJobQueueDto;
import process.model.dto.SourceTaskDto;
import process.model.dto.UserStatisticDto;
import process.model.dto.WeeklyHrJobDimensionStatisticsDto;
import process.model.dto.WeeklyJobStatisticsDto;
import process.model.enums.Execution;
import process.model.enums.Status;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.TaskReferenceRepository;
import process.model.service.AiAgentService;
import process.notifications.JobMail;
import process.notifications.NotificationPort;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.excel.BulkExcel;
import process.util.OpenSearchAuditLogClient;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Owner decision 2026-09-24: inside a workspace, a TENANT_USER sees and acts on their own jobs only -- the ones
 * they created or are assigned to -- and those jobs' runs, audit lines, schedules and statistics. A TENANT_ADMIN
 * and a PLATFORM_ADMIN see and act on every job in their scope, as before (process.security.JobOwnership).
 *
 * One workspace (4101) holds a tenant admin and two tenant users, Alice and Bob, each with jobs, and every job a
 * run in the same Chicago hour; a second workspace (4102) holds one more job. Who each job names:
 *
 *   6101 alice-own          created by Alice, assigned to Alice
 *   6102 assigned-to-alice  created by the admin, assigned to Alice
 *   6103 alice-made-for-bob created by Alice, assigned to Bob       -- both see it
 *   6104 bob-own            created by Bob, assigned to Bob
 *   6105 admin-own          created by the admin, assigned to the admin
 *   6106 nobodys            no creator, no assignee (a legacy row)  -- only an admin sees it
 *   6201 other-workspace    workspace 4102
 *
 * Every list, statistic and export is driven through its real service over the real schema and reduced to the
 * job ids it reveals; every by-id read and every write is tried on each job. Opt-in: needs NOTIFICATIONS_TEST_DB_URL
 * (see ScratchPostgres).
 */
class JobOwnershipPostgresTest {

    private static final String DAY = "2026-09-21";
    private static final long HOUR = 14L;

    private static final long TENANT = 4101L;
    private static final long OTHER_TENANT = 4102L;
    private static final long ADMIN = 5100L;
    private static final long ALICE = 5101L;
    private static final long BOB = 5102L;
    private static final long OTHER_USER = 5201L;

    private static final long TYPE = 41001L;
    private static final long TASK = 41002L;
    private static final long OTHER_TYPE = 42001L;
    private static final long OTHER_TASK = 42002L;

    private static final long ALICE_OWN = 6101L;
    private static final long ASSIGNED_TO_ALICE = 6102L;
    private static final long ALICE_MADE_FOR_BOB = 6103L;
    private static final long BOB_OWN = 6104L;
    private static final long ADMIN_OWN = 6105L;
    private static final long NOBODYS = 6106L;
    private static final long OTHER_WORKSPACE = 6201L;

    private static final List<Long> ALL_JOBS = Arrays.asList(ALICE_OWN, ASSIGNED_TO_ALICE, ALICE_MADE_FOR_BOB, BOB_OWN,
        ADMIN_OWN, NOBODYS, OTHER_WORKSPACE);
    private static final Set<Long> ALICES = ids(ALICE_OWN, ASSIGNED_TO_ALICE, ALICE_MADE_FOR_BOB);
    private static final Set<Long> BOBS = ids(ALICE_MADE_FOR_BOB, BOB_OWN);
    private static final Set<Long> WORKSPACE = ids(ALICE_OWN, ASSIGNED_TO_ALICE, ALICE_MADE_FOR_BOB, BOB_OWN, ADMIN_OWN, NOBODYS);
    private static final Set<Long> EVERY_WORKSPACE = new TreeSet<>(ALL_JOBS);

    private static ScratchPostgres db;
    private static ScratchJpa jpa;

    private IdentityPort identity;
    private ProducerBulkEngine engine;
    private BulkAction bulkAction;
    private AiAgentService agents;
    private QueryService queries;
    private SourceJobServiceImpl jobs;
    private MessageQServiceImpl queue;
    private DashboardServiceImpl dashboard;
    private ReportExportServiceImpl reports;
    private SourceJobBulkServiceImpl bulk;
    private SourceTaskServiceImpl tasks;
    private JobAssistantServiceImpl assistant;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("job_ownership");
        jpa = new ScratchJpa(db);
        JdbcTemplate sql = db.jdbc();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES "
            + "(?, 'Active', 'OWN', 'Ownership'), (?, 'Active', 'OTH', 'Elsewhere')", TENANT, OTHER_TENANT);
        sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, tenant_id, "
            + "task_type_status) VALUES (?, 'svc', 'd', 'topic=own&partitions=[*]', ?, 'Active'), "
            + "(?, 'svc', 'd', 'topic=oth&partitions=[*]', ?, 'Active')", TYPE, TENANT, OTHER_TYPE, OTHER_TENANT);
        sql.update("INSERT INTO source_task (task_detail_id, task_name, task_status, source_task_type_id, tenant_id, date_created) "
            + "VALUES (?, 'shared task', 'Active', ?, ?, now()), (?, 'their task', 'Active', ?, ?, now())",
            TASK, TYPE, TENANT, OTHER_TASK, OTHER_TYPE, OTHER_TENANT);
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

    /** Every test starts from the same seven jobs: the writes below change them. */
    @BeforeEach
    void seed() throws Exception {
        JdbcTemplate sql = db.jdbc();
        sql.update("DELETE FROM job_audit_logs");
        sql.update("DELETE FROM job_queue");
        sql.update("DELETE FROM scheduler");
        sql.update("DELETE FROM source_job");
        job(ALICE_OWN, "alice-own", TENANT, TASK, ALICE, ALICE);
        job(ASSIGNED_TO_ALICE, "assigned-to-alice", TENANT, TASK, ADMIN, ALICE);
        job(ALICE_MADE_FOR_BOB, "alice-made-for-bob", TENANT, TASK, ALICE, BOB);
        job(BOB_OWN, "bob-own", TENANT, TASK, BOB, BOB);
        job(ADMIN_OWN, "admin-own", TENANT, TASK, ADMIN, ADMIN);
        job(NOBODYS, "nobodys", TENANT, TASK, null, null);
        job(OTHER_WORKSPACE, "other-workspace", OTHER_TENANT, OTHER_TASK, OTHER_USER, OTHER_USER);
        this.wireServices();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** A job, its schedule, one run in the day's 14:00 hour (still Running, so it can be failed by hand) and one audit line. */
    private static void job(long jobId, String name, long tenant, long task, Long createdBy, Long assignedTo) {
        JdbcTemplate sql = db.jdbc();
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, job_running_status, priority, "
            + "tenant_id, task_detail_id, created_by, assigned_user_id, complete_job, fail_job, skip_job) "
            + "VALUES (?, '2026-09-21 09:00-05', 'Auto', ?, 'Active', 'Completed', 1, ?, ?, ?, ?, false, false, false)",
            jobId, name, tenant, task, createdBy, assignedTo);
        sql.update("INSERT INTO scheduler (scheduler_id, frequency, interval_value, job_id, start_date, start_time, next_run_at, tenant_id) "
            + "VALUES (?, 'Daily', '1', ?, current_date - 1, '00:00', now() + interval '1 day', ?)", jobId, jobId, tenant);
        long run = runOf(jobId);
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, start_time, end_time, job_id, job_status, status, tenant_id, job_send) "
            + "VALUES (?, '2026-09-21 14:05-05', '2026-09-21 14:05-05', '2026-09-21 14:10-05', ?, 'Running', 'Active', ?, true)",
            run, jobId, tenant);
        sql.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status, tenant_id) "
            + "VALUES (?, '2026-09-21 14:06-05', ?, 'a line', 'Active', ?)", run, run, tenant);
    }

    private static long runOf(long jobId) {
        return jobId * 10 + 1;
    }

    private void wireServices() throws Exception {
        this.identity = mock(IdentityPort.class);
        when(this.identity.person(anyLong())).thenAnswer(call -> Optional.of(person(call.getArgument(0))));
        when(this.identity.members(any())).thenReturn(Arrays.asList(person(ADMIN), person(ALICE), person(BOB)));
        this.engine = mock(ProducerBulkEngine.class);
        this.bulkAction = mock(BulkAction.class);
        this.agents = mock(AiAgentService.class);
        when(this.agents.resolveRuntimeConfig(any())).thenReturn(new ResponseDto(ProcessUtil.ERROR, "the agent is not configured"));
        OpenSearchAuditLogClient openSearch = mock(OpenSearchAuditLogClient.class);
        TenantFilterHelper filters = new TenantFilterHelper();

        this.queries = new QueryService();
        ReflectionTestUtils.setField(this.queries, "_em", jpa.sharedEntityManager());
        this.jobs = new SourceJobServiceImpl(jpa.repository(SourceJobRepository.class), jpa.repository(SchedulerRepository.class),
            jpa.repository(SourceTaskRepository.class), jpa.repository(JobAuditLogRepository.class),
            jpa.repository(JobQueueRepository.class), jpa.repository(TaskReferenceRepository.class), this.identity, this.engine,
            filters, openSearch, mock(NotificationPort.class), new UserNameResolver(this.identity));
        ReflectionTestUtils.setField(this.jobs, "entityManager", jpa.sharedEntityManager());
        this.queue = new MessageQServiceImpl(this.bulkAction, this.queries, jpa.repository(JobQueueRepository.class),
            jpa.repository(SourceJobRepository.class), mock(JobMail.class));
        this.dashboard = new DashboardServiceImpl(this.queries, jpa.repository(SourceJobRepository.class),
            jpa.repository(JobQueueRepository.class), jpa.repository(SchedulerRepository.class),
            jpa.repository(TaskReferenceRepository.class), this.identity);
        this.reports = new ReportExportServiceImpl(null, null, this.queries, this.identity);
        this.bulk = new SourceJobBulkServiceImpl(null, jpa.repository(SourceJobRepository.class),
            jpa.repository(SchedulerRepository.class), new BulkExcel(), mock(NotificationPort.class));
        this.tasks = new SourceTaskServiceImpl(null, this.queries, jpa.repository(SourceJobRepository.class),
            jpa.repository(SourceTaskRepository.class), null, filters, null, this.identity, null, new UserNameResolver(this.identity));
        ReflectionTestUtils.setField(this.tasks, "entityManager", jpa.sharedEntityManager());
        this.assistant = new JobAssistantServiceImpl(jpa.repository(SourceJobRepository.class),
            jpa.repository(JobQueueRepository.class), jpa.repository(SchedulerRepository.class), this.agents, filters);
        ReflectionTestUtils.setField(this.assistant, "entityManager", jpa.sharedEntityManager());
    }

    private static IdentityPort.Person person(long appUserId) {
        String role = appUserId == ADMIN ? "TENANT_ADMIN" : "TENANT_USER";
        long tenant = appUserId == OTHER_USER ? OTHER_TENANT : TENANT;
        return new IdentityPort.Person(appUserId, tenant, "u" + appUserId + "@own.test", "User " + appUserId, role, "Active");
    }

    private static void asAlice() {
        TenantContext.set(TENANT, "TENANT_USER", ALICE, "alice@own.test");
    }

    private static void asBob() {
        TenantContext.set(TENANT, "TENANT_USER", BOB, "bob@own.test");
    }

    private static void asAdmin() {
        TenantContext.set(TENANT, "TENANT_ADMIN", ADMIN, "admin@own.test");
    }

    private static void asPlatformAdmin() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@platform.test");
    }

    // ---- lists, statistics, exports ------------------------------------------------------------------------------

    @Test
    void aTenantUserListsCountsAndExportsOnlyTheJobsThatNameThem() throws Exception {
        asAlice();
        assertThat(this.everyListAndStatistic()).allSatisfy((endpoint, seen) ->
            assertThat(seen).as(endpoint).isEqualTo(ALICES));

        asBob();
        assertThat(this.everyListAndStatistic()).allSatisfy((endpoint, seen) ->
            assertThat(seen).as(endpoint).isEqualTo(BOBS));
    }

    @Test
    void aTenantAdminStillSeesEveryJobInTheWorkspace() throws Exception {
        asAdmin();
        assertThat(this.everyListAndStatistic()).allSatisfy((endpoint, seen) ->
            assertThat(seen).as(endpoint).isEqualTo(WORKSPACE));
    }

    @Test
    void aPlatformAdminStillSeesEveryJobInEveryWorkspace() throws Exception {
        asPlatformAdmin();
        Map<String, Set<Long>> seen = this.everyListAndStatistic();
        // The shared task's linked jobs are one workspace's by construction; everything else spans both.
        seen.forEach((endpoint, ids) -> assertThat(ids).as(endpoint)
            .isEqualTo(endpoint.startsWith("sourceTask.json") ? WORKSPACE : EVERY_WORKSPACE));
    }

    /** Fail closed: a tenant user whose token names nobody is no one's owner, and sees nothing. */
    @Test
    void aTenantUserWithNoIdOfTheirOwnSeesNoJobs() throws Exception {
        TenantContext.set(TENANT, "TENANT_USER", null, "ghost@own.test");
        assertThat(this.everyListAndStatistic()).allSatisfy((endpoint, seen) ->
            assertThat(seen).as(endpoint).isEmpty());
    }

    /**
     * userStatistics was already scoped: a tenant user is listed as their own row (MIG-46), counting the jobs assigned
     * to them -- all of which are theirs under this rule too. Pinned so the two rules cannot drift apart.
     */
    @Test
    void aTenantUsersPeopleStatisticIsTheirOwnRowOfTheirOwnJobs() throws Exception {
        asAlice();
        List<UserStatisticDto> rows = this.data(() -> this.dashboard.userStatistics(DAY, DAY));
        assertThat(rows).extracting(UserStatisticDto::getAppUserId).containsExactly(ALICE);
        assertThat(rows.get(0).getJobCount()).isEqualTo(2);
        assertThat(rows.get(0).getRunCount()).isEqualTo(2);

        asAdmin();
        assertThat(this.<List<UserStatisticDto>>data(() -> this.dashboard.userStatistics(DAY, DAY)))
            .extracting(UserStatisticDto::getAppUserId).containsExactlyInAnyOrder(ADMIN, ALICE, BOB);
    }

    /**
     * Every list, statistic and export a TENANT_USER can reach that reveals jobs, reduced to the job ids it reveals.
     * A statistic that only counts is read as "the jobs whose runs it counted": each job has exactly one run in the
     * hour, so a count of n is n jobs, and it is compared as the first n of the expected ids.
     */
    private Map<String, Set<Long>> everyListAndStatistic() throws Exception {
        Map<String, Set<Long>> seen = new LinkedHashMap<>();

        List<SourceJobDto> list = this.data(() -> this.jobs.listSourceJob());
        seen.put("sourceJob.json/listSourceJob", list.stream().map(SourceJobDto::getJobId).collect(toIds()));
        seen.put("sourceJob.json/downloadListSourceJob", this.exportedJobs());

        List<SourceJobDto> linked = this.data(() -> this.tasks.fetchAllLinkJobsWithSourceTaskId(TASK, null, null, null, null,
            PageRequest.of(0, 50), null));
        seen.put("sourceTask.json/fetchAllLinkJobsWithSourceTaskId", linked == null ? ids()
            : linked.stream().map(SourceJobDto::getJobId).collect(toIds()));
        List<SourceTaskDto> taskList = this.data(() -> this.tasks.listSourceTask(null, null, null, null, PageRequest.of(0, 50), null));
        long linkedCount = taskList.stream().filter(task -> TASK == task.getTaskDetailId()).findFirst()
            .map(task -> task.getTotalLinksJobs() == null ? 0L : task.getTotalLinksJobs()).orElse(0L);
        seen.put("sourceTask.json/listSourceTask totalLinksJobs", this.counted(linkedCount));

        // No runs at all is answered with an empty list rather than the map.
        Object logData = this.data(() -> this.queue.fetchLogs(new MessageQSearchDto(DAY, DAY, null, null, null)));
        Map<String, Object> logs = logData instanceof Map ? (Map<String, Object>) logData : null;
        List<SourceJobQueueDto> runs = logs == null ? new ArrayList<>() : (List<SourceJobQueueDto>) logs.get("sourceJobQueues");
        seen.put("message.json/fetchLogs", runs.stream().map(SourceJobQueueDto::getJobId).collect(toIds()));
        List<JobStatusStatisticDto> states = logs == null ? null : (List<JobStatusStatisticDto>) logs.get("jobStatusStatistic");
        seen.put("message.json/fetchLogs jobStatusStatistic", this.counted(sum(states)));

        Map<String, Object> report = this.data(() -> this.reports.runRows(DAY, DAY));
        seen.put("report.json/runs", ((List<List<Object>>) report.get("rows")).stream()
            .map(row -> ((Long) row.get(6)) / 10).collect(toIds()));

        List<JobStatusStatisticDto> statuses = this.data(() -> this.dashboard.jobStatusStatistics(DAY, DAY));
        seen.put("dashboard.json/jobStatusStatistics", this.counted(statuses == null ? 0 : statuses.stream()
            .filter(tile -> "All".equals(tile.getName())).mapToLong(JobStatusStatisticDto::getValue).sum()));
        seen.put("dashboard.json/jobRunningStatistics",
            this.counted(sum(this.data(() -> this.dashboard.jobRunningStatistics(DAY, DAY)))));
        seen.put("dashboard.json/weeklyRunningJobStatistics",
            this.counted(sum(this.data(() -> this.dashboard.weeklyRunningJobStatistics(DAY, DAY)))));
        List<WeeklyJobStatisticsDto> hours = this.data(() -> this.dashboard.weeklyHrsRunningJobStatistics(DAY, DAY));
        seen.put("dashboard.json/weeklyHrsRunningJobStatistics",
            this.counted(hours == null ? 0 : hours.stream().mapToLong(WeeklyJobStatisticsDto::getCount).sum()));
        List<WeeklyHrJobDimensionStatisticsDto> dimension = this.data(() -> this.dashboard.weeklyHrRunningStatisticsDimension(DAY, HOUR));
        Set<Long> dimensionJobs = dimension == null ? ids() : dimension.stream()
            .map(WeeklyHrJobDimensionStatisticsDto::getJobId).filter(id -> id != null).collect(toIds());
        seen.put("dashboard.json/weeklyHrRunningStatisticsDimension", dimensionJobs);
        seen.put("dashboard.json/weeklyHrRunningStatisticsDimension TOTAL", this.counted(dimension == null ? 0 : dimension.stream()
            .filter(row -> row.getJobId() == null).mapToLong(WeeklyHrJobDimensionStatisticsDto::getTotal).sum()));
        Map<String, Object> detail = this.data(() -> this.dashboard.weeklyHrRunningStatisticsDimensionDetail(DAY, HOUR, null, null));
        seen.put("dashboard.json/weeklyHrRunningStatisticsDimensionDetail", detail == null ? ids()
            : ((List<SourceJobQueueDto>) detail.get("sourceJobQueues")).stream().map(SourceJobQueueDto::getJobId).collect(toIds()));
        Set<Long> drilled = new TreeSet<>();
        Set<Long> perJobStatistics = new TreeSet<>();
        for (long jobId : ALL_JOBS) {
            Map<String, Object> one = this.data(() -> this.dashboard.weeklyHrRunningStatisticsDimensionDetail(DAY, HOUR, null, jobId));
            if (one != null && one.get("sourceJob") != null) {
                drilled.add(((SourceJobDto) one.get("sourceJob")).getJobId());
            }
            // The per-job statistic's own query, on its own: the drill-down above only reaches it past the job guard.
            if (sumOf(this.inTransaction(() -> this.queries.executeQuery(this.queries.statisticsBySourceJobId(jobId)))) > 0) {
                perJobStatistics.add(jobId);
            }
        }
        seen.put("dashboard.json/weeklyHrRunningStatisticsDimensionDetail?jobId", drilled);
        seen.put("QueryService.statisticsBySourceJobId", perJobStatistics);
        return seen;
    }

    /** The first n of the ids the caller is expected to see: a count compared as a set. */
    private Set<Long> counted(long n) {
        Set<Long> expected = this.expectedForCaller();
        return n <= expected.size() ? expected.stream().limit(n).collect(toIds()) : overflow(n);
    }

    private Set<Long> expectedForCaller() {
        if (TenantContext.isPlatformAdmin()) {
            return EVERY_WORKSPACE;
        }
        if ("TENANT_ADMIN".equals(TenantContext.getUserRole())) {
            return WORKSPACE;
        }
        Long me = TenantContext.getAppUserId();
        return me == null ? ids() : me == ALICE ? ALICES : BOBS;
    }

    /** More than the caller may see: shown as that many made-up ids, so the failure message says how many. */
    private static Set<Long> overflow(long n) {
        Set<Long> made = new TreeSet<>();
        for (long i = 0; i < n; i++) {
            made.add(-1 - i);
        }
        return made;
    }

    private Set<Long> exportedJobs() throws Exception {
        byte[] bytes = this.inTransaction(() -> this.bulk.downloadListSourceJob().toByteArray());
        Map<String, Long> byName = new HashMap<>();
        db.jdbc().query("SELECT job_id, job_name FROM source_job", row -> {
            byName.put(row.getString("job_name"), row.getLong("job_id"));
        });
        Set<Long> exported = new TreeSet<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue;
                }
                exported.add(byName.get(row.getCell(0).getStringCellValue()));
            }
        }
        return exported;
    }

    // ---- by-id reads ----------------------------------------------------------------------------------------------

    @Test
    void aTenantUserReadsTheirOwnJobsByIdAndAnotherUsersIsNotFound() throws Exception {
        asAlice();
        for (long jobId : ALL_JOBS) {
            boolean mine = ALICES.contains(jobId);
            String notFound = String.format("SourceJob not found with %d.", jobId);
            this.expect(this.call(() -> this.jobs.fetchSourceJobDetailWithSourceJobId(jobId)), mine, notFound, "detail " + jobId);
            this.expect(this.call(() -> this.jobs.fetchSourceJobQueueListWithJobId(jobId)), mine, notFound, "history " + jobId);
            this.expect(this.call(() -> this.jobs.findSourceJobAuditLog(runOf(jobId), jobId)), mine, notFound, "audit log " + jobId);
            ResponseDto asked = this.call(() -> this.assistant.ask(this.question(jobId)));
            assertThat(asked.getMessage()).as("assistant " + jobId)
                .isEqualTo(mine ? "the agent is not configured" : notFound);
            Map<String, Object> drill = this.data(() -> this.dashboard.weeklyHrRunningStatisticsDimensionDetail(DAY, HOUR, null, jobId));
            assertThat(drill != null && drill.get("sourceJob") != null).as("drill-down " + jobId).isEqualTo(mine);
        }
        // Another user's job reads exactly as a job that does not exist.
        long missing = 999999L;
        assertThat(this.call(() -> this.jobs.fetchSourceJobDetailWithSourceJobId(missing)).getMessage())
            .isEqualTo(String.format("SourceJob not found with %d.", missing));
    }

    @Test
    void aTenantAdminReadsEveryJobInTheWorkspaceById() throws Exception {
        asAdmin();
        for (long jobId : ALL_JOBS) {
            boolean inWorkspace = WORKSPACE.contains(jobId);
            String notFound = String.format("SourceJob not found with %d.", jobId);
            this.expect(this.call(() -> this.jobs.fetchSourceJobDetailWithSourceJobId(jobId)), inWorkspace, notFound, "detail " + jobId);
            this.expect(this.call(() -> this.jobs.fetchSourceJobQueueListWithJobId(jobId)), inWorkspace, notFound, "history " + jobId);
            this.expect(this.call(() -> this.jobs.findSourceJobAuditLog(runOf(jobId), jobId)), inWorkspace, notFound, "audit log " + jobId);
        }
    }

    private void expect(ResponseDto response, boolean found, String notFound, String what) {
        if (found) {
            assertThat(response.getStatus()).as(what + ": " + response.getMessage()).isEqualTo(ProcessUtil.SUCCESS);
        } else {
            assertThat(response.getStatus()).as(what).isEqualTo(ProcessUtil.ERROR);
            assertThat(response.getMessage()).as(what).isEqualTo(notFound);
            assertThat(response.getData()).as(what).isNull();
        }
    }

    private JobAssistantRequestDto question(long jobId) {
        JobAssistantRequestDto dto = new JobAssistantRequestDto();
        dto.setJobId(jobId);
        dto.setAiAgentId(1L);
        dto.setMessage("why did it fail?");
        return dto;
    }

    // ---- writes ---------------------------------------------------------------------------------------------------

    @Test
    void aTenantUserCannotChangeAnotherUsersJobInAnyWay() throws Exception {
        asAlice();
        for (long jobId : new long[] {BOB_OWN, ADMIN_OWN, NOBODYS, OTHER_WORKSPACE}) {
            String notFound = String.format("SourceJob not found with %d.", jobId);
            assertThat(this.call(() -> this.jobs.updateSourceJob(this.edit(jobId, "renamed by alice"))).getMessage())
                .as("update " + jobId).isEqualTo(notFound);
            assertThat(this.call(() -> this.jobs.toggleSourceJobStatus(this.dto(jobId, Status.Inactive))).getMessage())
                .as("deactivate " + jobId).isEqualTo(notFound);
            assertThat(this.call(() -> this.jobs.runSourceJob(this.dto(jobId, null))).getMessage())
                .as("run now " + jobId).isEqualTo("SourceJob not found with jobId.");
            assertThat(this.call(() -> this.jobs.skipNextSourceJob(this.dto(jobId, null))).getMessage())
                .as("skip " + jobId).isEqualTo("SourceJob not found with jobId.");
            assertThat(this.call(() -> this.jobs.deleteSourceJob(this.dto(jobId, null))).getMessage())
                .as("delete " + jobId).isEqualTo(notFound);
        }
        // Nothing moved: names, statuses, notification choices and schedules are as seeded, and nothing was queued.
        assertThat(db.jdbc().queryForList("SELECT job_name FROM source_job WHERE job_id IN (?, ?, ?, ?)", String.class,
            BOB_OWN, ADMIN_OWN, NOBODYS, OTHER_WORKSPACE)).containsExactlyInAnyOrder("bob-own", "admin-own", "nobodys", "other-workspace");
        assertThat(db.jdbc().queryForList("SELECT DISTINCT job_status FROM source_job WHERE job_id IN (?, ?, ?, ?)", String.class,
            BOB_OWN, ADMIN_OWN, NOBODYS, OTHER_WORKSPACE)).containsExactly("Active");
        assertThat(db.jdbc().queryForList("SELECT DISTINCT fail_job FROM source_job WHERE job_id IN (?, ?, ?, ?)", Boolean.class,
            BOB_OWN, ADMIN_OWN, NOBODYS, OTHER_WORKSPACE)).containsExactly(false);
        verify(this.engine, never()).addManualJobInQueue(any());
        verify(this.engine, never()).skipManualJobInQueue(any());
    }

    @Test
    void aTenantUserCreatesJobsAndChangesTheirOwnInEveryWay() throws Exception {
        asAlice();
        SourceJobDto created = this.edit(null, "alice-new");
        created.setExecution(Execution.Manual);
        created.setPriority(3);
        ResponseDto added = this.call(() -> this.jobs.addSourceJob(created));
        assertThat(added.getStatus()).as(added.getMessage()).isEqualTo(ProcessUtil.SUCCESS);
        Long newJob = db.jdbc().queryForObject("SELECT job_id FROM source_job WHERE job_name = 'alice-new'", Long.class);
        assertThat(db.jdbc().queryForObject("SELECT created_by FROM source_job WHERE job_id = ?", Long.class, newJob)).isEqualTo(ALICE);
        assertThat(this.<List<SourceJobDto>>data(() -> this.jobs.listSourceJob())).extracting(SourceJobDto::getJobId).contains(newJob);

        // Created by her, assigned to her, and only assigned to her: each is hers to change.
        for (long jobId : new long[] {ALICE_OWN, ASSIGNED_TO_ALICE, ALICE_MADE_FOR_BOB}) {
            SourceJobDto edited = this.edit(jobId, "edited " + jobId);
            edited.setFailJob(true);
            this.succeeds(this.call(() -> this.jobs.updateSourceJob(edited)), "update " + jobId);
            this.succeeds(this.call(() -> this.jobs.toggleSourceJobStatus(this.dto(jobId, Status.Inactive))), "deactivate " + jobId);
            this.succeeds(this.call(() -> this.jobs.toggleSourceJobStatus(this.dto(jobId, Status.Active))), "activate " + jobId);
            this.succeeds(this.call(() -> this.jobs.runSourceJob(this.dto(jobId, null))), "run now " + jobId);
            this.succeeds(this.call(() -> this.jobs.skipNextSourceJob(this.dto(jobId, null))), "skip " + jobId);
        }
        assertThat(db.jdbc().queryForObject("SELECT job_name FROM source_job WHERE job_id = ?", String.class, ALICE_OWN))
            .isEqualTo("edited " + ALICE_OWN);
        assertThat(db.jdbc().queryForObject("SELECT fail_job FROM source_job WHERE job_id = ?", Boolean.class, ASSIGNED_TO_ALICE))
            .isTrue();
        this.succeeds(this.call(() -> this.jobs.deleteSourceJob(this.dto(ALICE_MADE_FOR_BOB, null))), "delete");
        assertThat(db.jdbc().queryForObject("SELECT job_status FROM source_job WHERE job_id = ?", String.class, ALICE_MADE_FOR_BOB))
            .isEqualTo("Delete");
    }

    @Test
    void aTenantAdminStillChangesAndDeletesAnyJobInTheWorkspace() throws Exception {
        asAdmin();
        for (long jobId : new long[] {ALICE_OWN, BOB_OWN, NOBODYS}) {
            this.succeeds(this.call(() -> this.jobs.updateSourceJob(this.edit(jobId, "admin edit " + jobId))), "update " + jobId);
            this.succeeds(this.call(() -> this.jobs.toggleSourceJobStatus(this.dto(jobId, Status.Inactive))), "deactivate " + jobId);
            this.succeeds(this.call(() -> this.jobs.toggleSourceJobStatus(this.dto(jobId, Status.Active))), "activate " + jobId);
            this.succeeds(this.call(() -> this.jobs.runSourceJob(this.dto(jobId, null))), "run now " + jobId);
            this.succeeds(this.call(() -> this.jobs.skipNextSourceJob(this.dto(jobId, null))), "skip " + jobId);
            this.succeeds(this.call(() -> this.jobs.deleteSourceJob(this.dto(jobId, null))), "delete " + jobId);
        }
        assertThat(this.call(() -> this.jobs.deleteSourceJob(this.dto(OTHER_WORKSPACE, null))).getMessage())
            .isEqualTo(String.format("SourceJob not found with %d.", OTHER_WORKSPACE));
    }

    /** The queue screen's writes act on a run through its job: a tenant user's own runs only. */
    @Test
    void aTenantUserFailsInterruptsAndReportsOnlyTheirOwnRuns() throws Exception {
        asAlice();
        for (long jobId : new long[] {BOB_OWN, ADMIN_OWN, NOBODYS, OTHER_WORKSPACE}) {
            long run = runOf(jobId);
            assertThat(this.call(() -> this.queue.failJobLogs(run)).getMessage()).as("fail " + run).isEqualTo("JobQueue not found");
            assertThat(this.call(() -> this.queue.interruptJobLogs(run)).getMessage()).as("interrupt " + run).isEqualTo("JobQueue not found");
            assertThat(this.call(() -> this.queue.changeJobStatus(this.auditLine(run))).getMessage()).as("audit line " + run)
                .isEqualTo("JobQueue not found.");
        }
        verifyNoInteractions(this.bulkAction);

        for (long jobId : ALICES) {
            long run = runOf(jobId);
            this.succeeds(this.call(() -> this.queue.changeJobStatus(this.auditLine(run))), "audit line " + run);
            this.succeeds(this.call(() -> this.queue.failJobLogs(run)), "fail " + run);
        }
        this.succeeds(this.call(() -> this.queue.interruptJobLogs(runOf(ALICE_OWN))), "interrupt");
    }

    @Test
    void aTenantAdminActsOnEveryRunInTheWorkspace() throws Exception {
        asAdmin();
        for (long jobId : new long[] {BOB_OWN, NOBODYS}) {
            this.succeeds(this.call(() -> this.queue.failJobLogs(runOf(jobId))), "fail " + jobId);
        }
        assertThat(this.call(() -> this.queue.failJobLogs(runOf(OTHER_WORKSPACE))).getMessage()).isEqualTo("JobQueue not found");
    }

    private QueueMessageStatusDto auditLine(long run) {
        QueueMessageStatusDto dto = new QueueMessageStatusDto();
        dto.setJobQueueId(run);
        dto.setMessageType("AUDIT_LOG");
        dto.setLogsDetail("a note");
        return dto;
    }

    private SourceJobDto dto(Long jobId, Status status) {
        SourceJobDto dto = new SourceJobDto();
        dto.setJobId(jobId);
        dto.setJobStatus(status);
        return dto;
    }

    private SourceJobDto edit(Long jobId, String name) {
        SourceJobDto dto = new SourceJobDto();
        dto.setJobId(jobId);
        dto.setJobName(name);
        SourceTaskDto task = new SourceTaskDto();
        task.setTaskDetailId(jobId != null && jobId == OTHER_WORKSPACE ? OTHER_TASK : TASK);
        dto.setTaskDetail(task);
        return dto;
    }

    private void succeeds(ResponseDto response, String what) {
        assertThat(response.getStatus()).as(what + ": " + response.getMessage()).isEqualTo(ProcessUtil.SUCCESS);
    }

    // ---- plumbing -------------------------------------------------------------------------------------------------

    private <T> T inTransaction(Callable<T> work) {
        return jpa.transactions().execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private ResponseDto call(Callable<ResponseDto> work) {
        return this.inTransaction(work);
    }

    @SuppressWarnings("unchecked")
    private <T> T data(Callable<ResponseDto> work) {
        ResponseDto response = this.call(work);
        assertThat(response.getStatus()).as(response.getMessage()).isEqualTo(ProcessUtil.SUCCESS);
        return (T) response.getData();
    }

    private static long sum(Collection<JobStatusStatisticDto> tiles) {
        return tiles == null ? 0 : tiles.stream().mapToLong(JobStatusStatisticDto::getValue).sum();
    }

    private static long sumOf(List<Object[]> rows) {
        long total = 0;
        for (Object[] row : rows) {
            total += ((Number) row[row.length - 1]).longValue();
        }
        return total;
    }

    private static Set<Long> ids(Long... ids) {
        return new TreeSet<>(Arrays.asList(ids));
    }

    private static java.util.stream.Collector<Long, ?, Set<Long>> toIds() {
        return Collectors.toCollection(TreeSet::new);
    }
}
