package process.model.service.impl;

import org.barco.platform.correlation.CorrelationId;
import org.barco.platform.meter.Meter;
import org.barco.platform.meter.UsageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import process.ai.AiEndpointPolicy;
import process.ai.AiProviderGateway;
import process.billing.MeterClient;
import process.model.dto.AdHocPromptRequestDto;
import process.model.dto.ResponseDto;
import process.security.TenantContext;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-198 (the owner's decision, 2026-09-23): the file chat's and the job assistant's model calls are
 * metered like prompt runs -- ai.tokens.in and ai.tokens.out, from the one vocabulary -- where they were
 * metered nowhere. The key is the request's correlation id and the call's place in that request, so a
 * replayed request meters once and two calls in one request meter twice.
 */
class AdHocAiMeteringTest {

    private final AiProviderGateway gateway = mock(AiProviderGateway.class);
    private final MeterClient meter = mock(MeterClient.class);
    private AiAgentServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new AiAgentServiceImpl(mock(AiPromptServiceImpl.class), mock(AiModelConnectionServiceImpl.class), this.gateway, mock(AiEndpointPolicy.class));
        this.service.setMeter(this.meter);
        when(this.gateway.chat(any())).thenReturn(new AiProviderGateway.ChatAnswer("an answer", 1200, 80));
        TenantContext.set(2905L, "TENANT_USER", 4385L, "user");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CorrelationId.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    /** One HTTP request, as the correlation filter leaves it: an id bound, and request attributes of its own. */
    private static void request(String correlationId) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        CorrelationId.set(correlationId);
    }

    private static AdHocPromptRequestDto ask() {
        AdHocPromptRequestDto dto = new AdHocPromptRequestDto();
        dto.setProvider("OpenAI"); dto.setApiKey("sk"); dto.setModel("gpt-4o-mini");
        dto.setInstructions("Answer about the file."); dto.setText("What is in column B?");
        return dto;
    }

    private List<UsageEvent> reported() {
        ArgumentCaptor<UsageEvent> captor = ArgumentCaptor.forClass(UsageEvent.class);
        verify(this.meter, atLeast(0)).report(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void aCallsTokensAreReportedAgainstTheCallersWorkspace() throws Exception {
        request("0f7a4c1e-mig198-a");

        ResponseDto answer = this.service.processAdHoc(ask());

        assertThat(answer.getStatus()).isEqualTo("SUCCESS");
        List<UsageEvent> events = this.reported();
        assertThat(events).extracting(e -> e.meter).containsExactly(Meter.AI_TOKENS_IN.key(), Meter.AI_TOKENS_OUT.key());
        assertThat(events).extracting(e -> e.quantity).containsExactly(1200.0, 80.0);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.tenantId).isEqualTo(2905L);
            assertThat(e.actorUserId).isEqualTo(4385L);
            assertThat(e.subjectType).isEqualTo("ad-hoc");
            assertThat(e.note).isEqualTo("gpt-4o-mini");
        });
        assertThat(events).extracting(e -> e.dedupeKey)
            .containsExactly("ai-adhoc#0f7a4c1e-mig198-a#1#in", "ai-adhoc#0f7a4c1e-mig198-a#1#out");
    }

    @Test
    void aReplayedRequestMetersOnce() throws Exception {
        request("0f7a4c1e-mig198-b");
        this.service.processAdHoc(ask());
        request("0f7a4c1e-mig198-b");                 // the same request again: a retry, a replay
        this.service.processAdHoc(ask());

        List<String> keys = this.reported().stream().map(e -> e.dedupeKey).collect(Collectors.toList());
        assertThat(keys).hasSize(4);
        assertThat(keys.stream().distinct()).hasSize(2);  // two events, twice: one charge at the meter
    }

    @Test
    void twoCallsInOneRequestAreTwoCharges() throws Exception {
        request("0f7a4c1e-mig198-c");
        this.service.processAdHoc(ask());
        this.service.processAdHoc(ask());

        assertThat(this.reported().stream().map(e -> e.dedupeKey).distinct()).hasSize(4);
    }

    @Test
    void aFailedCallReportsNothing() throws Exception {
        request("0f7a4c1e-mig198-d");
        when(this.gateway.chat(any())).thenThrow(new IllegalStateException("provider down"));

        this.service.processAdHoc(ask());

        verify(this.meter, never()).report(any());
    }

    /** A provider that does not report usage answers -1: nothing is invented, and nothing reported. */
    @Test
    void aProviderThatReportsNoUsageReportsNothing() throws Exception {
        request("0f7a4c1e-mig198-e");
        when(this.gateway.chat(any())).thenReturn(new AiProviderGateway.ChatAnswer("an answer", -1, -1));

        this.service.processAdHoc(ask());

        verify(this.meter, never()).report(any());
    }

    @Test
    void noWorkspaceNoCharge() throws Exception {
        request("0f7a4c1e-mig198-f");
        TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "admin");

        this.service.processAdHoc(ask());

        verify(this.meter, never()).report(any());
    }

    @Test
    void aRefusedRequestNeverReachesTheProviderOrTheMeter() throws Exception {
        request("0f7a4c1e-mig198-g");
        AdHocPromptRequestDto dto = ask();
        dto.setText(" ");

        this.service.processAdHoc(dto);

        verify(this.meter, never()).report(any());
    }
}
