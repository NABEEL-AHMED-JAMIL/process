package process.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.model.dto.JobAssistantRequestDto;
import process.model.dto.SourceJobDto;
import process.model.dto.SourceTaskDto;
import process.model.enums.Execution;
import process.model.enums.Status;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static process.tenancy.CoreProbeFixture.A_AGENT;
import static process.tenancy.CoreProbeFixture.USER_OF_A;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_A;
import static process.tenancy.CoreProbeFixture.A_TASK;
import static process.tenancy.CoreProbeFixture.B_TASK;
import static process.tenancy.CoreProbeFixture.B_JOB;
import static process.tenancy.CoreProbeFixture.B_JOB_NAMING_USER_A;
import static process.tenancy.CoreProbeFixture.COLLEAGUE_JOB;
import static process.tenancy.CoreProbeFixture.REFUSED;
import static process.tenancy.CoreProbeFixture.B_RUN;
import static process.tenancy.CoreProbeFixture.A_JOB;
import static process.tenancy.CoreProbeFixture.A_RUN;
import static process.tenancy.CoreProbeFixture.COLLEAGUE_RUN;
import static process.tenancy.CoreProbeFixture.USER_B;
import static process.tenancy.CoreProbeFixture.jobSheet;
import static process.tenancy.CoreProbeFixture.B;
import static process.tenancy.CoreProbeFixture.SUCCEEDED;
import static process.tenancy.CoreProbeFixture.A;
import static process.tenancy.CoreProbeFixture.C;
import static process.tenancy.CoreProbeFixture.NO_WORKSPACE;
import static process.tenancy.CoreProbeFixture.tenantlessUser;
import process.tenancy.CoreProbeFixture.Caller;

/**
 * MIG-166's CI cross-tenant probe for Core, jobs: every /sourceJob.json endpoint, called by workspace A's tenant user
 * -- and, for the by-id ones, A's tenant admin too, who skips the job-ownership rule -- with the ids of workspace B's
 * jobs, runs and tasks, against a real etl_job (CoreProbeFixture).
 *
 * The job tables carry tenant_id, and with MIG-166 no foreign key onto tenant decides anything: what keeps B's job
 * from A is the tenant filter, TenantOwnership on every by-id read and write, and QueryService's tenant clause. A
 * tenant user is narrower still -- their own jobs only (JobOwnership) -- so a colleague's job in A is aimed at too.
 *
 * A probe passes when nothing of B's (or the colleague's) comes back, nothing of theirs changed, no job of theirs
 * was queued, skipped, announced or asked about, and a job A creates lands in A whatever the body says. Which
 * endpoints must be here is not left to memory: CoreProbeCoverageTest fails when a controller method is neither
 * probed in one of the CoreCrossTenantProbe classes nor listed there with a reason. Opt-in, like every ScratchPostgres
 * test.
 */
class CoreCrossTenantProbeJobsPostgresTest {

    private static CoreProbeFixture fx;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_jobs");
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

    private static SourceJobDto job(Long jobId, String name, long taskId) {
        SourceJobDto dto = new SourceJobDto();
        dto.setJobId(jobId);
        dto.setJobName(name);
        SourceTaskDto task = new SourceTaskDto();
        task.setTaskDetailId(taskId);
        dto.setTaskDetail(task);
        dto.setExecution(Execution.Manual);
        dto.setPriority(3);
        return dto;
    }

    private static SourceJobDto id(long jobId, Status status) {
        SourceJobDto dto = new SourceJobDto();
        dto.setJobId(jobId);
        dto.setJobStatus(status);
        return dto;
    }

    private static JobAssistantRequestDto question(long jobId) {
        JobAssistantRequestDto dto = new JobAssistantRequestDto();
        dto.setJobId(jobId);
        dto.setAiAgentId(A_AGENT);
        dto.setMessage("why did it fail?");
        return dto;
    }

    @Test
    void noJobEndpointHandsTheCallerAnotherWorkspacesOrAColleaguesJobOrChangesIt() throws Exception {
        String before = fx.foreignRows();

        // Lists, exports and the activity card: nothing of B's, and a tenant user nothing of a colleague's.
        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            fx.probe("GET sourceJob.json/listSourceJob", caller, fx.sourceJobs::listSourceJob);
            fx.probe("GET sourceJob.json/downloadListSourceJob", caller, fx.sourceJobs::downloadListSourceJob);
            String template = fx.probe("GET sourceJob.json/downloadSourceJobTemplateFile", caller,
                fx.sourceJobs::downloadSourceJobTemplateFile);
            assertThat(dropdownValues(template)).as("the template's task picker offers A's tasks only")
                .contains(String.valueOf(A_TASK)).doesNotContain(String.valueOf(B_TASK));
        }
        // B_JOB_NAMING_USER_A names Adam as its assignee: the card is Adam's, but the job is still B's.
        fx.probe("GET sourceJob.json/myActivity", USER_OF_A, () -> fx.sourceJobs.myActivity(50, 90));

        // By id, as the tenant user (whose colleague's job is not theirs) and as the tenant admin (whose is).
        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            long[] aimed = caller == USER_OF_A ? new long[] {B_JOB, B_JOB_NAMING_USER_A, COLLEAGUE_JOB}
                : new long[] {B_JOB, B_JOB_NAMING_USER_A};
            for (long theirs : aimed) {
                assertThat(fx.probe("GET sourceJob.json/fetchSourceJobDetailWithSourceJobId", caller,
                    () -> fx.sourceJobs.fetchSourceJobDetailWithSourceJobId(theirs))).contains(REFUSED);
                assertThat(fx.probe("GET sourceJob.json/fetchSourceJobQueueListWithJobId", caller,
                    () -> fx.sourceJobs.fetchSourceJobQueueListWithJobId(theirs))).contains(REFUSED);
                assertThat(fx.probe("PUT sourceJob.json/updateSourceJob", caller,
                    () -> fx.sourceJobs.updateSourceJob(job(theirs, "Renamed By Acme", A_TASK)))).contains(REFUSED);
                assertThat(fx.probe("PUT sourceJob.json/toggleSourceJobStatus", caller,
                    () -> fx.sourceJobs.toggleSourceJobStatus(id(theirs, Status.Inactive)))).contains(REFUSED);
                assertThat(fx.probe("POST sourceJob.json/runSourceJob", caller,
                    () -> fx.sourceJobs.runSourceJob(id(theirs, null)))).contains(REFUSED);
                assertThat(fx.probe("POST sourceJob.json/skipNextSourceJob", caller,
                    () -> fx.sourceJobs.skipNextSourceJob(id(theirs, null)))).contains(REFUSED);
                assertThat(fx.probe("POST sourceJob.json/askAssistant", caller,
                    () -> fx.sourceJobs.askAssistant(question(theirs)))).contains(REFUSED);
                assertThat(fx.probe("PUT sourceJob.json/deleteSourceJob", caller,
                    () -> fx.sourceJobs.deleteSourceJob(id(theirs, null)))).contains(REFUSED);
            }
            // The audit log names a run and a job: theirs and theirs, theirs through my job, mine through theirs.
            assertThat(fx.probe("GET sourceJob.json/findSourceJobAuditLog", caller,
                () -> fx.sourceJobs.findSourceJobAuditLog(B_RUN, B_JOB))).contains(REFUSED);
            assertThat(fx.probe("GET sourceJob.json/findSourceJobAuditLog(their run through my job)", caller,
                () -> fx.sourceJobs.findSourceJobAuditLog(B_RUN, A_JOB))).contains(REFUSED);
            assertThat(fx.probe("GET sourceJob.json/findSourceJobAuditLog(my run through their job)", caller,
                () -> fx.sourceJobs.findSourceJobAuditLog(A_RUN, B_JOB))).contains(REFUSED);
        }
        assertThat(fx.probe("GET sourceJob.json/findSourceJobAuditLog(colleague's run)", USER_OF_A,
            () -> fx.sourceJobs.findSourceJobAuditLog(COLLEAGUE_RUN, COLLEAGUE_JOB))).contains(REFUSED);

        // A's own job pointed at B's task, or at B's person: refused. (The job itself is A's, so not in the snapshot.)
        assertThat(fx.probe("PUT sourceJob.json/updateSourceJob(my job onto their task)", USER_OF_A,
            () -> fx.sourceJobs.updateSourceJob(job(A_JOB, "Acme Own Job", B_TASK)))).contains(REFUSED);
        SourceJobDto toTheirPerson = job(A_JOB, "Acme Own Job", A_TASK);
        toTheirPerson.setAssignedUserId(USER_B);
        assertThat(fx.probe("PUT sourceJob.json/updateSourceJob(my job to their person)", USER_OF_A,
            () -> fx.sourceJobs.updateSourceJob(toTheirPerson))).contains(REFUSED);

        // Creating against B's task, alone or in a workbook, is refused and writes nothing.
        long jobsBefore = fx.count("SELECT count(*) FROM source_job");
        assertThat(fx.probe("POST sourceJob.json/addSourceJob(their task)", USER_OF_A,
            () -> fx.sourceJobs.addSourceJob(job(null, "acme job on their task", B_TASK)))).contains(REFUSED);
        SourceJobDto assignedToThem = job(null, "acme job for their person", A_TASK);
        assignedToThem.setAssignedUserId(USER_B);
        assertThat(fx.probe("POST sourceJob.json/addSourceJob(their person)", USER_OF_A,
            () -> fx.sourceJobs.addSourceJob(assignedToThem))).contains(REFUSED);
        assertThat(fx.probe("POST sourceJob.json/uploadSourceJob(their task)", USER_OF_A,
            () -> fx.sourceJobs.uploadSourceJob(jobSheet(null, "acme bulk on their task", B_TASK)))).contains(REFUSED);
        assertThat(fx.probe("POST sourceJob.json/uploadSourceJob(mine and theirs)", ADMIN_OF_A,
            () -> fx.sourceJobs.uploadSourceJob(jobSheet(B, "acme bulk mixed", A_TASK, B_TASK)))).contains(REFUSED);
        assertThat(fx.count("SELECT count(*) FROM source_job")).as("a refused create writes no job").isEqualTo(jobsBefore);

        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).as("nothing of B's, the platform's or the colleague's changed").isEqualTo(before);
        for (long theirs : new long[] {B_JOB, B_JOB_NAMING_USER_A, COLLEAGUE_JOB}) {
            verify(fx.engine, never()).addManualJobInQueue(argThat(job -> job != null && Long.valueOf(theirs).equals(job.getJobId())), any());
        }
        verify(fx.engine, never()).workspacePause(eq(B));
        verify(fx.engine, never()).skipManualJobInQueue(any());
        verify(fx.agents, never()).resolveRuntimeConfig(anyLong());
        verify(fx.agents, never()).processAdHoc(any());
        verify(fx.notifications, never()).jobLifecycleChanged(eq(B), any());
        verify(fx.notifications, never()).notificationCreated(eq(B), any());
        verify(fx.openSearch, never()).searchByJobQueueId(eq(B_RUN));
        verify(fx.openSearch, never()).searchByJobQueueId(eq(COLLEAGUE_RUN));
    }

    /** A job is filed under its task's workspace, and the task has to be the caller's: the body's tenantId counts for nothing. */
    @Test
    void aJobTheCallerCreatesLandsInTheirWorkspaceWhateverTheBodyNames() throws Exception {
        SourceJobDto intoB = job(null, "acme job naming b", A_TASK);
        intoB.setTenantId(B);
        assertThat(fx.probe("POST sourceJob.json/addSourceJob(body names B)", USER_OF_A,
            () -> fx.sourceJobs.addSourceJob(intoB))).contains(SUCCEEDED);
        assertThat(fx.probe("POST sourceJob.json/uploadSourceJob(body names B)", USER_OF_A,
            () -> fx.sourceJobs.uploadSourceJob(jobSheet(B, "acme bulk naming b", A_TASK)))).contains(SUCCEEDED);

        assertThat(fx.tenantOf("SELECT tenant_id FROM source_job WHERE job_name = ?", "acme job naming b")).isEqualTo(A);
        assertThat(fx.tenantOf("SELECT tenant_id FROM source_job WHERE job_name = ?", "acme bulk naming b 0")).isEqualTo(A);
        assertThat(fx.tenantOf("SELECT s.tenant_id FROM scheduler s JOIN source_job j ON j.job_id = s.job_id WHERE j.job_name = ?",
            "acme bulk naming b 0")).as("and its schedule with it").isEqualTo(A);
        assertThat(fx.leaks).isEmpty();
        verify(fx.notifications, never()).jobLifecycleChanged(eq(B), any());
        verify(fx.notifications, never()).notificationCreated(eq(B), any());
    }

    /**
     * The null-equals-null case: a token with a role and no workspace (a legacy or broken app_user row), or one naming
     * a tenant id that is no workspace (0, -1), is scoped to nothing. It lists nothing of anyone's -- A's, B's or the
     * platform's -- and cannot create a job against any task.
     */
    @Test
    void aCallerWithNoWorkspaceGetsNothingOfAnyonesAndCreatesNothing() throws Exception {
        String before = fx.foreignRows(A, B, C, 0L, -1L);
        long jobsBefore = fx.count("SELECT count(*) FROM source_job");

        for (Long none : NO_WORKSPACE) {
            Caller caller = tenantlessUser(none);
            fx.probe("GET sourceJob.json/listSourceJob", caller, fx.sourceJobs::listSourceJob);
            fx.probe("GET sourceJob.json/downloadListSourceJob", caller, fx.sourceJobs::downloadListSourceJob);
            fx.probe("GET sourceJob.json/myActivity", caller, () -> fx.sourceJobs.myActivity(50, 90));
            String template = fx.probe("GET sourceJob.json/downloadSourceJobTemplateFile", caller,
                fx.sourceJobs::downloadSourceJobTemplateFile);
            assertThat(dropdownValues(template)).doesNotContain(String.valueOf(A_TASK), String.valueOf(B_TASK));
            assertThat(fx.probe("GET sourceJob.json/fetchSourceJobDetailWithSourceJobId", caller,
                () -> fx.sourceJobs.fetchSourceJobDetailWithSourceJobId(A_JOB))).contains(REFUSED);
            assertThat(fx.probe("POST sourceJob.json/addSourceJob", caller,
                () -> fx.sourceJobs.addSourceJob(job(null, "orphan job " + none, A_TASK)))).contains(REFUSED);
            assertThat(fx.probe("POST sourceJob.json/uploadSourceJob", caller,
                () -> fx.sourceJobs.uploadSourceJob(jobSheet(null, "orphan bulk " + none, A_TASK)))).contains(REFUSED);
        }

        assertThat(fx.leaks).isEmpty();
        assertThat(fx.count("SELECT count(*) FROM source_job")).isEqualTo(jobsBefore);
        assertThat(fx.rowsNaming(0L, -1L)).as("nothing filed under a tenant id that names no workspace").isZero();
        assertThat(fx.foreignRows(A, B, C, 0L, -1L)).isEqualTo(before);
    }

    private static final Pattern LIST = Pattern.compile("<formula1>(?:\"|&quot;)([^<]*?)(?:\"|&quot;)</formula1>");

    /** Every value any explicit-list dropdown in the workbook offers. */
    private static Set<String> dropdownValues(String workbookText) {
        Set<String> values = new HashSet<>();
        Matcher list = LIST.matcher(workbookText);
        while (list.find()) {
            for (String value : list.group(1).split(",")) {
                values.add(value.trim());
            }
        }
        return values;
    }
}
