package process.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.service.NotifyService;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The worker callbacks sit outside the JWT chain -- SecurityConfig permits them -- so the shared
 * secret is the only thing between the internet and any tenant's job status and audit log. It
 * used to be optional: a blank token meant "let everyone in", which is what an environment
 * missing WORKER_CALLBACK_TOKEN silently produced. These pin the closed behaviour.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class NotifyResetApiTest {

    private static final String CONFIGURED_TOKEN = "the-configured-worker-token";
    private static final long JOB_ID = 1196L;
    private static final long QUEUE_ID = 91422L;

    @Mock private NotifyService notifyService;

    /** The token is injected by Spring at runtime; there is no constructor to pass it through. */
    private NotifyResetApi apiWithToken(String configuredToken) throws Exception {
        NotifyResetApi api = new NotifyResetApi(this.notifyService);
        Field token = NotifyResetApi.class.getDeclaredField("workerCallbackToken");
        token.setAccessible(true);
        token.set(api, configuredToken);
        return api;
    }

    private SourceJobQueueDto callback() {
        SourceJobQueueDto dto = new SourceJobQueueDto();
        dto.setJobStatusMessage("upstream unavailable");
        return dto;
    }

    @Test
    void anUnconfiguredTokenRefusesToStart() throws Exception {
        assertThatThrownBy(() -> apiWithToken(null).requireCallbackToken())
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> apiWithToken("").requireCallbackToken())
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> apiWithToken("   ").requireCallbackToken())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aConfiguredTokenStarts() throws Exception {
        apiWithToken(CONFIGURED_TOKEN).requireCallbackToken();
    }

    @Test
    void anUnconfiguredTokenLetsNobodyThrough() throws Exception {
        // Startup refuses this, so it can only be reached if the value was cleared afterwards.
        // Either way, nothing to compare against is not permission.
        assertThat(apiWithToken("").rejectIfUntrusted(CONFIGURED_TOKEN)).isNotNull();
        assertThat(apiWithToken(null).rejectIfUntrusted(null)).isNotNull();
    }

    @Test
    void aMissingHeaderIsRejected() throws Exception {
        ResponseEntity<?> rejected = apiWithToken(CONFIGURED_TOKEN).rejectIfUntrusted(null);

        assertThat(rejected).isNotNull();
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aWrongTokenIsRejected() throws Exception {
        assertThat(apiWithToken(CONFIGURED_TOKEN).rejectIfUntrusted("not-the-token")).isNotNull();
        // A prefix of the real value must not be enough either.
        assertThat(apiWithToken(CONFIGURED_TOKEN).rejectIfUntrusted("the-configured")).isNotNull();
        assertThat(apiWithToken(CONFIGURED_TOKEN).rejectIfUntrusted("")).isNotNull();
    }

    @Test
    void theRightTokenIsLetThrough() throws Exception {
        assertThat(apiWithToken(CONFIGURED_TOKEN).rejectIfUntrusted(CONFIGURED_TOKEN)).isNull();
        // Whitespace either side of the header value is trimmed, as it is on the configured one.
        assertThat(apiWithToken(CONFIGURED_TOKEN).rejectIfUntrusted("  " + CONFIGURED_TOKEN + " ")).isNull();
    }

    @Test
    void aStateChangeWithoutTheTokenNeverReachesTheService() throws Exception {
        ResponseEntity<?> response = apiWithToken(CONFIGURED_TOKEN)
            .changeState(JOB_ID, QUEUE_ID, JobStatus.Failed, null, callback());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(this.notifyService);
    }

    @Test
    void logsWithoutTheTokenNeverReachTheService() throws Exception {
        NotifyResetApi api = apiWithToken(CONFIGURED_TOKEN);
        List<String> messages = Arrays.asList("line one", "line two");
        Map<String, List<String>> body = Collections.singletonMap("messages", messages);

        ResponseEntity<?> single = api.addLogs(JOB_ID, QUEUE_ID, "not-the-token", callback());
        ResponseEntity<?> batch = api.addLogsBatch(JOB_ID, QUEUE_ID, "not-the-token", body);

        assertThat(single.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(this.notifyService, never()).addLogs(any(SourceJobQueueDto.class));
        verify(this.notifyService, never()).addLogsBatch(anyLong(), anyLong(), anyList());
    }

    @Test
    void aStateChangeWithTheTokenReachesTheService() throws Exception {
        ResponseEntity<?> response = apiWithToken(CONFIGURED_TOKEN)
            .changeState(JOB_ID, QUEUE_ID, JobStatus.Failed, CONFIGURED_TOKEN, callback());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(this.notifyService).changeState(any(SourceJobQueueDto.class));
    }
}
