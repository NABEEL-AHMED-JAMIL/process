package process.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

        ResponseEntity<?> response = this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Failed, null, callback());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(this.notifyService);
        verify(this.runCallbackTokens, never()).retire(anyLong());
    }

    @Test
    void logsWithoutAGoodTokenNeverReachTheService() {
        tokenIsRefused(RunCallbackTokens.Refusal.EXPIRED);
        List<String> messages = Arrays.asList("line one", "line two");
        Map<String, List<String>> body = Collections.singletonMap("messages", messages);

        ResponseEntity<?> single = this.api.addLogs(JOB_ID, QUEUE_ID, "stale", callback());
        ResponseEntity<?> batch = this.api.addLogsBatch(JOB_ID, QUEUE_ID, "stale", body);

        assertThat(single.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(this.notifyService, never()).addLogs(any(SourceJobQueueDto.class));
        verify(this.notifyService, never()).addLogsBatch(anyLong(), anyLong(), anyList());
    }

    @Test
    void aTerminalStateChangeSpendsTheToken() {
        tokenIsGood();
        when(this.notifyService.changeState(any(SourceJobQueueDto.class)))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "ok"));

        ResponseEntity<?> response = this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Completed, TOKEN, callback());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(this.notifyService).changeState(any(SourceJobQueueDto.class));
        verify(this.runCallbackTokens).retire(QUEUE_ID);
    }

    /** Running is not the end of anything; the worker still has logs and a final state to send. */
    @Test
    void aRunningStateChangeKeepsTheToken() {
        tokenIsGood();
        when(this.notifyService.changeState(any(SourceJobQueueDto.class)))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "ok"));

        this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Running, TOKEN, callback());

        verify(this.runCallbackTokens, never()).retire(anyLong());
    }

    /** A refused transition leaves the run where it was, and its token with it. */
    @Test
    void aRefusedTerminalChangeKeepsTheToken() {
        tokenIsGood();
        when(this.notifyService.changeState(any(SourceJobQueueDto.class)))
            .thenReturn(new ResponseDto(ProcessUtil.ERROR, "already finished"));

        this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Failed, TOKEN, callback());

        verify(this.runCallbackTokens, never()).retire(anyLong());
    }

    @Test
    void aBadRequestIsCaughtAfterTheTokenCheckAndSpendsNothing() {
        tokenIsGood();
        SourceJobQueueDto noMessage = new SourceJobQueueDto();

        ResponseEntity<?> response = this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Failed, TOKEN, noMessage);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(this.notifyService);
        verify(this.runCallbackTokens, never()).retire(anyLong());
    }
}
