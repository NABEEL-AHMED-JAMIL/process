package process.tenancy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.model.dto.MessageQSearchDto;
import process.model.dto.QueueMessageStatusDto;
import process.model.dto.ReportExportRequestDto;
import process.model.enums.JobStatus;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static process.tenancy.CoreProbeFixture.USER_OF_A;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_A;
import static process.tenancy.CoreProbeFixture.DAY_BEFORE;
import static process.tenancy.CoreProbeFixture.DAY_AFTER;
import static process.tenancy.CoreProbeFixture.B_JOB;
import static process.tenancy.CoreProbeFixture.B_JOB_NAMING_USER_A;
import static process.tenancy.CoreProbeFixture.B_RUN;
import static process.tenancy.CoreProbeFixture.B_RUN_NAMING_USER_A;
import static process.tenancy.CoreProbeFixture.DAY;
import static process.tenancy.CoreProbeFixture.HOUR;
import static process.tenancy.CoreProbeFixture.COLLEAGUE_JOB;
import static process.tenancy.CoreProbeFixture.REFUSED;
import static process.tenancy.CoreProbeFixture.A_RUN;
import static process.tenancy.CoreProbeFixture.COLLEAGUE_RUN;
import static process.tenancy.CoreProbeFixture.B_BUCKET;
import static process.tenancy.CoreProbeFixture.SUCCEEDED;
import static process.tenancy.CoreProbeFixture.A_BUCKET;
import static process.tenancy.CoreProbeFixture.A;
import static process.tenancy.CoreProbeFixture.B;
import static process.tenancy.CoreProbeFixture.C;
import static process.tenancy.CoreProbeFixture.NO_WORKSPACE;
import static process.tenancy.CoreProbeFixture.tenantlessUser;
import static process.tenancy.CoreProbeFixture.A_JOB;
import process.tenancy.CoreProbeFixture.Caller;

/**
 * MIG-166's CI cross-tenant probe for Core, the read-mostly surfaces: the queue screen's run log and its three writes
 * (/message.json), the report grid and its export (/report.json), and the Operations and people dashboards
 * (/dashboard.json) -- called by workspace A's tenant user and tenant admin with B's job and run ids, against a real
 * etl_job (CoreProbeFixture).
 *
 * These are the string-built reads: QueryService splices the tenant clause and the job-ownership predicate into
 * every one, and a statistic that only counts carries no marker to find -- so the counts are asserted too: the
 * "All" tile is exactly the jobs the caller may see. A run's writes (fail, interrupt, a hand-written audit line) are
 * settled from the run's own row and its job, never from a jobId in the body.
 *
 * Report export is the one that leaves Core: a bucket write goes to storage-service as the signed-in user, which
 * decides whose bucket it is. The probe shows it asked as A's caller -- the caller's own bearer token, never the
 * service token -- that storage's refusal of B's bucket reached the caller as a refusal, and that nothing was
 * written there. Opt-in, like every ScratchPostgres test.
 */
class CoreCrossTenantProbeLogsReportsDashboardPostgresTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static CoreProbeFixture fx;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_logs");
    }

    @AfterAll
    static void drop() throws Exception {
        if (fx != null) {
            fx.close();
        }
    }

    @BeforeEach
    void forgetEarlierCalls() {
        fx.reset();
    }

    private static QueueMessageStatusDto line(long jobQueueId, Long jobId, String type) {
        QueueMessageStatusDto dto = new QueueMessageStatusDto();
        dto.setJobQueueId(jobQueueId);
        dto.setJobId(jobId);
        dto.setMessageType(type);
        dto.setJobStatus(JobStatus.Failed);
        dto.setLogsDetail("written by acme");
        return dto;
    }

    private static ReportExportRequestDto export(String bucket) {
        ReportExportRequestDto dto = new ReportExportRequestDto();
        dto.setTitle("acme runs");
        dto.setColumns(Arrays.asList("job", "runs"));
        dto.setRows(Collections.singletonList(Arrays.<Object>asList("Acme Own Job", 1)));
        dto.setFormat("csv");
        dto.setDestination("bucket");
        dto.setBucket(bucket);
        dto.setFolder("reports");
        return dto;
    }

    /** The value of the "All" tile of jobStatusStatistics: every job the caller may see. */
    private static int allTile(String body) throws Exception {
        for (JsonNode tile : JSON.readTree(body).path("data")) {
            if ("All".equals(tile.path("name").asText())) {
                return tile.path("value").asInt();
            }
        }
        return 0;
    }

    @Test
    void noLogReportOrDashboardEndpointHandsTheCallerAnotherWorkspacesOrAColleaguesRunsOrChangesThem() throws Exception {
        String before = fx.foreignRows();

        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            // The run log, whole and narrowed to B's job and run ids: A's own runs or none.
            fx.probe("POST message.json/fetchLogs", caller, () -> fx.messages.fetchLogs(
                new MessageQSearchDto(DAY_BEFORE, DAY_AFTER, null, null, null)));
            String narrowed = fx.probe("POST message.json/fetchLogs(their job and run)", caller, () -> fx.messages.fetchLogs(
                new MessageQSearchDto(DAY_BEFORE, DAY_AFTER, new HashSet<>(Arrays.asList(B_JOB, B_JOB_NAMING_USER_A)),
                    new HashSet<>(Arrays.asList(B_RUN, B_RUN_NAMING_USER_A)), null)));
            assertThat(narrowed).as("B's runs narrowed to are not A's runs").doesNotContain(String.valueOf(B_RUN));

            fx.probe("GET report.json/runs", caller, () -> fx.reports.runs(DAY_BEFORE, DAY_AFTER));

            String tiles = fx.probe("GET dashboard.json/jobStatusStatistics", caller,
                () -> fx.dashboard.jobStatusStatistics(DAY_BEFORE, DAY_AFTER));
            assertThat(allTile(tiles)).as("the All tile counts " + caller + "'s own jobs, not B's")
                .isEqualTo(caller == USER_OF_A ? 1 : 2);
            fx.probe("GET dashboard.json/userStatistics", caller, () -> fx.dashboard.userStatistics(DAY_BEFORE, DAY_AFTER));
            fx.probe("GET dashboard.json/jobRunningStatistics", caller, () -> fx.dashboard.jobRunningStatistics(DAY_BEFORE, DAY_AFTER));
            fx.probe("GET dashboard.json/weeklyRunningJobStatistics", caller,
                () -> fx.dashboard.weeklyRunningJobStatistics(DAY_BEFORE, DAY_AFTER));
            fx.probe("GET dashboard.json/weeklyHrsRunningJobStatistics", caller,
                () -> fx.dashboard.weeklyHrsRunningJobStatistics(DAY_BEFORE, DAY_AFTER));
            fx.probe("GET dashboard.json/weeklyHrRunningStatisticsDimension", caller,
                () -> fx.dashboard.weeklyHrRunningStatisticsDimension(DAY, HOUR));
            fx.probe("GET dashboard.json/weeklyHrRunningStatisticsDimensionDetail", caller,
                () -> fx.dashboard.weeklyHrRunningStatisticsDimensionDetail(DAY, HOUR, null, null));
            List<Long> aimed = caller == USER_OF_A ? Arrays.asList(B_JOB, B_JOB_NAMING_USER_A, COLLEAGUE_JOB)
                : Arrays.asList(B_JOB, B_JOB_NAMING_USER_A);
            for (long theirs : aimed) {
                fx.probe("GET dashboard.json/weeklyHrRunningStatisticsDimensionDetail(their job)", caller,
                    () -> fx.dashboard.weeklyHrRunningStatisticsDimensionDetail(DAY, HOUR, null, theirs));
            }

            // A run's writes: B's runs, and B's run named through A's own job.
            for (long theirs : new long[] {B_RUN, B_RUN_NAMING_USER_A}) {
                assertThat(fx.probe("DELETE message.json/failJobLogs", caller, () -> fx.messages.failJobLogs(theirs)))
                    .contains(REFUSED);
                assertThat(fx.probe("DELETE message.json/interruptJobLogs", caller, () -> fx.messages.interruptJobLogs(theirs)))
                    .contains(REFUSED);
                assertThat(fx.probe("PUT message.json/changeJobStatus", caller,
                    () -> fx.messages.changeJobStatus(line(theirs, null, "AUDIT_LOG")))).contains(REFUSED);
                assertThat(fx.probe("PUT message.json/changeJobStatus(queue detail)", caller,
                    () -> fx.messages.changeJobStatus(line(theirs, null, "QUEUE_DETAIL")))).contains(REFUSED);
            }
            assertThat(fx.probe("PUT message.json/changeJobStatus(my run, their job)", caller,
                () -> fx.messages.changeJobStatus(line(A_RUN, B_JOB, "QUEUE_DETAIL")))).contains(REFUSED);
        }
        // A tenant user acts on their own runs only: the colleague's run is not theirs to fail.
        assertThat(fx.probe("DELETE message.json/failJobLogs(colleague's run)", USER_OF_A,
            () -> fx.messages.failJobLogs(COLLEAGUE_RUN))).contains(REFUSED);
        assertThat(fx.probe("DELETE message.json/interruptJobLogs(colleague's run)", USER_OF_A,
            () -> fx.messages.interruptJobLogs(COLLEAGUE_RUN))).contains(REFUSED);
        assertThat(fx.probe("PUT message.json/changeJobStatus(colleague's run)", USER_OF_A,
            () -> fx.messages.changeJobStatus(line(COLLEAGUE_RUN, null, "AUDIT_LOG")))).contains(REFUSED);

        // Export into B's bucket: asked of storage as A's caller, refused by storage, nothing written.
        assertThat(fx.probe("POST report.json/export(their bucket)", USER_OF_A, () -> fx.reports.export(export(B_BUCKET))))
            .contains(REFUSED);
        assertThat(fx.storage.naming(B_BUCKET)).as("the export reached storage as the signed-in user").isNotEmpty()
            .allSatisfy(request -> {
                assertThat(request.authorization).isEqualTo(USER_OF_A.bearer());
                assertThat(request.internalToken).as("never the trusted path").isNull();
            });
        assertThat(fx.storage.stored).noneMatch(object -> object.startsWith(B_BUCKET));

        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).as("nothing of B's, the platform's or the colleague's changed").isEqualTo(before);
        verifyNoInteractions(fx.bulkAction, fx.jobMail, fx.trustedStorage);
    }

    /** A report the caller exports lands in their own bucket, written with their own token. */
    @Test
    void anExportLandsInTheCallersOwnBucketAsTheCaller() throws Exception {
        assertThat(fx.probe("POST report.json/export", USER_OF_A, () -> fx.reports.export(export(A_BUCKET)))).contains(SUCCEEDED);
        assertThat(fx.storage.stored).contains(A_BUCKET + " as " + USER_OF_A.bearer());
        assertThat(fx.leaks).isEmpty();
    }

    /** A caller with no workspace -- none named, or 0 or -1 -- reads no run, no report row and no statistic of anyone's. */
    @Test
    void aCallerWithNoWorkspaceGetsNothingOfAnyones() throws Exception {
        String before = fx.foreignRows(A, B, C, 0L, -1L);
        for (Long none : NO_WORKSPACE) {
            Caller caller = tenantlessUser(none);
            fx.probe("POST message.json/fetchLogs", caller, () -> fx.messages.fetchLogs(
                new MessageQSearchDto(DAY_BEFORE, DAY_AFTER, null, null, null)));
            fx.probe("GET report.json/runs", caller, () -> fx.reports.runs(DAY_BEFORE, DAY_AFTER));
            assertThat(allTile(fx.probe("GET dashboard.json/jobStatusStatistics", caller,
                () -> fx.dashboard.jobStatusStatistics(DAY_BEFORE, DAY_AFTER)))).isZero();
            fx.probe("GET dashboard.json/userStatistics", caller, () -> fx.dashboard.userStatistics(DAY_BEFORE, DAY_AFTER));
            fx.probe("GET dashboard.json/weeklyHrRunningStatisticsDimension", caller,
                () -> fx.dashboard.weeklyHrRunningStatisticsDimension(DAY, HOUR));
            fx.probe("GET dashboard.json/weeklyHrRunningStatisticsDimensionDetail", caller,
                () -> fx.dashboard.weeklyHrRunningStatisticsDimensionDetail(DAY, HOUR, null, A_JOB));
            assertThat(fx.probe("DELETE message.json/failJobLogs", caller, () -> fx.messages.failJobLogs(A_RUN)))
                .contains(REFUSED);
        }
        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows(A, B, C, 0L, -1L)).isEqualTo(before);
        verifyNoInteractions(fx.bulkAction);
    }
}
