package process.storage;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import process.config.StorageClientFactory;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.model.service.ObjectStorageService;
import process.model.service.impl.LookupDataCacheService;
import process.model.service.impl.StorageBrowserServiceImpl;
import process.security.TenantContext;

import java.io.ByteArrayInputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * MIG-65's rules for the trusted path, behaviourally: a trusted call is made BY someone (a named
 * principal, or nothing happens), is audited with who, why, which object and the correlation id, and
 * still works on the threads that legitimately have no tenant -- a dispatch, a startup, a cron.
 */
class TrustedStorageAccessTest {

    private static final String PLATFORM_BUCKET = "etl-config";

    private final ObjectStorageService store = mock(ObjectStorageService.class);
    private StorageBrowserServiceImpl storage;
    private ListAppender<ILoggingEvent> audit;

    @BeforeEach
    void setUp() {
        StorageConnectionRepository connections = mock(StorageConnectionRepository.class);
        StorageClientFactory factory = mock(StorageClientFactory.class);
        this.storage = new StorageBrowserServiceImpl(mock(LookupDataCacheService.class), connections, factory, this.store,
            "etl-avatar", PLATFORM_BUCKET, mock(ObjectChangeLog.class));
        StorageConnection platform = new StorageConnection();
        platform.setStorageConnectionId(1L);
        platform.setAlias(PLATFORM_BUCKET);
        platform.setBucketName(PLATFORM_BUCKET);
        platform.setProvider(StorageProvider.MINIO);
        platform.setStatus(Status.Active);
        lenient().when(connections.findByAlias(PLATFORM_BUCKET)).thenReturn(Optional.of(platform));
        lenient().when(connections.findByAliasAndStatus(PLATFORM_BUCKET, Status.Active)).thenReturn(Optional.of(platform));
        lenient().when(factory.serviceFor(any())).thenReturn(this.store);
        this.audit = new ListAppender<>();
        this.audit.start();
        ((Logger) LoggerFactory.getLogger(TrustedStorageAudit.class)).addAppender(this.audit);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(TrustedStorageAudit.class)).detachAppender(this.audit);
        RequestContextHolder.resetRequestAttributes();
        TenantContext.clear();
    }

    @Test
    void withoutAPrincipalNothingHappens() {
        assertThatThrownBy(() -> this.storage.readForWorkflow(null, PLATFORM_BUCKET, "kafka/truststore.p12"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("principal");
        assertThatThrownBy(() -> this.storage.uploadForWorkflow(null, PLATFORM_BUCKET, "kafka/truststore.p12",
            new ByteArrayInputStream(new byte[] {1}), 1, "application/octet-stream")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(this.store);
    }

    @Test
    void aPrincipalMustBeNamedAndGiveAReason() {
        assertThatThrownBy(() -> TrustedAccess.of(null, "why")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TrustedAccess.of(TrustedCaller.BILLING_DOCUMENTS, " ")).isInstanceOf(IllegalStateException.class);
    }

    /** The legitimate tenant-less caller: a dispatch thread reading a profile's key material from the platform bucket. */
    @Test
    void aTrustedReadWorksWithNoTenantAndIsAudited() {
        this.storage.readForWorkflow(TrustedAccess.of(TrustedCaller.KAFKA_TEMPLATE_PROVIDER, "profile 41 key material"),
            PLATFORM_BUCKET, "kafka-secrets/41/truststore.p12");

        verify(this.store).getObjectContent(PLATFORM_BUCKET, "kafka-secrets/41/truststore.p12", null, null);
        assertThat(this.audit.list).hasSize(1);
        String line = this.audit.list.get(0).getFormattedMessage();
        assertThat(line).contains("read").contains("KAFKA_TEMPLATE_PROVIDER").contains("profile 41 key material")
            .contains(PLATFORM_BUCKET + "/kafka-secrets/41/truststore.p12").contains("correlationId=no-request");
    }

    /** Inside a request, the audit line carries the gateway's correlation id, and never the bytes. */
    @Test
    void aTrustedUploadInARequestCarriesItsCorrelationId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "c0ffee00-1234-4abc-9def-001122334455");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        TenantContext.set(2905L, "TENANT_ADMIN", 61L, "ops@medaxis.example");

        this.storage.uploadForWorkflow(TrustedAccess.of(TrustedCaller.BILLING_DOCUMENTS, "store a invoice"), PLATFORM_BUCKET,
            "billing/2905/2026/invoice/1-inv.pdf", new ByteArrayInputStream("%PDF-secret".getBytes()), 11, "application/pdf");

        String line = this.audit.list.get(0).getFormattedMessage();
        assertThat(line).contains("upload").contains("BILLING_DOCUMENTS").contains("correlationId=c0ffee00-1234-4abc-9def-001122334455")
            .doesNotContain("%PDF-secret");
    }
}
