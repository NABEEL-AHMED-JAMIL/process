package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.AdHocPromptRequestDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.AiAgent;
import process.model.repository.AiAgentRepository;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.EncryptionUtil;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Two things the AI agent service must not do for a caller: hand over another tenant's agent
 * because the caller happened to know its tool uuid, and dial an address of the caller's
 * choosing from inside the network.
 *
 * The second is the sharper of the two. processAdHoc takes an apiEndpoint out of the request
 * body and returns what came back, so without a rule about which addresses are callable it is a
 * way to read anything the application server can reach and the caller cannot.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class AiAgentServiceImplTenantIsolationTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;

    @Mock
    private AiAgentRepository aiAgentRepository;
    @Mock
    private EncryptionUtil encryptionUtil;
    @Mock
    private TenantFilterHelper tenantFilterHelper;
    @Mock
    private UserNameResolver userNameResolver;

    private AiAgentServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new AiAgentServiceImpl(this.aiAgentRepository, this.encryptionUtil,
            this.tenantFilterHelper, this.userNameResolver);
        lenient().doNothing().when(this.tenantFilterHelper).enableIfNeeded(any());
        // What the property carries in a real deployment; a unit test gets no @Value injection.
        ReflectionTestUtils.setField(this.service, "allowedEndpointHosts", "host.docker.internal");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void actAsTenantUser(long tenantId) {
        TenantContext.set(tenantId, "TENANT_USER", 9000L, "user@example.com");
    }

    private AiAgent agentOwnedBy(long tenantId) {
        AiAgent aiAgent = new AiAgent();
        aiAgent.setAiAgentId(500L);
        aiAgent.setTenantId(tenantId);
        aiAgent.setAgentName("Invoice reader");
        aiAgent.setProvider("OpenAI");
        aiAgent.setModel("gpt-4o-mini");
        aiAgent.setInstructions("The tenant's own prompt engineering.");
        aiAgent.setToolUuid("a-random-uuid");
        aiAgent.setStatus(Status.Active);
        return aiAgent;
    }

    private AdHocPromptRequestDto promptTo(String apiEndpoint) {
        AdHocPromptRequestDto dto = new AdHocPromptRequestDto();
        dto.setProvider("custom");
        dto.setApiEndpoint(apiEndpoint);
        dto.setApiKey("x");
        dto.setModel("m");
        dto.setInstructions("i");
        dto.setText("t");
        return dto;
    }

    @Test
    void tenantBCannotReadTenantAsAgentByItsToolUuid() throws Exception {
        when(this.aiAgentRepository.findByToolUuid("a-random-uuid"))
            .thenReturn(Optional.of(this.agentOwnedBy(TENANT_A)));

        this.actAsTenantUser(TENANT_B);
        ResponseDto response = this.service.fetchToolByUuid("a-random-uuid");

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        // The same wording as a uuid that does not exist, so a refusal confirms nothing.
        assertThat(response.getMessage()).isEqualTo("Tool not found or not active.");
        assertThat(response.getData()).isNull();
    }

    @Test
    void tenantACanStillReadItsOwnAgentByItsToolUuid() throws Exception {
        when(this.aiAgentRepository.findByToolUuid("a-random-uuid"))
            .thenReturn(Optional.of(this.agentOwnedBy(TENANT_A)));

        this.actAsTenantUser(TENANT_A);
        ResponseDto response = this.service.fetchToolByUuid("a-random-uuid");

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(response.getData()).isNotNull();
    }

    @Test
    void aPrivateAddressIsNotAnAiProvider() throws Exception {
        this.actAsTenantUser(TENANT_A);

        ResponseDto response = this.service.processAdHoc(this.promptTo("https://10.0.3.14:8080/internal/config"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).doesNotContain("10.0.3.14");
    }

    @Test
    void loopbackIsNotAnAiProviderEither() throws Exception {
        this.actAsTenantUser(TENANT_A);

        assertThat(this.service.processAdHoc(this.promptTo("https://127.0.0.1:8080/actuator/env")).getStatus())
            .isEqualTo(ProcessUtil.ERROR);
    }

    @Test
    void theCloudMetadataAddressIsRefused() throws Exception {
        this.actAsTenantUser(TENANT_A);

        // 169.254.169.254 is link-local -- the one address worth naming, since it hands out
        // instance credentials to anything that asks from the machine itself.
        assertThat(this.service.processAdHoc(this.promptTo("https://169.254.169.254/latest/meta-data/")).getStatus())
            .isEqualTo(ProcessUtil.ERROR);
    }

    @Test
    void theOtherPrivateRangesAreRefusedToo() throws Exception {
        this.actAsTenantUser(TENANT_A);

        // Carrier-grade NAT and the IPv6 unique-local range: private in practice, and neither
        // one classified as such by InetAddress on its own.
        assertThat(this.service.processAdHoc(this.promptTo("https://100.64.1.1/x")).getStatus())
            .isEqualTo(ProcessUtil.ERROR);
        assertThat(this.service.processAdHoc(this.promptTo("https://[fd00::1]/x")).getStatus())
            .isEqualTo(ProcessUtil.ERROR);
        assertThat(this.service.processAdHoc(this.promptTo("https://192.168.1.10/x")).getStatus())
            .isEqualTo(ProcessUtil.ERROR);
    }

    @Test
    void plainHttpIsRefusedForAHostNobodyAllowListed() throws Exception {
        this.actAsTenantUser(TENANT_A);

        assertThat(this.service.processAdHoc(this.promptTo("http://198.51.100.7/v1/chat/completions")).getStatus())
            .isEqualTo(ProcessUtil.ERROR);
    }

    @Test
    void somethingThatIsNotAUrlIsRefusedBeforeAnythingIsDialled() throws Exception {
        this.actAsTenantUser(TENANT_A);

        assertThat(this.service.processAdHoc(this.promptTo("file:///etc/passwd")).getStatus())
            .isEqualTo(ProcessUtil.ERROR);
        assertThat(this.service.processAdHoc(this.promptTo("not a url at all")).getStatus())
            .isEqualTo(ProcessUtil.ERROR);
    }

    @Test
    void theHostTheOperatorAllowListedStaysCallableOverPlainHttp() {
        // The Ollama the server already reaches by itself. Checked through the rule rather than
        // through processAdHoc, which would go on to make the request.
        ResponseDto refusal = ReflectionTestUtils.invokeMethod(this.service, "validateEndpoint",
            "http://host.docker.internal:11434");

        assertThat(refusal).isNull();
    }

    @Test
    void anAllowListedNameDoesNotCoverEverythingUnderIt() {
        ResponseDto refusal = ReflectionTestUtils.invokeMethod(this.service, "validateEndpoint",
            "http://evil.host.docker.internal:11434");

        assertThat(refusal).isNotNull();
    }
}
