package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.dto.LookupDataDto;
import process.model.dto.ResponseDto;
import process.model.pojo.LookupData;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.LookupDataRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.security.TenantContext;
import process.util.EncryptionUtil;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Editing an engine dial into a state the engine cannot read.
 *
 * Deleting QUEUE_FETCH_LIMIT was already refused, with a comment explaining that the scheduler
 * reads it by name every cycle and has no default -- but the same outage was reachable by
 * editing, which the guard explicitly left alone. Renaming the row takes it out from under
 * findByLookupType; a value like "5,000" fails to parse; and ticking "Store encrypted" stores
 * ciphertext that the dispatcher never decrypts, after which the list serves the value back
 * masked and the next edit writes the mask in as the value. All three stopped job dispatch
 * platform-wide, and none of them said anything the operator could see.
 */
@ExtendWith(MockitoExtension.class)
class EngineLookupEditGuardTest {

    private static final long LOOKUP_ID = 88L;

    @Mock private LookupDataRepository lookupDataRepository;
    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private SourceTaskTypeRepository sourceTaskTypeRepository;
    @Mock private KafkaConnectionProfileRepository kafkaConnectionProfileRepository;
    @Mock private TenantTaskTypeKafkaRouteRepository tenantTaskTypeKafkaRouteRepository;
    @Mock private EncryptionUtil encryptionUtil;
    @Mock private KafkaTemplateProvider kafkaTemplateProvider;
    @Mock private KafkaConnectionResolver kafkaConnectionResolver;
    @Mock private LookupDataCacheService lookupDataCacheService;
    @Mock private UserNameResolver userNameResolver;

    private SettingServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new SettingServiceImpl(this.lookupDataRepository, this.sourceJobRepository,
            this.sourceTaskTypeRepository, this.kafkaConnectionProfileRepository,
            this.tenantTaskTypeKafkaRouteRepository, null, this.encryptionUtil, this.kafkaTemplateProvider,
            this.kafkaConnectionResolver, this.lookupDataCacheService, this.userNameResolver);
        // The edit is a platform-admin action; a tenant admin is already refused a step earlier.
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void theFetchLimitRowExists() {
        LookupData lookupData = new LookupData();
        lookupData.setLookupId(LOOKUP_ID);
        lookupData.setLookupType(ProcessUtil.QUEUE_FETCH_LIMIT);
        lookupData.setLookupValue("5000");
        lookupData.setEncrypted(false);
        when(this.lookupDataRepository.findById(LOOKUP_ID)).thenReturn(Optional.of(lookupData));
    }

    private static LookupDataDto editOf(String lookupType, String lookupValue, Boolean encrypted) {
        LookupDataDto lookupDataDto = new LookupDataDto();
        lookupDataDto.setLookupId(LOOKUP_ID);
        lookupDataDto.setLookupType(lookupType);
        lookupDataDto.setLookupValue(lookupValue);
        lookupDataDto.setEncrypted(encrypted);
        return lookupDataDto;
    }

    @Test
    void anOrdinaryValueChangeIsStillAllowed() throws Exception {
        this.theFetchLimitRowExists();

        ResponseDto response = this.service.updateLookupData(
            editOf(ProcessUtil.QUEUE_FETCH_LIMIT, "8000", false));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.lookupDataRepository).save(any(LookupData.class));
    }

    @Test
    void aNonNumericValueIsRefused() throws Exception {
        this.theFetchLimitRowExists();

        ResponseDto response = this.service.updateLookupData(
            editOf(ProcessUtil.QUEUE_FETCH_LIMIT, "5,000", false));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("must be a whole number");
        verify(this.lookupDataRepository, never()).save(any());
    }

    @Test
    void aRenameIsRefused() throws Exception {
        this.theFetchLimitRowExists();

        ResponseDto response = this.service.updateLookupData(
            editOf("QUEUE FETCH LIMIT", "5000", false));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("cannot be renamed");
        verify(this.lookupDataRepository, never()).save(any());
    }

    @Test
    void encryptingAnEngineSettingIsRefused() throws Exception {
        this.theFetchLimitRowExists();

        ResponseDto response = this.service.updateLookupData(
            editOf(ProcessUtil.QUEUE_FETCH_LIMIT, "5000", true));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("cannot be stored encrypted");
        verify(this.encryptionUtil, never()).encrypt(any());
        verify(this.lookupDataRepository, never()).save(any());
    }

    /** The guard is for engine settings only; ordinary platform reference data is untouched. */
    @Test
    void anUnrelatedLookupIsNotSubjectToAnyOfThis() throws Exception {
        LookupData lookupData = new LookupData();
        lookupData.setLookupId(LOOKUP_ID);
        lookupData.setLookupType("SMTP_HOST");
        lookupData.setLookupValue("mail.example.com");
        lookupData.setEncrypted(false);
        when(this.lookupDataRepository.findById(LOOKUP_ID)).thenReturn(Optional.of(lookupData));

        ResponseDto response = this.service.updateLookupData(editOf("SMTP_HOST", "smtp.example.com", false));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
    }

}
