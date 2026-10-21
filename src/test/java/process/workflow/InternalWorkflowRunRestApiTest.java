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
import process.security.TenantContext;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MIG-273: a workflow's run_pipeline step reaches Core with the internal token, and runs the job as its workspace. */
class InternalWorkflowRunRestApiTest {

    private final SourceJobService jobs = mock(SourceJobService.class);
    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final InternalWorkflowRunRestApi api = new InternalWorkflowRunRestApi(this.jobs, this.runs, "shared-token");

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
    }

    @Test
    void theJobRunsAsItsWorkspaceForThisCallOnlyWithTheStepInItsAuditLog() throws Exception {
        AtomicReference<Long> tenantDuringRun = new AtomicReference<>();
        when(this.jobs.runSourceJobFor(eq(2849L), eq("Queued by workflow instance 1001, step run."))).thenAnswer(call -> {
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
}
