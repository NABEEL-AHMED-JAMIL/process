package process.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import process.callback.CallbackKeys;
import process.callback.CallbackReceipts;
import process.callback.ReplayedResponse;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.service.NotifyService;
import process.security.RunCallbackTokens;
import process.util.ProcessUtil;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The worker callbacks sit outside the JWT chain -- SecurityConfig permits them -- so the run's
 * own token is the only thing between the internet and any tenant's job status and audit log.
 * The controller asks {@link RunCallbackTokens} and does exactly two things with the answer:
 * refuses with a bare 401 before touching the service, or lets the call through and spends the
 * token once the run is over. The verdict itself is pinned in RunCallbackTokensTest.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class NotifyResetApiTest {

    private static final String TOKEN = "cbt_1.91422.some-random-part";
    private static final long JOB_ID = 1196L;
    private static final long QUEUE_ID = 91422L;

    @Mock private NotifyService notifyService;
    @Mock private RunCallbackTokens runCallbackTokens;

    private NotifyResetApi api;

    @BeforeEach
    void setUp() {
        this.api = new NotifyResetApi(this.notifyService, this.runCallbackTokens);
    }

    private SourceJobQueueDto callback() {
        SourceJobQueueDto dto = new SourceJobQueueDto();
        dto.setJobStatusMessage("upstream unavailable");
        return dto;
    }

    private void tokenIsGood() {
        when(this.runCallbackTokens.verify(JOB_ID, QUEUE_ID, TOKEN)).thenReturn(Optional.empty());
    }

    private void tokenIsRefused(RunCallbackTokens.Refusal why) {
        when(this.runCallbackTokens.verify(eq(JOB_ID), eq(QUEUE_ID), any())).thenReturn(Optional.of(why));
    }

    @Test
    void everyRefusalIsTheSameBare401() {
        for (RunCallbackTokens.Refusal why : RunCallbackTokens.Refusal.values()) {
            when(this.runCallbackTokens.verify(JOB_ID, QUEUE_ID, "x")).thenReturn(Optional.of(why));
            ResponseEntity<?> rejected = this.api.rejectIfUntrusted(JOB_ID, QUEUE_ID, "x");
            assertThat(rejected).as(why.name()).isNotNull();
            assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            // The reason stays in the log: a caller probing for a live run learns nothing here.
            assertThat(rejected.getBody().toString()).doesNotContain(why.name());
        }
    }

    @Test
    void theRunsOwnTokenIsLetThrough() {
        tokenIsGood();
        assertThat(this.api.rejectIfUntrusted(JOB_ID, QUEUE_ID, TOKEN)).isNull();
    }

    @Test
    void aStateChangeWithoutAGoodTokenNeverReachesTheService() {
        tokenIsRefused(RunCallbackTokens.Refusal.MISMATCH);

        ResponseEntity<?> response = this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Failed, null, null, null, callback());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(this.notifyService);
        verify(this.runCallbackTokens, never()).retire(anyLong());
    }

    @Test
    void logsWithoutAGoodTokenNeverReachTheService() {
        tokenIsRefused(RunCallbackTokens.Refusal.EXPIRED);
        List<String> messages = Arrays.asList("line one", "line two");
        Map<String, List<String>> body = Collections.singletonMap("messages", messages);

        ResponseEntity<?> single = this.api.addLogs(JOB_ID, QUEUE_ID, "stale", null, null, callback());
        ResponseEntity<?> batch = this.api.addLogsBatch(JOB_ID, QUEUE_ID, "stale", null, null, body);

        assertThat(single.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(this.notifyService, never()).addLogs(any(SourceJobQueueDto.class), any());
        verify(this.notifyService, never()).addLogsBatch(anyLong(), anyLong(), anyList(), any());
    }

    @Test
    void aTerminalStateChangeSpendsTheToken() {
        tokenIsGood();
        when(this.notifyService.changeState(any(SourceJobQueueDto.class), isNull()))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "ok"));

        ResponseEntity<?> response = this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Completed, TOKEN, null, null, callback());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(this.notifyService).changeState(any(SourceJobQueueDto.class), isNull());
        verify(this.runCallbackTokens).retire(QUEUE_ID);
    }

    /** Running is not the end of anything; the worker still has logs and a final state to send. */
    @Test
    void aRunningStateChangeKeepsTheToken() {
        tokenIsGood();
        when(this.notifyService.changeState(any(SourceJobQueueDto.class), isNull()))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "ok"));

        this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Running, TOKEN, null, null, callback());

        verify(this.runCallbackTokens, never()).retire(anyLong());
    }

    /** A refused transition leaves the run where it was, and its token with it. */
    @Test
    void aRefusedTerminalChangeKeepsTheToken() {
        tokenIsGood();
        when(this.notifyService.changeState(any(SourceJobQueueDto.class), isNull()))
            .thenReturn(new ResponseDto(ProcessUtil.ERROR, "already finished"));

        this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Failed, TOKEN, null, null, callback());

        verify(this.runCallbackTokens, never()).retire(anyLong());
    }

    @Test
    void aBadRequestIsCaughtAfterTheTokenCheckAndSpendsNothing() {
        tokenIsGood();
        SourceJobQueueDto noMessage = new SourceJobQueueDto();

        ResponseEntity<?> response = this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Failed, TOKEN, null, null, noMessage);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(this.notifyService);
        verify(this.runCallbackTokens, never()).retire(anyLong());
    }

    // ---- MIG-18: idempotency keys ---------------------------------------------------------------------------

    private static ReplayedResponse firstAnswer(String message) {
        return new ReplayedResponse(new CallbackReceipts.Receipt("changeState:Completed", null, message), null);
    }

    @Test
    void theWorkersKeyIsHandedToTheService() {
        tokenIsGood();
        when(this.notifyService.changeState(any(SourceJobQueueDto.class), eq("done-7f3a9c2e")))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "ok"));
        when(this.notifyService.addLogs(any(SourceJobQueueDto.class), eq("line-0000042")))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "ok"));
        when(this.notifyService.addLogsBatch(eq(JOB_ID), eq(QUEUE_ID), anyList(), eq("batch-0000007")))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "ok"));

        this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Completed, TOKEN, "done-7f3a9c2e", null, callback());
        this.api.addLogs(JOB_ID, QUEUE_ID, TOKEN, "line-0000042", null, callback());
        this.api.addLogsBatch(JOB_ID, QUEUE_ID, TOKEN, "batch-0000007", null, Collections.singletonMap("messages", Arrays.asList("a", "b")));

        verify(this.notifyService).changeState(any(SourceJobQueueDto.class), eq("done-7f3a9c2e"));
        verify(this.notifyService).addLogs(any(SourceJobQueueDto.class), eq("line-0000042"));
        verify(this.notifyService).addLogsBatch(eq(JOB_ID), eq(QUEUE_ID), anyList(), eq("batch-0000007"));
    }

    /** A key that could not be written to a log line safely is refused -- after the token, like every 400. */
    @Test
    void anUnacceptableKeyIsABadRequestAndReachesNothing() {
        tokenIsGood();

        ResponseEntity<?> response = this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Completed, TOKEN,
            "two\nlines", null, callback());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(this.notifyService);
    }

    /**
     * The worker lost the answer to its Completed and sends it again. The run is over, so the callback
     * itself would be refused -- but the token is the run's own, and the answer was recorded, so the
     * worker is told what it was told the first time rather than that it is unauthorised.
     */
    @Test
    void aFinishedRunsLastCallbackSentAgainGetsItsFirstAnswer() {
        tokenIsRefused(RunCallbackTokens.Refusal.RUN_OVER);
        when(this.notifyService.replay(QUEUE_ID, JobStatus.Completed, CallbackKeys.changeState(JobStatus.Completed), null))
            .thenReturn(Optional.of(firstAnswer("Job 1196 status changed to Completed")));

        ResponseEntity<?> response = this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Completed, TOKEN, null, null, callback());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((ResponseDto) response.getBody()).getMessage()).isEqualTo("Job 1196 status changed to Completed");
        verify(this.notifyService, never()).changeState(any(SourceJobQueueDto.class), any());
        verify(this.runCallbackTokens, never()).retire(anyLong());
    }

    @Test
    void aFinishedRunWithNothingRecordedIsStillThe401() {
        tokenIsRefused(RunCallbackTokens.Refusal.RUN_OVER);
        when(this.notifyService.replay(eq(QUEUE_ID), any(), anyString(), any())).thenReturn(Optional.empty());

        ResponseEntity<?> response = this.api.addLogs(JOB_ID, QUEUE_ID, TOKEN, "line-0000099", null, callback());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(this.notifyService, never()).addLogs(any(SourceJobQueueDto.class), any());
    }

    /** Only a refusal that proves the token is the run's own may be answered from a receipt. */
    @Test
    void noOtherRefusalLooksForAReceipt() {
        for (RunCallbackTokens.Refusal why : RunCallbackTokens.Refusal.values()) {
            if (why == RunCallbackTokens.Refusal.RUN_OVER) {
                continue;
            }
            when(this.runCallbackTokens.verify(JOB_ID, QUEUE_ID, "x")).thenReturn(Optional.of(why));
            ResponseEntity<?> response = this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Completed, "x", "done-7f3a9c2e", null, callback());
            assertThat(response.getStatusCode()).as(why.name()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        verify(this.notifyService, never()).replay(any(), any(), any(), any());
    }

    /** A redelivered Completed answered from its receipt spends nothing a second time. */
    @Test
    void aReplayedTerminalChangeDoesNotSpendTheTokenAgain() {
        tokenIsGood();
        when(this.notifyService.changeState(any(SourceJobQueueDto.class), isNull()))
            .thenReturn(firstAnswer("Job 1196 status changed to Completed"));

        this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Completed, TOKEN, null, null, callback());

        verify(this.runCallbackTokens, never()).retire(anyLong());
    }

    // ---- MIG-63: a genuine report refused for an expired token -------------------------------------------------

    /**
     * Still the same bare 401 -- reconciliation is separate from acceptance -- but the refusal of the
     * run's own token for expiry is proof its worker can no longer be heard, and is noted on the run so
     * the stall sweep closes it on its next pass.
     */
    @Test
    void aGenuineReportRefusedForExpiryIsStillRefusedAndIsNotedForTheSweep() {
        tokenIsRefused(RunCallbackTokens.Refusal.EXPIRED);

        ResponseEntity<?> state = this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Completed, TOKEN, null, null, callback());
        ResponseEntity<?> line = this.api.addLogs(JOB_ID, QUEUE_ID, TOKEN, null, null, callback());

        assertThat(state.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(line.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(((ResponseDto) state.getBody()).getMessage()).isEqualTo("Unauthorized worker callback.");
        verify(this.notifyService).noteRefusedCallback(QUEUE_ID, JobStatus.Completed);
        verify(this.notifyService).noteRefusedCallback(QUEUE_ID, null);
        verify(this.notifyService, never()).changeState(any(SourceJobQueueDto.class), any());
        verify(this.notifyService, never()).addLogs(any(SourceJobQueueDto.class), any());
    }

    /** Anyone can send a wrong token; only the run's own, expired, is evidence of anything. */
    @Test
    void noOtherRefusalIsNoted() {
        for (RunCallbackTokens.Refusal why : RunCallbackTokens.Refusal.values()) {
            if (why == RunCallbackTokens.Refusal.EXPIRED) {
                continue;
            }
            when(this.runCallbackTokens.verify(JOB_ID, QUEUE_ID, "x")).thenReturn(Optional.of(why));
            this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Completed, "x", null, null, callback());
        }
        verify(this.notifyService, never()).noteRefusedCallback(any(), any());
    }
}
