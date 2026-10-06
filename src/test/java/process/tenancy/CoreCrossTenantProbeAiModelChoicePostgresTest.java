package process.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ai.AiPort;
import process.model.dto.AiModelChoiceDto;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static process.tenancy.CoreProbeFixture.A_PIPELINE;
import static process.tenancy.CoreProbeFixture.A;
import static process.tenancy.CoreProbeFixture.B_PIPELINE;
import static process.tenancy.CoreProbeFixture.B;
import static process.tenancy.CoreProbeFixture.B_JOB;
import static process.tenancy.CoreProbeFixture.B_RUN;
import static process.tenancy.CoreProbeFixture.A_TASK;
import static process.tenancy.CoreProbeFixture.B_TASK;
import static process.tenancy.CoreProbeFixture.USER_OF_A;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_A;
import static process.tenancy.CoreProbeFixture.COLLEAGUE_JOB;
import static process.tenancy.CoreProbeFixture.REFUSED;
import static process.tenancy.CoreProbeFixture.COLLEAGUE_RUN;
import static process.tenancy.CoreProbeFixture.A_JOB;
import static process.tenancy.CoreProbeFixture.SUCCEEDED;
import static process.tenancy.CoreProbeFixture.NO_WORKSPACE;
import static process.tenancy.CoreProbeFixture.tenantlessUser;
import process.tenancy.CoreProbeFixture.Caller;

/**
 * MIG-166's cross-tenant probe for the AI model choice (Wave 4, MIG-242's Core part): the schedule setting, "Run
 * with...", a run's AI steps and a pipeline step's own allowed list, called by workspace A's tenant user and admin with
 * workspace B's jobs, runs and tasks -- and with B's model option on A's own job. Against a real etl_job (CoreProbeFixture).
 *
 * ai-service answers per workspace, as it does (every list is read with the workspace in the query): A's AI step may run
 * on option 1204, B's on 2204. A probe passes when nothing of B's comes back or changes, no job of B's is queued, and
 * ai-service is never asked to read or write a list in B's name. Opt-in, like every ScratchPostgres test.
 */
class CoreCrossTenantProbeAiModelChoicePostgresTest {

    private static final long A_OPTION = 1204L;
    private static final long B_OPTION = 2204L;

    private static CoreProbeFixture fx;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_ai_model");
        JdbcTemplate sql = fx.db.jdbc();
        // One AI step on each workspace's pipeline, and B's schedule already set to B's option.
        sql.update("INSERT INTO pipeline_field (pipeline_field_id, pipeline_key, tag_key, label, field_type, tenant_id, prompt_id, run_in, "
            + "position) VALUES (?, ?, 'summary', 'Acme summary', 'ai', ?, 5001, 'server', 5)", A_PIPELINE + 3000, A_PIPELINE, A);
        sql.update("INSERT INTO pipeline_field (pipeline_field_id, pipeline_key, tag_key, label, field_type, tenant_id, prompt_id, run_in, "
            + "position) VALUES (?, ?, 'summary', 'Bravo secret summary', 'ai', ?, 5002, 'server', 5)", B_PIPELINE + 3000, B_PIPELINE, B);
        sql.update("UPDATE source_job SET model_profiles = '{\"summary\":\"2204\"}' WHERE job_id = ?", B_JOB);
        sql.update("INSERT INTO run_ai_step (job_queue_id, step_key, run_in, outcome, model, model_option_id, model_choice, "
            + "model_profile, profile_source) VALUES (?, 'summary', 'server', 'answered', 'bravo-secret-model', 2204, 'override', '2204', "
            + "'schedule')", B_RUN);
    }

    @AfterAll
    static void drop() throws Exception {
        if (fx != null) {
            fx.close();
        }
    }

    @BeforeEach
    void forgetEarlierCalls() throws Exception {
        fx.reset();
        lenient().when(fx.ai.stepModelOptions(eq(A), eq(A_TASK), eq("summary"), eq(5001L)))
            .thenReturn(Collections.singletonList(option(A_OPTION, "Acme Ollama")));
        lenient().when(fx.ai.stepModelOptions(eq(B), eq(B_TASK), eq("summary"), eq(5002L)))
            .thenReturn(Collections.singletonList(option(B_OPTION, "Bravo Hidden Workspace connection")));
    }

    private static AiPort.ModelOption option(long id, String name) {
        AiPort.ModelOption o = new AiPort.ModelOption();
        o.modelOptionId = id;
        o.connectionId = id - 200;
        o.connectionName = name;
        o.isDefault = true;
        o.connectionActive = true;
        return o;
    }

    private static AiModelChoiceDto choice(long jobId, long option) {
        AiModelChoiceDto dto = new AiModelChoiceDto();
        dto.setJobId(jobId);
        dto.getSteps().add(new AiModelChoiceDto.StepChoice("summary", String.valueOf(option)));
        return dto;
    }

    private static AiModelChoiceDto.StepOptions list(long taskId) {
        AiModelChoiceDto.StepOptions dto = new AiModelChoiceDto.StepOptions();
        dto.setTaskDetailId(taskId);
        dto.setStepKey("summary");
        AiPort.ModelOption o = new AiPort.ModelOption();
        o.connectionId = 1004L;
        o.isDefault = true;
        dto.getOptions().add(o);
        return dto;
    }

    @Test
    void noModelChoiceEndpointReadsOrChangesAnotherWorkspacesOrAColleaguesJob() throws Exception {
        String before = fx.foreignRows();

        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            long[] aimed = caller == USER_OF_A ? new long[] {B_JOB, COLLEAGUE_JOB} : new long[] {B_JOB};
            for (long theirs : aimed) {
                assertThat(fx.probe("GET sourceJob.json/aiModelChoice", caller, () -> fx.modelChoice.jobChoices(theirs))).contains(REFUSED);
                assertThat(fx.probe("POST sourceJob.json/aiModelChoice/save", caller,
                    () -> fx.modelChoice.saveSchedule(choice(theirs, A_OPTION)))).contains(REFUSED);
                assertThat(fx.probe("POST sourceJob.json/runSourceJobWith", caller,
                    () -> fx.modelChoice.runWith(choice(theirs, A_OPTION)))).contains(REFUSED);
            }
            long[] runs = caller == USER_OF_A ? new long[] {B_RUN, COLLEAGUE_RUN} : new long[] {B_RUN};
            for (long theirs : runs) {
                assertThat(fx.probe("GET sourceJob.json/aiSteps", caller, () -> fx.modelChoice.runSteps(theirs))).contains(REFUSED);
            }
        }
        // A pipeline step's own list: task editors only, and only their workspace's tasks.
        assertThat(fx.probe("GET sourceTask.json/aiStepModelOptions", ADMIN_OF_A,
            () -> fx.modelChoice.stepOptions(B_TASK, "summary"))).contains(REFUSED);
        assertThat(fx.probe("POST sourceTask.json/aiStepModelOptions/save", ADMIN_OF_A,
            () -> fx.modelChoice.saveStepOptions(list(B_TASK)))).contains(REFUSED);

        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).as("nothing of B's, the platform's or the colleague's changed").isEqualTo(before);
        verify(fx.engine, never()).addManualJobInQueue(argThat(job -> job != null && Long.valueOf(B_JOB).equals(job.getJobId())), any());
        verify(fx.engine, never()).addManualJobInQueue(argThat(job -> job != null && Long.valueOf(COLLEAGUE_JOB).equals(job.getJobId())),
            any());
        verify(fx.ai, never()).stepModelOptions(eq(B), any(), any(), any());
        verify(fx.ai, never()).saveStepModelOptions(eq(B), any(), any(), any(), any(), any());
    }

    /** Workspace A cannot set B's model option on its own job, nor run it with one: it is not on A's list. */
    @Test
    void anotherWorkspacesOptionOnMyOwnJobIsRefusedAndNothingIsWrittenOrRun() throws Exception {
        String scheduleBefore = fx.db.jdbc().queryForObject("SELECT coalesce(model_profiles, '') FROM source_job WHERE job_id = ?",
            String.class, A_JOB);

        assertThat(fx.probe("POST sourceJob.json/aiModelChoice/save(their option on my job)", ADMIN_OF_A,
            () -> fx.modelChoice.saveSchedule(choice(A_JOB, B_OPTION)))).contains(REFUSED).contains("not one AI step <summary> may run on");
        assertThat(fx.probe("POST sourceJob.json/runSourceJobWith(their option on my job)", ADMIN_OF_A,
            () -> fx.modelChoice.runWith(choice(A_JOB, B_OPTION)))).contains(REFUSED);

        assertThat(fx.db.jdbc().queryForObject("SELECT coalesce(model_profiles, '') FROM source_job WHERE job_id = ?", String.class, A_JOB))
            .isEqualTo(scheduleBefore);
        verify(fx.engine, never()).addManualJobInQueue(any(), any());
        assertThat(fx.leaks).isEmpty();
    }

    /** The control: A's own job, on A's own option, is set -- and the answer carries A's list, nothing of B's. */
    @Test
    void myOwnJobOnMyOwnOptionIsSet() {
        assertThat(fx.probe("POST sourceJob.json/aiModelChoice/save(mine)", ADMIN_OF_A,
            () -> fx.modelChoice.saveSchedule(choice(A_JOB, A_OPTION)))).contains(SUCCEEDED);
        assertThat(fx.probe("GET sourceJob.json/aiModelChoice(mine)", ADMIN_OF_A, () -> fx.modelChoice.jobChoices(A_JOB)))
            .contains(SUCCEEDED).contains("Acme Ollama").contains("\"modelOptionId\":\"1204\"");
        assertThat(fx.db.jdbc().queryForObject("SELECT model_profiles FROM source_job WHERE job_id = ?", String.class, A_JOB))
            .isEqualTo("{\"summary\":\"1204\"}");
        assertThat(fx.leaks).isEmpty();
    }

    @Test
    void aCallerWithNoWorkspaceGetsNothingAndSetsNothing() throws Exception {
        String before = fx.foreignRows(A, B, 0L, -1L);
        for (Long none : NO_WORKSPACE) {
            Caller caller = tenantlessUser(none);
            assertThat(fx.probe("GET sourceJob.json/aiModelChoice", caller, () -> fx.modelChoice.jobChoices(A_JOB))).contains(REFUSED);
            assertThat(fx.probe("POST sourceJob.json/aiModelChoice/save", caller,
                () -> fx.modelChoice.saveSchedule(choice(A_JOB, A_OPTION)))).contains(REFUSED);
            assertThat(fx.probe("POST sourceJob.json/runSourceJobWith", caller,
                () -> fx.modelChoice.runWith(choice(A_JOB, A_OPTION)))).contains(REFUSED);
        }
        assertThat(fx.foreignRows(A, B, 0L, -1L)).isEqualTo(before);
        verify(fx.engine, never()).addManualJobInQueue(any(), any());
    }
}
