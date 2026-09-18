package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import process.util.UserNameResolver;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Editing an encrypted lookup must not destroy the secret.
 *
 * The console shows "••••••••" for an encrypted value and, on Edit, sent exactly that back as
 * the value. The server took it as a new secret: after one edit of the description the row
 * held the mask encrypted, and unticking "Store encrypted" left the literal mask in the clear.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class EncryptedLookupEditTest {

    private static final long LOOKUP_ID = 1279L;
    private static final String MASK = "••••••••";
    private static final String CIPHER = "1Xr2VVAYoNUxyX689zEjc7alQ3O+6Ty3pD/jtiPMWxDB5va18t4YBPCDPvg=";

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
    private LookupData row;

    @BeforeEach
    void setUp() {
        this.service = new SettingServiceImpl(this.lookupDataRepository, this.sourceJobRepository,
            this.sourceTaskTypeRepository, this.kafkaConnectionProfileRepository,
            this.tenantTaskTypeKafkaRouteRepository, null, this.encryptionUtil, this.kafkaTemplateProvider,
            this.kafkaConnectionResolver, this.lookupDataCacheService, this.userNameResolver);
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");
        this.row = new LookupData();
        this.row.setLookupId(LOOKUP_ID);
        this.row.setLookupType("Encrypt probe");
        this.row.setLookupValue(CIPHER);
        this.row.setEncrypted(true);
        when(this.lookupDataRepository.findById(LOOKUP_ID)).thenReturn(Optional.of(this.row));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static LookupDataDto edit(String value, boolean encrypted, String description) {
        LookupDataDto dto = new LookupDataDto();
        dto.setLookupId(LOOKUP_ID);
        dto.setLookupType("Encrypt probe");
        dto.setLookupValue(value);
        dto.setEncrypted(encrypted);
        dto.setDescription(description);
        return dto;
    }

    @Test
    void editingTheDescriptionWithTheMaskEchoedBackKeepsTheStoredSecret() throws Exception {
        ResponseDto response = this.service.updateLookupData(edit(MASK, true, "now with a description"));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(this.row.getLookupValue()).isEqualTo(CIPHER);
        assertThat(this.row.getEncrypted()).isTrue();
        assertThat(this.row.getDescription()).isEqualTo("now with a description");
        verify(this.encryptionUtil, never()).encrypt(any());
    }

    @Test
    void aBlankValueOnAnEncryptedRowAlsoKeepsTheStoredSecret() throws Exception {
        ResponseDto response = this.service.updateLookupData(edit("", true, null));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(this.row.getLookupValue()).isEqualTo(CIPHER);
        verify(this.encryptionUtil, never()).encrypt(any());
    }

    @Test
    void untickingEncryptedWithTheMaskEchoedBackDecryptsTheRealSecret() throws Exception {
        when(this.encryptionUtil.decrypt(CIPHER)).thenReturn("s3cret-value-123");

        ResponseDto response = this.service.updateLookupData(edit(MASK, false, null));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(this.row.getLookupValue()).isEqualTo("s3cret-value-123");
        assertThat(this.row.getEncrypted()).isFalse();
    }

    @Test
    void aRealNewValueStillReplacesTheSecret() throws Exception {
        when(this.encryptionUtil.encrypt("new-secret")).thenReturn("NEWCIPHER");

        ResponseDto response = this.service.updateLookupData(edit("new-secret", true, null));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        ArgumentCaptor<LookupData> saved = ArgumentCaptor.forClass(LookupData.class);
        verify(this.lookupDataRepository).save(saved.capture());
        assertThat(saved.getValue().getLookupValue()).isEqualTo("NEWCIPHER");
    }

    @Test
    void aPlainRowStillNeedsAValueAndTheMaskIsNotOne() throws Exception {
        this.row.setEncrypted(false);
        this.row.setLookupValue("visible");

        assertThat(this.service.updateLookupData(edit("", false, null)).getStatus()).isEqualTo("ERROR");
        assertThat(this.service.updateLookupData(edit(MASK, false, null)).getStatus()).isEqualTo("ERROR");
        assertThat(this.row.getLookupValue()).isEqualTo("visible");
    }
}
