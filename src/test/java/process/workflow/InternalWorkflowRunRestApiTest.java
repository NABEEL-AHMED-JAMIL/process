package process.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import process.model.dto.ResponseDto;
import process.model.service.SourceJobService;
import process.security.TenantContext;

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
    private final InternalWorkflowRunRestApi api = new InternalWorkflowRunRestApi(this.jobs, "shared-token");

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
}
