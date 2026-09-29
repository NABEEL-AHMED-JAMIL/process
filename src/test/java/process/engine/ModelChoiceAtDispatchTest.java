package process.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;
import process.ai.AiPort;
import process.ai.AiStepService;
import process.ai.InMemoryModelChoiceStore;
import process.ai.RunAiStep;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.repository.PipelineRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wave 4 (MIG-242's Core part), at dispatch: a run's AI steps ask ai-service for the model the run was started with --
 * its "Run with..." for the steps it names, else its schedule's setting, else the step's default -- naming the pipeline's
 * source task so that step's own allowed list is used. What each step asked for and what ai-service answered it ran on
 * (model, connection, option, how it was chosen, prompt version) is kept in run_ai_step with the verdict. A model
 * ai-service refuses (422) fails the run with ai-service's own words, once: no retry, and no "continue with it empty".
 */
@ExtendWith(MockitoExtension.class)
class ModelChoiceAtDispatchTest {

    private static final long TENANT = 2905L;
    private static final long JOB_ID = 1196L;
    private static final long QUEUE_ID = 5073L;
    private static final long TASK_ID = 8801L;
    private static final String STORED = "<pipeline><document>notes</document></pipeline>";
    private static final String NOT_ALLOWED = "That model is not one this step may run on. Pick one from the step's allowed models.";

    @Mock private BulkAction bulkAction;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private JobMail jobMail;
    @Mock private PipelineRepository pipelines;
    @Mock private AiPort ai;

    private final InMemoryModelChoiceStore store = new InMemoryModelChoiceStore();
    private PreDispatchPhase phase;
    private JobQueue run;
    private SourceJob job;

    @BeforeEach
    void setUp() throws Exception {
        AiStepService steps = new AiStepService(this.pipelines, this.ai);
        this.phase = new PreDispatchPhase(this.transactionService, this.bulkAction, steps, this.store, this.jobMail,
            TransactionOperations.withoutTransaction(), new DispatchPipeline.SameThread());
        this.run = new JobQueue();
        this.run.setJobQueueId(QUEUE_ID);
        this.run.setJobId(JOB_ID);
        this.run.setJobStatus(JobStatus.Queue);
        this.run.setAttempt(2);
        this.job = job();
        lenient().when(this.transactionService.markPrepared(anyLong(), any(), any(), any())).thenReturn(1);
        Pipeline pipeline = new Pipeline();
        pipeline.setPipelineId("F1");
        pipeline.setTenantId(TENANT);
        pipeline.setStatus(Status.Active);
        PipelineField document = new PipelineField();
        document.setTagKey("document"); document.setLabel("Doc"); document.setFieldType("text"); document.setPosition(0);
        PipelineField summary = new PipelineField();
        summary.setTagKey("summary"); summary.setLabel("AI summary"); summary.setFieldType("ai"); summary.setPosition(1);
        summary.setPromptId(1000L); summary.setVariableMap("{\"text\":\"document\"}"); summary.setOnError("continue"); summary.setRunIn("server");
        PipelineField caption = new PipelineField();
        caption.setTagKey("caption"); caption.setLabel("AI caption"); caption.setFieldType("ai"); caption.setPosition(2);
        caption.setPromptId(2000L); caption.setVariableMap("{}"); caption.setRunIn("worker");
        pipeline.getFields().addAll(Arrays.asList(document, summary, caption));
        when(this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete)).thenReturn(Collections.singletonList(pipeline));
        AiPort.PromptInfo prompt = new AiPort.PromptInfo();
        prompt.promptId = 2000L; prompt.promptUuid = "uuid-2000"; prompt.name = "Caption"; prompt.version = 4; prompt.status = "Active";
        prompt.tenantId = TENANT;
        lenient().when(this.ai.prompts(any())).thenReturn(Collections.singletonMap(2000L, prompt));
    }

    private static SourceJob job() {
        SourceTaskType type = new SourceTaskType();
        type.setSourceTaskTypeId(31L);
        type.setStatus(Status.Active);
        type.setQueueTopicPartition("topic=etl.jobs&partitions=[*]");
        SourceTask task = new SourceTask();
        task.setTaskDetailId(TASK_ID);
        task.setPipelineId("F1");
        task.setTaskPayload(STORED);
        task.setSourceTaskType(type);
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(TENANT);
        job.setJobStatus(Status.Active);
        job.setTaskDetail(task);
        return job;
    }

    private static AiPort.StepResult ranOn(String model, long optionId, String choice) {
        AiPort.StepResult r = new AiPort.StepResult();
        r.status = "ok"; r.output = "a summary"; r.promptId = 1000L; r.promptName = "Summarise"; r.promptVersion = 3;
        r.latencyMs = 800; r.tokensIn = 20; r.tokensOut = 9;
        r.model = model; r.connectionId = 1003L; r.modelOptionId = optionId; r.modelChoice = choice;
        return r;
    }

    private RunAiStep recorded(String stepKey) {
        return this.store.stepsOfRun(QUEUE_ID).stream().filter(s -> s.stepKey.equals(stepKey)).findFirst().orElse(null);
    }

    @Test
    void aScheduleSetToAModelRunsOnItAndTheRunSaysWhatItRanOn() {
        this.job.setModelProfiles("{\"summary\":\"1204\"}");
        when(this.ai.runStep(eq(TENANT), eq(QUEUE_ID), eq("summary"), eq(1000L), anyMap(), eq("1204"), eq(TASK_ID)))
            .thenReturn(ranOn("llama3.1:8b", 1204L, "override"));

        this.phase.prepare(Optional.of(this.job), this.run);

        verify(this.transactionService).markPrepared(eq(QUEUE_ID), contains("<summary>a summary</summary>"), any(), any());
        RunAiStep summary = this.recorded("summary");
        assertThat(summary.runIn).isEqualTo(RunAiStep.SERVER);
        assertThat(summary.attempt).as("the run's attempt").isEqualTo(2);
        assertThat(summary.modelProfile).isEqualTo("1204");
        assertThat(summary.profileSource).isEqualTo("schedule");
        assertThat(summary.outcome).isEqualTo(RunAiStep.ANSWERED);
        assertThat(summary.model).isEqualTo("llama3.1:8b");
        assertThat(summary.connectionId).isEqualTo(1003L);
        assertThat(summary.modelOptionId).isEqualTo(1204L);
        assertThat(summary.modelChoice).isEqualTo("override");
        assertThat(summary.promptId).isEqualTo(1000L);
        assertThat(summary.promptVersion).isEqualTo(3);
        RunAiStep caption = this.recorded("caption");
        assertThat(caption.runIn).isEqualTo(RunAiStep.WORKER);
        assertThat(caption.outcome).isEqualTo(RunAiStep.HANDED);
        assertThat(caption.modelProfile).as("the schedule names no model for it: its default").isNull();
        assertThat(caption.promptVersion).isEqualTo(4);
        verify(this.bulkAction).saveJobAuditLogs(QUEUE_ID,
            "AI step <summary>: Summarise v3 answered in 0.8 s (20 in, 9 out tokens), on llama3.1:8b (override).");
    }

    @Test
    void runWithWinsOverTheScheduleForTheStepsItNames() {
        this.job.setModelProfiles("{\"summary\":\"1204\",\"caption\":\"2100\"}");
        this.run.setModelProfiles("{\"summary\":\"1300\"}");
        when(this.ai.runStep(eq(TENANT), eq(QUEUE_ID), eq("summary"), eq(1000L), anyMap(), eq("1300"), eq(TASK_ID)))
            .thenReturn(ranOn("gpt-4o-mini", 1300L, "override"));

        this.phase.prepare(Optional.of(this.job), this.run);

        assertThat(this.recorded("summary").modelProfile).isEqualTo("1300");
        assertThat(this.recorded("summary").profileSource).isEqualTo("run");
        assertThat(this.recorded("caption").modelProfile).as("not named by the run: the schedule's").isEqualTo("2100");
        assertThat(this.recorded("caption").profileSource).isEqualTo("schedule");
        verify(this.bulkAction).saveJobAuditLogs(QUEUE_ID,
            "AI step <caption>: handed to the worker (Caption v4), asked to run on model option 2100 (schedule).");
    }

    @Test
    void noSettingAsksForNothingAndTheStepRunsOnItsDefault() {
        when(this.ai.runStep(eq(TENANT), eq(QUEUE_ID), eq("summary"), eq(1000L), anyMap(), isNull(), eq(TASK_ID)))
            .thenReturn(ranOn("llama3.1:8b", 1100L, "default"));

        this.phase.prepare(Optional.of(this.job), this.run);

        assertThat(this.recorded("summary").modelProfile).isNull();
        assertThat(this.recorded("summary").profileSource).isNull();
        assertThat(this.recorded("summary").modelChoice).isEqualTo("default");
    }

    /**
     * The acceptance: a "Run with..." ai-service refuses surfaces as a failed run carrying ai-service's message --
     * even on a step whose rule is "continue", and never as a retry: the model call is asked for exactly once.
     */
    @Test
    void aRefusedModelFailsTheRunOnceWithAiServicesMessage() {
        this.run.setModelProfiles("{\"summary\":\"9999\"}");
        AiPort.StepResult refused = AiPort.StepResult.failed(NOT_ALLOWED);
        refused.refused = true;
        when(this.ai.runStep(eq(TENANT), eq(QUEUE_ID), eq("summary"), eq(1000L), anyMap(), eq("9999"), eq(TASK_ID))).thenReturn(refused);

        this.phase.prepare(Optional.of(this.job), this.run);

        String why = "Job 1196: AI step <summary> could not run on model option 9999 (asked by the run): " + NOT_ALLOWED;
        verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Failed, why);
        verify(this.bulkAction, never()).scheduleRetry(any(JobQueue.class), anyString());
        verify(this.transactionService, never()).markPrepared(anyLong(), any(), any(), any());
        verify(this.ai, times(1)).runStep(any(), any(), any(), any(), any(), any(), any());
        RunAiStep summary = this.recorded("summary");
        assertThat(summary.outcome).isEqualTo(RunAiStep.REFUSED);
        assertThat(summary.error).isEqualTo(NOT_ALLOWED);
        assertThat(summary.model).isNull();
    }

    /** An ordinary failure still follows the step's rule: "continue" leaves the tag empty and says so. */
    @Test
    void anOrdinaryFailedStepStillFollowsItsOnErrorRule() {
        when(this.ai.runStep(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(AiPort.StepResult.failed("The provider timed out."));

        this.phase.prepare(Optional.of(this.job), this.run);

        verify(this.transactionService).markPrepared(eq(QUEUE_ID), contains("<summary/>"), any(), any());
        assertThat(this.recorded("summary").outcome).isEqualTo(RunAiStep.FAILED);
        assertThat(this.recorded("summary").error).isEqualTo("The provider timed out.");
    }
}
