package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.AiAgentDto;
import process.model.dto.ResponseDto;
import process.model.pojo.AiAgent;
import process.model.pojo.Tenant;
import process.model.repository.AiAgentRepository;
import process.model.repository.TenantRepository;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.EncryptionUtil;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What tenant owns a row a PLATFORM_ADMIN creates.
 *
 * A platform admin has no tenant of their own, so TenantContext.getTenantId() is null for them.
 * AiAgent declares `@Filter(condition = "tenant_id = :tenantId")`, and NULL satisfies that for
 * no tenant at all -- so an agent stamped with a null tenant is invisible to every workspace on
 * the platform, including the one the admin was looking at when they made it. Only another
 * platform admin (who has the filter switched off entirely) could ever see it again.
 *
 * The fallback is the seeded default tenant, which is the same choice saveForm already makes
 * for a platform admin's pipeline forms.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class PlatformAdminOwnershipFallbackTest {

    private static final long DEFAULT_TENANT = 1815L;
    private static final long REAL_TENANT = 2364L;

    @Mock private AiAgentRepository aiAgentRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private EncryptionUtil encryptionUtil;
    @Mock private TenantFilterHelper tenantFilterHelper;
    @Mock private UserNameResolver userNameResolver;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private AiAgentServiceImpl service() {
        return new AiAgentServiceImpl(this.aiAgentRepository, this.tenantRepository,
            this.encryptionUtil, this.tenantFilterHelper, this.userNameResolver);
    }

    private AiAgentDto validAgent() {
        AiAgentDto dto = new AiAgentDto();
        dto.setAgentName("Document Assistant");
        dto.setProvider("Ollama");
        dto.setModel("qwen3:8b");
        dto.setTargetFileTypes("pdf,txt");
        dto.setInstructions("Answer questions about the attached document.");
        return dto;
    }

    private Tenant defaultTenant() {
        Tenant tenant = new Tenant();
        tenant.setTenantId(DEFAULT_TENANT);
        tenant.setTenantCode(TenantSeedService.DEFAULT_TENANT_CODE);
        return tenant;
    }

    @Test
    void aPlatformAdminsAgentIsFiledUnderTheDefaultTenantRatherThanNoTenant() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        when(this.tenantRepository.findByTenantCode(TenantSeedService.DEFAULT_TENANT_CODE))
            .thenReturn(Optional.of(this.defaultTenant()));
        lenient().when(this.aiAgentRepository.save(any(AiAgent.class)))
            .thenAnswer(call -> call.getArgument(0));

        ResponseDto response = this.service().addAgent(this.validAgent());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<AiAgent> saved = ArgumentCaptor.forClass(AiAgent.class);
        verify(this.aiAgentRepository).save(saved.capture());
        assertThat(saved.getValue().getTenantId())
            .as("a null tenant here is an agent nobody can ever see again")
            .isEqualTo(DEFAULT_TENANT);
    }

    @Test
    void aTenantsOwnAgentIsStillFiledUnderThatTenant() throws Exception {
        TenantContext.set(REAL_TENANT, "TENANT_ADMIN", 2L, "admin@acme.test");
        lenient().when(this.aiAgentRepository.save(any(AiAgent.class)))
            .thenAnswer(call -> call.getArgument(0));

        ResponseDto response = this.service().addAgent(this.validAgent());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<AiAgent> saved = ArgumentCaptor.forClass(AiAgent.class);
        verify(this.aiAgentRepository).save(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo(REAL_TENANT);
        verify(this.tenantRepository, never()).findByTenantCode(any());
    }

    @Test
    void withNoDefaultTenantSeededTheAgentIsRefusedRatherThanOrphaned() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        when(this.tenantRepository.findByTenantCode(TenantSeedService.DEFAULT_TENANT_CODE))
            .thenReturn(Optional.empty());

        ResponseDto response = this.service().addAgent(this.validAgent());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).contains("No default tenant");
        verify(this.aiAgentRepository, never()).save(any(AiAgent.class));
    }
}
