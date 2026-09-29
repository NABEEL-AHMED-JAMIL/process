package process.engine;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.support.TransactionOperations;
import process.ai.AiStepService;
import process.ai.InMemoryModelChoiceStore;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.service.NotifyService;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.pipeline.DefinitionCodec;
import process.pipeline.DefinitionValidator;
import process.pipeline.Definitions;
import process.pipeline.InMemoryDatasetStore;
import process.pipeline.InMemoryStepStore;
import process.pipeline.PipelineDefinition;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.StepEngine;
import process.pipeline.StepTasks;

import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MIG-230's first acceptance criterion: every existing pipeline is its legacy wrap -- one legacy step that runs today's
 * path -- so every existing job does exactly what it did. Each way a pre-dispatch pass can go (a sendable run, a failed
 * AI step, a configuration fault, a job gone, an exception) is driven three times: with no step engine at all (the
 * build before MIG-230), with the engine wired and no stored definition (every pipeline today), and with the engine
 * wired and the legacy wrap stored. Every call the pass makes to the transaction service, the bulk actions, the AI
 * steps and the mailer -- the whole of what a run's JobStatus, its audit log and its dispatch are made of -- is the same
 * in all three, in the same order with the same arguments; and the engine never touches the run or the worker callback.
 */
class LegacyWrapTest {

    private static final long TENANT = 2924L;
    private static final long JOB_ID = 2834L;
    private static final long QUEUE_ID = 7383L;
    private static final String PAYLOAD = "<pipeline><source_folder>in/claims</source_folder></pipeline>";

    enum Scenario { SENDABLE, AI_STEP_FAILED, NO_TASK_TYPE, JOB_GONE, AI_SEAM_THREW }

    enum Build { NO_ENGINE, ENGINE_NO_DEFINITION, ENGINE_LEGACY_STORED }

    /** Everything a pass did, as the calls it made. */
    private static List<String> pass(Scenario scenario, Build build) {
        TransactionServiceImpl transactionService = mock(TransactionServiceImpl.class);
        BulkAction bulkAction = mock(BulkAction.class);
        AiStepService aiStepService = mock(AiStepService.class);
        JobMail jobMail = mock(JobMail.class);
        NotifyService notify = mock(NotifyService.class);
        PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
        InMemoryStepStore steps = new InMemoryStepStore();

        JobQueue run = new JobQueue();
        run.setJobQueueId(QUEUE_ID);
        run.setJobId(JOB_ID);
        run.setTenantId(TENANT);
        run.setAttempt(1);
        run.setJobStatus(JobStatus.Queue);
        run.setCorrelationId("corr-legacy-7383");
        SourceJob job = job(scenario);

        when(transactionService.claimRunsToPrepare(any(), anyInt(), any())).thenReturn(new ArrayList<>(Collections.singletonList(QUEUE_ID)));
        when(transactionService.findJobQueueByJobQueueId(QUEUE_ID)).thenReturn(Optional.of(run));
        when(transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active))
            .thenReturn(scenario == Scenario.JOB_GONE ? Optional.empty() : Optional.of(job));
        lenient().when(transactionService.markPrepared(anyLong(), any(), any(), any())).thenReturn(1);
        switch (scenario) {
            case AI_STEP_FAILED:
                when(aiStepService.apply(any(AiStepService.Run.class), eq(PAYLOAD)))
                    .thenReturn(new AiStepService.Outcome(null, "AI step <summary> failed: model overloaded"));
                break;
            case AI_SEAM_THREW:
                when(aiStepService.apply(any(AiStepService.Run.class), eq(PAYLOAD))).thenThrow(new IllegalStateException("ai-service down"));
                break;
            default:
                lenient().when(aiStepService.apply(any(AiStepService.Run.class), eq(PAYLOAD)))
                    .thenReturn(new AiStepService.Outcome(PAYLOAD, null));
        }
        if (build == Build.ENGINE_LEGACY_STORED) {
            PipelineDefinitionStore.Stored legacy = new PipelineDefinitionStore.Stored();
            legacy.id = 1001L;
            legacy.tenantId = TENANT;
            legacy.version = 1;
            legacy.json = DefinitionCodec.toJson(PipelineDefinition.legacy("F768927"));
            lenient().when(definitions.latestFor(TENANT, "F768927")).thenReturn(Optional.of(legacy));
        }

        PreDispatchPhase phase = new PreDispatchPhase(transactionService, bulkAction, aiStepService, new InMemoryModelChoiceStore(), jobMail,
            TransactionOperations.withoutTransaction(), new DispatchPipeline.SameThread());
        if (build != Build.NO_ENGINE) {
            StepTasks tasks = Definitions.builtInTasks();
            phase.useStepEngine(new StepEngine(definitions, steps, tasks, new DefinitionValidator(tasks), new InMemoryDatasetStore(), notify,
                transactionService, TransactionOperations.withoutTransaction(), Executors.newSingleThreadExecutor(),
                Executors.newCachedThreadPool(), duration -> { }));
        }
        phase.runPass();

        verifyNoInteractions(notify);
        assertThat(steps.rows).as("no step row for a legacy run").isEmpty();
        assertThat(steps.claimed).as("the engine never takes a legacy run").isEmpty();
        List<String> calls = new ArrayList<>();
        for (Object mock : Arrays.asList(transactionService, bulkAction, aiStepService, jobMail)) {
            calls.addAll(mockingDetails(mock).getInvocations().stream()
                .map(call -> mock.getClass().getSimpleName().replaceAll("\\$.*", "") + "." + call.getMethod().getName()
                    + Arrays.stream(call.getArguments()).map(LegacyWrapTest::describe).collect(Collectors.toList()))
                .collect(Collectors.toList()));
        }
        return calls;
    }

    /** An argument as the comparison sees it: a time only as "a time" (two passes never run in the same instant). */
    private static String describe(Object argument) {
        if (argument instanceof Temporal) {
            return "<time>";
        }
        if (argument instanceof AiStepService.Run) {
            AiStepService.Run r = (AiStepService.Run) argument;
            return "Run[" + r.tenantId + "," + r.pipelineId + "," + r.jobQueueId + "," + r.attempt + "," + r.sourceTaskId + "]";
        }
        // An entity is printed with its fields, a time among them (a closed run's end time): times as "a time" there too.
        return String.valueOf(argument).replaceAll("\\d{4}-\\d{2}-\\d{2}[T ][0-9:.]+", "<time>");
    }

    private static SourceJob job(Scenario scenario) {
        SourceTaskType type = new SourceTaskType();
        type.setSourceTaskTypeId(31L);
        type.setStatus(Status.Active);
        type.setQueueTopicPartition("topic=etl.claims&partitions=[*]");
        SourceTask task = new SourceTask();
        task.setTaskDetailId(8661L);
        task.setPipelineId("F768927");
        task.setTaskPayload(PAYLOAD);
        task.setSourceTaskType(scenario == Scenario.NO_TASK_TYPE ? null : type);
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(TENANT);
        job.setJobStatus(Status.Active);
        job.setTaskDetail(task);
        return job;
    }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void everyExistingPipelineRunsExactlyAsBeforeWithTheEngineWired(Scenario scenario) {
        List<String> before = pass(scenario, Build.NO_ENGINE);
        assertThat(before).as("the pass did something").isNotEmpty();
        assertThat(pass(scenario, Build.ENGINE_NO_DEFINITION)).as("no stored definition").isEqualTo(before);
        assertThat(pass(scenario, Build.ENGINE_LEGACY_STORED)).as("the legacy wrap stored").isEqualTo(before);
    }
}
