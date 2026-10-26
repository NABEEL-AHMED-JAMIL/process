package process.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import process.model.dto.ResponseDto;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.repository.JobQueueRepository;
import process.model.service.SourceJobService;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.PipelineOutputFields;
import process.pipeline.RunResults;
import process.pipeline.StepStore;
import process.pipeline.items.ItemMasks;
import process.pipeline.items.WorkspaceMasks;
import process.security.TenantContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MIG-273: a workflow's run_pipeline step reaches Core with the internal token, and runs the job as its workspace. */
class InternalWorkflowRunRestApiTest {

    private final SourceJobService jobs = mock(SourceJobService.class);
    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final RunResults results = mock(RunResults.class);
    private final WorkspaceMasks masks = mock(WorkspaceMasks.class);
    private final PipelineOutputFields fields = mock(PipelineOutputFields.class);
    private final StepStore steps = mock(StepStore.class);
    private final PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
    private final InternalWorkflowRunRestApi api = new InternalWorkflowRunRestApi(this.jobs, this.runs, "shared-token", this.results,
        this.masks, this.fields, this.steps, this.definitions);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static InternalWorkflowRunRestApi.RunRequest request() {
        InternalWorkflowRunRestApi.RunRequest request = new InternalWorkflowRunRestApi.RunRequest();
        request.setTenantId(2924L);
        request.setJobId(2849L);
        request.setInstanceId(1001L);
        request.setStepKey("run");
        return request;
    }

    @Test
    void withoutTheInternalTokenNothingRuns() throws Exception {
        assertThat(this.api.run(null, request()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(this.api.run("guess", request()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(this.jobs, never()).runSourceJobFor(anyLong(), any());
        verify(this.jobs, never()).runSourceJobFor(anyLong(), any(), any(), any());
        verify(this.jobs, never()).runSourceJobFor(anyLong(), any(), any(), any(), any());
    }

    @Test
    void theJobRunsAsItsWorkspaceForThisCallOnlyWithTheStepInItsAuditLog() throws Exception {
        AtomicReference<Long> tenantDuringRun = new AtomicReference<>();
        when(this.jobs.runSourceJobFor(eq(2849L), eq(1001L), eq("run"), eq("Queued by workflow instance 1001, step run."), isNull())).thenAnswer(call -> {
            tenantDuringRun.set(TenantContext.getTenantId());
            return new ResponseDto("SUCCESS", "SourceJob job successfully added into queue.", 9123L);
        });

        ResponseEntity<?> answer = this.api.run("shared-token", request());

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((ResponseDto) answer.getBody()).getData()).isEqualTo(9123L);
        assertThat(tenantDuringRun.get()).isEqualTo(2924L);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void aRequestWithoutItsWorkspaceOrJobIsRefusedInWords() throws Exception {
        InternalWorkflowRunRestApi.RunRequest request = request();
        request.setJobId(null);
        ResponseEntity<?> answer = this.api.run("shared-token", request);
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ResponseDto) answer.getBody()).getMessage()).isEqualTo("Name the workspace (tenantId) and the job (jobId).");
    }

    private static InternalWorkflowRunRestApi.StateRequest state(long tenantId, long runId) {
        InternalWorkflowRunRestApi.StateRequest request = new InternalWorkflowRunRestApi.StateRequest();
        request.setTenantId(tenantId);
        request.setRunId(runId);
        return request;
    }

    private static JobQueue run(long tenantId, JobStatus status, String message) {
        JobQueue q = new JobQueue();
        q.setTenantId(tenantId);
        q.setJobStatus(status);
        q.setJobStatusMessage(message);
        return q;
    }

    /** 2026-10-21: a run_pipeline step that waits asks how its run is; the token first, then the workspace's own run only. */
    @Test
    void aRunsStateIsTheWorkspacesOwnOnly() {
        AtomicReference<Long> tenantDuringRead = new AtomicReference<>();
        when(this.runs.findById(1019L)).thenAnswer(call -> {
            tenantDuringRead.set(TenantContext.getTenantId());
            return Optional.of(run(2924L, JobStatus.Completed, "8 of 8 step(s) completed."));
        });

        assertThat(this.api.runState(null, state(2924L, 1019L)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseDto mine = (ResponseDto) this.api.runState("shared-token", state(2924L, 1019L)).getBody();
        assertThat(mine.getStatus()).isEqualTo("SUCCESS");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) mine.getData();
        assertThat(data).containsEntry("status", "Completed").containsEntry("ended", true).containsEntry("message", "8 of 8 step(s) completed.");
        assertThat(tenantDuringRead.get()).isEqualTo(2924L);
        assertThat(TenantContext.getTenantId()).isNull();

        ResponseDto theirs = (ResponseDto) this.api.runState("shared-token", state(4414L, 1019L)).getBody();
        assertThat(theirs.getStatus()).isEqualTo("ERROR");
        assertThat(theirs.getMessage()).isEqualTo("No run 1019 in this workspace.");
    }

    @Test
    void aRunWaitingForItsApprovalHasNotEnded() {
        when(this.runs.findById(1020L)).thenReturn(Optional.of(run(2924L, JobStatus.Running, "Waiting for approval at step 6.")));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ((ResponseDto) this.api.runState("shared-token", state(2924L, 1020L)).getBody()).getData();
        assertThat(data).containsEntry("status", "Running").containsEntry("ended", false);
    }

    /** MIG-424: the step's parameters reach Run now's check as they were mapped. */
    @Test
    void theStepsParametersGoWithTheRun() throws Exception {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("mrn", "MRN-1");
        InternalWorkflowRunRestApi.RunRequest request = request();
        request.setParameters(parameters);
        when(this.jobs.runSourceJobFor(eq(2849L), eq(1001L), eq("run"), any(), eq(parameters)))
            .thenReturn(new ResponseDto("SUCCESS", "SourceJob job successfully added into queue.", 9124L));
        assertThat(((ResponseDto) this.api.run("shared-token", request).getBody()).getData()).isEqualTo(9124L);
    }

    /** MIG-424: a completed run answers what the step chose, masked for no one person; a run still going answers none. */
    @Test
    @SuppressWarnings("unchecked")
    void aCompletedRunAnswersTheChosenResultsMasked() throws Exception {
        JobQueue done = run(2924L, JobStatus.Completed, "3 of 3 step(s) completed.");
        done.setJobQueueId(1021L);
        when(this.runs.findById(1021L)).thenReturn(Optional.of(done));
        when(this.steps.pinnedDefinition(1021L)).thenReturn(Optional.empty());
        ItemMasks shown = ItemMasks.forWorkspace(null, false);
        when(this.masks.of(eq(2924L), isNull())).thenReturn(shown);
        RunResults.Result result = new RunResults.Result();
        result.step = "careplan";
        result.rowCount = 1;
        result.values.put("risk", "High");
        when(this.results.read(eq(1021L), any(), eq(shown))).thenReturn(result);
        InternalWorkflowRunRestApi.StateRequest request = state(2924L, 1021L);
        request.setResults(new ObjectMapper().readTree("{\"fields\": [\"risk\"]}"));
        Map<String, Object> data = (Map<String, Object>) ((ResponseDto) this.api.runState("shared-token", request).getBody()).getData();
        assertThat((Map<String, Object>) data.get("results")).containsEntry("step", "careplan")
            .containsEntry("values", Map.of("risk", "High"));

        when(this.runs.findById(1022L)).thenReturn(Optional.of(run(2924L, JobStatus.Running, "Step 2 of 3.")));
        InternalWorkflowRunRestApi.StateRequest running = state(2924L, 1022L);
        running.setResults(request.getResults());
        Map<String, Object> still = (Map<String, Object>) ((ResponseDto) this.api.runState("shared-token", running).getBody()).getData();
        assertThat(still).doesNotContainKey("results");

        InternalWorkflowRunRestApi.StateRequest wrong = state(2924L, 1021L);
        wrong.setResults(new ObjectMapper().readTree("{\"take\": \"median\"}"));
        Map<String, Object> refused = (Map<String, Object>) ((ResponseDto) this.api.runState("shared-token", wrong).getBody()).getData();
        assertThat((Map<String, Object>) refused.get("results")).containsEntry("problem",
            "results.take: one of first, all, count, sum, min, max, avg.");
    }

    /** MIG-424: a publish asks about a job; another workspace's is "No pipeline", the token first. */
    @Test
    void aPipelinesFieldsAreTheWorkspacesOwnOnly() {
        InternalWorkflowRunRestApi.PipelineRequest request = new InternalWorkflowRunRestApi.PipelineRequest();
        request.setTenantId(2924L);
        request.setJobId(2849L);
        AtomicReference<Long> tenantDuringRead = new AtomicReference<>();
        when(this.fields.of(2924L, 2849L)).thenAnswer(call -> {
            tenantDuringRead.set(TenantContext.getTenantId());
            return Optional.of(Map.of("jobId", 2849L));
        });
        when(this.fields.of(4414L, 2849L)).thenReturn(Optional.empty());
        assertThat(this.api.pipeline(null, request).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(((ResponseDto) this.api.pipeline("shared-token", request).getBody()).getStatus()).isEqualTo("SUCCESS");
        assertThat(tenantDuringRead.get()).isEqualTo(2924L);
        request.setTenantId(4414L);
        ResponseDto theirs = (ResponseDto) this.api.pipeline("shared-token", request).getBody();
        assertThat(theirs.getStatus()).isEqualTo("ERROR");
        assertThat(theirs.getMessage()).isEqualTo("No pipeline 2849 in this workspace.");
        assertThat(TenantContext.getTenantId()).isNull();
    }
}
