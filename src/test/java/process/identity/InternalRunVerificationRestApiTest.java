package process.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.repository.JobQueueRepository;
import process.model.repository.PipelineRepository;
import process.model.repository.SourceJobRepository;
import process.security.RunCallbackTokens;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MIG-189: a worker's run token is Core's to check, so the AI service asks here rather than reading
 * job_queue itself. The answer carries what AI needs to run the step without any Core table --
 * the job, its tenant, its pipeline and the steps that pipeline hands to a worker -- and a refusal
 * carries nothing at all: the six reasons are logged here and never disclosed, because "expired" or
 * "wrong job" would confirm a guess. Both variants exist, because a usage report may arrive after
 * the run is over and a callback may not.
 */
class InternalRunVerificationRestApiTest {

    private static final String SERVICE = "t0ken";

    private final RunCallbackTokens tokens = mock(RunCallbackTokens.class);
    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private final PipelineRepository pipelines = mock(PipelineRepository.class);
    private final InternalRunVerificationRestApi api = new InternalRunVerificationRestApi(this.tokens, this.runs, this.jobs,
        this.pipelines, SERVICE);

    private static Map<String, Object> body(Long jobId, String token, String variant) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("token", token);
        body.put("variant", variant);
        return body;
    }

    private static PipelineField step(String tag, String runIn, long promptId, int position) {
        PipelineField field = new PipelineField();
        field.setFieldType("ai");
        field.setTagKey(tag);
        field.setRunIn(runIn);
        field.setPromptId(promptId);
        field.setPosition(position);
        return field;
    }

    private void aLiveRun(JobStatus status) {
        JobQueue run = new JobQueue();
        run.setJobQueueId(900L);
        run.setJobId(77L);
        run.setJobStatus(status);
        when(this.runs.findById(900L)).thenReturn(Optional.of(run));
        SourceTask task = new SourceTask();
        task.setPipelineId("F100001");
        SourceJob job = new SourceJob();
        job.setJobId(77L);
        job.setTenantId(2901L);
        job.setTaskDetail(task);
        when(this.jobs.findByJobIdAndJobStatus(77L, Status.Active)).thenReturn(Optional.of(job));
        Pipeline pipeline = new Pipeline();
        pipeline.setFields(Arrays.asList(step("summary", "server", 5, 1), step("caption", "worker", 6, 2), step("tags", "worker", 7, 3)));
        when(this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F100001", 2901L, Status.Delete))
            .thenReturn(Collections.singletonList(pipeline));
    }

    @Test
    void withoutTheServiceTokenNothingIsAnswered() {
        assertThat(this.api.verifyCallback(null, 900L, body(77L, "w", "callback")).getStatusCodeValue()).isEqualTo(401);
        assertThat(this.api.verifyCallback("wrong", 900L, body(77L, "w", "callback")).getStatusCodeValue()).isEqualTo(401);
        verifyNoInteractions(this.tokens, this.runs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aValidTokenAnswersWhoseRunItIsAndTheStepsItHandsToAWorker() {
        aLiveRun(JobStatus.Running);
        when(this.tokens.verify(77L, 900L, "w")).thenReturn(Optional.empty());

        ResponseEntity<?> answer = this.api.verifyCallback(SERVICE, 900L, body(77L, "w", "callback"));

        Map<String, Object> verdict = (Map<String, Object>) answer.getBody();
        assertThat(verdict).containsEntry("valid", true).containsEntry("jobId", 77L).containsEntry("jobQueueId", 900L)
            .containsEntry("tenantId", 2901L).containsEntry("pipelineId", "F100001").containsEntry("terminal", false);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) verdict.get("workerSteps");
        assertThat(steps).extracting(s -> s.get("stepTag")).containsExactly("caption", "tags");
        assertThat(steps).extracting(s -> s.get("promptId")).containsExactly(6L, 7L);
    }

    /** One flat answer for all six reasons: the reason is for the log, not for the caller. */
    @Test
    @SuppressWarnings("unchecked")
    void everyRefusalLooksTheSame() {
        aLiveRun(JobStatus.Running);
        for (RunCallbackTokens.Refusal reason : RunCallbackTokens.Refusal.values()) {
            when(this.tokens.verify(anyLong(), anyLong(), anyString())).thenReturn(Optional.of(reason));
            Map<String, Object> verdict = (Map<String, Object>) this.api.verifyCallback(SERVICE, 900L, body(77L, "w", "callback")).getBody();
            assertThat(verdict).as(reason.name()).containsOnlyKeys("valid").containsEntry("valid", false);
        }
    }

    /** Status stops callbacks, expiry stops reports: a finished run may still report its usage. */
    @Test
    @SuppressWarnings("unchecked")
    void theReportVariantAcceptsAFinishedRunAndSaysItIsOver() {
        aLiveRun(JobStatus.Completed);
        when(this.tokens.verifyForReport(77L, 900L, "w")).thenReturn(Optional.empty());

        Map<String, Object> verdict = (Map<String, Object>) this.api.verifyCallback(SERVICE, 900L, body(77L, "w", "report")).getBody();

        assertThat(verdict).containsEntry("valid", true).containsEntry("terminal", true);
        verify(this.tokens, never()).verify(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRunWhoseJobHasNoTaskIsValidButHandsNoSteps() {
        JobQueue run = new JobQueue();
        run.setJobQueueId(901L);
        run.setJobId(78L);
        when(this.runs.findById(901L)).thenReturn(Optional.of(run));
        when(this.jobs.findByJobIdAndJobStatus(78L, Status.Active)).thenReturn(Optional.empty());
        when(this.tokens.verify(78L, 901L, "w")).thenReturn(Optional.empty());

        Map<String, Object> verdict = (Map<String, Object>) this.api.verifyCallback(SERVICE, 901L, body(78L, "w", "callback")).getBody();

        assertThat(verdict).containsEntry("valid", true).containsEntry("pipelineId", null);
        assertThat((List<?>) verdict.get("workerSteps")).isEmpty();
    }

    @Test
    void anUnknownVariantIsRefusedBeforeAnyTokenIsChecked() {
        assertThat(this.api.verifyCallback(SERVICE, 900L, body(77L, "w", "anything")).getStatusCodeValue()).isEqualTo(400);
        verifyNoInteractions(this.tokens);
    }

    // ---- MIG-188: the prompt-delete guard ----------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void thePromptDeleteGuardCountsThePipelinesUsingAPrompt() {
        when(this.pipelines.countUsingPrompt(eq(6L))).thenReturn(2L);
        Map<String, Object> answer = (Map<String, Object>) this.api.countUsingPrompt(SERVICE, Collections.singletonMap("promptId", 6)).getBody();
        assertThat(answer).containsEntry("promptId", 6L).containsEntry("count", 2L);
        assertThat(this.api.countUsingPrompt(null, Collections.singletonMap("promptId", 6)).getStatusCodeValue()).isEqualTo(401);
        assertThat(this.api.countUsingPrompt(SERVICE, Collections.emptyMap()).getStatusCodeValue()).isEqualTo(400);
    }
}
