package process.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.AiModelChoiceDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.repository.JobQueueRepository;
import process.model.repository.PipelineRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.service.SourceJobService;
import process.security.TenantContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Core's side of the AI model choice (MIG-242): a job's schedule setting and "Run with...", checked before anything is
 * saved or run -- the step an AI step of the job's pipeline, the option one ai-service lists for that step in the JOB's
 * workspace on an active connection -- and a pipeline step's own list, edited through Core by whoever may edit the task.
 * Another workspace's job, task or run reads as not found; another workspace's option is refused in the same words as
 * any option off the list, and nothing is saved or run.
 */
@ExtendWith(MockitoExtension.class)
class AiModelChoiceServiceTest {

    private static final long A = 6601L;
    private static final long B = 6602L;
    private static final long A_JOB = 8671L;
    private static final long B_JOB = 8673L;
    private static final long A_TASK = 8661L;
    private static final long B_TASK = 8662L;
    private static final long ADMIN_A = 7601L;
    private static final long USER_A = 7602L;

    @Mock private SourceJobRepository jobs;
    @Mock private SourceTaskRepository tasks;
    @Mock private JobQueueRepository runs;
    @Mock private PipelineRepository pipelines;
    @Mock private AiPort ai;
    @Mock private SourceJobService sourceJobs;

    private final InMemoryModelChoiceStore store = new InMemoryModelChoiceStore();
    private AiModelChoiceService service;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new AiModelChoiceService(this.jobs, this.tasks, this.runs, this.pipelines, this.ai, this.store, this.sourceJobs);
        for (long[] j : new long[][] {{A_JOB, A, A_TASK}, {B_JOB, B, B_TASK}}) {
            SourceJob job = job(j[0], j[1], j[2]);
            lenient().when(this.jobs.findByJobIdAndJobStatus(j[0], Status.Active)).thenReturn(Optional.of(job));
            lenient().when(this.jobs.findById(j[0])).thenReturn(Optional.of(job));
            lenient().when(this.tasks.findByTaskDetailIdAndTaskStatus(j[2], Status.Active)).thenReturn(Optional.of(job.getTaskDetail()));
            this.store.jobs.put(j[0], j[1]);
            lenient().when(this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("PIPE-" + j[1], j[1], Status.Delete))
                .thenReturn(Collections.singletonList(pipeline(j[1])));
        }
        // ai-service's lists, per workspace: A's summary step may run on 1204 (active) and 1205 (inactive); B's on 2204.
        lenient().when(this.ai.stepModelOptions(eq(A), eq(A_TASK), eq("summary"), eq(1000L)))
            .thenReturn(Arrays.asList(option(1204L, true, true), option(1205L, false, false)));
        lenient().when(this.ai.stepModelOptions(eq(A), eq(A_TASK), eq("caption"), eq(2000L)))
            .thenReturn(Collections.singletonList(option(1301L, true, true)));
        lenient().when(this.ai.stepModelOptions(eq(B), eq(B_TASK), eq("summary"), eq(1000L)))
            .thenReturn(Collections.singletonList(option(2204L, true, true)));
        TenantContext.set(A, "TENANT_ADMIN", ADMIN_A, "alice@acme.example");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static SourceJob job(long jobId, long tenant, long taskId) {
        SourceTask task = new SourceTask();
        task.setTaskDetailId(taskId);
        task.setTenantId(tenant);
        task.setPipelineId("PIPE-" + tenant);
        SourceJob job = new SourceJob();
        job.setJobId(jobId);
        job.setTenantId(tenant);
        job.setJobStatus(Status.Active);
        job.setCreatedBy(ADMIN_A);
        job.setTaskDetail(task);
        return job;
    }

    private static Pipeline pipeline(long tenant) {
        Pipeline p = new Pipeline();
        p.setPipelineId("PIPE-" + tenant);
        p.setTenantId(tenant);
        PipelineField text = new PipelineField();
        text.setTagKey("document"); text.setLabel("Doc"); text.setFieldType("text"); text.setPosition(0);
        PipelineField summary = new PipelineField();
        summary.setTagKey("summary"); summary.setLabel("AI summary"); summary.setFieldType("ai"); summary.setPosition(1);
        summary.setPromptId(1000L); summary.setRunIn("server");
        PipelineField caption = new PipelineField();
        caption.setTagKey("caption"); caption.setLabel("AI caption"); caption.setFieldType("ai"); caption.setPosition(2);
        caption.setPromptId(2000L); caption.setRunIn("worker");
        p.getFields().addAll(Arrays.asList(text, summary, caption));
        return p;
    }

    private static AiPort.ModelOption option(long id, boolean active, boolean isDefault) {
        AiPort.ModelOption o = new AiPort.ModelOption();
        o.modelOptionId = id;
        o.connectionId = 1003L;
        o.connectionName = "Local Ollama";
        o.effectiveModel = "llama3.1:8b";
        o.isDefault = isDefault;
        o.connectionActive = active;
        return o;
    }

    private static AiModelChoiceDto choice(long jobId, String... stepAndOption) {
        AiModelChoiceDto dto = new AiModelChoiceDto();
        dto.setJobId(jobId);
        for (int i = 0; i < stepAndOption.length; i += 2) {
            dto.getSteps().add(new AiModelChoiceDto.StepChoice(stepAndOption[i], stepAndOption[i + 1]));
        }
        return dto;
    }

    // ---- the schedule's setting ---------------------------------------------------------------------------------

    @Test
    void aScheduleSettingIsSavedPerStepAndABlankStepIsItsDefault() {
        ResponseDto answer = this.service.saveSchedule(choice(A_JOB, "summary", "1204", "caption", ""));

        assertThat(answer.getStatus()).isEqualTo("SUCCESS");
        assertThat(this.store.schedules.get(A_JOB)).isEqualTo("{\"summary\":\"1204\"}");
    }

    @Test
    void savingNoStepsPutsEveryStepBackOnItsDefault() {
        this.store.schedules.put(A_JOB, "{\"summary\":\"1204\"}");

        assertThat(this.service.saveSchedule(choice(A_JOB)).getStatus()).isEqualTo("SUCCESS");
        assertThat(this.store.schedules.get(A_JOB)).isNull();
    }

    @Test
    void theJobsStepsComeWithTheirSettingAndTheirAllowedModels() {
        this.store.schedules.put(A_JOB, "{\"summary\":\"1204\"}");

        ResponseDto answer = this.service.jobChoices(A_JOB);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) answer.getData();
        assertThat(data).containsEntry("jobId", A_JOB).containsEntry("taskDetailId", A_TASK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) data.get("steps");
        assertThat(steps).extracting(s -> s.get("stepKey")).containsExactly("summary", "caption");
        assertThat(steps.get(0)).containsEntry("modelOptionId", "1204").containsEntry("runIn", "server").containsEntry("promptId", 1000L);
        assertThat(steps.get(1)).doesNotContainKey("modelOptionId").containsEntry("runIn", "worker");
        assertThat((List<?>) steps.get(0).get("options")).hasSize(2);
    }

    @Test
    void whenAiServiceCannotBeAskedTheStepSaysSoInsteadOfListingNothing() throws Exception {
        when(this.ai.stepModelOptions(eq(A), eq(A_TASK), eq("caption"), eq(2000L)))
            .thenThrow(new AiPort.AiUnavailableException("down", null));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) ((Map<String, Object>) this.service.jobChoices(A_JOB).getData()).get("steps");

        assertThat(steps.get(1)).containsKey("optionsError").doesNotContainKey("options");
    }

    @Test
    void anOptionOffTheStepsListIsRefusedAndNothingIsSaved() {
        ResponseDto answer = this.service.saveSchedule(choice(A_JOB, "summary", "9999"));

        assertThat(answer.getStatus()).isEqualTo("ERROR");
        assertThat(answer.getMessage()).isEqualTo("That model is not one AI step <summary> may run on. Pick one from the step's allowed models.");
        assertThat(this.store.schedules).doesNotContainKey(A_JOB);
    }

    /** Workspace A cannot put B's option on its job: B's option is not on A's list, and reads like any other refusal. */
    @Test
    void anotherWorkspacesOptionIsRefusedInTheSameWords() throws Exception {
        ResponseDto foreign = this.service.saveSchedule(choice(A_JOB, "summary", "2204"));
        ResponseDto unknown = this.service.saveSchedule(choice(A_JOB, "summary", "9999"));

        assertThat(foreign.getStatus()).isEqualTo("ERROR");
        assertThat(foreign.getMessage()).isEqualTo(unknown.getMessage());
        assertThat(this.store.schedules).doesNotContainKey(A_JOB);
        verify(this.ai, never()).stepModelOptions(eq(B), anyLong(), anyString(), anyLong());
    }

    @Test
    void anotherWorkspacesJobIsNotFoundAndItsSettingUntouched() throws Exception {
        this.store.schedules.put(B_JOB, "{\"summary\":\"2204\"}");

        assertThat(this.service.saveSchedule(choice(B_JOB, "summary", "1204")).getMessage()).isEqualTo(AiModelChoiceService.JOB_NOT_FOUND);
        assertThat(this.service.jobChoices(B_JOB).getMessage()).isEqualTo(AiModelChoiceService.JOB_NOT_FOUND);
        assertThat(this.service.runWith(choice(B_JOB, "summary", "2204")).getMessage()).isEqualTo(AiModelChoiceService.JOB_NOT_FOUND);
        assertThat(this.store.schedules.get(B_JOB)).isEqualTo("{\"summary\":\"2204\"}");
        verifyNoInteractions(this.sourceJobs);
    }

    /** A tenant user sees only jobs that name them (JobOwnership): a colleague's job reads as not found. */
    @Test
    void aTenantUserCannotSetAColleaguesJob() {
        TenantContext.set(A, "TENANT_USER", USER_A, "adam@acme.example");

        assertThat(this.service.saveSchedule(choice(A_JOB, "summary", "1204")).getMessage()).isEqualTo(AiModelChoiceService.JOB_NOT_FOUND);
        assertThat(this.store.schedules).doesNotContainKey(A_JOB);
    }

    @Test
    void aStepThatIsNotAnAiStepOfTheJobsPipelineIsRefused() {
        assertThat(this.service.saveSchedule(choice(A_JOB, "document", "1204")).getMessage())
            .isEqualTo("This job's pipeline has no AI step <document>.");
        assertThat(this.service.saveSchedule(choice(A_JOB, "summary", "1204", "summary", "")).getMessage())
            .isEqualTo("AI step <summary> is named twice.");
        assertThat(this.service.saveSchedule(choice(A_JOB, "summary", "12a")).getStatus()).isEqualTo("ERROR");
        assertThat(this.store.schedules).doesNotContainKey(A_JOB);
    }

    @Test
    void anOptionOnAnInactiveConnectionIsRefused() {
        assertThat(this.service.saveSchedule(choice(A_JOB, "summary", "1205")).getMessage()).contains("connection is not active");
    }

    @Test
    void whenAiServiceCannotConfirmNothingIsSaved() throws Exception {
        when(this.ai.stepModelOptions(eq(A), eq(A_TASK), eq("summary"), eq(1000L))).thenThrow(new AiPort.AiUnavailableException("down", null));

        assertThat(this.service.saveSchedule(choice(A_JOB, "summary", "1204")).getMessage()).isEqualTo(AiModelChoiceService.AI_UNREACHABLE);
        assertThat(this.store.schedules).doesNotContainKey(A_JOB);
    }

    // ---- Run with... ----------------------------------------------------------------------------------------------

    @Test
    void runWithRunsTheJobNowWithTheCheckedChoice() throws Exception {
        when(this.sourceJobs.runSourceJob(argThat(dto -> dto.getJobId() == A_JOB), eq("{\"caption\":\"1301\",\"summary\":\"1204\"}")))
            .thenReturn(new ResponseDto("SUCCESS", "SourceJob job successfully added into queue."));

        ResponseDto answer = this.service.runWith(choice(A_JOB, "summary", "1204", "caption", "1301"));

        assertThat(answer.getStatus()).isEqualTo("SUCCESS");
        assertThat(this.store.schedules).as("a run's choice is not the schedule's").doesNotContainKey(A_JOB);
    }

    @Test
    void runWithNothingChosenIsRunNow() throws Exception {
        when(this.sourceJobs.runSourceJob(any(), isNull())).thenReturn(new ResponseDto("SUCCESS", "queued"));

        assertThat(this.service.runWith(choice(A_JOB)).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void runWithARefusedChoiceRunsNothing() throws Exception {
        assertThat(this.service.runWith(choice(A_JOB, "summary", "2204")).getStatus()).isEqualTo("ERROR");
        verifyNoInteractions(this.sourceJobs);
    }

    // ---- a run's AI steps ---------------------------------------------------------------------------------------

    private static JobQueue run(long id, long jobId, long tenant) {
        JobQueue run = new JobQueue();
        run.setJobQueueId(id);
        run.setJobId(jobId);
        run.setTenantId(tenant);
        return run;
    }

    @Test
    void aRunsAiStepsAreReadForItsOwnWorkspaceOnly() {
        when(this.runs.findById(8681L)).thenReturn(Optional.of(run(8681L, A_JOB, A)));
        when(this.runs.findById(8683L)).thenReturn(Optional.of(run(8683L, B_JOB, B)));
        RunAiStep step = new RunAiStep();
        step.jobQueueId = 8681L; step.attempt = 1; step.stepKey = "summary"; step.model = "llama3.1:8b";
        this.store.recordSteps(Collections.singletonList(step));

        assertThat((List<?>) this.service.runSteps(8681L).getData()).hasSize(1);
        assertThat(this.service.runSteps(8683L).getMessage()).isEqualTo(AiModelChoiceService.RUN_NOT_FOUND);
        assertThat(this.service.runSteps(null).getMessage()).isEqualTo(AiModelChoiceService.RUN_NOT_FOUND);
    }

    // ---- a pipeline step's own list -------------------------------------------------------------------------------

    private static AiModelChoiceDto.StepOptions stepOptions(long taskId, String stepKey, AiPort.ModelOption... options) {
        AiModelChoiceDto.StepOptions dto = new AiModelChoiceDto.StepOptions();
        dto.setTaskDetailId(taskId);
        dto.setStepKey(stepKey);
        dto.setOptions(Arrays.asList(options));
        return dto;
    }

    @Test
    void aStepsListIsSavedThroughAiServiceInTheTasksWorkspace() throws Exception {
        AiPort.ModelOption local = option(0, true, true);
        local.modelOptionId = null;
        when(this.ai.saveStepModelOptions(eq(A), eq(A_TASK), eq("summary"), eq(1000L), any(), eq(ADMIN_A)))
            .thenReturn(new ResponseDto("SUCCESS", "saved"));

        assertThat(this.service.saveStepOptions(stepOptions(A_TASK, "summary", local)).getStatus()).isEqualTo("SUCCESS");
        assertThat(this.service.stepOptions(A_TASK, "summary").getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void anotherWorkspacesTaskIsNotFoundAndAiServiceIsNotAsked() throws Exception {
        assertThat(this.service.saveStepOptions(stepOptions(B_TASK, "summary", option(1, true, true))).getMessage())
            .isEqualTo(AiModelChoiceService.TASK_NOT_FOUND);
        assertThat(this.service.stepOptions(B_TASK, "summary").getMessage()).isEqualTo(AiModelChoiceService.TASK_NOT_FOUND);
        verify(this.ai, never()).saveStepModelOptions(any(), any(), any(), any(), any(), any());
        verify(this.ai, never()).stepModelOptions(eq(B), any(), any(), any());
    }

    @Test
    void aStepsListIsCheckedBeforeAiServiceIsAsked() throws Exception {
        AiPort.ModelOption noConnection = new AiPort.ModelOption();
        assertThat(this.service.saveStepOptions(stepOptions(A_TASK, "document", option(1, true, true))).getMessage())
            .isEqualTo("This task's pipeline has no AI step <document>.");
        assertThat(this.service.saveStepOptions(stepOptions(A_TASK, "summary", noConnection)).getMessage())
            .isEqualTo("Every allowed model names a model connection.");
        assertThat(this.service.saveStepOptions(stepOptions(A_TASK, "summary", option(1, true, true), option(2, true, true))).getMessage())
            .isEqualTo("Only one allowed model can be the default.");
        verify(this.ai, never()).saveStepModelOptions(any(), any(), any(), any(), any(), any());
    }
}
