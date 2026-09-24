package process.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.security.RunCallbackTokens;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-190, the console's side of the usage-reporting contract (job-search etl/meter/CONTRACT.md §2):
 * the meter learns whose run it is only from /meter.json/verifyRun, and reads exactly these bodies --
 * status, message and data.tenantId. A split must keep them byte for byte, or the meter mis-bills.
 */
class MeterVerifyRunContractTest {

    private final RunCallbackTokens tokens = mock(RunCallbackTokens.class);
    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private final MeterRestApi api = new MeterRestApi(this.tokens, this.runs, this.jobs);
    private final ObjectMapper json = new ObjectMapper();

    private static MeterRestApi.VerifyRunDto run(long jobId, long jobQueueId) {
        MeterRestApi.VerifyRunDto dto = new MeterRestApi.VerifyRunDto();
        dto.jobId = jobId;
        dto.jobQueueId = jobQueueId;
        return dto;
    }

    private void jobOf(long jobQueueId, long jobId, Long tenantId) {
        JobQueue queued = new JobQueue();
        queued.setJobQueueId(jobQueueId);
        queued.setJobId(jobId);
        SourceJob job = new SourceJob();
        job.setJobId(jobId);
        job.setTenantId(tenantId);
        when(this.runs.findById(jobQueueId)).thenReturn(Optional.of(queued));
        when(this.jobs.findById(jobId)).thenReturn(Optional.of(job));
    }

    private String body(ResponseEntity<?> answer) throws Exception {
        return this.json.writeValueAsString(answer.getBody());
    }

    @Test
    void aLiveTokenOfARunNamesItsWorkspace() throws Exception {
        when(this.tokens.verifyForReport(2600L, 6000L, "tok")).thenReturn(Optional.empty());
        this.jobOf(6000L, 2600L, 2905L);

        ResponseEntity<?> answer = this.api.verifyRun("tok", run(2600L, 6000L));

        assertThat(answer.getStatusCodeValue()).isEqualTo(200);
        assertThat(this.json.readTree(this.body(answer)).path("status").asText()).isEqualTo("SUCCESS");
        assertThat(this.json.readTree(this.body(answer)).path("message").asText()).isEqualTo("Run verified.");
        assertThat(this.json.readTree(this.body(answer)).path("data").path("tenantId").asLong()).isEqualTo(2905L);
        assertThat(this.json.readTree(this.body(answer)).path("data").path("jobQueueId").asLong()).isEqualTo(6000L);
        assertThat(this.json.readTree(this.body(answer)).path("data").path("jobId").asLong()).isEqualTo(2600L);
    }

    @Test
    void anyRefusedTokenIsAFlat401InTheSameWords() throws Exception {
        for (RunCallbackTokens.Refusal refusal : RunCallbackTokens.Refusal.values()) {
            when(this.tokens.verifyForReport(anyLong(), anyLong(), any())).thenReturn(Optional.of(refusal));

            ResponseEntity<?> answer = this.api.verifyRun("wrong", run(2600L, 6000L));

            assertThat(answer.getStatusCodeValue()).as(refusal.name()).isEqualTo(401);
            assertThat(this.json.readTree(this.body(answer)).path("status").asText()).isEqualTo("ERROR");
            assertThat(this.json.readTree(this.body(answer)).path("message").asText()).as(refusal.name()).isEqualTo("Unauthorized worker callback.");
            assertThat(this.json.readTree(this.body(answer)).path("data").isMissingNode() || this.json.readTree(this.body(answer)).path("data").isNull()).isTrue();
        }
    }

    @Test
    void aRunOfNoWorkspaceIsRefusedAndNeverBilledToOne() throws Exception {
        when(this.tokens.verifyForReport(2600L, 6000L, "tok")).thenReturn(Optional.empty());
        this.jobOf(6000L, 2600L, null);

        ResponseEntity<?> answer = this.api.verifyRun("tok", run(2600L, 6000L));

        assertThat(answer.getStatusCodeValue()).isEqualTo(401);
        assertThat(this.json.readTree(this.body(answer)).path("message").asText()).isEqualTo("This run belongs to no workspace.");
    }

    @Test
    void aRunThatIsGoneIsRefusedToo() throws Exception {
        when(this.tokens.verifyForReport(2600L, 6000L, "tok")).thenReturn(Optional.empty());
        when(this.runs.findById(6000L)).thenReturn(Optional.empty());

        ResponseEntity<?> answer = this.api.verifyRun("tok", run(2600L, 6000L));

        assertThat(answer.getStatusCodeValue()).isEqualTo(401);
        assertThat(this.json.readTree(this.body(answer)).path("message").asText()).isEqualTo("This run belongs to no workspace.");
    }
}
