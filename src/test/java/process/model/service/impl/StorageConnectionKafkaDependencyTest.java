package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.config.StorageClientFactory;
import process.model.dto.ResponseDto;
import process.model.dto.StorageConnectionDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.KafkaConnectionProfile;
import process.model.pojo.StorageConnection;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.StorageConnectionRepository;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.EncryptionUtil;
import process.util.UserNameResolver;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * A Kafka profile points at a storage connection by alias and by nothing else -- no foreign key,
 * no cascade -- so deleting or retiring the connection leaves the profile intact and broken, and
 * the first sign of it is a producer that cannot load its truststore. The browser asks about
 * dependants before it commits, but a script calling the API does not, which is why the refusal
 * has to live on the server.
 *
 * The edits that do NOT break the binding have to keep working: a connection nobody may touch is
 * a connection whose expired keys nobody may rotate.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class StorageConnectionKafkaDependencyTest {

    private static final long TENANT_A = 1001L;

    @Mock
    private StorageConnectionRepository storageConnectionRepository;
    @Mock
    private StorageClientFactory storageClientFactory;
    @Mock
    private KafkaConnectionProfileRepository profileRepository;
    @Mock
    private EncryptionUtil encryptionUtil;
    @Mock
    private TenantFilterHelper tenantFilterHelper;
    @Mock
    private UserNameResolver userNameResolver;

    private StorageConnectionServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new StorageConnectionServiceImpl(this.storageConnectionRepository,
            this.storageClientFactory, this.profileRepository, this.encryptionUtil,
            this.tenantFilterHelper, this.userNameResolver);
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 9000L, "tenant-admin@example.com");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private StorageConnection savedConnection() {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(55L);
        connection.setTenantId(TENANT_A);
        connection.setAlias("kafka-certs");
        connection.setConnectionName("Kafka certificates");
        connection.setProvider(StorageProvider.MINIO);
        connection.setBucketName("kafka-certs");
        connection.setEndpoint("http://minio:9000");
        connection.setStatus(Status.Active);
        return connection;
    }

    private StorageConnectionDto dtoFor(StorageConnection connection) {
        StorageConnectionDto dto = new StorageConnectionDto();
        dto.setStorageConnectionId(connection.getStorageConnectionId());
        dto.setConnectionName(connection.getConnectionName());
        dto.setAlias(connection.getAlias());
        dto.setProvider(connection.getProvider());
        dto.setBucketName(connection.getBucketName());
        dto.setEndpoint(connection.getEndpoint());
        return dto;
    }

    private KafkaConnectionProfile profileUsing(String name, String truststoreBucket) {
        KafkaConnectionProfile profile = new KafkaConnectionProfile();
        profile.setProfileName(name);
        profile.setTenantId(TENANT_A);
        profile.setSslTruststoreBucket(truststoreBucket);
        return profile;
    }

    private void profilesVisibleToTheTenant(KafkaConnectionProfile... profiles) {
        when(this.profileRepository.findVisibleToTenant(TENANT_A, Status.Delete))
            .thenReturn(Arrays.asList(profiles));
    }

    @Test
    void deleteIsRefusedWhileAProfileLoadsAStoreFromTheAlias() throws Exception {
        StorageConnection connection = this.savedConnection();
        when(this.storageConnectionRepository.findById(55L)).thenReturn(Optional.of(connection));
        this.profilesVisibleToTheTenant(this.profileUsing("prod-events", "kafka-certs"));

        ResponseDto response = this.service.deleteConnection(55L);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).contains("prod-events");
        // The refusal has to be the whole of it: a message plus the delete would be worse than
        // either on its own, since the caller is told to fix something already broken.
        assertThat(connection.getStatus()).isEqualTo(Status.Active);
        verify(this.storageConnectionRepository, never()).save(any(StorageConnection.class));
    }

    @Test
    void deleteGoesThroughWhenNoProfileUsesTheAlias() throws Exception {
        StorageConnection connection = this.savedConnection();
        when(this.storageConnectionRepository.findById(55L)).thenReturn(Optional.of(connection));
        when(this.profileRepository.findVisibleToTenant(TENANT_A, Status.Delete))
            .thenReturn(Collections.<KafkaConnectionProfile>emptyList());

        ResponseDto response = this.service.deleteConnection(55L);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        assertThat(connection.getStatus()).isEqualTo(Status.Delete);
    }

    @Test
    void everyDependantIsNamed() throws Exception {
        StorageConnection connection = this.savedConnection();
        when(this.storageConnectionRepository.findById(55L)).thenReturn(Optional.of(connection));
        KafkaConnectionProfile keystoreUser = new KafkaConnectionProfile();
        keystoreUser.setProfileName("stage-events");
        keystoreUser.setSslKeystoreBucket("kafka-certs");
        this.profilesVisibleToTheTenant(this.profileUsing("prod-events", "kafka-certs"), keystoreUser);

        String message = this.service.deleteConnection(55L).getMessage();

        // Naming one of two would send the caller round the loop again for the second.
        assertThat(message).contains("prod-events").contains("stage-events");
    }

    @Test
    void renamingIsRefusedWhileAProfileUsesTheOldAlias() throws Exception {
        StorageConnection connection = this.savedConnection();
        StorageConnectionDto dto = this.dtoFor(connection);
        dto.setAlias("kafka-certs-v2");
        when(this.storageConnectionRepository.findByAlias("kafka-certs-v2")).thenReturn(Optional.empty());
        when(this.storageConnectionRepository.findById(55L)).thenReturn(Optional.of(connection));
        this.profilesVisibleToTheTenant(this.profileUsing("prod-events", "kafka-certs"));

        ResponseDto response = this.service.updateConnection(dto);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).contains("prod-events");
        assertThat(connection.getAlias()).isEqualTo("kafka-certs");
        verify(this.storageConnectionRepository, never()).save(any(StorageConnection.class));
    }

    @Test
    void retiringIsRefusedWhileAProfileUsesTheAlias() throws Exception {
        StorageConnection connection = this.savedConnection();
        StorageConnectionDto dto = this.dtoFor(connection);
        dto.setStatus(Status.Inactive);
        when(this.storageConnectionRepository.findByAlias("kafka-certs")).thenReturn(Optional.of(connection));
        when(this.storageConnectionRepository.findById(55L)).thenReturn(Optional.of(connection));
        this.profilesVisibleToTheTenant(this.profileUsing("prod-events", "kafka-certs"));

        ResponseDto response = this.service.updateConnection(dto);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(connection.getStatus()).isEqualTo(Status.Active);
    }

    @Test
    void anEditThatKeepsTheAliasAndTheStatusStillSaves() throws Exception {
        StorageConnection connection = this.savedConnection();
        StorageConnectionDto dto = this.dtoFor(connection);
        // The case the guard must not catch: rotating an endpoint on a connection three Kafka
        // profiles depend on leaves every one of them resolving exactly as before.
        dto.setEndpoint("http://minio-2:9000");
        dto.setStatus(Status.Active);
        when(this.storageConnectionRepository.findByAlias("kafka-certs")).thenReturn(Optional.of(connection));
        when(this.storageConnectionRepository.findById(55L)).thenReturn(Optional.of(connection));

        ResponseDto response = this.service.updateConnection(dto);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        assertThat(connection.getEndpoint()).isEqualTo("http://minio-2:9000");
        // Not merely allowed -- the dependants are never even looked up, since nothing about
        // this edit could break one.
        verify(this.profileRepository, never()).findVisibleToTenant(anyLong(), any(Status.class));
        verify(this.storageConnectionRepository).save(connection);
    }

    @Test
    void theRefusalSaysWhatToDoAboutIt() {
        List<String> names = Arrays.asList("prod-events");
        String message = StorageConnectionServiceImpl.stillUsedByKafka(names, "deleted");

        assertThat(message).contains("deleted").contains("prod-events");
        assertThat(message.toLowerCase()).contains("point it at another connection");
    }

}
