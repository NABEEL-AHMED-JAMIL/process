package process.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.ai.InMemoryModelChoiceStore;
import process.ai.RunAiStep;
import process.model.dto.ResponseDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.security.TenantContext;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** MIG-230: the timeline a run shows -- its steps per attempt, or, for a legacy run, the run as its one step. */
class StepTimelineServiceTest {

    private static final long TENANT = 2924L;
    private static final long JOB = 2834L;
    private static final long RUN = 7383L;

    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final InMemoryModelChoiceStore ai = new InMemoryModelChoiceStore();
    private final StepTimelineService service = new StepTimelineService(this.runs, this.jobs, this.steps, this.ai);
    private JobQueue run;
    private SourceJob job;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 7L, "admin@example");
        this.run = new JobQueue();
        this.run.setJobQueueId(RUN);
        this.run.setJobId(JOB);
        this.run.setTenantId(TENANT);
        this.run.setAttempt(1);
        this.run.setJobStatus(JobStatus.Completed);
        this.run.setStartTime(LocalDateTime.of(2026, 9, 28, 10, 0, 0));
        this.run.setEndTime(LocalDateTime.of(2026, 9, 28, 10, 2, 30));
        this.run.setJobStatusMessage("Job 2834 completed: 12 files.");
        this.job = new SourceJob();
        this.job.setJobId(JOB);
        this.job.setTenantId(TENANT);
        this.job.setJobStatus(Status.Active);
        when(this.runs.findById(RUN)).thenReturn(Optional.of(this.run));
        when(this.jobs.findById(JOB)).thenReturn(Optional.of(this.job));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> timeline(Integer attempt) {
        ResponseDto answer = this.service.timeline(RUN, attempt);
        assertThat(answer.getStatus()).as(answer.getMessage()).isEqualTo("SUCCESS");
        return (Map<String, Object>) answer.getData();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLegacyRunIsItsOneLegacyStepWithItsAiStepsAndTheRunsLog() {
        RunAiStep handed = new RunAiStep();
        handed.jobQueueId = RUN;
        handed.attempt = 1;
        handed.stepKey = "summary";
        handed.runIn = RunAiStep.WORKER;
        handed.promptId = 5001L;
        handed.outcome = RunAiStep.HANDED;
        this.ai.recordSteps(Arrays.asList(handed));

        Map<String, Object> timeline = this.timeline(null);

        assertThat(timeline).containsEntry("legacy", true).containsEntry("attempt", 1).containsEntry("runStatus", JobStatus.Completed);
        List<Map<String, Object>> list = (List<Map<String, Object>>) timeline.get("steps");
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).containsEntry("key", "legacy").containsEntry("task", "legacy").containsEntry("status", "Completed")
            .containsEntry("durationMs", 150000L).containsEntry("statusMessage", "Job 2834 completed: 12 files.")
            .containsEntry("log", "run");
        assertThat((List<RunAiStep>) timeline.get("aiSteps")).extracting(step -> step.stepKey).containsExactly("summary");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStepRunShowsTheLatestAttemptByDefaultAndAnyAttemptItHad() {
        this.steps.plan(RUN, 1, 1001L, Arrays.asList(new StepStore.Planned(0, "read", "sample", "fail"),
            new StepStore.Planned(1, "call", "api", "fail")));
        List<Long> second = this.steps.plan(RUN, 2, 1001L, Arrays.asList(new StepStore.Planned(0, "read", "sample", "fail"),
            new StepStore.Planned(1, "call", "api", "fail")));
        this.steps.started(second.get(0), 0L);
        this.steps.ended(second.get(0), "Completed", 3L, null, "Completed in 0.1 s: 0 in, 3 out.");
        this.steps.dataset(second.get(0), "output", "datasets/7383/2/read/output.json", 3, "[\"id\"]", null);
        this.steps.ended(second.get(1), "Failed", null, "{\"message\":\"503\",\"tries\":2,\"timedOut\":false}", "Failed after 2 tries: 503");
        this.run.setAttempt(2);

        Map<String, Object> latest = this.timeline(null);
        assertThat(latest).containsEntry("legacy", false).containsEntry("attempt", 2).containsEntry("attempts", Arrays.asList(1, 2))
            .containsEntry("pipelineDefinitionId", 1001L);
        List<Map<String, Object>> list = (List<Map<String, Object>>) latest.get("steps");
        assertThat(list).extracting(step -> step.get("key") + ":" + step.get("status")).containsExactly("read:Completed", "call:Failed");
        List<Map<String, Object>> datasets = (List<Map<String, Object>>) list.get(0).get("datasets");
        assertThat(datasets).hasSize(1);
        assertThat(datasets.get(0)).containsEntry("name", "output").containsEntry("rowCount", 3L)
            .containsEntry("columns", Arrays.asList("id")).doesNotContainKey("storageKey");
        assertThat((Map<String, Object>) list.get(1).get("error")).containsEntry("message", "503").containsEntry("tries", 2);

        assertThat((List<Map<String, Object>>) this.timeline(1).get("steps")).extracting(step -> step.get("status"))
            .containsExactly("Queue", "Queue");
        assertThat(this.service.timeline(RUN, 3).getMessage()).isEqualTo("This run has no attempt 3; it has [1, 2].");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStepsLogIsReadByItsIdWhenItsRunIsTheCallers() {
        List<Long> ids = this.steps.plan(RUN, 1, 1001L, Arrays.asList(new StepStore.Planned(0, "read", "sample", "fail")));
        this.steps.log(ids.get(0), 1, "INFO", "3 sample row(s).");
        ResponseDto answer = this.service.log(ids.get(0));
        assertThat(answer.getStatus()).isEqualTo("SUCCESS");
        assertThat((List<StepStore.LogLine>) ((Map<String, Object>) answer.getData()).get("lines")).extracting(line -> line.message)
            .containsExactly("3 sample row(s).");

        assertThat(this.service.log(99L).getMessage()).isEqualTo("Step not found with stepExecutionId.");
        assertThat(this.service.log(null).getMessage()).isEqualTo("Step not found with stepExecutionId.");
        this.job.setJobStatus(Status.Delete);
        assertThat(this.service.log(ids.get(0)).getMessage()).as("a deleted job has no history to show")
            .isEqualTo("Step not found with stepExecutionId.");
        assertThat(this.service.timeline(RUN, null).getMessage()).isEqualTo("Run not found with jobQueueId.");
    }

    @Test
    void aRunOfAnotherWorkspaceOrNoneIsNotFound() {
        TenantContext.set(4242L, "TENANT_ADMIN", 8L, "other@example");
        assertThat(this.service.timeline(RUN, null).getMessage()).isEqualTo("Run not found with jobQueueId.");
        assertThat(this.service.timeline(null, null).getMessage()).isEqualTo("Run not found with jobQueueId.");
    }
}
