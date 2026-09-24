package process.model.service.impl;

import org.barco.platform.correlation.CorrelationId;
import org.barco.platform.meter.MeterReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import process.ai.AiEndpointPolicy;
import process.ai.AiProviderGateway;
import process.ai.PromptRunner;
import process.model.dto.AdHocPromptRequestDto;
import process.model.dto.ResponseDto;
import process.model.repository.AiPromptRunRepository;
import process.security.TenantContext;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-159 / DEF-015: AiAgentServiceImpl.processAdHoc -- the file chat's and the job assistant's
 * model call -- goes straight to the gateway. It has none of what PromptRunner puts around a call:
 * no daily budget, no per-connection semaphore, no retry, no JSON repair round and no run row.
 * Metering it WAS missing too, and is no longer (MIG-198, AdHocAiMeteringTest), so of DEF-015 what
 * remains is the rest of the list, pinned here as today's behaviour. The corrected behaviour --
 * the ad-hoc call going through the same guard rails as a prompt run -- is a follow-up test, not
 * this one: every test in this class is expected to change when DEF-015 is fixed.
 */
@Tag("pinned-unreviewed")
class AdHocAiBypassTest {

    private final AiProviderGateway gateway = mock(AiProviderGateway.class);
    private AiAgentServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new AiAgentServiceImpl(mock(AiPromptServiceImpl.class), mock(AiModelConnectionServiceImpl.class),
            this.gateway, mock(AiEndpointPolicy.class));
        this.service.setMeter(mock(MeterReporter.class));
        TenantContext.set(2905L, "TENANT_USER", 4385L, "user");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CorrelationId.clear();
    }

    private static AdHocPromptRequestDto ask(boolean json) {
        AdHocPromptRequestDto dto = new AdHocPromptRequestDto();
        dto.setProvider("OpenAI"); dto.setApiKey("sk"); dto.setModel("gpt-4o-mini");
        dto.setInstructions("Answer about the file."); dto.setText("What is in column B?"); dto.setJsonMode(json);
        return dto;
    }

    /** Nothing that could hold a budget, a run row or a cap is even reachable from the service. */
    @Test
    void theAdHocPathHasNoRunnerNoRunRepositoryAndSoNoBudgetCapOrRow() {
        assertThat(Arrays.stream(AiAgentServiceImpl.class.getDeclaredFields()).map(Field::getType).collect(Collectors.toList()))
            .doesNotContain(PromptRunner.class, AiPromptRunRepository.class);
    }

    /** A 429 is a single call and an immediate failure; PromptRunner would have tried three times. */
    @Test
    void aRateLimitIsNotRetried() throws Exception {
        when(this.gateway.chat(any())).thenThrow(new AiProviderGateway.ProviderException(429, "HTTP 429: slow down"))
            .thenReturn(new AiProviderGateway.ChatAnswer("would have worked", 1, 1));

        ResponseDto answer = this.service.processAdHoc(ask(false));

        assertThat(answer.getStatus()).isEqualTo("ERROR");
        assertThat(answer.getMessage()).isEqualTo("The AI provider request failed.");
        verify(this.gateway, times(1)).chat(any());
    }

    /** JSON mode strips the fences and hands back whatever came; there is no repair round. */
    @Test
    void jsonModeIsNeverValidatedOrRepaired() throws Exception {
        when(this.gateway.chat(any())).thenReturn(new AiProviderGateway.ChatAnswer("```json\nnot json at all\n```", 1, 1));

        ResponseDto answer = this.service.processAdHoc(ask(true));

        assertThat(answer.getStatus()).isEqualTo("SUCCESS");
        assertThat(answer.getData()).isEqualTo("not json at all");
        verify(this.gateway, times(1)).chat(any());
    }

    /** No semaphore: any number of ad-hoc calls are in flight at once, whatever the connection's cap. */
    @Test
    void concurrentCallsAreNotCapped() throws Exception {
        int calls = 6;
        CountDownLatch allInFlight = new CountDownLatch(calls);
        CountDownLatch release = new CountDownLatch(1);
        when(this.gateway.chat(any())).thenAnswer(inv -> {
            allInFlight.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new AiProviderGateway.ChatAnswer("answer", 1, 1);
        });
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        try {
            Future<?>[] running = new Future<?>[calls];
            for (int i = 0; i < calls; i++) running[i] = pool.submit(() -> this.service.processAdHoc(ask(false)));
            assertThat(allInFlight.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (Future<?> f : running) f.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * Its own truncation, at 60,000 characters rather than the runner's 200,000, and just as
     * silent: the answer is SUCCESS and says nothing about the cut.
     */
    @Test
    void theTextIsCutAtSixtyThousandCharactersSilently() throws Exception {
        StringBuilder text = new StringBuilder();
        while (text.length() < 70_000) text.append("abcdefghij");
        AdHocPromptRequestDto dto = ask(false);
        dto.setText(text.toString());
        ArgumentCaptor<AiProviderGateway.ChatRequest> sent = ArgumentCaptor.forClass(AiProviderGateway.ChatRequest.class);
        when(this.gateway.chat(sent.capture())).thenReturn(new AiProviderGateway.ChatAnswer("answer", 1, 1));

        ResponseDto answer = this.service.processAdHoc(dto);

        assertThat(sent.getValue().user).hasSize(60_000).isEqualTo(text.substring(0, 60_000));
        assertThat(answer.getMessage()).isEqualTo("Processed successfully.");
    }
}
